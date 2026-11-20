// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.hookclient.InputFields.Field;

/**
 * The fields of a host's hook input ({@link InputFields}): the file format and the four files of this module. Case table (input: the text of a fields file, or a label; expected: the
 * members, judged by record equality, or the {@link IllegalArgumentException} or the empty result it earns):
 * <ol>
 * <li>a line is a path of names separated by {@code /}: {@code a} is the member {@code a} whole ({@link Field.Value}); {@code a/b} is {@code b} of the object {@code a}
 * ({@link Field.Section}); lines of one parent make one section; a path followed by {@code first-text} is {@link Field.FirstText}</li>
 * <li>blank lines, lines of white space only and lines starting with {@code #} (also after white space) are left out; a file with no line has no member</li>
 * <li>a field that is both a whole member and a section ({@code a} and {@code a/b}, in either order) is an error that names the field</li>
 * <li>a line that is not a path and an optional {@code first-text} (a second word that is another, three words) is an error that shows the line</li>
 * <li>a path whose segment is empty ({@code a//b}, {@code /a}, {@code a/}) or has a character other than a letter, digit or underscore ({@code a-b}, {@code a.b}, {@code a:b})
 * is an error that shows the line</li>
 * <li>the four files {@code sennard-hook-input/<label>.fields} (gemini, agy, claude, codex) are read by {@link InputFields#of}: their top members, and the sections and the list of texts
 * where the host writes one (Gemini: {@code llm_request} keeps only {@code model}, {@code mcp_context} two names, {@code tool_response} {@code llmContent} and {@code error};
 * Antigravity: {@code workspacePaths} first text, {@code toolCall} {@code name} and {@code args}; Claude: {@code effort} keeps {@code level}; Codex: no section)</li>
 * <li>a label with no file, an empty label and a label that could name another resource ({@code ../x}, {@code a/b}, {@code a\b}, {@code gemini.fields}, a label with a space) have no
 * fields: {@code Optional.empty()}, and nothing outside the folder is read</li>
 * </ol>
 */
class InputFieldsTest {
    private static Field.Section section(String member, Field field) {
        return new Field.Section(Map.of(member, field));
    }

    @Test
    void aPathIsAWholeMemberASectionOrAListOfTexts() {
        assertEquals(Map.of("a", new Field.Value()), InputFields.parse("a").members());
        assertEquals(Map.of("a", section("b", new Field.Value())), InputFields.parse("a/b").members());
        assertEquals(Map.of("a", section("b", section("c", new Field.Value()))), InputFields.parse("a/b/c").members());
        assertEquals(Map.of("a", new Field.FirstText()), InputFields.parse("a first-text").members());
        assertEquals(Map.of("a", section("b", new Field.FirstText())), InputFields.parse("a/b first-text").members());
    }

    @Test
    void linesOfOneParentMakeOneSectionAndSeveralTopMembersAreKept() {
        InputFields fields = InputFields.parse("a/b\nc\na/d\ne first-text\n");

        assertEquals(Map.of("a", new Field.Section(Map.of("b", new Field.Value(), "d", new Field.Value())), "c", new Field.Value(), "e", new Field.FirstText()), fields.members());
    }

    @Test
    void blankLinesAndCommentLinesAreLeftOutAndAFileWithNoLineHasNoMember() {
        assertEquals(Map.of("a", new Field.Value(), "b", new Field.Value()), InputFields.parse("# a comment\n\n   \n\t\na\n   # another, after white space\r\nb\r\n").members());
        assertEquals(Map.of(), InputFields.parse("").members());
        assertEquals(Map.of(), InputFields.parse("# only a comment\n\n").members());
    }

    @Test
    void whiteSpaceAroundALineAndBetweenThePathAndFirstTextIsAllowed() {
        assertEquals(Map.of("a", new Field.FirstText()), InputFields.parse("   a \t first-text   \n").members());
    }

    @Test
    void aFieldThatIsBothAWholeMemberAndASectionIsAnErrorInEitherOrder() {
        for (String text : List.of("a\na/b\n", "a/b\na\n", "x/a\nx/a/b\n", "a first-text\na/b\n")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> InputFields.parse(text), text);
            assertTrue(error.getMessage().contains("a whole member or a section, not both: a"), error.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a first-text extra", "a first-text first-text", "first-text a", "a b c"})
    void aLineThatIsNotAPathAndAnOptionalFirstTextIsAnError(String line) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> InputFields.parse("ok\n" + line + "\n"), line);

        assertTrue(error.getMessage().contains("is a path and, for a list of texts, first-text") && error.getMessage().endsWith(": " + line), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a//b", "/a", "a/", "/", "a-b", "a.b", "a/b-c", "a$", "a\"b", "a\\b", "a:b"})
    void aPathWithAnEmptyOrAnUnsupportedSegmentIsAnError(String line) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> InputFields.parse(line + "\n"), line);

        assertTrue(error.getMessage().contains("names of letters, digits and underscores between slashes") && error.getMessage().endsWith(": " + line), error.getMessage());
    }

