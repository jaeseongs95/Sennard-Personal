// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jaeseongs95.sennard.hookclient.HookNoAnswer.Reason;

/**
 * What the client keeps of a host's hook input ({@link InputReducer}), with the real fields files of the four hosts ({@link InputFields#of}) and, where a case is about the reader and
 * not a host, a field list written in the case. The output is compared as its exact text: the members the fields name, each as the host wrote its value (compared as text, so a
 * number, an escape or a space inside a kept value is as it came), in the order of the input, with no space between the parts. Nothing is parsed by a JSON library: the tests
 * have the JDK and the reducer only. Case table (input: a label or a field list, the input text; expected: the exact output text, or the reason of the refusal):
 * <table>
 * <caption>what is kept</caption>
 * <tr><th>host, input</th><th>output</th></tr>
 * <tr><td>gemini {@code BeforeModel}: id, path, cwd, event name, time, {@code llm_request} with {@code model}, four 1 MiB {@code messages}, {@code config}, {@code toolConfig}</td>
 *     <td>id, path, cwd and {@code llm_request:{model}} only (the 4 MB conversation is not in the output)</td></tr>
 * <tr><td>gemini {@code llm_request} with {@code messages}, {@code model}, {@code config}</td><td>{@code {"llm_request":{"model":..}}}; with no {@code model} {@code {"llm_request":{}}}</td></tr>
 * <tr><td>gemini {@code tool_response} with {@code llmContent} (a list too), {@code returnDisplay}, {@code error}</td><td>{@code llmContent} and {@code error} whole; {@code returnDisplay} dropped</td></tr>
 * <tr><td>gemini {@code mcp_context}: server, tool, url</td><td>server and tool names</td></tr>
 * <tr><td>agy {@code workspacePaths: ["/w1","/w2"]}</td><td>{@code ["/w1"]}; an empty list, a first element that is not a text, a value that is no list: no field</td></tr>
 * <tr><td>agy {@code toolCall}: name, id, args</td><td>name and args (args whole, white space inside included); a {@code toolCall} that is no object: no field</td></tr>
 * <tr><td>claude {@code effort: {level, extra}}</td><td>{@code {"effort":{"level":..}}}; {@code effort} that is no object: no field</td></tr>
 * <tr><td>a section's place holds a value that is no object (a text, a number, null, a list)</td><td>no field, and the commas stay right whether it came first, in the middle or last</td></tr>
 * <tr><td>the same name twice (also a section twice, or an object once and a text once)</td><td>every one of them that is kept, in order</td></tr>
 * <tr><td>a name written with unicode escapes (four hexadecimal digits) that spell a kept name</td><td>kept, written with its plain name</td></tr>
 * <tr><td>numbers, a big integer, exponents, {@code true}, {@code false}, {@code null}, escapes, non-ASCII text in kept values</td><td>as written</td></tr>
 * <tr><td>a name that is kept only below a section, written at the top (or below another)</td><td>dropped</td></tr>
 * <tr><td>{@code {}}, white space before and after the object, nothing kept</td><td>{@code {}} or the kept members</td></tr>
 * </table>
 * <table>
 * <caption>what is refused (the server's {@code HookBody.Posted.bind} refuses the same: strict JSON as Jackson reads it by default)</caption>
 * <tr><th>input</th><th>reason</th></tr>
 * <tr><td>no byte</td><td>HOOK_BODY_EMPTY</td></tr>
 * <tr><td>a byte sequence that is not UTF-8 (0xFF, a cut sequence, an overlong sequence, a surrogate), also in an input that is not JSON either</td><td>HOOK_BODY_NOT_UTF8</td></tr>
 * <tr><td>white space only, {@code []}, {@code "text"}, {@code 42}, {@code true}, {@code null}, a byte order mark before the object</td><td>HOOK_BODY_NOT_A_JSON_OBJECT</td></tr>
 * <tr><td>a cut object, a trailing comma, single quotes, an unquoted name, a missing colon or comma, a comment, a leading zero, {@code 1.}, {@code +1}, {@code .5}, {@code NaN}, a
 *     misspelled literal, a bare {@code -}, an exponent with no digit, a raw control character in a text, an unknown escape ({@code \x}), a unicode escape with fewer than four hexadecimal digits, an unterminated text,
 *     mismatched brackets</td><td>HOOK_BODY_NOT_A_JSON_OBJECT, in a kept member and in a member that is dropped alike</td></tr>
 * <tr><td>anything but white space after the closing brace (words, a second object, a comment)</td><td>HOOK_BODY_NOT_A_JSON_OBJECT</td></tr>
 * <tr><td>the same faults inside a member that is dropped, below a kept section, or after the first text of a list</td><td>HOOK_BODY_NOT_A_JSON_OBJECT</td></tr>
 * <tr><td>nesting of 100 levels (the top object is level 1) in a dropped member, a kept member and by objects</td><td>read</td></tr>
 * <tr><td>nesting of {@value InputReducer#MAX_DEPTH} levels / of one more / of 1000</td><td>read / HOOK_BODY_NOT_A_JSON_OBJECT / HOOK_BODY_NOT_A_JSON_OBJECT</td></tr>
 * </table>
 */
