// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;

public final class LocalWakeProcess implements WakeProcess {
    @Override
    public ToolProcess.Outcome run(ToolProcess.Request request) throws IOException, InterruptedException {
        try {
            return ToolProcess.run(request);
        } catch (ToolProcess.RunFailure failure) {
            throw new WakeProcess.Failure(failure.started() ? Phase.STARTED : Phase.NOT_STARTED, failure);
        }
    }
}
