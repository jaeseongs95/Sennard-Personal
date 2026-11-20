// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a hook call leaves behind, through {@code /hooks/v1/...} on the assembled server: the board row, the skill calls, the role binding's last use and the
 * once-per-version update notice. Each is read back from its table; nothing here calls a store to record.
 */
@Execution(ExecutionMode.SAME_THREAD)
@Tag("business-db")
class HookObservationsServerTest {
    private static final String CHILD = "agent-1";
    private static final String CLAUDE_SKILL = "sennard:minimal-implementation";

    private static RunningServer server;
    @TempDir
    private static Path skills; // JUnit deletes it after the class (D-0194)

    @BeforeAll
    static void start() throws Exception {
        server = SessionServerSupport.started(SessionServerSupport.sources(), "--sennard.skills-dir=" + skills.toAbsolutePath());
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static String post(String host, String event, String session, Consumer<ObjectNode> edit) throws IOException {
        RawHttp.Response response = DomainServerSupport.hook(server.port, "windows", host, event, null, session, edit);
        assertEquals(200, response.status(), DomainServerSupport.text(response));
        return DomainServerSupport.text(response);
    }

    private static Consumer<ObjectNode> none() {
        return body -> {
        };
    }

    private static Consumer<ObjectNode> child() {
        return body -> body.put("agent_id", CHILD).put("agent_type", "general-purpose");
    }

    private static long count(String sql, Object... params) {
        return SessionServerSupport.count(server, sql, params);
    }

    private static long board(String session) {
        return count("SELECT count(*) FROM sennard.board_sessions WHERE session_id = ? AND last_prompt_at IS NOT NULL", session);
    }

    private static long skillCalls(String session) {
        return count("SELECT count(*) FROM sennard.skill_calls WHERE session_id = ?", session);
    }

    private static Consumer<ObjectNode> skillTool(String skill) {
        return DomainServerSupport.tool("Skill", DomainServerSupport.MAPPER.createObjectNode().put("skill", skill));
    }

    @Test
    void aRootPromptIsOnTheBoardAndAChildPromptIsNot() throws IOException {
        String root = SessionServerSupport.uniqueSession();
        post("claude", "UserPromptSubmit", root, none());
        assertEquals(1, board(root));

        String withChild = SessionServerSupport.uniqueSession();
        post("claude", "UserPromptSubmit", withChild, child());
        assertEquals(0, board(withChild), "a subagent's prompt is not the session's prompt");
    }

    @Test
    void aSkillToolCallIsRecordedWithWhoMadeIt() throws IOException {
        String root = SessionServerSupport.uniqueSession();
        post("claude", "PostToolUse", root, skillTool(CLAUDE_SKILL));
        Map<String, Object> row = SessionServerSupport.row(server, "SELECT skill_id, caller, basis FROM sennard.skill_calls WHERE session_id = ?", root);
        assertEquals("minimal-implementation", row.get("skill_id"));
        assertEquals("skill-tool", row.get("basis"));
        assertEquals("MAIN", row.get("caller"), row.toString());

        String subagent = SessionServerSupport.uniqueSession();
        post("claude", "PostToolUse", subagent, skillTool(CLAUDE_SKILL).andThen(child()));
        assertEquals("SUBAGENT", SessionServerSupport.row(server, "SELECT caller FROM sennard.skill_calls WHERE session_id = ?", subagent).get("caller"));
    }

    @Test
    void aSkillOfAnotherPluginAndAnOrdinaryToolAreNotCounted() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        post("claude", "PostToolUse", session, skillTool("some-other-plugin:minimal-implementation"));
        post("claude", "PostToolUse", session, DomainServerSupport.tool("Bash", DomainServerSupport.MAPPER.createObjectNode().put("command", "ls")));
        assertEquals(0, skillCalls(session));
    }

