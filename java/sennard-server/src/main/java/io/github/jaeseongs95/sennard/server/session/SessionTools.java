// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.server.mcp.OperationKeys;
import io.github.jaeseongs95.sennard.server.mcp.ToolSupport;
import io.github.jaeseongs95.sennard.server.mcp.McpConfiguration;
import io.github.jaeseongs95.sennard.server.mcp.McpSessionResolver;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.mcp.SessionBinding;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code open_session}: the way into the session tools for a host Sennard has no adapter for, where nothing tells the server
 * which session a call belongs to. The server mints the session and the caller passes the reference back.
 */
@Component
public class SessionTools {
    private static final String TOOL = "open_session";
    /** A label is the caller's own text and is stored; one that is not a plain name is not kept. */
    private static final Pattern PLAIN_LABEL = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final String UNKNOWN_HOST = "unknown";

    /** @param trust {@code observed} when the host itself said which session this is, {@code unavailable} when only this reference says so */
    public record OpenedSession(String sessionRef, long generation, String trust) {
    }

    /** How a session of this kind can be reached, as the identity area stores it: not at all. */
    public record Delivery(String wakeVisibility, boolean canWakeSilently, String idleWake, List<String> supportedInjection) {
    }

    private final McpSessionResolver sessions;
    private final SessionOpening opening;
    private final OperationKeys keys;
    private final ToolSupport support;
    /** The stored text of {@link Delivery}; the same for every session this tool opens, so it is made once. */
    private final String delivery;

    public SessionTools(McpSessionResolver sessions, SessionOpening opening, OperationKeys keys, ToolSupport support, JsonMapper mapper) {
        this.sessions = sessions;
        this.support = support;
        this.opening = opening;
        this.keys = keys;
        this.delivery = mapper.writeValueAsString(new Delivery("none", false, "none", List.of()));
    }

    /**
     * A host that says which session it is has one already, and gets that one. Any other caller gets a new session each time it asks: with
     * nothing observed, two calls cannot be told to be the same session, except by the operationKey: the same key answers the session the first call opened (AGS3-D-0267).
     */
    @McpTool(name = TOOL, description = "Opens a session and returns its sessionRef, for a host whose tool calls do not identify their session. Pass the sessionRef to later tools. "
            + "The result's trust is 'unavailable' when the server could not observe the caller. The operationKey is required and is yours to make: a new random one for each call "
            + "(a UUID, for example), 16 to 128 characters of A-Z a-z 0-9 _ . : -. Sending the same key again answers the same session, and this tool has no session of the caller to keep keys apart, "
            + "so a key is shared by every caller of it: a key another caller or an earlier call used opens that session again, not a new one. The result is kept for 24 hours.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult openSession(@McpToolParam(required = true, description = "Your key for this operation: make a new random one for each call (a UUID, for example), 16 to 128 characters of A-Z a-z 0-9 _ . : -. "
            + "The same key answers the same session, for every caller: never reuse a key, or send one another caller may have used.") String operationKey,
            McpMeta meta, McpTransportContext context) {
        // The answer is the envelope every tool answers in (ok, data, error), so a refusal of the key reads like any other tool's.
        return support.run(() -> {
            Map<String, Object> arguments = new HashMap<>();
            arguments.put(OperationKey.FIELD, operationKey);
            // The key is judged before anything is read of the caller, and the server makes none: a call without a valid key is refused.
            OperationKey operation = keys.global(TOOL, arguments);
            if (sessions.bind(TOOL, Map.of(), meta, context, null) instanceof SessionBinding.Bound bound && bound.session().provenance() == SelfSession.Provenance.HOST_OBSERVED) {
                return new OpenedSession(bound.session().sessionRef(), bound.session().generation(), "observed");
            }
            String host = context.get(McpConfiguration.HOST_LABEL) instanceof String label && PLAIN_LABEL.matcher(label).matches() ? label : UNKNOWN_HOST;
            return opening.open(host, delivery, operation);
        });
    }
}
