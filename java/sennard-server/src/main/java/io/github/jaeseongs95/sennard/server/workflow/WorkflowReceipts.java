// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.server.mcp.OperationKeys;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the workflow tools do with the operationKey of a write call, over the common {@link OperationReceipts}: check the key, make the {@link OperationKey} (the key and the digest of
 * the call's content). Which results are stored and when is the rule of {@link OperationReceipts}: only a success, as the last statement of the use case's own transaction.
 */
@Component
class WorkflowReceipts {
    static final String FIELD = OperationKey.FIELD;

    /** The member of a completed receipt's JSON that holds the lines the tool showed after its result (no result of a workflow tool has a member of this name). */
    private static final String NOTES = "answerNotes";

    private final OperationReceipts receipts;
    private final JsonMapper mapper;

    WorkflowReceipts(OperationReceipts receipts, JsonMapper mapper) {
        this.receipts = receipts;
        this.mapper = mapper;
    }

    /**
     * What the receipt of a key keeps for a tool whose answer is more than its use case's result: the use case's result is written by the use case, in its transaction ({@link Unanswered}); the
     * answer the call made of it (the result as it was published, and the lines shown after it) is written once the call has it ({@link Answered}, {@link #completeAnswer}).
     */
    sealed interface Stored<R> {
        /** The result as the first call published it ({@link Answered}), or as the use case made it ({@link Unanswered}). */
        R result();

        /** The call that wrote the receipt finished: this is its answer as it was given. */
        record Answered<R>(R result, List<String> notes) implements Stored<R> {
        }

        /** The use case committed with its receipt and the call did not finish (it stopped before it kept its answer): only the use case's result is known. */
        record Unanswered<R>(R result) implements Stored<R> {
        }
    }

    /**
     * What the receipt of the key keeps, when it has succeeded before for this content. The receipt's JSON is the result, and a completed receipt has the notes as one more member; the one
     * place that tells the two apart. (A use case that reads a completed receipt as its own result, in the moment between this read and its own, reads the result and ignores that member: the
     * application's mapper does not refuse an unknown member.)
     *
     * @throws io.github.jaeseongs95.sennard.domain.error.ConflictException the key succeeded before, for this tool and scope, with another content
     */
    <R> Optional<Stored<R>> replayStored(OperationKey operation, Class<R> type) {
        return receipts.replayText(operation).<Stored<R>>map(text -> {
            JsonNode tree = mapper.readTree(text);
            JsonNode notes = tree.get(NOTES);
            if (notes == null) {
                return new Stored.Unanswered<>(mapper.treeToValue(tree, type));
            }
            ((ObjectNode) tree).remove(NOTES);
            return new Stored.Answered<>(mapper.treeToValue(tree, type), List.of(mapper.treeToValue(notes, String[].class)));
        });
    }

    /**
     * Keeps the answer of a call whose use case has committed with its receipt: the result as it is published and the lines shown after it ({@link OperationReceipts#complete}), so that a request
     * that comes again is answered with them and the notes are not made again from what has changed since.
     */
    void completeAnswer(OperationKey operation, Object result, List<String> notes) {
        ObjectNode answer = mapper.valueToTree(result);
        answer.set(NOTES, mapper.valueToTree(notes));
        receipts.complete(operation, answer);
    }

    /**
     * The operation of a call.
     *
     * @param scope the calling session's server-issued reference: the key is the caller's own, so another session's call with the same key is another operation
     * @param sent the operationKey argument, null when the caller sent none
     * @param content the call's arguments (the key, the session reference and the response mode are not part of the digest: {@link OperationKeys#digest})
     * @throws WorkflowFailure {@code INVALID_INPUT}: the key is missing or is not of the common shape. The server makes no key for a caller that sent none.
     */
    OperationKey of(String tool, String scope, Object sent, JsonNode content) {
        if (!OperationKey.valid(sent)) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.service.operationKeyInvalid"), details("reason", "INVALID_OPERATION_KEY", "field", FIELD));
        }
        return new OperationKey(tool, scope, (String) sent, OperationKeys.digest(content));
    }

    /** The stored result of the key, when it has succeeded before for this content; a key that succeeded for other content is the {@code ConflictException} of {@link OperationReceipts}. */
    <R> Optional<R> replay(OperationKey operation, Class<R> type) {
        return receipts.replay(operation, type);
    }

    /** The receipt of a success; the use case calls it in its own transaction, after the last of its work. */
    void record(OperationKey operation, Object result) {
        receipts.record(operation, result);
    }

    /** The answer of a tool whose answer is made after the transaction of its effect (the change plan): replaces the answer of the receipt the use case wrote ({@link OperationReceipts#complete}). */
    void complete(OperationKey operation, Object result) {
        receipts.complete(operation, result);
    }

    <R> R once(OperationKey operation, Class<R> type, Supplier<R> work) {
        return receipts.once(operation, type, work).value();
    }
}
