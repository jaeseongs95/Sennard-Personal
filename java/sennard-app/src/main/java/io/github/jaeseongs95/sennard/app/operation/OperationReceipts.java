// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.operation;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one mechanism by which a write tool answers a request that comes again with the same operationKey (AGS3-D-0267). The caller names the operation with an operationKey
 * (required, never made by the server); the receipt of (tool, scope, key) keeps the answer of the first request that succeeded and the digest of that request's content. The scope is the caller (the server-issued reference of the calling session; {@link OperationKey#GLOBAL_SCOPE} for {@code open_session}), so another session that sends the same key is another operation with its own effect.
 *
 * <ul>
 * <li>The same key and the same digest: {@link #replay} gives the stored answer, and the use case does not run again.</li>
 * <li>The same key and another digest: {@link ConflictException} ({@code OPERATION_KEY_CONFLICT}, published as {@code REQUEST_CONFLICT}).</li>
 * <li>A key not seen: the use case runs, and {@link #record} writes the receipt as the last statement of the same transaction, so the effect and its receipt commit or roll back together.</li>
 * </ul>
 *
 * <p><b>Only a success is stored.</b> A refusal has no effect and is rolled back with the transaction of the use case, so the same key sent again is judged again and refused again for the same
 * reason; no receipt of a refusal exists to answer it. (A use case that commits what it observed on a refusal returns the refusal as a value instead of throwing it, and still writes no receipt, because it reaches {@link #record}
 * only when it succeeds.)
 *
 * <p>The use case claims nothing first: the key is read, the work is done, the receipt is inserted last. Two first requests with one key at the same moment both find no receipt, and the
 * second one's INSERT meets the first one's primary key and ends with the retryable {@code OPERATION_KEY_IN_PROGRESS} conflict (made in {@link #recordText}, the one place that knows the key is the cause), which rolls back its work; it is not run again
 * (PostgreSQL ends the transaction of a unique violation, so a claim by INSERT first could not read the stored row afterwards). The caller sends the request again with the same key and is answered with the
 * stored answer. Both conflicts of the key carry {@code details.reason} and {@code details.field}, so every tool publishes them alike.
 *
 * <p><b>Retention.</b> A receipt answers for {@code sennard.operations.receipt-retention} (default 24 hours); an older one is not answered from and is removed when the next receipt is written.
 * <b>Not on this mechanism:</b> {@code classify_skills} and {@code record_skill_selection} keep their operationKey in the server's memory ({@code SkillClassificationService}): their answer is advice
 * for the session's skill choice and is needed only for the life of the server, so a receipt in the database would keep it longer than it is of use.
 *
 * <p>External effects that cannot roll back reserve a pending result with {@link #reserveExternal} before calling the host. The caller runs without a transaction, sends only for
 * {@link Receipted.Fresh}, and updates the receipt with {@link #complete} when the outcome is known.
 *
 * <p>The response is stored as text, and this service writes and reads it as JSON: {@link #once}, {@link #record} and {@link #complete} write the result's JSON with the mapper, and
 * {@link #once} and {@link #replay} read it back as the result's type. {@link #replayText} and {@link #recordText} do not touch the text: a caller that makes the text itself
 * (the entry of a tool that reads the stored answer before it plans its work) reads and writes it as it is.
 */
@Service
public class OperationReceipts {
    private final OperationReceiptRepository receipts;
    private final Clock clock;
    private final OperationProperties properties;
    private final JsonMapper mapper;

    public OperationReceipts(OperationReceiptRepository receipts, Clock clock, OperationProperties properties, JsonMapper mapper) {
        this.receipts = receipts;
        this.clock = clock;
        this.properties = properties;
        this.mapper = mapper;
    }

    /** The stored result of the operation, when its key has succeeded before for this content ({@link #replayText}); the receipt's text is the result's JSON. */
    @Transactional(readOnly = true)
    public <R> Optional<R> replay(OperationKey operation, Class<R> type) {
        return replayText(operation).map(text -> mapper.readValue(text, type));
    }

    /** The receipt of a success: the result as JSON, in the transaction of the use case ({@link #recordText}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(OperationKey operation, Object result) {
        recordText(operation, mapper.writeValueAsString(result));
    }

    /**
     * The use case once per key, in the transaction of the use case that calls it: the stored result when the key has succeeded before for this content ({@link Receipted.Replayed}),
     * otherwise the work and then its receipt ({@link Receipted.Fresh}). A use case that has no result to keep returns a value of its own that stands for it. What a caller does after
     * the work that is not part of the transaction (an event, a wake) it does for {@code Fresh} only.
     */
    public <R> Receipted<R> once(OperationKey operation, Class<R> type, Supplier<R> work) {
        Optional<R> stored = replay(operation, type);
        if (stored.isPresent()) {
            return new Receipted.Replayed<>(stored.get());
        }
        R result = work.get();
        record(operation, result);
        return new Receipted.Fresh<>(result);
    }

    /** Reserves a pending receipt for a caller outside any transaction. Only Fresh may proceed to the external effect. */
    @Transactional
    public <R> Receipted<R> reserveExternal(OperationKey operation, Class<R> type, R pending) {
        return once(operation, type, () -> pending);
    }

    /**
     * The stored answer (the text) of the operation, or nothing when its key has not succeeded yet for this tool and scope. Called by the entry before it plans its file reads (a
     * replay of a request whose revision has moved on must not be refused as stale by that planning) and again by the use case inside its transaction.
     *
     * @throws ConflictException the key succeeded before, for this tool and scope, with another content (the operation's digest differs)
     */
    @Transactional(readOnly = true)
    public Optional<String> replayText(OperationKey operation) {
        // A receipt past the retention is not answered from, though it may not be removed yet: the key is a new request (record removes it before it writes the new receipt).
        Instant oldest = oldestKept();
        return receipts.findByKeyToolAndKeyScopeAndKeyOperationKey(operation.tool(), operation.scope(), operation.key()).filter(receipt -> !receipt.createdAt().isBefore(oldest)).map(receipt -> {
            if (!receipt.requestDigest().equals(operation.digest())) {
                throw new ConflictException(ConflictCode.OPERATION_KEY_CONFLICT, Map.of(), SennardException.args("reason", "OPERATION_CONFLICT", "field", OperationKey.FIELD));
            }
            return receipt.response();
        });
    }

    /**
     * Writes the receipt of a success, in the transaction of the use case that did the work (it has none of its own). Receipts older than {@code sennard.operations.receipt-retention}
     * (default 24 hours) are removed in the same statement run, as the board and the messaging queue remove what has lapsed when they write: Sennard has no periodic cleanup job to
     * attach this to, and none is made for it. A key sent again after the retention is a new request.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordText(OperationKey operation, String response) {
        Instant now = now();
        receipts.deleteByCreatedAtLessThan(oldestKept());
        try {
            receipts.insert(operation.tool(), operation.scope(), operation.key(), operation.digest(), response, now);
        } catch (DuplicateKeyException raced) {
            // The one INSERT whose primary key is the operation's own: another first request of the same caller and key committed its receipt while this one worked. This call's work is
            // rolled back with its transaction and nothing runs it again; it is the retryable conflict, and the caller's same request answers with the first one's result. This is
            // the one translation of that race (every tool's entrance shows the exception as it is), and the cause is kept.
            throw new ConflictException(ConflictCode.OPERATION_KEY_IN_PROGRESS, Map.of(), SennardException.args("reason", "OPERATION_IN_PROGRESS", "field", OperationKey.FIELD), raced);
        }
    }

    /**
     * Replaces the answer of the receipt this call wrote, for a tool whose answer is made after the transaction that wrote the effect and its receipt ({@code submit_change_plan}: its
     * file reads and its baseline capture stay outside every transaction; the workflow tools: the change notice scans the files). It is one statement of a use case of its own, run after the call has the answer it gives, so that a request
     * that comes again is answered with that first answer and not with one made again from what has changed since. The receipt of the effect is the one written by {@link #once} (or
     * {@link #record}) for the same operation; it is not made here.
     *
     * @throws IllegalStateException the receipt is not there (it was never written for this operation, or the call outlived its retention)
     */
    @Transactional
    public void complete(OperationKey operation, Object result) {
        int changed = receipts.replaceResponse(operation.tool(), operation.scope(), operation.key(), operation.digest(), mapper.writeValueAsString(result));
        if (changed != 1) {
            throw new IllegalStateException("There is no receipt of this operation to complete: " + operation.tool());
        }
    }

    /** The clock's instant to the millisecond, the precision the column stores (every receipt time is made and compared at it). */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * The oldest creation time a receipt may have and still answer. One value for both sides: a receipt made before it is not answered from ({@link #replay}) and is removed
     * ({@link #record}); a receipt made at it or after it is answered from and is not removed, so no receipt is both unanswered and unremoved.
     */
    private Instant oldestKept() {
        return now().minus(properties.receiptRetention());
    }
}
