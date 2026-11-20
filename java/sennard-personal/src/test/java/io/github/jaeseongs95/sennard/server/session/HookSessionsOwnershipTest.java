// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.session;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;

/**
 * What {@code HookSessions} does when the registry refuses the owner it decided on (a scheduled task, run on the scheduler's one thread, whose failures go to the scheduler's error handler):
 * <ol>
 * <li>A refusal of the generation (CURRENT_GENERATION_PROTECTED, GENERATION_CHANGED, GENERATION_STALE): the host does not own the session; it is logged as SESSION_NOT_OWNED and nothing is raised.</li>
 * <li>Any other SennardException (here CONCURRENT_UPDATE and DUPLICATE): not a refusal of ownership; it is not recorded as one, and goes up to the scheduler's error handler.</li>
 * </ol>
 * The host process is a real one: a JVM of its own that waits on its input, because the JDK does not allow watching its own process, and the watch of a process that is not there binds nothing.
 */
class HookSessionsOwnershipTest {
    @Test
    void aRefusalOfTheGenerationIsNotOwnershipAndRaisesNothing() throws Exception {
        for (ConflictCode code : List.of(ConflictCode.CURRENT_GENERATION_PROTECTED, ConflictCode.GENERATION_CHANGED, ConflictCode.GENERATION_STALE)) {
            assertTrue(decide(new ConflictException(code)).isEmpty(), code + " is the registry saying this host does not own the session");
        }
    }

    @Test
    void anSennardExceptionThatIsNotARefusalOfTheGenerationGoesUp() throws Exception {
        for (ConflictCode code : List.of(ConflictCode.CONCURRENT_UPDATE, ConflictCode.DUPLICATE)) {
            ConflictException failure = new ConflictException(code);
            assertSame(failure, decide(failure).orElseThrow(() -> new AssertionError(code + " was swallowed as a refusal of ownership")));
        }
    }

    /**
     * Starts a session whose one host candidate is a live process, lets the decision run with the registry throwing {@code registryFailure}, and returns what reached the scheduler's error
     * handler: empty when nothing did. The decision has run when a task queued after it on the same one thread has.
     */
    private static Optional<Throwable> decide(SennardException registryFailure) throws Exception {
        Process host = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Waiting.class.getName())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("hook-sessions-test-");
        CompletableFuture<Throwable> raised = new CompletableFuture<>();
        scheduler.setErrorHandler(raised::complete);
        scheduler.initialize();
        try {
            SessionLifecycle lifecycle = new SessionLifecycle(null, null, null, null) {
                @Override
                public Registered register(Registration registration) {
                    throw registryFailure;
                }

                @Override
                public void noteHook(HookSeen hook) {
                    // The hook's own row is the registry's; this case is about the owner decision.
                }
            };
            SennardProperties properties = new SennardProperties(null, null, null, Path.of("."), false, null, new SennardProperties.Hooks(Duration.ofHours(1), Duration.ZERO), null, null, null, null, null);
            HookSessions sessions = new HookSessions(lifecycle, event -> {
            }, scheduler, Clock.systemUTC(), properties);
            sessions.started("test-host", "session-1", List.of(host.pid()), System.currentTimeMillis());
            CompletableFuture<Void> after = new CompletableFuture<>();
            scheduler.schedule(() -> after.complete(null), scheduler.getClock().instant().plusMillis(1));
            waitFor(after);
            sessions.destroy();
            return raised.isDone() ? Optional.of(raised.get()) : Optional.empty();
        } finally {
            scheduler.shutdown();
            host.destroyForcibly();
        }
    }

    /** Waits for a task that must finish; the wait's limit only keeps a broken run from hanging, nothing is asserted about the time. */
    private static void waitFor(CompletableFuture<Void> done) throws InterruptedException, ExecutionException, TimeoutException {
        done.get(60, TimeUnit.SECONDS);
    }

    /** The host process of the cases: alive until its input closes. */
    public static final class Waiting {
        public static void main(String[] arguments) throws IOException {
            System.in.read();
        }
    }
}
