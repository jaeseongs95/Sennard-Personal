// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.wake;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** An isolated stdio process fixture. It makes no model requests and installs no hooks. */
public final class CodexAppServerProbe {
    private CodexAppServerProbe() {
    }

    public static void main(String[] args) throws Exception {
        String behavior = args[0];
        Path requests = Path.of(args[1]);
        var mapper = JsonMapper.builder().build();
        try (var stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                var stdout = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            for (String line; (line = stdin.readLine()) != null;) {
                var message = mapper.readTree(line);
                String method = message.path("method").asString();
                int id = message.path("id").asInt();
                var params = message.path("params");
                Files.writeString(requests, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                switch (method) {
                    case "initialize" -> send(stdout, mapper, Map.of("id", id, "result", Map.of()));
                    case "initialized" -> {
                    }
                    case "thread/read" -> {
                        if (behavior.equals("internal-error")) {
                            send(stdout, mapper, Map.of("id", id, "error", Map.of("code", -32603, "message", "native error details")));
                            continue;
                        }
                        String thread = behavior.equals("wrong-thread") ? "different" : params.path("threadId").asString();
                        var metadata = mapper.createObjectNode().put("id", thread);
                        switch (behavior) {
                            case "untitled-thread" -> metadata.putNull("name");
                            case "malformed-title" -> metadata.put("name", 42);
                            default -> metadata.put("name", "Chosen title");
                        }
                        send(stdout, mapper, Map.of("id", id, "result", Map.of("thread", metadata)));
                    }
                    case "model/list" -> {
                        var chosen = Map.of("model", "chosen-model", "supportedReasoningEfforts", List.of(Map.of("reasoningEffort",
                                behavior.equals("unlisted-effort") ? "other-effort" : "chosen-effort")));
                        boolean nextPage = behavior.equals("catalog-pages") && !params.has("cursor");
                        if (nextPage) {
                            send(stdout, mapper, Map.of("id", id, "result", Map.of("data", List.of(), "nextCursor", "catalog-page-2")));
                        } else {
                            var items = behavior.equals("unlisted-model") ? List.of() : List.of(chosen);
                            send(stdout, mapper, Map.of("id", id, "result", Map.of("data", items)));
                        }
                    }
                    case "thread/start", "thread/resume" -> {
                        String thread = behavior.equals("wrong-thread") ? "different" : "child";
                        send(stdout, mapper, Map.of("id", id, "result", Map.of("thread", Map.of("id", thread))));
                    }
                    case "turn/start" -> {
                        if (behavior.equals("silent")) {
                            continue;
                        }
                        if (behavior.equals("oversized")) {
                            stdout.write("x".repeat(1024 * 1024 + 1));
                            stdout.newLine();
                            stdout.flush();
                            continue;
                        }
                        if (behavior.equals("foreign-reply")) {
                            send(stdout, mapper, Map.of("id", id + 100, "result", Map.of()));
                            continue;
                        }
                        if (behavior.equals("server-request")) {
                            send(stdout, mapper, Map.of("id", 100, "method", "item/commandExecution/requestApproval", "params", Map.of()));
                            continue;
                        }
                        if (behavior.equals("invalid-parameters") || behavior.equals("internal-error")) {
                            int code = behavior.equals("invalid-parameters") ? -32602 : -32603;
                            send(stdout, mapper, Map.of("id", id, "error", Map.of("code", code, "message", "native error details")));
                            continue;
                        }
                        String thread = params.path("threadId").asString();
                        var turn = Map.of("id", "turn-" + id, "status", "completed");
                        send(stdout, mapper, Map.of("id", id, "result", Map.of("turn", turn)));
                        String delta = behavior.equals("streaming") ? "x".repeat(512 * 1024) : "private model response";
                        send(stdout, mapper, Map.of("method", "item/agentMessage/delta", "params", Map.of("delta", delta)));
                        send(stdout, mapper, Map.of("method", "turn/completed", "params", Map.of("threadId", thread, "turn", turn)));
                        if (behavior.equals("streaming")) {
                            Files.createFile(requests.resolveSibling("turn-completed"));
                        }
                    }
                    default -> throw new IllegalArgumentException(method);
                }
            }
        }
    }

    private static void send(BufferedWriter output, JsonMapper mapper, Map<String, ?> result) throws Exception {
        output.write(mapper.writeValueAsString(result));
        output.newLine();
        output.flush();
    }
}
