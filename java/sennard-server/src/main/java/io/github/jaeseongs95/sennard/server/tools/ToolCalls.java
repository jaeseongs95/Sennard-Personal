// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.contract.SennardToolResults;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.mcp.McpSessionResolver;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

/** What every tool method does around its service call: which session is calling, and how the outcome becomes the result envelope ({@link SennardToolResults}). */
@Component
public class ToolCalls {
    private static final Logger LOG = LoggerFactory.getLogger(ToolCalls.class);

    private final McpSessionResolver sessions;
    private final SennardToolResults results;

    public ToolCalls(McpSessionResolver sessions, SennardToolResults results) {
        this.sessions = sessions;
        this.results = results;
    }

    /**
     * @param arguments the call's arguments as received, the {@code sessionRef} among them when it was given
     * @param sessionRef the tool's optional {@code sessionRef} argument, or null; used only when the host names no session (McpSessionResolver)
     * @param failures what any other failure of this domain becomes
     */
    public McpSchema.CallToolResult call(String tool, Map<String, Object> arguments, String sessionRef, McpMeta meta, McpTransportContext context,
            Function<RuntimeException, SennardException> failures, Function<SelfSession, Object> body) {
        return respond(failures, () -> {
            SelfSession session = sessions.bind(tool, arguments, meta, context, sessionRef).require();
            return body.apply(session);
        });
    }

    /** The arguments as the call carried them, the optional {@code sessionRef} included: a host that names no call is matched to its hook by these. */
    public static Map<String, Object> withSessionRef(Map<String, Object> arguments, String sessionRef) {
        if (sessionRef == null) {
            return arguments;
        }
        Map<String, Object> all = new LinkedHashMap<>(arguments);
        all.put(ContractGuard.SESSION_REF, sessionRef);
        return all;
    }

    /** Runs a body that needs no session; a failure that is not an {@code SennardException} is answered as a fixed server failure. */
    public McpSchema.CallToolResult call(Supplier<Object> body) {
        return respond(null, body);
    }

    /** @param failures how this domain says its unexpected failures, or null for the fixed server failure */
    private McpSchema.CallToolResult respond(Function<RuntimeException, SennardException> failures, Supplier<Object> body) {
        try {
            return results.success(body.get());
        } catch (SennardException failure) {
            return results.failure(failure);
        } catch (RuntimeException failure) {
            // Boundary (MCP tool entrance): the tool's own domain says how its failures read (the failures function); the failure is logged with its cause.
            LOG.warn("TOOL_CALL_FAILED {}", failure.getClass().getSimpleName(), failure);
            return failures == null ? results.unexpected(failure) : results.failure(failures.apply(failure));
        }
    }

    /** The SDK's own check of a tool's input is switched off in {@code ToolInputConfiguration}: a contract violation is answered by this guard's envelope. */
    @Configuration(proxyBeanMethods = false)
    static class GuardConfiguration {
        // @Bean: a static BeanPostProcessor, so that the container makes it before the tool lists it post-processes.
        @Bean
        static ContractGuard contractGuard(ObjectProvider<Messages> messages, ObjectProvider<SennardToolResults> results, ObjectProvider<tools.jackson.databind.json.JsonMapper> mapper) {
            return new ContractGuard(messages, results, mapper);
        }
    }
}
