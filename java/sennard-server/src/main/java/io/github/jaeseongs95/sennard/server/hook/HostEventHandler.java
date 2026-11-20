// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.util.List;

/**
 * The domain side of a hook call: it records what happened and answers. The domain services implement this; the hook path only depends on
 * it, so nothing here knows how a session is stored and nothing there knows a host's wire format.
 */
public interface HostEventHandler {
    HookVerdict handle(HookCall call);

    /**
     * @param module the registration this call came through, when one event has separately registered checks with different failure
     *     behaviour; null for the call of no separate registration
     * @param hostCandidates the process ids above the Sennard hook client that ran this hook, the nearest first, when the hook's definition asks it to tell
     *     (a session start does); empty for a hook that does not. The host is among them: the hook client does not know which
     *     (a host may start a hook through a shell of its own that ends with the hook), so the server decides (see {@code HookSessions})
     */
    record HookCall(HostEvent event, String module, List<Long> hostCandidates) {
        public HookCall {
            hostCandidates = List.copyOf(hostCandidates);
        }

        public HookCall(HostEvent event, String module) {
            this(event, module, List.of());
        }

        /** Whether the call came through a registration of another module than {@code own}: a call of no module is every handler's. */
        public boolean isForAnotherThan(HookModule own) {
            return module != null && !own.isNamed(module);
        }
    }
}
