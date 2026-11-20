// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.github.jaeseongs95.sennard.hookclient.HookHeaders;
import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol;

/**
 * The HTTP hook entry point: the Sennard hook client (every host's command hook; a host's own HTTP hook cannot name the server's address, which is the operating system's choice at each start) posts what the host
 * wrote for the hook, and the body of the answer is what the host reads as the hook's output. The label in the path picks the adapter; no code here looks
 * at what a label says. On a session start the client sends the process ids above it in {@link HookHeaders#HOST_PIDS} (Spring reads the comma-separated value as a list).
 *
 * <p>The body arrives as bytes because which record it is read into is the adapter's, and the adapter is known only from the label: a handler method's
 * {@code @RequestBody} type is fixed when the method is declared, so one method per host would make a new host a change here, which the adapter beans
 * exist to avoid. The adapter binds the bytes once, straight to its input record; the answer is the adapter's output record, which the message converters
 * write.
 *
 * <p>A refusal is an answer, so it is HTTP 200 in the host's own JSON. A status above 299 means the server could not answer at all, which the host (or the
 * hook client, through its failure answer) turns into the host's failure behaviour instead of a silent empty answer.
 */
@RestController
public class HookController {
    private final HookPipeline pipeline;

    public HookController(HookPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping({"/hooks/v1/{label}/{event:[A-Za-z]+}", "/hooks/v1/{label}/{event:[A-Za-z]+}/{module:[a-z][a-z0-9-]*}"})
    public ResponseEntity<HookOutput> receive(@PathVariable String label, @PathVariable String event, @PathVariable(required = false) String module,
            @RequestHeader(name = HookHeaders.HOST_PIDS, required = false) List<Long> hostCandidates,
            @RequestHeader(name = NativeWakeProtocol.KEY, required = false) String wakeKey, @RequestBody(required = false) byte[] body) {
        List<Long> candidates = hostCandidates == null ? List.of() : hostCandidates;
        String registration = !candidates.isEmpty() && NativeWakeProtocol.validKey(wakeKey) ? wakeKey : null;
        HookPipeline.Answer answer = pipeline.answer(label, event, module, HookBody.posted(body), candidates, registration);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
        if (answer.wakeRegistered()) {
            response.header(NativeWakeProtocol.KEY, registration);
        }
        return response.body(answer.output());
    }
}
