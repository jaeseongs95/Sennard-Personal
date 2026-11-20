// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.entrance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;

/**
 * A hook the server answers with a status of 500 or above leaves a line in the log that names the refusal; before, {@code HOOK_VERDICT_NOT_DELIVERABLE} (500) was answered with
 * nothing in the log, and the host's "Hook failed ... SERVER_REFUSED 500" had no trace on the server. Cases: a 500 refusal and a 503 refusal are each logged once at WARN with the
 * status and the rejection's name; a 400 refusal (the caller's) is not logged; the answer's status is the rejection's in every case.
 */
class HookRejectedLogTest {
    private final SennardExceptionHandler handler = new SennardExceptionHandler(null);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(SennardExceptionHandler.class);
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
        logged.stop();
    }

    @Test
    void aHookRefusedWithA500IsLoggedWithItsName() {
        var answer = handler.hookRejected(new HookRejected(HookRejection.HOOK_VERDICT_NOT_DELIVERABLE));

        assertEquals(500, answer.getStatusCode().value());
        assertEquals(1, logged.list.size());
        assertEquals(Level.WARN, logged.list.getFirst().getLevel());
        assertTrue(logged.list.getFirst().getFormattedMessage().contains("HOOK_VERDICT_NOT_DELIVERABLE"), logged.list.getFirst().getFormattedMessage());
        assertTrue(logged.list.getFirst().getFormattedMessage().contains("status=500"), logged.list.getFirst().getFormattedMessage());
    }

    @Test
    void aHookRefusedWithA503IsLoggedWithItsReason() {
        var answer = handler.hookRejected(new HookRejected(HookRejection.HOOK_HANDLER_FAILED, "STORE_UNAVAILABLE", new IllegalStateException("down")));

        assertEquals(503, answer.getStatusCode().value());
        assertEquals(1, logged.list.size());
        assertTrue(logged.list.getFirst().getFormattedMessage().contains("STORE_UNAVAILABLE"), logged.list.getFirst().getFormattedMessage());
    }

    @Test
    void aHookRefusedWithA400IsTheCallersAndIsNotLogged() {
        var answer = handler.hookRejected(new HookRejected(HookRejection.HOOK_EVENT_UNKNOWN));

        assertEquals(400, answer.getStatusCode().value());
        assertEquals(0, logged.list.size());
    }
}