    @Test
    void theMembersCannotBeChanged() {
        InputFields fields = InputFields.parse("a/b\nc\n");
        Map<String, Field> members = fields.members();
        Field.Section section = (Field.Section) members.get("a");
        Map<String, Field> inner = section.members();

        assertThrows(UnsupportedOperationException.class, () -> members.put("x", new Field.Value()));
        assertThrows(UnsupportedOperationException.class, () -> inner.put("x", new Field.Value()));
    }

    // the files of this module

    private static InputFields shipped(String label) throws IOException {
        return InputFields.of(label).orElseThrow(() -> new AssertionError("no fields file for " + label));
    }

    private static final Map<String, Set<String>> TOP_MEMBERS = Map.of(
            "gemini", Set.of("session_id", "cwd", "transcript_path", "source", "reason", "prompt", "stop_hook_active", "llm_request", "tool_name", "tool_input", "mcp_context", "tool_response"),
            "agy", Set.of("conversationId", "workspacePaths", "transcriptPath", "artifactDirectoryPath", "modelName", "lastUserInput", "invocationNum", "toolCall", "error", "terminationReason",
                    "fullyIdle", "executionNum"),
            "claude", Set.of("session_id", "transcript_path", "cwd", "workspace_id", "model", "effort", "agent_id", "agent_type", "turn_id", "prompt", "source", "reason", "tool_name",
                    "tool_use_id", "tool_input", "tool_response", "error", "stop_hook_active"),
            "codex", Set.of("session_id", "transcript_path", "cwd", "workspace_id", "model", "agent_id", "turn_id", "prompt", "source", "reason", "tool_name", "tool_use_id", "tool_input",
                    "tool_response", "error", "stop_hook_active", "status"));

    @Test
    void theFourFilesAreReadByTheirLabelsWithTheTopMembersOfTheirHost() throws IOException {
        for (Map.Entry<String, Set<String>> host : TOP_MEMBERS.entrySet()) {
            assertEquals(host.getValue(), shipped(host.getKey()).members().keySet(), host.getKey());
        }
    }

    @Test
    void geminiKeepsTheModelOfARequestTheNamesOfAnMcpContextAndTheContentAndErrorOfAResponse() throws IOException {
        Map<String, Field> members = shipped("gemini").members();

        assertEquals(section("model", new Field.Value()), members.get("llm_request"), "not the messages, the config or the tool config");
        assertEquals(new Field.Section(Map.of("server_name", new Field.Value(), "tool_name", new Field.Value())), members.get("mcp_context"));
        assertEquals(new Field.Section(Map.of("llmContent", new Field.Value(), "error", new Field.Value())), members.get("tool_response"), "not returnDisplay");
        assertEquals(new Field.Value(), members.get("tool_input"));
    }

    @Test
    void antigravityKeepsTheFirstWorkspacePathAndTheNameAndArgumentsOfAToolCall() throws IOException {
        Map<String, Field> members = shipped("agy").members();

        assertEquals(new Field.FirstText(), members.get("workspacePaths"));
        assertEquals(new Field.Section(Map.of("name", new Field.Value(), "args", new Field.Value())), members.get("toolCall"));
        assertEquals(new Field.Value(), members.get("conversationId"));
    }

    @Test
    void claudeKeepsTheLevelOfTheEffortAndCodexHasNoSection() throws IOException {
        assertEquals(section("level", new Field.Value()), shipped("claude").members().get("effort"));
        assertEquals(new Field.Value(), shipped("claude").members().get("tool_response"));
        assertTrue(shipped("codex").members().values().stream().noneMatch(field -> field instanceof Field.Section || field instanceof Field.FirstText), "every Codex member is kept whole");
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-such-host", "", "../x", "..\\x", "a/b", "a\\b", "gemini.fields", "gemini ", " gemini", "gem ini", "sennard-hook-input/gemini", "../sennard-hook-input/gemini"})
    void aLabelWithNoFileOrThatCouldNameAnotherResourceHasNoFields(String label) throws IOException {
        assertEquals(Optional.empty(), InputFields.of(label), label);
    }
}
