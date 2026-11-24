// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import io.github.jaeseongs95.sennard.protocol.ToolProcess;
import io.github.jaeseongs95.sennard.server.diagnostics.SafeFailure;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Availability;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Connection;
import io.github.jaeseongs95.sennard.server.messaging.WakeTransport.Outcome;

final class NativeWakeSend {
    record Diagnostics(String nonzero, String timedOut, String outputLimit, String ioAfterStart, String startFailed,
            String preparationFailed, String cancelled, String sendFailureLog, String preparationFailureLog) {
    }

    private NativeWakeSend() {
    }

    static Availability.Ready boundTo(String expectedSession, String mismatch, Connection send) {
        return new Availability.Ready((session, marker) -> expectedSession.equals(session)
                ? send.wake(session, marker) : new Outcome.NotSent(mismatch));
    }

    static Map<String, String> selectEnvironment(Map<String, String> exposed, Set<String> allowedFields) {
        return exposed.entrySet().stream()
                .filter(entry -> allowedFields.contains(entry.getKey()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    static ToolProcess.Request request(List<String> command, ToolProcess.Stderr stderr, Map<String, String> environment) {
        return ToolProcess.Request.of(command, stderr, Duration.ofSeconds(10)).limitedTo(65536)
                .withoutInheritedEnvironment().withEnvironment(environment);
    }

    static Outcome run(WakeProcess process, ToolProcess.Request request, Logger log, Diagnostics diagnostics) {
        try {
            return switch (process.run(request)) {
                case ToolProcess.Finished finished when finished.exitCode() == 0 -> new Outcome.Submitted();
                // Nonzero exits can follow partial submission. The CLI output does not establish send certainty.
                case ToolProcess.Finished ignored -> new Outcome.Unknown(diagnostics.nonzero());
                case ToolProcess.TimedOut ignored -> new Outcome.Unknown(diagnostics.timedOut());
                case ToolProcess.Overflowed ignored -> new Outcome.Unknown(diagnostics.outputLimit());
            };
        } catch (WakeProcess.Failure failed) {
            log.warn(diagnostics.sendFailureLog(), SafeFailure.capture(failed));
            return failed.phase() == WakeProcess.Phase.STARTED
                    ? new Outcome.Unknown(diagnostics.ioAfterStart())
                    : new Outcome.NotSent(diagnostics.startFailed());
        } catch (IOException beforeStart) {
            // ToolProcess can fail before launch while allocating its temporary output file.
            log.warn(diagnostics.preparationFailureLog(), SafeFailure.capture(beforeStart));
            return new Outcome.NotSent(diagnostics.preparationFailed());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return new Outcome.Unknown(diagnostics.cancelled());
        }
    }
}
