// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the server remembers of the intents it prepared (D-0222): only those, once, for as long as an intent lives. The clock is a bean the test moves by hand, so no test waits
 * for time to pass.
 */
class AdminIssuedIntentsTest {
    private static final Instant START = Instant.parse("2026-11-15T09:00:00Z");

    /** A clock that stands still until the test moves it. */
    private static final class HandClock extends Clock {
        private Instant now = START;

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void onlyATextThatWasPreparedIsHeldAndOneFurtherCharacterIsAnotherText() {
        AdminIssuedIntents issued = new AdminIssuedIntents(new HandClock());
        assertFalse(issued.holds("{\"a\":1}"), "nothing prepared yet");
        issued.issue("{\"a\":1}");
        assertTrue(issued.holds("{\"a\":1}"));
        assertFalse(issued.holds("{\"a\":2}"), "the same target and head with another change is not the prepared text");
        assertFalse(issued.holds("{\"a\":1} "), "the text is held whole");
    }

    @Test
    void anIntentIsSpentOnce() {
        AdminIssuedIntents issued = new AdminIssuedIntents(new HandClock());
        issued.issue("one");
        issued.issue("two");
        issued.use("one");
        assertFalse(issued.holds("one"));
        assertTrue(issued.holds("two"), "another intent is not spent with it");
    }

    @Test
    void anIntentIsHeldForAsLongAsAnIntentLivesAndNotAnInstantLonger() {
        HandClock clock = new HandClock();
        AdminIssuedIntents issued = new AdminIssuedIntents(clock);
        issued.issue("one");
        clock.advance(Duration.ofMillis(AdminLog.INTENT_TTL_MS));
        assertTrue(issued.holds("one"), "the last instant of its time");
        clock.advance(Duration.ofMillis(1));
        assertFalse(issued.holds("one"));
    }

    @Test
    void anExpiredIntentIsDroppedWhenAnotherIsPreparedAndAPreparedAgainOneLivesFromThen() {
        HandClock clock = new HandClock();
        AdminIssuedIntents issued = new AdminIssuedIntents(clock);
        issued.issue("old");
        issued.issue("again");
        clock.advance(Duration.ofMillis(AdminLog.INTENT_TTL_MS - 1));
        issued.issue("again");
        clock.advance(Duration.ofMillis(2));
        issued.issue("new");
        assertFalse(issued.holds("old"));
        assertTrue(issued.holds("again"), "prepared again, it lives from the second time");
        assertTrue(issued.holds("new"));
    }

    @Test
    void theNewestAreKeptWhenMoreArePreparedThanAreRemembered() {
        AdminIssuedIntents issued = new AdminIssuedIntents(new HandClock());
        int prepared = 5000;
        for (int number = 0; number < prepared; number++) {
            issued.issue("intent " + number);
        }
        assertFalse(issued.holds("intent 0"), "the oldest was dropped to make room");
        assertTrue(issued.holds("intent " + (prepared - 1)));
    }

    @Test
    void aServerThatStartedLaterHoldsNothingAnEarlierRunPrepared() {
        Clock clock = new HandClock();
        new AdminIssuedIntents(clock).issue("earlier run");
        assertFalse(new AdminIssuedIntents(clock).holds("earlier run"));
    }

    /** D-0249: what the commit asks of this memory does not depend on the clock having kept still since the start; an intent prepared after the clock was set back is held. */
    @Test
    void anIntentPreparedAfterTheClockWasSetBackIsHeldForItsTimeAndSpentOnce() {
        HandClock clock = new HandClock();
        AdminIssuedIntents issued = new AdminIssuedIntents(clock);
        clock.advance(Duration.ofHours(-1));
        issued.issue("after the clock was set back");
        assertTrue(issued.holds("after the clock was set back"));
        clock.advance(Duration.ofMillis(AdminLog.INTENT_TTL_MS));
        assertTrue(issued.holds("after the clock was set back"), "the last instant of its time");
        clock.advance(Duration.ofMillis(1));
        assertFalse(issued.holds("after the clock was set back"));
    }

    /** A transaction manager that holds nothing: only the phases of a transaction, and a commit that the test can make fail. */
    static final class PhasesOnlyTransactionManager extends AbstractPlatformTransactionManager {
        private static final long serialVersionUID = 1L;

        private boolean failCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // nothing to open
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("the database did not commit");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // nothing to undo
        }
    }

    // Deliberately not @Configuration: this class sits under the server package, so the component scan of every server test would pick it up and its transactionManager would collide with the real one.
    @EnableTransactionManagement
    static class TransactionalBeans {
        @Bean
        Clock clock() {
            return new HandClock();
        }

        @Bean
        AdminIssuedIntents issuedIntents(Clock clock) {
            return new AdminIssuedIntents(clock);
        }

        @Bean
        PhasesOnlyTransactionManager transactionManager() {
            return new PhasesOnlyTransactionManager();
        }
    }

    /** D-0249: the intent is spent when the transaction that recorded it has committed, not while it is open and not when it rolled back or the commit failed. */
    @Test
    void theIntentOfARecordIsSpentOnlyWhenItsTransactionCommits() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TransactionalBeans.class)) {
            AdminIssuedIntents issued = context.getBean(AdminIssuedIntents.class);
            PhasesOnlyTransactionManager manager = context.getBean(PhasesOnlyTransactionManager.class);
            TransactionTemplate transaction = new TransactionTemplate(manager);

            issued.issue("committed");
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new AdminIntentSpent("committed"));
                assertTrue(issued.holds("committed"), "still held while the transaction is open: a refusal later in it leaves it");
            });
            assertFalse(issued.holds("committed"), "spent by the commit");

            issued.issue("rolled back");
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new AdminIntentSpent("rolled back"));
                status.setRollbackOnly();
            });
            assertTrue(issued.holds("rolled back"), "a rolled-back record leaves its intent for the page to commit again");

            issued.issue("commit failed");
            manager.failCommit = true;
            assertThrows(TransactionSystemException.class, () -> transaction.executeWithoutResult(status -> context.publishEvent(new AdminIntentSpent("commit failed"))));
            assertTrue(issued.holds("commit failed"), "a commit the database did not make leaves its intent too");
        }
    }
}
