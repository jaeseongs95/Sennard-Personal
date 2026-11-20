// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimitSource;
import io.github.jaeseongs95.sennard.server.limits.ResourceLimits;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Real settings API, business log and a software WebAuthn authenticator. */
@Tag("business-db")
class ResourceLimitsServerTest {
    @Test
    void onlyValidSignedChangesReplaceTheCurrentLimitsAndSurviveAnotherServer() throws Exception {
        try (ToolServerSupport server = ToolServerSupport.start()) {
            ToolServerSupport.Passkey owner = server.owner();
            assertEffective(server, 8_388_608, 5, false);
            for (String bad : List.of(
                    "{\"csArtifactMaxBytes\":1048575,\"maxOpenWorkflowsPerSession\":5}",
                    "{\"csArtifactMaxBytes\":67108865,\"maxOpenWorkflowsPerSession\":5}",
                    "{\"csArtifactMaxBytes\":8388608,\"maxOpenWorkflowsPerSession\":0}",
                    "{\"csArtifactMaxBytes\":8388608,\"maxOpenWorkflowsPerSession\":2147483648}",
                    "{\"csArtifactMaxBytes\":8388608.0,\"maxOpenWorkflowsPerSession\":5}",
                    "{\"csArtifactMaxBytes\":8388608,\"maxOpenWorkflowsPerSession\":\"5\"}",
                    "{\"csArtifactMaxBytes\":8388608,\"maxOpenWorkflowsPerSession\":null}",
                    "{\"csArtifactMaxBytes\":8388608}",
                    "{\"csArtifactMaxBytes\":8388608,\"maxOpenWorkflowsPerSession\":5,\"other\":1}", "null")) {
                assertProblem(server.post("/api/intent", request(change(ToolServerSupport.MAPPER.readTree(bad)))), 400, "ADMIN_CHANGE_INVALID");
            }
            assertEffective(server, 8_388_608, 5, false);
            ObjectNode valid = change(limits(12_582_912, 3));
            ObjectNode competing = change(limits(16_777_216, 8));
            String intent = server.prepare(valid, false).get("intent").asString();
            String stale = server.prepare(competing, false).get("intent").asString();
            ObjectNode invalidAssertion = ToolServerSupport.MAPPER.createObjectNode().put("credentialId", owner.credentialId)
                    .put("authenticatorData", "AA").put("clientDataJSON", "AA").put("signature", "AA");
            assertProblem(server.commit(intent, invalidAssertion, requestId(), null, null), 400, "ADMIN_SIGNATURE_INVALID");
            assertEffective(server, 8_388_608, 5, false);
            RawHttp.Response committed = server.commit(intent, owner.assertion(intent, server.adminOrigin), requestId(), null, null);
            assertEquals(200, committed.status(), committed.text());
            assertEffective(server, 12_582_912, 3, true);
            assertProblem(server.post("/api/intent", request(competing).put("baseCheck", true).putNull("baseHash")), 409, "ADMIN_TARGET_STALE");
            assertProblem(server.commit(stale, owner.assertion(stale, server.adminOrigin), requestId(), null, null), 409, "ADMIN_TARGET_STALE");
            assertEffective(server, 12_582_912, 3, true);
            try (ToolServerSupport reader = ToolServerSupport.start()) {
                reader.adoptPasskeysOf(server);
                assertEffective(reader, 12_582_912, 3, true);
            }
        }
    }

    private static ObjectNode limits(int bytes, int workflows) {
        return ToolServerSupport.MAPPER.createObjectNode().put("csArtifactMaxBytes", bytes).put("maxOpenWorkflowsPerSession", workflows);
    }
    private static ObjectNode change(JsonNode limits) {
        ObjectNode node = ToolServerSupport.MAPPER.createObjectNode().put("op", "RESOURCE_LIMITS_SET");
        node.set("limits", limits);
        return node;
    }
    private static ObjectNode request(JsonNode change) {
        ObjectNode node = ToolServerSupport.MAPPER.createObjectNode().put("bootstrap", false);
        node.set("change", change);
        return node;
    }
    private static String requestId() { return UUID.randomUUID().toString(); }
    private static void assertEffective(ToolServerSupport server, int bytes, int workflows, boolean signed) throws Exception {
        JsonNode state = server.state();
        JsonNode view = state.path("settings").path("resourceLimits");
        assertEquals(limits(bytes, workflows), view.path("limits"), view.toString());
        assertEquals(limits(8_388_608, 5), view.path("defaults"), view.toString());
        assertEquals(signed, view.path("signed").asBoolean(!signed), view.toString());
        assertEquals(1_048_576, view.path("minCsArtifactBytes").asInt());
        assertEquals(67_108_864, view.path("maxCsArtifactBytes").asInt());
        assertEquals(Integer.MAX_VALUE, view.path("maxWorkflowsPerSession").asInt());
        JsonNode signedState = state.path("signedSettings").path("resourceLimits");
        if (signed) {
            assertEquals(limits(bytes, workflows), signedState.path("limits"));
            assertFalse(view.path("stateToken").asString("").isBlank(), view.toString());
        } else {
            assertTrue(signedState.isNull() || signedState.isMissingNode(), signedState.toString());
        }
        assertEquals(new ResourceLimits(bytes, workflows), server.bean(ResourceLimitSource.class).current());
    }
    private static void assertProblem(RawHttp.Response response, int status, String code) throws Exception {
        assertEquals(status, response.status(), response.text());
        assertEquals(code, SessionServerSupport.json(response).path("code").asString(), response.text());
    }
}
