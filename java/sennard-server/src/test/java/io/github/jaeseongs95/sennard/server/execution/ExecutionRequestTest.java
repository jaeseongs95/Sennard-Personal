// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a request for an execution may be: the checks of {@link ExecutionRequest} at their edges, and that the environment is never implied.
 *
 * <pre>
 * r1  argv         0 items and 129 items are refused, 1 and 128 are taken; null is refused
 * r2  time limit   zero and negative are refused, one nanosecond is taken
 * r3  log limit    0, a negative one and one byte over the largest are refused; 1 and the largest are taken
 * r4  folder       a relative path is refused
 * r5  environment  a request without one (null) is refused: there is no default, and the type has no way to ask for the server's whole environment
 * r6  the copies   the argv and the variables of a request do not change when the lists they were made from do
 * </pre>
 */
class ExecutionRequestTest {
    private static final EnvironmentSpec NOTHING = new EnvironmentSpec(List.of(), Map.of());
    private static final Duration LIMIT = Duration.ofMinutes(1);

    private static List<String> argv(int items) {
        return Collections.nCopies(items, "x");
    }

    private static ExecutionRequest request(List<String> argv, Path folder, EnvironmentSpec environment, Duration timeout, long logLimit) {
        return new ExecutionRequest(argv, folder, environment, timeout, logLimit);
    }

    @Test
    void anArgvHoldsOneToTheMostItems(@TempDir Path work) {
        assertThrows(IllegalArgumentException.class, () -> request(argv(0), work, NOTHING, LIMIT, 1));
        assertThrows(IllegalArgumentException.class, () -> request(argv(ExecutionRequest.MAX_ARGV + 1), work, NOTHING, LIMIT, 1));
        assertThrows(NullPointerException.class, () -> request(null, work, NOTHING, LIMIT, 1));
        assertEquals(1, request(argv(1), work, NOTHING, LIMIT, 1).argv().size());
        assertEquals(ExecutionRequest.MAX_ARGV, request(argv(ExecutionRequest.MAX_ARGV), work, NOTHING, LIMIT, 1).argv().size());
    }

    @Test
    void theTimeLimitIsLongerThanZero(@TempDir Path work) {
        assertThrows(IllegalArgumentException.class, () -> request(argv(1), work, NOTHING, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> request(argv(1), work, NOTHING, Duration.ofNanos(-1), 1));
        assertThrows(NullPointerException.class, () -> request(argv(1), work, NOTHING, null, 1));
        assertEquals(Duration.ofNanos(1), request(argv(1), work, NOTHING, Duration.ofNanos(1), 1).timeout());
    }

    @Test
    void theLogLimitIsOneByteToTheLargest(@TempDir Path work) {
        assertThrows(IllegalArgumentException.class, () -> request(argv(1), work, NOTHING, LIMIT, 0));
        assertThrows(IllegalArgumentException.class, () -> request(argv(1), work, NOTHING, LIMIT, -1));
        assertThrows(IllegalArgumentException.class, () -> request(argv(1), work, NOTHING, LIMIT, ExecutionRequest.MAX_LOG_BYTES + 1));
        assertEquals(1, request(argv(1), work, NOTHING, LIMIT, 1).logLimitBytes());
        assertEquals(ExecutionRequest.MAX_LOG_BYTES, request(argv(1), work, NOTHING, LIMIT, ExecutionRequest.MAX_LOG_BYTES).logLimitBytes());
    }

    @Test
    void theWorkingFolderIsAnAbsolutePath() {
        assertThrows(IllegalArgumentException.class, () -> request(argv(1), Path.of("relative", "folder"), NOTHING, LIMIT, 1));
        assertThrows(NullPointerException.class, () -> request(argv(1), null, NOTHING, LIMIT, 1));
    }

    @Test
    void thereIsNoDefaultEnvironmentAndNoSpecThatMeansTheServersWhole(@TempDir Path work) {
        assertThrows(NullPointerException.class, () -> request(argv(1), work, null, LIMIT, 1));
        assertThrows(NullPointerException.class, () -> new EnvironmentSpec(null, Map.of()));
        assertThrows(NullPointerException.class, () -> new EnvironmentSpec(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentSpec(List.of(), Collections.singletonMap("NAME", null)));
        // what the spec says is exactly the names it lists: an empty list is nothing of the server's
        assertEquals(List.of(), NOTHING.inheritedNames());
        EnvironmentSpec named = new EnvironmentSpec(List.of("PATH"), Map.of("A", "b"));
        assertEquals(List.of("PATH"), named.inheritedNames());
        assertSame(named, request(argv(1), work, named, LIMIT, 1).environment());
    }

    @Test
    void theArgvAndTheVariablesAreCopiedWhenTheRequestIsMade(@TempDir Path work) {
        List<String> given = new ArrayList<>(List.of("a", "b"));
        List<String> names = new ArrayList<>(List.of("PATH"));
        ExecutionRequest request = request(given, work, new EnvironmentSpec(names, Map.of()), LIMIT, 1);

        given.add("c");
        names.add("HOME");

        assertEquals(List.of("a", "b"), request.argv());
        assertEquals(List.of("PATH"), request.environment().inheritedNames());
        assertThrows(UnsupportedOperationException.class, () -> request.argv().add("d"));
    }
}
