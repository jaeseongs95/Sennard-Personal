// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * What the golden tests ({@code serverit.golden}) reach of this package's server support, through one public door: the assembled server
 * as the other server tests start it, the bytes a host sends and the bytes it gets back. Nothing here interprets an answer.
 */
public final class GoldenHarness implements AutoCloseable {
    /** One HTTP answer as it came off the socket: status, the Content-Type header, and the body bytes. */
    public record Reply(int status, String contentType, byte[] body) {
    }

    private final RunningServer server;
    private final ToolServerSupport tools;
    private ToolServerSupport.Passkey owner;

    private GoldenHarness(RunningServer server, ToolServerSupport tools) {
        this.server = server;
        this.tools = tools;
    }

    /** The application alone, with no store and no tool a test adds: what it publishes is the product's. */
    public static GoldenHarness plain() throws IOException {
        return new GoldenHarness(RunningServer.start(List.of()), null);
    }

    /**
     * The whole application as {@link ToolServerSupport} starts it (real PostgreSQL, the stand-ins it names), with the repository's
     * contracts and skills as the content root, as {@code WorkflowToolsServerTest} starts it.
     */
    public static GoldenHarness full(Path repository) throws Exception {
        ToolServerSupport started = ToolServerSupport.startWith("--sennard.content-root=" + repository, "--sennard.skills-dir=" + repository.resolve("skills"));
        return new GoldenHarness(started.server, started);
    }

    public int port() {
        return server.port;
    }

    public void upload(String uploadId, byte[] bytes) {
        ToolServerSupport.sendMultipart(server.port, uploadId, bytes);
    }

    public int adminPort() {
        return tools.adminPort;
    }

    public Path stateDirectory() {
        return server.stateDirectory();
    }

    /** A JSON-RPC request to {@code /mcp} as a host's client sends it, under this host label (none when null). */
    public Reply mcp(byte[] body, String hostLabel) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("Content-Type", "application/json");
        headers.put("Content-Length", Integer.toString(body.length));
        headers.put("MCP-Protocol-Version", "2025-11-25");
        if (hostLabel != null) {
            headers.put("Sennard-Host-Label", hostLabel);
        }
        return reply(RawHttp.exchange(server.port, "POST", "/mcp", headers, body));
    }

    /** A hook body posted the way a host's own HTTP hook posts it. */
    public Reply hook(String path, byte[] body) throws IOException {
        return reply(SessionServerSupport.post(server.port, path, body));
    }

    private static Reply reply(RawHttp.Response response) {
        return new Reply(response.status(), response.headers().get("content-type"), response.body());
    }

    /** The recorded hook body, unchanged. */
    public static String hookFixture(String os, String host, String event) {
        return HookTestSupport.fixtureText(os, host, event);
    }

    /** The recorded hook body with this session and tool call in place of the recorded ones. */
    public static byte[] hookFixture(String os, String host, String event, String session, String toolUse) {
        return SessionServerSupport.hook(os, host, event, session, toolUse);
    }

    /** The recorded {@code _meta} of a tool call by this host, with this session and tool call. */
    public static Map<String, Object> meta(String os, String host, String session, String toolUse) {
        return SessionServerSupport.meta(os, host, session, toolUse);
    }

    /** Starts a session the way the host starts one: its recorded Windows SessionStart hook with this session. */
    public void startSession(String host, String session) throws IOException {
        RawHttp.Response started = SessionServerSupport.post(server.port, "/hooks/v1/" + host + "/SessionStart", SessionServerSupport.hook("windows", host, "SessionStart", session, "none"));
        if (started.status() != 200) {
            throw new AssertionError("SessionStart of " + host + " answered " + started.status() + ": " + started.text());
        }
    }

    /** Registers a role under this id on the admin page, signed with the page's passkey (registered on first use). */
    public void addRole(String roleId, String name) throws Exception {
        tools.sign(owner(), ToolServerSupport.roleAdd(roleId, name));
    }

    /** Approves an agent's pending request on the admin page. */
    public void approve(String proposalId) throws Exception {
        tools.approve(owner(), proposalId);
    }

    private ToolServerSupport.Passkey owner() throws Exception {
        if (owner == null) {
            owner = tools.owner();
        }
        return owner;
    }

    /** The admin page's state, as its script reads it. */
    public JsonNode adminState() throws IOException {
        return tools.state();
    }

    @Override
    public void close() {
        if (tools != null) {
            tools.close();
        } else {
            server.close();
        }
    }
}
