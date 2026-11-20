// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.approval.StoreRootApprovalService.Decision;
import io.github.jaeseongs95.sennard.server.approval.StoreRootApprovalService.DecisionSource;
import io.github.jaeseongs95.sennard.server.approval.StoreRootApprovalService.PendingRecord;
import io.github.jaeseongs95.sennard.server.approval.StoreRootApprovalService.ScopeFacts;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The records of a root approval. The scope a decision carries is the waiting request's scope as it was written, because the workflow checks the decision by the canonical
 * digest of exactly those members; the rest of the stored text is read and written through records. The queue, the lock and the delete are held by the admin page and workflow
 * tests, which go through the service.
 *
 * <pre>
 * a1  a waiting request          the scope's action, root and actor, the two texts; a release has no replacement scope (null)
 * a2  a decision                 the members in stored order, the source's kind by the constant's name, revokedAt null and present
 * a3  the scope is verbatim      a member this class does not know (and a null one) is in the decision and the digest of the copy is the digest of the original
 * a4  an action that is not one  the binding is an error
 * </pre>
 */
class StoreRootApprovalRecordsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SCOPE = "{\"action\":\"REPLACE_ROOT\",\"rootId\":\"root-1\",\"actorId\":\"codex:s:root\",\"rootDigest\":\"sha256:aa\",\"replacementDigest\":null,\"future\":{\"a\":[1,2]}}";

    @Test
    void a1AWaitingRequestIsReadToItsFacts() {
        PendingRecord record = MAPPER.readValue("{\"scopeDigest\":\"sha256:x\",\"scope\":" + SCOPE + ",\"message\":\"m\",\"currentScope\":\"now\",\"replacementScope\":\"then\",\"requestedBy\":\"a\"}", PendingRecord.class);
        ScopeFacts scope = MAPPER.treeToValue(record.scope(), ScopeFacts.class);
        assertEquals(RootAction.REPLACE_ROOT, scope.action());
        assertEquals("root-1", scope.rootId());
        assertEquals("codex:s:root", scope.actorId());
        assertEquals("now", record.currentScope());
        assertEquals("then", record.replacementScope());
        assertNull(MAPPER.readValue("{\"scope\":" + SCOPE + ",\"currentScope\":\"now\"}", PendingRecord.class).replacementScope());
    }

    @Test
    void a2ADecisionIsWrittenInStoredOrder() {
        JsonNode scope = MAPPER.readTree("{\"action\":\"RELEASE_ROOT\"}");
        Decision decision = new Decision("approval-1", scope, "sha256:x", new DecisionSource(ApprovalSourceKind.ADMIN_PAGE, "sha256:h", "cred"), "2026-11-11T00:00:00Z", null);
        assertEquals("{\"approvalId\":\"approval-1\",\"scope\":{\"action\":\"RELEASE_ROOT\"},\"scopeDigest\":\"sha256:x\","
                + "\"source\":{\"kind\":\"ADMIN_PAGE\",\"recordHash\":\"sha256:h\",\"credentialId\":\"cred\"},\"createdAt\":\"2026-11-11T00:00:00Z\",\"revokedAt\":null}", MAPPER.writeValueAsString(decision));
    }

    @Test
    void a3TheScopeIsCopiedVerbatimSoItsDigestHolds() {
        JsonNode original = MAPPER.readTree(SCOPE);
        PendingRecord record = MAPPER.readValue("{\"scope\":" + SCOPE + ",\"currentScope\":\"now\"}", PendingRecord.class);
        Decision decision = new Decision("approval-1", record.scope(), CanonicalJson.digest(original), new DecisionSource(ApprovalSourceKind.ADMIN_PAGE, "sha256:h", "cred"), "t", null);
        JsonNode stored = MAPPER.readTree(MAPPER.writeValueAsString(decision));
        assertEquals(CanonicalJson.digest(original), CanonicalJson.digest(stored.get("scope")));
        assertEquals(CanonicalJson.digest(original), stored.get("scopeDigest").asString());
    }

    @Test
    void a4AnActionThatIsNoneOfThemIsABindingError() {
        PendingRecord record = MAPPER.readValue("{\"scope\":{\"action\":\"DELETE_ROOT\",\"rootId\":\"r\",\"actorId\":\"a\"},\"currentScope\":\"now\"}", PendingRecord.class);
        assertThrows(JacksonException.class, () -> MAPPER.treeToValue(record.scope(), ScopeFacts.class));
    }
}
