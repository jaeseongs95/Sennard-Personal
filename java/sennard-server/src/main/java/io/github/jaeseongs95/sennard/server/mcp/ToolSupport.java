// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.contract.ApiResult;
import io.github.jaeseongs95.sennard.server.contract.SennardToolResults;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

/** The edge every tool method runs through: the calling session, and the one envelope a result or a failure becomes ({@link SennardToolResults}). */
@Component
public class ToolSupport {
    private static final Logger LOG = LoggerFactory.getLogger(ToolSupport.class);
    /** The published description of the optional {@code sessionRef} every session tool takes. */
    public static final String SESSION_REF_DESCRIPTION = "The server-issued address of this session, for a host that cannot attach its session to a call";

    /** The session id a session is stored under: the host's own, or the server-issued reference of a session the server minted. */
    public static String sessionId(SelfSession session) {
        return session.hostSessionId() != null ? session.hostSessionId() : session.sessionRef();
    }

    /** Who in the session called, as a stored label. */
    public static String actorId(SelfSession session) {
        return session.actor().agentKeyOr("root");
    }

    /** One piece of work whose result or failure becomes the tool call's envelope. */
    @FunctionalInterface
    public interface Work {
        Object run() throws Exception;
    }

    /** One piece of work whose answer is already the envelope's content ({@link ApiResult}: a result or a failure the work judged itself, as a skill check does). */
    @FunctionalInterface
    public interface Judged {
        ApiResult run() throws Exception;
    }

    @FunctionalInterface
    private interface Answering {
        McpSchema.CallToolResult make() throws Exception;
    }

    private final SennardToolResults results;
    private final McpSessionResolver sessions;

    public ToolSupport(SennardToolResults results, McpSessionResolver sessions) {
        this.results = results;
        this.sessions = sessions;
    }

    public McpSchema.CallToolResult run(Work work) {
        return respond(() -> results.success(work.run()));
    }

    /** The same edge for work that makes the envelope itself: only what it does not expect is answered here. */
    public McpSchema.CallToolResult answer(Judged work) {
        return respond(() -> results.answer(work.run()));
    }

    private McpSchema.CallToolResult respond(Answering answering) {
        try {
            return answering.make();
        } catch (SennardException failure) {
            return results.failure(failure);
        } catch (Exception cause) {
            // Boundary (MCP tool entrance): any other failure of a tool call is answered as a fixed server failure, never as the caller's invalid input.
            LOG.warn("TOOL_CALL_FAILED", cause);
            return results.unexpected(cause);
        }
    }

    /** The calling session ({@code sessionRef}: the tool's own argument, null when the call has none). Bind once per call: a claim consumes the row the hook left. */
    public SessionBinding bind(String tool, Map<String, Object> arguments, McpMeta meta, McpTransportContext context, String sessionRef) {
        return sessions.bind(tool, arguments, meta, context, sessionRef);
    }
}
