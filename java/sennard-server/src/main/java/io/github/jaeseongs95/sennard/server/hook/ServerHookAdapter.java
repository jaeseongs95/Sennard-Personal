// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/** Translates a host's hook inputs and verdicts at the server boundary. */
public interface ServerHookAdapter {
    /** The label identifies the host in incoming hook requests and is unique among server adapters. */
    HostId host();

    /** The input record shared with the client so only fields used by Sennard travel to the server. */
    Class<?> inputType();

    /**
     * Reads the payload of the named native hook into an event understood by the server.
     *
     * @throws HookRejected if the input cannot be read as that event
     */
    HostEvent read(String event, HookBody body);

    /** Whether the host requires the termination reason to be sent at the next input. */
    default boolean defersStopReason() {
        return false;
    }

    /**
     * Renders a verdict in the format expected by the named native hook.
     *
     * @throws HookRejected if the host cannot deliver the verdict
     */
    HookOutput respond(String event, HostEvent read, HookVerdict verdict);
}