    @Test
    void aSkillFileReadCountsForAHostWithNoSkillToolUnlessTheReadFailed() throws IOException {
        Consumer<ObjectNode> read = body -> {
            ObjectNode call = body.putObject("toolCall");
            call.put("name", "view_file");
            call.putObject("args").put("AbsolutePath", skills.resolve("minimal-implementation").resolve("SKILL.md").toString());
        };
        String session = SessionServerSupport.uniqueSession();
        post("agy", "PostToolUse", session, read.andThen(body -> body.put("error", "")));
        assertEquals(1, count("SELECT count(*) FROM sennard.skill_calls WHERE session_id = ? AND skill_id = 'minimal-implementation'", session));

        String failed = SessionServerSupport.uniqueSession();
        post("agy", "PostToolUse", failed, read.andThen(body -> body.put("error", "no such file")));
        assertEquals(0, skillCalls(failed));
    }

    @Test
    void anyHookMarksTheSessionInUseAndAPromptRefreshesAMarkThatIsStillRecent() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        post("claude", "PostToolUse", session, DomainServerSupport.tool("Bash", DomainServerSupport.MAPPER.createObjectNode().put("command", "ls")));
        assertEquals(1, count("SELECT count(*) FROM sennard.session_seen WHERE session_id = ?", session));

        String recent = Timestamps.format(Instant.now().minusSeconds(60));
        SessionServerSupport.update(server, "UPDATE sennard.session_seen SET last_seen_at = ? WHERE session_id = ?", Timestamps.toTimestamp(recent), session);
        post("claude", "PostToolUse", session, DomainServerSupport.tool("Bash", DomainServerSupport.MAPPER.createObjectNode().put("command", "ls")));
        assertEquals(recent, seen(session), "a tool call does not rewrite a recent mark");

        post("claude", "UserPromptSubmit", session, none());
        assertNotEquals(recent, seen(session), "a prompt keeps the binding's 30 days running");
    }

    @Test
    void aToolCallRefreshesAMarkThatHasGoneStale() throws IOException {
        String session = SessionServerSupport.uniqueSession();
        post("claude", "PostToolUse", session, DomainServerSupport.tool("Bash", DomainServerSupport.MAPPER.createObjectNode().put("command", "ls")));
        String stale = Timestamps.format(Instant.now().minusSeconds(40L * 24 * 3600));
        SessionServerSupport.update(server, "UPDATE sennard.session_seen SET last_seen_at = ? WHERE session_id = ?", Timestamps.toTimestamp(stale), session);
        post("claude", "PostToolUse", session, DomainServerSupport.tool("Bash", DomainServerSupport.MAPPER.createObjectNode().put("command", "ls")));
        assertNotEquals(stale, seen(session));
    }

    private static String seen(String session) {
        return (String) SessionServerSupport.row(server, "SELECT to_char(last_seen_at, 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') AS last_seen_at FROM sennard.session_seen WHERE session_id = ?", session).get("last_seen_at");
    }

    @Test
    void theUpdateNoticeReachesTheFirstRootPromptOnceAndNotAChild() throws IOException {
        String now = Instant.now().toString();
        SessionServerSupport.update(server, "DELETE FROM sennard.plugin_update_state WHERE target_id = 'sennard'");
        SessionServerSupport.update(server, "INSERT INTO sennard.plugin_update_state (target_id, current_version, latest_version, latest_tag, latest_commit, comparison, "
                + "last_attempt_at, last_successful_check_at, next_check_at) VALUES ('sennard', '0.0.1', '99.0.0', 'v99.0.0', ?, 'UPDATE_AVAILABLE', ?, ?, ?)",
                "a".repeat(40), Timestamps.toTimestamp(now), Timestamps.toTimestamp(now), Timestamps.toTimestamp(Instant.now().plusSeconds(3600)));

        String answerToChild = post("claude", "UserPromptSubmit", SessionServerSupport.uniqueSession(), child());
        assertFalse(answerToChild.contains("plugin-update-notice"), answerToChild);

        String first = post("claude", "UserPromptSubmit", SessionServerSupport.uniqueSession(), none());
        assertTrue(first.contains("plugin-update-notice") && first.contains("99.0.0"), first);

        String second = post("claude", "UserPromptSubmit", SessionServerSupport.uniqueSession(), none());
        assertFalse(second.contains("plugin-update-notice"), second);
    }
}
