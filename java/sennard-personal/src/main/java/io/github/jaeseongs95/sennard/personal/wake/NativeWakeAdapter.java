// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import io.github.jaeseongs95.sennard.hookclient.NativeWakeProtocol.Launch;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Availability;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Connection;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Target;

/** A native command selected and started only in the local worker. */
public interface NativeWakeAdapter {
    Availability connect(Map<String, String> environment, Target target);

    default Optional<ManagedLauncher> managedLauncher(Map<String, String> environment, Target target) throws IOException {
        return Optional.empty();
    }

    @FunctionalInterface
    interface ManagedLauncher {
        ManagedConversation create(Launch launch) throws IOException, InterruptedException;
    }

    interface ManagedConversation extends Connection {
        String threadId();
    }

    final class UnsupportedSelection extends IOException {
        private static final long serialVersionUID = 1L;

        public UnsupportedSelection() {
            super("The host did not list the selected model and reasoning effort together.");
        }
    }
}
