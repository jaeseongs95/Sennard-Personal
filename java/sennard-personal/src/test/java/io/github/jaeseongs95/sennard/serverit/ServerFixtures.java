// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.github.jaeseongs95.sennard.server.continuity.ContinuityService;
import io.github.jaeseongs95.sennard.server.continuity.WorkflowGate;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import io.github.jaeseongs95.sennard.server.mcp.McpConfiguration;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.workflow.ConvergenceLimitSource;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowService;
import io.modelcontextprotocol.common.McpTransportContext;
import tools.jackson.databind.json.JsonMapper;

/** What the server tests add to the real application: tools and a handler that counts reaches. */
final class ServerFixtures {
    private ServerFixtures() {
    }

    /** A handler behind the guard. Its count tells whether a rejected request reached anything. */
    @RestController
    static class Endpoints {
        final AtomicInteger reached = new AtomicInteger();

        @PostMapping("/hooks/v1/probe")
        ResponseEntity<String> post(@RequestBody(required = false) byte[] body) {
            reached.incrementAndGet();
            return ResponseEntity.ok("length=" + (body == null ? 0 : body.length));
        }

        /** The time zone and locale the request context holds, as the server's own code would read them. */
        @GetMapping("/hooks/v1/context")
        ResponseEntity<String> context() {
            return ResponseEntity.ok(org.springframework.context.i18n.LocaleContextHolder.getTimeZone().getID() + ";" + org.springframework.context.i18n.LocaleContextHolder.getLocale());
        }

        @GetMapping("/hooks/v1/probe")
        ResponseEntity<String> get() {
            reached.incrementAndGet();
            return ResponseEntity.ok("ok");
        }
    }

    /** A bean holding tools. */
    static class Tools {
        final AtomicInteger calls = new AtomicInteger();

        public record Inner(String a) {
        }

        public record Outer(Inner left, Inner right, List<Inner> more) {
        }

        @McpTool(name = "context_report", description = "Reports the handling thread and the host label.",
                annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
        public String context(McpTransportContext context) {
            calls.incrementAndGet();
            return "virtual=" + Thread.currentThread().isVirtual() + ";label=" + context.get(McpConfiguration.HOST_LABEL);
        }

        @McpTool(name = "strict_tool", description = "Declares one argument.",
                annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
        public String strict(@McpToolParam(description = "The only declared argument") String a) {
            calls.incrementAndGet();
            return "a=" + a;
        }

        @McpTool(name = "nested_tool", description = "Takes a nested record that repeats a type.",
                annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
        public String nested(@McpToolParam(description = "Nested input") Outer input) {
            calls.incrementAndGet();
            return "ok";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {
        /**
         * Tests that exercise the workflow gate start their server with {@code sennard.test.workflow-gate=on}. This lets other tests isolate the parts of the hooks they exercise,
         * such as a turn end, a message, or a recorded input. The edit gate and the rest of the product are the real ones.
         */
        @Bean
        @Primary
        WorkflowGate testWorkflowGate(ContinuityService continuity, WorkflowService workflows, Messages messages, JsonMapper mapper, ConvergenceLimitSource limits, Environment environment) {
            boolean gateOn = "on".equals(environment.getProperty("sennard.test.workflow-gate"));
            return new WorkflowGate(continuity, workflows, messages, mapper, limits) {
                @Override
                public List<MessageRef> promptLines(String session, HostWorkFacts host, Supplier<String> changeExample) {
                    return gateOn ? super.promptLines(session, host, changeExample) : List.of();
                }

                @Override
                public Optional<String> refusal(ToolCall tool, HostWorkFacts host, String session, String actor, Supplier<String> changeExample) {
                    return gateOn ? super.refusal(tool, host, session, actor, changeExample) : Optional.empty();
                }

                @Override
                public StopCheck stopCheck(String session, String actor, boolean continuable) {
                    return gateOn ? super.stopCheck(session, actor, continuable) : new StopCheck(List.of(), null);
                }
            };
        }

        @Bean
        Endpoints endpoints() {
            return new Endpoints();
        }

        @Bean
        Tools tools() {
            return new Tools();
        }
    }
}
