// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.sennard.app.message.CatalogMessageSource;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The envelope of a failure that the old stack raised as a {@code ToolFailure}, a {@code ContractError} or a {@code ServiceFailure}, before and after: the same
 * {@code error.code} and {@code details} (and, but for the refusals of the session identity, the same {@code messageKey}; the golden files of the tools show them), made at the one
 * boundary {@link SennardToolResults}. Cases (each is what a service raised; expected is what the envelope's error said before):
 *
 * <pre>
 * r1  stale state     the checkpoint's state token is not the current one: STALE_REVISION, key continuity.checkpoint.stateChanged, details with the reason code CONTINUITY_STATE_STALE,
 *                     expectedStateToken and actualStateToken
 * r2  no reason       a request ID reused, a snapshot not found, a snapshot source that changed, a digest that is off, a cleanup token that is malformed, an arguments mismatch with a
 *                     name: their codes (REQUEST_CONFLICT, SNAPSHOT_NOT_FOUND, SNAPSHOT_CONFLICT, INTEGRITY_FAILED, INVALID_INPUT) and keys, and details null
 * r3  a reason        a token that expired: BINDING_INVALID and details {reasonCode: CONTINUITY_TOKEN_EXPIRED}; a layer that is too large: INVALID_INPUT with the reason, the bytes, the limit
 * r4  role unbound    BINDING_REQUIRED, key continuity.layers.roleUnbound, the sentence made from the admin URL, details {reasonCode: ROLE_NOT_BOUND, adminUrl}
 * r5  the continuity  CONTINUITY_UNAVAILABLE with the store's reason in details.reason
 * r6  unexpected      a failure nobody expected is MCP_UNAVAILABLE with a fixed sentence and the reason INTERNAL_FAILURE, a StoreException with its own code as the reason
 * r7  session identity the registry's and the binding's refusals (once a ServiceFailure) are their own names as the code and the reason code, with their catalog key, and details
 *                     {reasonCode, then their own members}
 * r8  a send          the sentence of a malformed send says how to go on, with the contract's sentence in it
 * r9  the flag        the result of any failure is an error result (isError) in English
 * </pre>
 */
class SennardToolResultsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SennardToolResults RESULTS = new SennardToolResults(new CatalogMessageSource(new PathMatchingResourcePatternResolver(), MAPPER), MAPPER);

    private static JsonNode error(McpSchema.CallToolResult result) {
        assertTrue(result.isError(), "a failure is an error result");
        return MAPPER.readTree(((McpSchema.TextContent) result.content().getFirst()).text()).path("error");
    }

    private static JsonNode error(SennardException failure) {
        return error(RESULTS.failure(failure));
    }

    @Test
    void aStaleStateTokenIsStaleRevisionWithItsReasonAndBothTokens() {
        JsonNode error = error(new ConflictException(ConflictCode.CHECKPOINT_STATE_CHANGED, Map.of(),
                SennardException.args("reasonCode", "CONTINUITY_STATE_STALE", "expectedStateToken", "aaa", "actualStateToken", "bbb")));
        assertEquals("STALE_REVISION", error.path("code").asString());
        assertEquals("continuity.checkpoint.stateChanged", error.path("messageKey").asString());
        assertEquals(MAPPER.readTree("{\"reasonCode\":\"CONTINUITY_STATE_STALE\",\"expectedStateToken\":\"aaa\",\"actualStateToken\":\"bbb\"}"), error.path("details"));
    }

    /** The two conflicts of an operationKey carry their reason and field in the exception, and every write tool shows them in this one shape (REQUEST_CONFLICT, details.reason, details.field). */
    @Test
    void theConflictsOfAnOperationKeyAreRequestConflictWithTheReasonAndTheField() {
        JsonNode content = error(new ConflictException(ConflictCode.OPERATION_KEY_CONFLICT, Map.of(), SennardException.args("reason", "OPERATION_CONFLICT", "field", "operationKey")));
        assertEquals("REQUEST_CONFLICT", content.path("code").asString());
        assertEquals("services.operation.keyConflict", content.path("messageKey").asString());
        assertEquals(MAPPER.readTree("{\"reason\":\"OPERATION_CONFLICT\",\"field\":\"operationKey\"}"), content.path("details"));
        JsonNode raced = error(new ConflictException(ConflictCode.OPERATION_KEY_IN_PROGRESS, Map.of(), SennardException.args("reason", "OPERATION_IN_PROGRESS", "field", "operationKey")));
        assertEquals("REQUEST_CONFLICT", raced.path("code").asString());
        assertEquals("services.operation.keyInProgress", raced.path("messageKey").asString());
        assertEquals(MAPPER.readTree("{\"reason\":\"OPERATION_IN_PROGRESS\",\"field\":\"operationKey\"}"), raced.path("details"));
        assertTrue(raced.path("message").asString().contains("send the same request again"), raced.toString());
    }

    @Test
    void aFailureThatNeverShowedAReasonHasNoneAndItsDetailsAreNull() {
        record Case(SennardException failure, String code, String key) {
        }
        for (Case expected : java.util.List.of(new Case(new ConflictException(ConflictCode.CHECKPOINT_REQUEST_CONFLICT), "REQUEST_CONFLICT", "continuity.checkpoint.requestConflict"),
                new Case(new NotFoundException(NotFoundCode.RESTORE_SUPPRESSED), "SNAPSHOT_NOT_FOUND", "continuity.restore.suppressed"),
                new Case(new ConflictException(ConflictCode.SNAPSHOT_SOURCE_CHANGED), "SNAPSHOT_CONFLICT", "continuity.source.changed"),
                new Case(new IntegrityException(IntegrityCode.SNAPSHOT_DIGEST_INVALID), "INTEGRITY_FAILED", "continuity.snapshot.digestInvalid"),
                new Case(new InvalidInputException(InputCode.STATE_CLEANUP_TOKEN_MALFORMED), "INVALID_INPUT", "services.stateCleanup.tokenMalformed"),
                new Case(new InvalidInputException(InputCode.UPDATES_ARGUMENTS_INVALID), "INVALID_INPUT", "server.updates.argumentsInvalid"),
                new Case(new InvalidInputException(InputCode.TOOL_ARGUMENTS_MISMATCH, Map.of("name", "koreanProseGlossaryLookupRequest")), "INVALID_INPUT",
                        "workflow.schema.contractMismatch"))) {
            JsonNode error = error(expected.failure());
            assertEquals(expected.code(), error.path("code").asString(), expected.key());
            assertEquals(expected.key(), error.path("messageKey").asString(), expected.key());
            assertTrue(error.path("details").isNull(), expected.key() + ": " + error);
        }
    }

    @Test
    void aReasonTheContractShowedIsInTheDetails() {
        JsonNode expired = error(new BindingException(BindingCode.CONTINUITY_TOKEN_EXPIRED));
        assertEquals("BINDING_INVALID", expired.path("code").asString());
        assertEquals(MAPPER.readTree("{\"reasonCode\":\"CONTINUITY_TOKEN_EXPIRED\"}"), expired.path("details"));

        JsonNode large = error(new InvalidInputException(InputCode.CONTEXT_LAYER_TOO_LARGE, Map.of("layer", "global", "bytes", 4001, "limit", 4000), Map.of("actualBytes", 4001, "maxBytes", 4000)));
        assertEquals("INVALID_INPUT", large.path("code").asString());
        assertEquals("CONTEXT_LAYER_TOO_LARGE", large.path("details").path("reasonCode").asString());
        assertEquals(4001, large.path("details").path("actualBytes").asInt());
        assertEquals(MAPPER.readTree("{\"layer\":\"global\",\"bytes\":4001,\"limit\":4000}"), large.path("params"));
    }

    @Test
    void anUnboundRoleIsBindingRequiredWithTheReasonAndTheAdminUrlOfTheOldAnswer() {
        JsonNode error = error(new BindingException(BindingCode.CONTEXT_ROLE_UNBOUND, Map.of("adminUrl", "http://localhost:1"), SennardException.args("reasonCode", "ROLE_NOT_BOUND", "adminUrl", "http://localhost:1")));
        assertEquals("BINDING_REQUIRED", error.path("code").asString());
        assertEquals("continuity.layers.roleUnbound", error.path("messageKey").asString());
        assertEquals("This session is not bound to a role. The user approves a binding with a passkey on the local admin page (http://localhost:1). Leave a request with bind_session_role and tell the user.",
                error.path("message").asString());
        assertEquals(MAPPER.readTree("{\"reasonCode\":\"ROLE_NOT_BOUND\",\"adminUrl\":\"http://localhost:1\"}"), error.path("details"));
    }

    @Test
    void aContinuityFailureKeepsItsPublicCodeAndTheStoresReason() {
        JsonNode error = error(new UnavailableException(UnavailableCode.CONTINUITY_UNAVAILABLE, Map.of(), Map.of("reason", "STORE_TIMEOUT"), null));
        assertEquals("CONTINUITY_UNAVAILABLE", error.path("code").asString());
        assertEquals("continuity.operation.failed", error.path("messageKey").asString());
        assertEquals(MAPPER.readTree("{\"reason\":\"STORE_TIMEOUT\"}"), error.path("details"));
    }

    @Test
    void aFailureNobodyExpectedIsAFixedSentenceAndItsReason() {
        JsonNode other = error(RESULTS.unexpected(new IllegalStateException("secret detail")));
        assertEquals("MCP_UNAVAILABLE", other.path("code").asString());
        assertEquals("The server could not complete the request.", other.path("message").asString());
        assertEquals(MAPPER.readTree("{\"reason\":\"INTERNAL_FAILURE\"}"), other.path("details"));
        assertTrue(!other.toString().contains("secret detail"), other.toString());

        JsonNode store = error(RESULTS.unexpected(new StoreException(StoreCode.STORE_FAILURE, "The database statement failed.")));
        assertEquals("MCP_UNAVAILABLE", store.path("code").asString());
        assertEquals(MAPPER.readTree("{\"reason\":\"STORE_FAILURE\"}"), store.path("details"));
    }

    @Test
    void aSessionIdentityRefusalIsItsOwnNameAsTheCodeAndTheReasonWithItsMembersInTheDetails() {
        JsonNode required = error(new BindingException(BindingCode.BINDING_REQUIRED, Map.of(), SennardException.args("reasonCode", "CODEX_CALL_META_MISSING", "count", "3")));
        assertEquals("BINDING_REQUIRED", required.path("code").asString());
        assertEquals("error.BINDING_REQUIRED", required.path("messageKey").asString());
        assertEquals(MAPPER.readTree("{\"reasonCode\":\"CODEX_CALL_META_MISSING\",\"count\":\"3\"}"), required.path("details"));

        JsonNode protectedGeneration = error(new ConflictException(ConflictCode.CURRENT_GENERATION_PROTECTED, Map.of(), SennardException.args("sessionRef", "ref-1", "retryAfterMs", 1500L)));
        assertEquals("CURRENT_GENERATION_PROTECTED", protectedGeneration.path("code").asString());
        assertEquals("error.CURRENT_GENERATION_PROTECTED", protectedGeneration.path("messageKey").asString());
        assertEquals(MAPPER.readTree("{\"reasonCode\":\"CURRENT_GENERATION_PROTECTED\",\"sessionRef\":\"ref-1\",\"retryAfterMs\":1500}"), protectedGeneration.path("details"));
    }

    /** r10: the one place that builds a tool's result: the notes follow the envelope as one more text, and isError is the envelope's own ok. */
    @Test
    void theResultOfAToolIsTheEnvelopeFirstThenItsNotesAndIsErrorFollowsOk() {
        McpSchema.CallToolResult withNotes = RESULTS.answer(ApiResult.ok(Map.of("a", 1)), java.util.List.of("first note", "second note"));
        assertEquals(2, withNotes.content().size());
        assertEquals(MAPPER.readTree("{\"schemaVersion\":\"1.0.0\",\"ok\":true,\"data\":{\"a\":1},\"error\":null}"), MAPPER.readTree(((McpSchema.TextContent) withNotes.content().getFirst()).text()));
        assertEquals("first note\nsecond note",((McpSchema.TextContent) withNotes.content().get(1)).text());
        assertEquals(Boolean.FALSE, withNotes.isError());
        assertEquals("en", withNotes.meta().get("io.modelcontextprotocol/contentLanguage"));
        assertEquals(1, RESULTS.answer(ApiResult.ok(null)).content().size(), "no notes, no second text");

        McpSchema.CallToolResult failed = RESULTS.failure(new ApiResult.ErrorBody("GATE_FAILED", "sentence", "workflow.key", Map.of(), Map.of("reason", "X")));
        assertEquals("GATE_FAILED", error(failed).path("code").asString());
        assertEquals(Boolean.TRUE, failed.isError());
    }

    /** r11: the guard in front of every tool refuses through the same place, with a catalog key and its arguments, and the sentence the old guard wrote. */
    @Test
    void theGuardsRefusalsAreInputFailuresWithTheirOwnKeys() {
        JsonNode undeclared = error(new InvalidInputException(InputCode.UNDECLARED_ARGUMENT, SennardException.args("arguments", "[bogus]")));
        assertEquals("INVALID_INPUT", undeclared.path("code").asString());
        assertEquals("Undeclared arguments: [bogus]", undeclared.path("message").asString());
        assertEquals("server.call.undeclaredArgument", undeclared.path("messageKey").asString());
        assertEquals(MAPPER.readTree("{\"reasonCode\":\"UNDECLARED_ARGUMENT\"}"), undeclared.path("details"));
        JsonNode conversion = error(new InvalidInputException(InputCode.ARGUMENT_CONVERSION_FAILED));
        assertEquals("The arguments do not match the tool's input schema.", conversion.path("message").asString());
        assertEquals(MAPPER.readTree("{\"reasonCode\":\"ARGUMENT_CONVERSION_FAILED\"}"), conversion.path("details"));
    }

    /** r12: a job that is not there, and a text that is not a job reference, answer in the envelope's own codes. */
    @Test
    void aJobThatIsNotThereAndAReferenceThatIsNotOneAreNotFoundAndInvalidInput() {
        JsonNode absent = error(new NotFoundException(NotFoundCode.JOB_NOT_FOUND));
        assertEquals("NOT_FOUND", absent.path("code").asString());
        assertEquals("skillCheck.jobNotFound", absent.path("messageKey").asString());
        assertEquals(MAPPER.readTree("{\"reasonCode\":\"JOB_NOT_FOUND\"}"), absent.path("details"));
        JsonNode malformed = error(new InvalidInputException(InputCode.JOB_REFERENCE_INVALID));
        assertEquals("INVALID_INPUT", malformed.path("code").asString());
        assertEquals("jobRef is not a job reference.", malformed.path("message").asString());
    }

    @Test
    void aMalformedSendSaysHowToGoOnWithTheContractsSentenceInIt() {
        JsonNode error = error(new InvalidInputException(InputCode.SEND_INVALID, Map.of("cause", "send_session_message does not match its v1 contract."),
                Map.of("validationErrors", java.util.List.of("required property 'messageId' not found"))));
        assertEquals("INVALID_INPUT", error.path("code").asString());
        assertEquals("server.sessionMessage.sendInvalid", error.path("messageKey").asString());
        assertTrue(error.path("message").asString().startsWith("send_session_message does not match its v1 contract."), error.path("message").asString());
        assertEquals(MAPPER.readTree("{\"validationErrors\":[\"required property 'messageId' not found\"]}"), error.path("details"));
    }
}
