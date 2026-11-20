// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.app.operation.Receipted;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;

/**
 * Cases planned before execution (AGS3-D-0267), over the real table {@code sennard.operation_receipt} on the business database:
 * <ol>
 * <li>a key that has not succeeded has no stored answer;</li>
 * <li>a receipt written in a transaction answers the same key and the same digest with the stored response;</li>
 * <li>the same key with another digest is the {@code OPERATION_KEY_CONFLICT} conflict;</li>
 * <li>the key of another tool is another operation;</li>
 * <li>a transaction that fails after writing the receipt leaves none (only a success is stored, effect and receipt commit or roll back together);</li>
 * <li>a receipt cannot be written outside the transaction of a use case;</li>
 * <li>the key is the caller's own: the same tool and key under another scope is another operation (and {@code open_session}'s global scope is one scope for every caller);</li>
 * <li>one boundary of the retention for answering and for removing (a fixed clock bean, no waiting);</li>
 * <li>two overlapping first requests of one caller and key: the second ends in the retryable {@code OPERATION_KEY_IN_PROGRESS}, and both conflicts carry the reason and field details;</li>
 * <li>a receipt is completed with the answer of a tool that makes it after the transaction of its effect, and only the receipt of the same operation and content;</li>
 * <li>{@code once} does the work for the first request only and says whether a call was that one ({@code Fresh}) or was answered from the receipt ({@code Replayed}).</li>
 * </ol>
 */
@BusinessDbTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationReceiptsTest {
    private static final String DIGEST = "a".repeat(64);
    private static final String OTHER_DIGEST = "b".repeat(64);
    private static final String SCOPE_A = "as1_TESTSCOPEAAAAAAAAAAAAAAAA";
    private static final String SCOPE_B = "as1_TESTSCOPEBBBBBBBBBBBBBBBB";

    private RewrittenAreasContext context;
    private OperationReceipts receipts;
    private TransactionTemplate transaction;

    @BeforeAll
    void start() {
        context = RewrittenAreasContext.appConnection(Instant.parse("2026-03-04T05:06:07.089Z"));
        receipts = context.bean(OperationReceipts.class);
        transaction = new TransactionTemplate(context.transactionManager());
    }

    @AfterAll
    void close() {
        if (context != null) {
            context.close();
        }
    }

    private static OperationKey operation(String tool, String key, String digest) {
        return new OperationKey(tool, SCOPE_A, key, digest);
    }

    private static OperationKey operation(String tool, String scope, String key, String digest) {
        return new OperationKey(tool, scope, key, digest);
    }

    /** The key is the caller's own: another scope's request with the same tool, key and content is a request of its own, and is not answered with the first scope's answer. */
    @Test
    void theSameKeyOfAnotherScopeIsAnotherOperationWithItsOwnReceipt() {
        String key = key();
        transaction.executeWithoutResult(status -> receipts.recordText(operation("update_session_status", SCOPE_A, key, DIGEST), "{\"who\":\"a\"}"));
        assertEquals(Optional.empty(), receipts.replayText(operation("update_session_status", SCOPE_B, key, DIGEST)), "no answer of A's for B, whatever the content");
        assertEquals(Optional.empty(), receipts.replayText(operation("update_session_status", SCOPE_B, key, OTHER_DIGEST)), "and no conflict with A's content either");
        transaction.executeWithoutResult(status -> receipts.recordText(operation("update_session_status", SCOPE_B, key, OTHER_DIGEST), "{\"who\":\"b\"}"));
        assertEquals(Optional.of("{\"who\":\"a\"}"), receipts.replayText(operation("update_session_status", SCOPE_A, key, DIGEST)));
        assertEquals(Optional.of("{\"who\":\"b\"}"), receipts.replayText(operation("update_session_status", SCOPE_B, key, OTHER_DIGEST)));
        assertThrows(ConflictException.class, () -> receipts.replayText(operation("update_session_status", SCOPE_A, key, OTHER_DIGEST)), "A's own key with other content is still A's conflict");
    }

    /** {@code open_session} has no caller yet: its scope is the one global constant, so the same key answers the same receipt whoever sends it. */
    @Test
    void theGlobalScopeIsOneScopeForEveryCaller() {
        String key = key();
        transaction.executeWithoutResult(status -> receipts.recordText(operation("open_session", OperationKey.GLOBAL_SCOPE, key, DIGEST), "{\"sessionRef\":\"as1_x\"}"));
        assertEquals(Optional.of("{\"sessionRef\":\"as1_x\"}"), receipts.replayText(operation("open_session", OperationKey.GLOBAL_SCOPE, key, DIGEST)));
        assertEquals(Optional.empty(), receipts.replayText(operation("open_session", SCOPE_A, key, DIGEST)), "a session's scope is not the global one");
    }

    private static String key() {
        return "receipt-test-" + UUID.randomUUID();
    }

    /** The work of {@code once} runs for the first request only, and the caller can tell which request it was: its after-effects (an event, a wake) are for the first one. */
    @Test
    void onceSaysWhetherThisCallDidTheWorkOrWasAnsweredFromTheReceipt() {
        OperationKey operation = operation("update_session_status", key(), DIGEST);
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        Receipted<String> first = transaction.execute(status -> receipts.once(operation, String.class, () -> {
            runs.incrementAndGet();
            return "the first answer";
        }));
        Receipted<String> again = transaction.execute(status -> receipts.once(operation, String.class, () -> {
            runs.incrementAndGet();
            return "an answer that must not be made";
        }));
        assertEquals(new Receipted.Fresh<>("the first answer"), first);
        assertEquals(new Receipted.Replayed<>("the first answer"), again);
        assertEquals(1, runs.get(), "the work was done once");
    }

    @Test
    void aKeyThatHasNotSucceededHasNoStoredAnswer() {
        assertEquals(Optional.empty(), receipts.replayText(operation("start_workflow", key(), DIGEST)));
    }

    @Test
    void aReceiptAnswersTheSameKeyAndDigestWithTheStoredResponse() {
        String key = key();
        transaction.executeWithoutResult(status -> receipts.recordText(operation("start_workflow", key, DIGEST), "{\"runId\":\"run-1\"}"));
        assertEquals(Optional.of("{\"runId\":\"run-1\"}"), receipts.replayText(operation("start_workflow", key, DIGEST)));
        assertEquals(Optional.of("{\"runId\":\"run-1\"}"), receipts.replayText(operation("start_workflow", key, DIGEST)));
    }

    @Test
    void theSameKeyWithAnotherDigestIsAConflict() {
        String key = key();
        transaction.executeWithoutResult(status -> receipts.recordText(operation("finalize_workflow", key, DIGEST), "{}"));
        ConflictException conflict = assertThrows(ConflictException.class, () -> receipts.replayText(operation("finalize_workflow", key, OTHER_DIGEST)));
        assertEquals(ConflictCode.OPERATION_KEY_CONFLICT, conflict.code());
    }

    @Test
    void theKeyOfAnotherToolIsAnotherOperation() {
        String key = key();
        transaction.executeWithoutResult(status -> receipts.recordText(operation("abort_workflow", key, DIGEST), "{}"));
        assertEquals(Optional.empty(), receipts.replayText(operation("finalize_workflow", key, OTHER_DIGEST)));
    }

    @Test
    void aTransactionThatFailsAfterWritingTheReceiptLeavesNone() {
        String key = key();
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            receipts.recordText(operation("plan_workflow", key, DIGEST), "{}");
            throw new IllegalStateException("the use case failed after its last statement");
        }));
        assertEquals(Optional.empty(), receipts.replayText(operation("plan_workflow", key, DIGEST)));
    }

    @Test
    void aReceiptOlderThanTheRetentionIsNotAnsweredFromAndTheKeyIsANewRequest() {
        Instant start = Instant.parse("2026-03-04T05:06:07.089Z");
        String key = key();
        try {
            context.clock().set(start);
            transaction.executeWithoutResult(status -> receipts.recordText(operation("start_workflow", key, DIGEST), "{\"runId\":\"old\"}"));
            context.clock().set(start.plus(java.time.Duration.ofHours(25)));
            assertEquals(Optional.empty(), receipts.replayText(operation("start_workflow", key, OTHER_DIGEST)), "past the 24 hours default, even another content is a new request, not a conflict");
            transaction.executeWithoutResult(status -> receipts.recordText(operation("start_workflow", key, OTHER_DIGEST), "{\"runId\":\"new\"}"));
            assertEquals(Optional.of("{\"runId\":\"new\"}"), receipts.replayText(operation("start_workflow", key, OTHER_DIGEST)));
        } finally {
            context.clock().set(start);
        }
    }

    /** One boundary for answering and for removing, at the millisecond the column stores, with the fixed clock bean and no waiting. */
    @Test
    void aReceiptAnswersUpToTheRetentionAndIsRemovedOnlyAfterItAtTheSameBoundary() {
        Instant start = Instant.parse("2026-03-04T05:06:07.089Z");
        java.time.Duration retention = java.time.Duration.ofHours(24);
        String key = key();
        try {
            context.clock().set(start);
            transaction.executeWithoutResult(status -> receipts.recordText(operation("start_workflow", key, DIGEST), "{\"runId\":\"first\"}"));
            context.clock().set(start.plus(retention));
            assertEquals(Optional.of("{\"runId\":\"first\"}"), receipts.replayText(operation("start_workflow", key, DIGEST)), "a receipt exactly at the retention still answers");
            context.clock().set(start.plus(retention).plusNanos(900_000));
            assertEquals(Optional.of("{\"runId\":\"first\"}"), receipts.replayText(operation("start_workflow", key, DIGEST)), "the clock's sub-millisecond part is cut as record cuts it");
            context.clock().set(start.plus(retention).plusMillis(1));
            assertEquals(Optional.empty(), receipts.replayText(operation("start_workflow", key, DIGEST)), "one millisecond later the receipt does not answer");
            // The same instant removes it: the key can be written again at once (it would meet its own primary key if it were not removed).
            transaction.executeWithoutResult(status -> receipts.recordText(operation("start_workflow", key, DIGEST), "{\"runId\":\"second\"}"));
            assertEquals(Optional.of("{\"runId\":\"second\"}"), receipts.replayText(operation("start_workflow", key, DIGEST)));
        } finally {
            context.clock().set(start);
        }
    }

    /**
     * Two first requests of one caller with one key that overlap: both found no receipt, the first one committed its receipt, and the second one's INSERT meets its primary key. The
     * second ends with the retryable conflict {@code OPERATION_KEY_IN_PROGRESS}, with the same reason and field details every tool shows, and its transaction leaves no receipt of its
     * own; the winner's receipt answers the same request sent again. (The winner commits in a thread of its own, on the other connection.)
     */
    @Test
    void theSecondOfTwoOverlappingFirstRequestsEndsInTheRetryableConflictAndTheFirstReceiptStays() {
        OperationKey operation = operation("update_session_status", key(), DIGEST);
        ConflictException lost = assertThrows(ConflictException.class, () -> transaction.executeWithoutResult(status -> {
            assertEquals(Optional.empty(), receipts.replayText(operation), "the second request found no receipt");
            // The first request commits its receipt on a connection of its own (another thread, another transaction), as the request of another connection does.
            try (java.util.concurrent.ExecutorService other = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                other.submit(() -> transaction.executeWithoutResult(winner -> receipts.recordText(operation, "{\"who\":\"first\"}"))).get();
            } catch (InterruptedException | java.util.concurrent.ExecutionException failed) {
                throw new IllegalStateException("the first request did not commit its receipt", failed);
            }
            receipts.recordText(operation, "{\"who\":\"second\"}");
        }));
        assertEquals(ConflictCode.OPERATION_KEY_IN_PROGRESS, lost.code());
        assertEquals("OPERATION_IN_PROGRESS", lost.details().get("reason"));
        assertEquals("operationKey", lost.details().get("field"));
        assertEquals(Optional.of("{\"who\":\"first\"}"), receipts.replayText(operation), "the first receipt is the answer of the request sent again");
    }

    @Test
    void theConflictOfAnotherContentCarriesTheSameReasonAndFieldDetails() {
        String key = key();
        transaction.executeWithoutResult(status -> receipts.recordText(operation("update_session_status", key, DIGEST), "{}"));
        ConflictException conflict = assertThrows(ConflictException.class, () -> receipts.replayText(operation("update_session_status", key, OTHER_DIGEST)));
        assertEquals("OPERATION_CONFLICT", conflict.details().get("reason"));
        assertEquals("operationKey", conflict.details().get("field"));
    }

    /** A tool whose answer is made after the transaction of its effect completes the receipt with that answer, and a request that comes again is answered with it. */
    @Test
    void aReceiptIsCompletedWithTheAnswerTheCallGaveAfterItsEffectWasWritten() {
        OperationKey operation = operation("submit_change_plan", key(), DIGEST);
        transaction.executeWithoutResult(status -> receipts.recordText(operation, "{\"planRevision\":2,\"answer\":null}"));
        java.util.Map<String, Object> answer = new java.util.LinkedHashMap<>();
        answer.put("planRevision", 2);
        answer.put("answer", "the first answer");
        receipts.complete(operation, answer);
        assertEquals(Optional.of("{\"planRevision\":2,\"answer\":\"the first answer\"}"), receipts.replayText(operation));
        assertThrows(IllegalStateException.class, () -> receipts.complete(operation("submit_change_plan", key(), DIGEST), java.util.Map.of()), "no receipt of the operation, nothing to complete");
        assertThrows(IllegalStateException.class, () -> receipts.complete(operation("submit_change_plan", operation.key(), OTHER_DIGEST), java.util.Map.of()), "another content does not complete the receipt");
    }

    @Test
    void aReceiptIsNotWrittenOutsideTheTransactionOfAUseCase() {
        assertThrows(IllegalTransactionStateException.class, () -> receipts.recordText(operation("plan_workflow", key(), DIGEST), "{}"));
    }
}
