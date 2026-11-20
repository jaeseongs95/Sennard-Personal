// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.util.Objects;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;

/** Injectable process boundary; production always uses the real bounded ToolProcess runner. */
@FunctionalInterface
public interface WakeProcess {
    ToolProcess.Outcome run(ToolProcess.Request request) throws IOException, InterruptedException;

    enum Phase { NOT_STARTED, STARTED }

    /** Checked process boundary failure; a started process may already have submitted input. */
    final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        private final Phase phase;

        public Failure(Phase phase, IOException cause) {
            super("Wake process failed", Objects.requireNonNull(cause));
            this.phase = Objects.requireNonNull(phase);
        }

        public Phase phase() {
            return phase;
        }
    }
}