class InputReducerTest {
    /** One kept member {@code a} whole: what a case about the reader and not about a host needs. */
    private static final InputFields KEEPING_A = InputFields.parse("a");
    /** Nothing named {@code a} is kept, so {@code a} is a member that is dropped. */
    private static final InputFields KEEPING_B = InputFields.parse("b");

    private static String reduce(InputFields fields, String input) throws IOException {
        return new String(InputReducer.reduce(fields, input.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    private static String reduce(String label, String input) throws IOException {
        return reduce(InputFields.of(label).orElseThrow(() -> new AssertionError("no fields file for " + label)), input);
    }

    private static HookNoAnswer refused(InputFields fields, byte[] input) {
        return assertThrows(HookNoAnswer.class, () -> InputReducer.reduce(fields, input), new String(input, StandardCharsets.ISO_8859_1));
    }

    private static HookNoAnswer refused(InputFields fields, String input) {
        return refused(fields, input.getBytes(StandardCharsets.UTF_8));
    }

    // what is kept

    @Test
    void aGeminiModelRequestIsReducedToItsModelAndTheCommonFields(TestReporter reporter) throws IOException {
        String turn = "y".repeat(1_048_576);
        String input = "{\"session_id\":\"g1\",\"transcript_path\":\"/t.json\",\"cwd\":\"/w\",\"hook_event_name\":\"BeforeModel\",\"timestamp\":\"2026-11-11T00:00:00Z\","
                + "\"llm_request\":{\"model\":\"gemini-3-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"" + turn + "\"},{\"role\":\"model\",\"content\":\"" + turn + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + turn + "\"},{\"role\":\"model\",\"content\":\"" + turn + "\"}],"
                + "\"config\":{\"temperature\":0.2,\"topP\":0.95,\"systemInstruction\":\"" + "s".repeat(10_000) + "\"},\"toolConfig\":{\"mode\":\"AUTO\"}}}";

        String output = reduce("gemini", input);

        assertEquals("{\"session_id\":\"g1\",\"transcript_path\":\"/t.json\",\"cwd\":\"/w\",\"llm_request\":{\"model\":\"gemini-3-pro\"}}", output);
        assertTrue(input.length() > 4_000_000, "the model request is megabytes: " + input.length());
        assertFalse(output.contains("yyyy") || output.contains("systemInstruction") || output.contains("messages"), "no part of the conversation or the config is in the output");
        reporter.publishEntry("Gemini BeforeModel bytes, input -> output", input.length() + " -> " + output.length());
    }

    @Test
    void aGeminiRequestKeepsItsModelAndNothingElseOfItself() throws IOException {
        assertEquals("{\"llm_request\":{\"model\":\"m\"}}", reduce("gemini", "{\"llm_request\":{\"messages\":[{\"role\":\"user\"}],\"model\":\"m\",\"config\":{\"a\":1},\"toolConfig\":2}}"));
        assertEquals("{\"llm_request\":{}}", reduce("gemini", "{\"llm_request\":{\"messages\":[]}}"));
        assertEquals("{\"llm_request\":{}}", reduce("gemini", "{\"llm_request\":{}}"));
        assertEquals("{\"llm_request\":{\"model\":\"m\"}}", reduce("gemini", "{\"model\":\"top\",\"llm_request\":{\"model\":\"m\"}}"), "a model at the top is not the request's");
    }

    @Test
    void aGeminiToolResponseIsItsContentAndErrorAndAnMcpContextIsItsTwoNames() throws IOException {
        assertEquals("{\"tool_response\":{\"llmContent\":[{\"text\":\"x\"}],\"error\":{\"message\":\"m\"}}}",
                reduce("gemini", "{\"tool_response\":{\"llmContent\":[{\"text\":\"x\"}],\"returnDisplay\":\"y\",\"error\":{\"message\":\"m\"}}}"));
        assertEquals("{\"tool_response\":{}}", reduce("gemini", "{\"tool_response\":{\"returnDisplay\":\"y\"}}"));
        assertEquals("{\"mcp_context\":{\"server_name\":\"sennard\",\"tool_name\":\"x\"}}", reduce("gemini", "{\"mcp_context\":{\"server_name\":\"sennard\",\"url\":\"u\",\"tool_name\":\"x\"}}"));
    }

    @Test
    void anAntigravityWorkspaceIsItsFirstPathAsAListAndAToolCallIsItsNameAndArguments() throws IOException {
        assertEquals("{\"conversationId\":\"c1\",\"workspacePaths\":[\"/w1\"],\"toolCall\":{\"name\":\"n\",\"args\":{ \"k\" : [ 1 , 2 ] }}}",
                reduce("agy", "{\"conversationId\":\"c1\",\"workspacePaths\":[\"/w1\",\"/w2\"],\"stepIndex\":4,\"toolCall\":{\"name\":\"n\",\"id\":\"i\",\"args\":{ \"k\" : [ 1 , 2 ] }}}"));
        assertEquals("{\"workspacePaths\":[\"only\"]}", reduce("agy", "{\"workspacePaths\":[\"only\"]}"));
        assertEquals("{\"workspacePaths\":[\"C:\\\\w\\u0031\"]}", reduce("agy", "{\"workspacePaths\":[ \"C:\\\\w\\u0031\" , \"x\" ]}"), "the first text as it was written");
        assertEquals("{\"workspacePaths\":[\"a\"]}", reduce("agy", "{\"workspacePaths\":[\"a\",{\"deep\":[1,{\"x\":null}]},[2]]}"), "the rest of the list is read and dropped");
    }

    @Test
    void aListThatHasNoTextAsItsFirstElementIsNoField() throws IOException {
        for (String workspacePaths : List.of("[]", "[ ]", "[1,\"a\"]", "[null]", "[[\"a\"]]", "[{\"a\":1}]", "[true,\"a\"]", "\"/w1\"", "7", "null", "{\"0\":\"a\"}")) {
            assertEquals("{\"conversationId\":\"c\"}", reduce("agy", "{\"conversationId\":\"c\",\"workspacePaths\":" + workspacePaths + "}"), workspacePaths);
            assertEquals("{\"conversationId\":\"c\"}", reduce("agy", "{\"workspacePaths\":" + workspacePaths + ",\"conversationId\":\"c\"}"), workspacePaths);
        }
    }

    @Test
    void aClaudeEffortIsItsLevel() throws IOException {
        assertEquals("{\"effort\":{\"level\":\"high\"}}", reduce("claude", "{\"effort\":{\"level\":\"high\",\"extra\":1}}"));
        assertEquals("{\"effort\":{}}", reduce("claude", "{\"effort\":{\"extra\":1}}"));
    }

    @Test
    void aSectionsPlaceThatHoldsNoObjectIsNoFieldAndTheCommasStayRight() throws IOException {
        for (String notAnObject : List.of("\"x\"", "5", "null", "true", "[1,{\"model\":\"m\"}]", "[]", "-1.5e3")) {
            assertEquals("{\"session_id\":\"s\"}", reduce("gemini", "{\"llm_request\":" + notAnObject + ",\"session_id\":\"s\"}"), "first: " + notAnObject);
            assertEquals("{\"session_id\":\"s\",\"cwd\":\"/w\"}", reduce("gemini", "{\"session_id\":\"s\",\"llm_request\":" + notAnObject + ",\"cwd\":\"/w\"}"), "in the middle: " + notAnObject);
            assertEquals("{\"session_id\":\"s\"}", reduce("gemini", "{\"session_id\":\"s\",\"llm_request\":" + notAnObject + "}"), "last: " + notAnObject);
            assertEquals("{}", reduce("gemini", "{\"llm_request\":" + notAnObject + "}"), "alone: " + notAnObject);
        }
    }

    @Test
    void theSameNameTwiceIsKeptTwiceInOrder() throws IOException {
        assertEquals("{\"session_id\":\"a\",\"session_id\":\"b\"}", reduce("gemini", "{\"session_id\":\"a\",\"x\":1,\"session_id\":\"b\"}"));
        assertEquals("{\"llm_request\":{\"model\":\"a\",\"model\":\"b\"},\"llm_request\":{\"model\":\"c\"}}",
                reduce("gemini", "{\"llm_request\":{\"model\":\"a\",\"model\":\"b\"},\"llm_request\":{\"model\":\"c\"}}"));
        assertEquals("{\"llm_request\":{\"model\":\"a\"}}", reduce("gemini", "{\"llm_request\":5,\"llm_request\":{\"model\":\"a\"}}"), "the one that is no object is no field");
        assertEquals("{\"workspacePaths\":[\"a\"],\"workspacePaths\":[\"b\"]}", reduce("agy", "{\"workspacePaths\":[\"a\",\"x\"],\"workspacePaths\":[\"b\"]}"));
    }

    @Test
    void aNameWrittenWithUnicodeEscapesIsKeptWhenItSpellsAKeptNameAndIsWrittenPlain() throws IOException {
        assertEquals("{\"session_id\":\"s\",\"session_id\":\"t\"}", reduce("gemini", "{\"\\u0073ession_id\":\"s\",\"session\\u005fid\":\"t\",\"sess\\u0069on_id\\u0021\":\"u\"}"));
        assertEquals("{\"llm_request\":{\"model\":\"m\"}}", reduce("gemini", "{\"llm\\u005Frequest\":{\"mod\\u0065l\":\"m\"}}"), "also the name of a section and of a member of it (upper-case digits too)");
        assertEquals("{}", reduce("gemini", "{\"\\u0073ession_i\\u0044\":\"s\"}"), "the name spelled is session_iD, which is not kept");
        assertEquals("{\"session_id\":\"s\"}", reduce("gemini", "{\"s\\u0065ssion_\\u0069d\":\"s\"}"));
    }

    @Test
    void aKeptValueIsAsItWasWrittenWhateverItIs() throws IOException {
        InputFields fields = InputFields.parse("a\nb\nc\nd\ne\nf\ng\nh\ni");
        String input = "{ \"a\" : -0 , \"b\" : 2.50e+3 , \"c\" : 12345678901234567890123456789 , \"d\" : 1E-5 , \"e\" : true , \"f\" : false , \"g\" : null , "
                + "\"h\" : \"quote \\\" slash \\\\ \\/ line\\n tab\\t \\u00e9 \\ud83d\\ude00 한글 😀\" , \"i\" : { \"k\" : [ 1 , { \"z\" : null } ] } }";

        assertEquals("{\"a\":-0,\"b\":2.50e+3,\"c\":12345678901234567890123456789,\"d\":1E-5,\"e\":true,\"f\":false,\"g\":null,"
                + "\"h\":\"quote \\\" slash \\\\ \\/ line\\n tab\\t \\u00e9 \\ud83d\\ude00 한글 😀\",\"i\":{ \"k\" : [ 1 , { \"z\" : null } ] }}", reduce(fields, input));
    }

    @Test
    void theOutputFollowsTheOrderOfTheInputAndOnlyTopLevelNamesMatchTheTopMembers() throws IOException {
        assertEquals("{\"prompt\":\"p\",\"session_id\":\"s\"}", reduce("gemini", "{\"prompt\":\"p\",\"x\":0,\"session_id\":\"s\"}"));
        assertEquals("{\"session_id\":\"s\",\"prompt\":\"p\"}", reduce("gemini", "{\"session_id\":\"s\",\"x\":0,\"prompt\":\"p\"}"));
        assertEquals("{\"session_id\":\"top\"}", reduce("gemini", "{\"x\":{\"session_id\":\"nested\"},\"y\":[{\"session_id\":\"in a list\"}],\"session_id\":\"top\"}"));
        assertEquals("{\"llm_request\":{\"model\":\"m\"}}", reduce("gemini", "{\"llm_request\":{\"session_id\":\"x\",\"model\":\"m\",\"llm_request\":{\"model\":\"deeper\"}}}"));
    }

    @Test
    void anObjectWithNothingKeptIsAnEmptyObjectAndWhiteSpaceAroundTheObjectIsAllowed() throws IOException {
        assertEquals("{}", reduce("claude", "{}"));
        assertEquals("{}", reduce("claude", "{ \n\t }"));
        assertEquals("{}", reduce("claude", "{\"unknown\":[1,2,{\"a\":null}],\"other\":\"x\"}"));
        assertEquals("{\"session_id\":\"s\"}", reduce("claude", " \r\n\t{\"session_id\":\"s\"}"));
    }

    @Test
    void whiteSpaceAfterTheClosingBraceIsAllowed() throws IOException {
        assertEquals("{\"a\":1}", reduce(KEEPING_A, "{\"a\":1} \r\n\t "));
        assertEquals("{}", reduce(KEEPING_B, "{}\n"));
    }

    @Test
    void whatFollowsTheClosingBraceIsRefusedAsTheServerRefusesIt() {
        for (String input : List.of("{\"a\":1} garbage", "{\"a\":1}{\"a\":2}", "{\"a\":1}}", "{\"a\":1}\u0000 [[[ \"unterminated", "{} // a comment", "{} 1")) {
            assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused(KEEPING_A, input).reason(), input);
        }
    }

    // what is refused

    @Test
    void anInputWithNoByteIsEmpty() {
        HookNoAnswer failure = refused(KEEPING_A, new byte[0]);

        assertEquals(Reason.HOOK_BODY_EMPTY, failure.reason());
        assertNull(failure.detail());
        assertEquals("HOOK_BODY_EMPTY", failure.getMessage());
    }

    @Test
    void anInputThatIsNotUtf8IsRefusedAsSuchBeforeAnythingElseIsLookedAt() {
        List<byte[]> notUtf8 = List.of(new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xFF, '"', '}'}, new byte[] {(byte) 0xFF}, new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xC3},
                new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xC0, (byte) 0x80, '"', '}'}, new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '"', '}'},
                new byte[] {(byte) 0x80, '{', '}'}, new byte[] {'[', (byte) 0xF0, (byte) 0x9F, (byte) 0x98});
        for (byte[] input : notUtf8) {
            for (InputFields fields : List.of(KEEPING_A, KEEPING_B)) {
                HookNoAnswer failure = refused(fields, input);
                assertEquals(Reason.HOOK_BODY_NOT_UTF8, failure.reason());
                assertNull(failure.detail());
                assertEquals("HOOK_BODY_NOT_UTF8", failure.getMessage());
            }
        }
    }

    @Test
    void aByteOrderMarkBeforeTheObjectIsNotAnObject() {
        byte[] withMark = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'};

        assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused(KEEPING_A, withMark).reason());
    }

    /** The cases are not named by their text: some hold control characters, which a report of the run cannot carry. */
    @ParameterizedTest(name = "case {index}")
    @ValueSource(strings = {
            // not an object
            "   \n\t ", "[]", "[{\"a\":1}]", "\"text\"", "42", "true", "null", "a", "}", "{",
            // cut, or two of the grammar's marks where one belongs
            "{\"a\":", "{\"a\"", "{\"a\"}", "{\"a\":1,}", "{\"a\":1 \"b\":2}", "{\"a\":1,,\"b\":2}", "{,}", "{\"a\":1", "{\"a\":[1,2}", "{\"a\":{\"b\":1]}", "{\"a\":[1,]}", "{\"a\":[1 2]}",
            "{\"a\":[,1]}", "{\"a\":{\"b\":}}", "{\"a\":{\"b\":1,}}",
            // names
            "{'a':1}", "{a:1}", "{1:1}", "{\"a\":'x'}", "{\"a\":\"unterminated}", "{\"a\":\"x\"y\"}",
            // numbers
            "{\"a\":01}", "{\"a\":00}", "{\"a\":-01}", "{\"a\":1.}", "{\"a\":+1}", "{\"a\":.5}", "{\"a\":-}", "{\"a\":1e}", "{\"a\":1e+}", "{\"a\":0x1}", "{\"a\":1_0}", "{\"a\":NaN}",
            "{\"a\":Infinity}", "{\"a\":-Infinity}",
            // literals
            "{\"a\":tru}", "{\"a\":True}", "{\"a\":nul}", "{\"a\":undefined}",
            // comments
            "{/*c*/\"a\":1}", "{\"a\":1 /* c */}", "{// c\n\"a\":1}", "{\"a\":1,// c\n\"b\":2}", "{\"a\":1 # c\n}",
            // texts
            "{\"a\":\"x\ty\"}", "{\"a\":\"x\ny\"}", "{\"a\":\"x\ry\"}", "{\"a\":\"\u0001\"}", "{\"a\":\"\\x\"}", "{\"a\":\"\\u12\"}", "{\"a\":\"\\u12G4\"}", "{\"a\":\"\\u\"}", "{\"a\":\"\\'\"}",
            "{\"a\":\"\\"})
    void aTextThatIsNotAStrictJsonObjectIsRefusedWhetherItsFaultIsInAKeptMemberOrInOneThatIsDropped(String input) {
        for (InputFields fields : List.of(KEEPING_A, KEEPING_B)) {
            HookNoAnswer failure = refused(fields, input);
            assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, failure.reason(), input);
            assertNull(failure.detail(), input);
            assertEquals("HOOK_BODY_NOT_A_JSON_OBJECT", failure.getMessage(), input);
        }
    }

    @Test
    void aSyntaxErrorInAMemberTheClientDropsIsRefusedLikeTheServerWouldWhereverItIs() throws IOException {
        InputFields gemini = InputFields.of("gemini").orElseThrow();
        List<String> faults = List.of("{\"x\":{\"y\":}}", "{\"x\":[1,]}", "{\"x\":\"a\\qb\"}", "{\"x\":01}", "{\"x\":{\"a\":1 \"b\":2}}", "{\"x\":[1 2]}", "{\"x\":tru}", "{\"x\":\"a\nb\"}", "{\"x\":1.}");
        for (String fault : faults) {
            String inside = fault.substring(1, fault.length() - 1);
            assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused(gemini, "{\"session_id\":\"s\"," + inside + "}").reason(), "after a kept member: " + inside);
            assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused(gemini, "{" + inside + ",\"session_id\":\"s\"}").reason(), "before a kept member: " + inside);
            assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused(gemini, "{\"llm_request\":{\"model\":\"m\"," + inside + "}}").reason(), "below a kept section: " + inside);
            assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused(gemini, "{\"llm_request\":{\"model\":\"m\",\"messages\":[" + inside.substring(inside.indexOf(':') + 1) + "]}}").reason(),
                    "in a dropped member of a kept section: " + inside);
        }
        InputFields antigravity = InputFields.of("agy").orElseThrow();
        for (String afterTheFirstText : List.of("[\"a\",]", "[\"a\" \"b\"]", "[\"a\",{\"x\":}]", "[\"a\",01]", "[\"a\"", "[\"a\",\"b\"}", "[1,]", "[1 2]", "[]]", "[,]")) {
            assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused(antigravity, "{\"workspacePaths\":" + afterTheFirstText + "}").reason(), afterTheFirstText);
        }
        assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused(gemini, "{\"llm_request\":[1,]}").reason(), "a value in the place of a section is read too");
        assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused(antigravity, "{\"toolCall\":{\"name\":\"n\",\"id\":01}}").reason(), "below a kept section, in a member that is dropped");
    }

    private static String nestedArrays(int depth) {
        return "{\"x\":" + "[".repeat(depth - 1) + "]".repeat(depth - 1) + "}";
    }

    private static String nestedObjects(int depth) {
        return "{\"x\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 100, InputReducer.MAX_DEPTH})
    void anObjectNestedUpToTheLimitIsRead(int depth) throws IOException {
        InputFields keepingX = InputFields.parse("x");

        assertEquals("{}", reduce(KEEPING_B, nestedArrays(depth)), "arrays in a dropped member");
        assertEquals(nestedArrays(depth), reduce(keepingX, nestedArrays(depth)), "arrays in a kept member, copied whole");
        assertEquals("{}", reduce(KEEPING_B, nestedObjects(depth)), "objects in a dropped member");
        assertEquals(nestedObjects(depth), reduce(keepingX, nestedObjects(depth)), "objects in a kept member, copied whole");
    }

    @ParameterizedTest
    @ValueSource(ints = {InputReducer.MAX_DEPTH + 1, 1000, 5000})
    void anObjectNestedBeyondTheLimitIsRefused(int depth) {
        InputFields keepingX = InputFields.parse("x");
        for (InputFields fields : List.of(KEEPING_B, keepingX)) {
            assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused(fields, nestedArrays(depth)).reason(), "arrays, depth " + depth);
            assertEquals(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, refused(fields, nestedObjects(depth)).reason(), "objects, depth " + depth);
        }
    }

    @Test
    void theLimitIsJacksonsDefaultOfFiveHundredLevels() {
        assertEquals(500, InputReducer.MAX_DEPTH);
    }

    // rawMembers: the members of the top object as written, for the MCP relay's JSON-RPC messages

    @Test
    void rawMembersAreTheTopMembersAsTheyWereWrittenAndOnlyThoseNamedAndOnlyOfTheTopObject() throws IOException {
        String message = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"id\":\"inner\",\"method\":\"inner\"}}";

        assertEquals(Map.of("id", "7", "method", "\"tools/call\""), InputReducer.rawMembers(message, Set.of("id", "method")));
        assertEquals(Map.of(), InputReducer.rawMembers(message, Set.of("result")), "a member the object does not have is not in the answer");
        assertEquals(Map.of("params", "{\"id\":\"inner\",\"method\":\"inner\"}"), InputReducer.rawMembers(message, Set.of("params")), "an object whole, as written");
    }

    @Test
    void anIdIsGivenInTheFormItWasWrittenSoAnAnswerCanCarryItUnchanged() throws IOException {
        for (String id : List.of("1", "-3", "\"abc\"", "\"한글 \\u0041\"", "1.5e3", "null")) {
            assertEquals(id, InputReducer.rawMembers("{\"id\": " + id + " ,\"method\":\"x\"}", Set.of("id")).get("id"), id);
        }
    }

    @Test
    void rawMembersRefuseAMessageThatIsNotAJsonObjectWhereverItsFaultIs() {
        for (String text : List.of("", "[1]", "not json", "{\"id\":1,", "{\"id\":1}{}", "{\"params\":{\"a\":01},\"id\":1}")) {
            assertThrows(HookNoAnswer.class, () -> InputReducer.rawMembers(text, Set.of("id")), text);
        }
    }
}
