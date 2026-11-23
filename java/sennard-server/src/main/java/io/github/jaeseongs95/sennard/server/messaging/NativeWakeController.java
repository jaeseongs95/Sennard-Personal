// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;

/** An accepted worker receives its own reservation marker or launch control. */
@RestController
public class NativeWakeController {
    private static final System.Logger LOG = System.getLogger(NativeWakeController.class.getName());
    private final NativeWakeRelay relay;
    private final IdleWakeService wake;

    public NativeWakeController(NativeWakeRelay relay, IdleWakeService wake) {
        this.relay = relay;
        this.wake = wake;
    }

    @PostMapping(NativeWakeProtocol.POLL)
    public ResponseEntity<?> poll(@RequestHeader(NativeWakeProtocol.KEY) String key,
            @RequestHeader(value = NativeWakeProtocol.CAPABILITIES, defaultValue = "ROOT") NativeWakeProtocol.Capability capability,
            @RequestHeader(value = NativeWakeProtocol.ROOT_UNAVAILABLE, required = false) NativeWakeProtocol.RootUnavailable unavailable) throws InterruptedException {
        if (!capability.accepts(unavailable)) {
            return ResponseEntity.badRequest().build();
        }
        NativeWakeRelay.Channel channel = relay.find(key);
        if (channel == null) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        NativeWakeRelay.Poll poll = channel.beginPoll(capability, unavailable);
        switch (poll) {
            case CLOSED -> {
                return ResponseEntity.status(HttpStatus.GONE).build();
            }
            case BUSY -> {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            case FIRST_CONTACT, CONTINUING -> {
            }
        }
        NativeWakeRelay.Reply reply;
        try {
            if (capability == NativeWakeProtocol.Capability.NONE) {
                return ResponseEntity.noContent().build();
            }
            if (poll == NativeWakeRelay.Poll.FIRST_CONTACT && capability.rootWake()) {
                wake.relayAvailable(channel.host(), channel.session());
            }
            reply = channel.awaitOffer();
        } finally {
            channel.endPoll();
        }
        if (reply.closed() || !channel.actorCurrent()) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        if (reply.launch() != null) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(reply.launch());
        }
        if (reply.marker() == null) {
            return ResponseEntity.noContent().build();
        }
        var response = ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN);
        if (reply.childId() != null) {
            response.header(NativeWakeProtocol.CHILD_ID, reply.childId());
        }
        return response.body(reply.marker());
    }

    @PostMapping(NativeWakeProtocol.COMPLETE + "/{result}")
    public ResponseEntity<Void> complete(@RequestHeader(NativeWakeProtocol.KEY) String key,
            @RequestHeader(NativeWakeProtocol.MARKER) String marker,
            @RequestHeader(value = NativeWakeProtocol.CHILD_ID, required = false) String childId, @PathVariable NativeWakeProtocol.Result result) {
        NativeWakeRelay.Channel channel = relay.find(key);
        if (channel == null) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        return channel.complete(marker, childId, result) ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PostMapping(NativeWakeProtocol.LAUNCH_COMPLETE + "/{result}")
    public ResponseEntity<Void> completeLaunch(@RequestHeader(NativeWakeProtocol.KEY) String key,
            @RequestHeader(NativeWakeProtocol.LAUNCH_ID) String id,
            @RequestHeader(value = NativeWakeProtocol.CHILD_ID, required = false) String childId,
            @PathVariable NativeWakeProtocol.LaunchResult result) {
        NativeWakeRelay.Channel channel = relay.find(key);
        if (channel == null) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        NativeWakeProtocol.LaunchReply reply;
        try {
            reply = new NativeWakeProtocol.LaunchReply(result, childId);
        } catch (IllegalArgumentException invalid) {
            LOG.log(System.Logger.Level.DEBUG, "The local worker returned an invalid launch result ({0})", invalid.getClass().getSimpleName());
            return ResponseEntity.badRequest().build();
        }
        return channel.completeLaunch(id, reply) ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
