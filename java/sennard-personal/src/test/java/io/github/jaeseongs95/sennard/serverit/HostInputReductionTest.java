// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.type.filter.AssignableTypeFilter;

import io.github.jaeseongs95.sennard.hookclient.HookNoAnswer;
import io.github.jaeseongs95.sennard.hookclient.InputFields;
import io.github.jaeseongs95.sennard.hookclient.InputReducer;
import io.github.jaeseongs95.sennard.serverit.HookAdapterGoldenTest.Read;
import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookPipeline;
import io.github.jaeseongs95.sennard.server.hook.HostDefinition;
import io.github.jaeseongs95.sennard.server.hook.HostDefinitions;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler;
import io.github.jaeseongs95.sennard.server.hook.HostHookAdapter;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two implementations of one reading, held to the same result on the same fixed input: the server's ({@code HookBody.Posted.bind} in the host's adapter, Jackson) and the hook
 * client's ({@link InputReducer} with the host's {@link InputFields}, a small reader of JSON that may not load a library). What the client sends the server of a command hook's input must
 * be read by the adapter as the original is, and what the server refuses with a 400 the client refuses for the same reason. The client's side of a case is "reduce, then the
 * adapter reads what is left"; a refusal of the reduction ({@link HookNoAnswer}) is written as the server writes its own, {@code REJECT 400 <reason>}, so the two compare as text.
 * That every host has a fields file and that the file is the record's is {@link HostInputFieldsTest}'s. Case table (input; expected):
 * <ol>
 * <li>every read case of {@link HookAdapterGoldenTest} (right types, wrong types, absent, null, markers, long texts) and every recorded host input of
 * the fixtures (claude, codex, agy; linux, windows): the adapter's result (a {@link HostEvent}, or the rejection's status and reason) for the reduced input equals the result for the
 * original</li>
 * <li>the host's fields the server does not read are not sent (Gemini's {@code llm_request.messages} and {@code config}, a tool's {@code returnDisplay}, an
 * unknown field); the fields it reads, as the host wrote them, are (a tool's arguments and result at any size, Antigravity's first workspace path as a list)</li>
 * <li>a label no host has a fields file for: no fields, the same bytes, the same array; a label that could name another resource: no fields</li>
 * <li>an input the server refuses with a 400 (empty, blank, not UTF-8, not a JSON object, cut off): refused by the reduction with the server's reason, for every host</li>
 * <li>the edges of the JSON grammar, in a member the host does not read (comment, single quote, trailing comma, leading zero, NaN, raw control character, bad escape, a text after
 * the closing brace, nesting of 100 and of 1000 levels and others): the server and the client both accept or both refuse, with the reason the server's refusal has, and the
 * accepted ones are read the same; the table also says which of the two it is, so that a change of what Jackson accepts shows here</li>
 * <li>every adapter the product has has a host definition (found by ServiceLoader) of the same host, with the record it reads, and no definition is without an
 * adapter; two definitions, or two adapters, of one label are refused</li>
 * </ol>
 */
class HostInputReductionTest {
    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** How the server's refusal reads in a result: its status and reason. */
    private static String rejection(int status, String reason) {
        return "REJECT " + status + " " + reason;
    }

    /** The bytes the client sends of the host's input, or the input itself for a label that has no fields file; the way a hook of that host goes through the client. */
    private static byte[] reduce(String label, byte[] input) throws IOException {
        Optional<InputFields> fields = InputFields.of(label);
        return fields.isEmpty() ? input : InputReducer.reduce(fields.get(), input);
    }

    /** What the adapter made of an input: the event, or the rejection as (status, reason). */
    private static Object readOriginal(HostHookAdapter adapter, String event, byte[] input) {
        try {
            return adapter.read(event, HookBody.posted(input));
        } catch (HookRejected rejected) {
            return rejection(rejected.status(), rejected.reason());
        }
    }

    /** What the adapter made of what the client sends of the input; when the client refuses the input, the refusal as the server writes its own (the reasons carry the same names). */
    private static Object readReduced(String host, HostHookAdapter adapter, String event, byte[] input) throws IOException {
        byte[] sent;
        try {
            sent = reduce(host, input);
        } catch (HookNoAnswer refused) {
            return rejection(400, refused.reason().name());
        }
        return readOriginal(adapter, event, sent);
    }

    @Test
    void everyReadCaseOfTheGoldenIsReadAsItWasWhenItsInputIsReduced(org.junit.jupiter.api.TestReporter reporter) throws IOException {
        List<Read> reads = HookAdapterGoldenTest.reads();
        int events = 0;
        for (Read row : reads) {
            HostHookAdapter adapter = HookAdapterGoldenTest.ADAPTERS.get(row.host());
            Object original = readOriginal(adapter, row.event(), bytes(row.input()));
            Object reduced = readReduced(row.host(), adapter, row.event(), bytes(row.input()));
            assertEquals(original, reduced, row.host() + " " + row.event() + " " + (row.input().length() <= 300 ? row.input() : row.input().substring(0, 300) + "..."));
            if (original instanceof HostEvent) {
                events++;
            }
        }
        reporter.publishEntry("golden read cases, of them read as an event", reads.size() + ", " + events);
        assertTrue(reads.size() > 300, "the golden's read cases: " + reads.size());
        assertTrue(events > 300,"cases that are read as an event, not rejected: " + events + " of " + reads.size());
    }

    @Test
    void everyRecordedHostInputIsReadAsItWasWhenItsInputIsReduced() throws IOException {
        int cases = 0;
        for (String os : List.of("linux", "windows")) {
            Map<String, List<String>> files = Map.of("claude", List.of("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop", "SessionEnd"),
                    "codex", List.of("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop", "SessionEnd"),
                    "agy", List.of("SessionStart", "PreInvocation", "PreInvocation-next", "PreToolUse", "PostToolUse", "PostInvocation", "Stop"));
            for (Map.Entry<String, List<String>> host : files.entrySet()) {
                HostHookAdapter adapter = HookAdapterGoldenTest.ADAPTERS.get(host.getKey());
                for (String file : host.getValue()) {
                    String event = file.replace("-next", "");
                    byte[] input = HookTestSupport.fixture(os, host.getKey(), file);
                    Object original = readOriginal(adapter, event, input);
                    assertTrue(original instanceof HostEvent, os + " " + host.getKey() + " " + file + " is read: " + original);
                    assertEquals(original, readReduced(host.getKey(), adapter, event, input), os + " " + host.getKey() + " " + file);
                    cases++;
                }
            }
        }
        assertEquals(38, cases);
    }

    private static JsonNode json(String text) {
        return HookAdapters.MAPPER.readTree(text);
    }

    private static JsonNode reduced(String host, String input) throws IOException {
        return HookAdapters.MAPPER.readTree(reduce(host, bytes(input)));
    }

    @Test
    void geminiSendsTheModelOfAModelRequestAndNotItsConversationOrConfig() throws IOException {
        String input = "{\"session_id\":\"g1\",\"hook_event_name\":\"BeforeModel\",\"timestamp\":\"2026-11-11T00:00:00Z\",\"llm_request\":{\"model\":\"gemini-3-pro\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"config\":{\"temperature\":0.2},\"toolConfig\":{}}}";

        assertEquals(json("{\"session_id\":\"g1\",\"llm_request\":{\"model\":\"gemini-3-pro\"}}"), reduced("gemini", input));
    }

    @Test
    void theFieldsTheServerReadsArriveAsTheHostWroteThem() throws IOException {
        // Gemini: a tool's arguments, an MCP context, and a result of which only llmContent and error are read (returnDisplay is not).
        assertEquals(json("{\"session_id\":\"g\",\"tool_name\":\"t\",\"tool_input\":{\"a\":{\"b\":[1,2.5,\"c\",null]}},\"mcp_context\":{\"server_name\":\"sennard\",\"tool_name\":\"x\"},"
                + "\"tool_response\":{\"llmContent\":[{\"text\":\"r\"}],\"error\":null}}"),
                reduced("gemini", "{\"session_id\":\"g\",\"tool_name\":\"t\",\"tool_input\":{\"a\":{\"b\":[1,2.5,\"c\",null]}},\"mcp_context\":{\"server_name\":\"sennard\",\"tool_name\":\"x\",\"extra\":1},"
                        + "\"tool_response\":{\"llmContent\":[{\"text\":\"r\"}],\"returnDisplay\":\"shown\",\"error\":null},\"timestamp\":\"t\"}"));
        // Antigravity: the first workspace path stays a list of one, the tool call keeps its arguments of any shape.
        assertEquals(json("{\"conversationId\":\"c\",\"workspacePaths\":[\"/w1\"],\"toolCall\":{\"name\":\"n\",\"args\":{\"Path\":\"/p\",\"k\":[1]}}}"),
                reduced("agy", "{\"conversationId\":\"c\",\"workspacePaths\":[\"/w1\",\"/w2\"],\"toolCall\":{\"name\":\"n\",\"args\":{\"Path\":\"/p\",\"k\":[1]},\"id\":\"i\"},\"unknown\":true}"));
        // Claude and Codex: a tool's input and response are the server's to read, whatever their size; an agent marker that is there stays there, one that is not stays absent.
        String big = "x".repeat(200_000);
        assertEquals(json("{\"session_id\":\"s\",\"agent_id\":\"a\",\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"" + big + "\"},\"tool_response\":\"" + big + "\"}"),
                reduced("claude", "{\"session_id\":\"s\",\"agent_id\":\"a\",\"permission_mode\":\"default\",\"hook_event_name\":\"PostToolUse\",\"tool_name\":\"Bash\","
                        + "\"tool_input\":{\"command\":\"" + big + "\"},\"tool_response\":\"" + big + "\"}"));
        assertFalse(reduced("claude", "{\"session_id\":\"s\",\"tool_name\":\"Bash\"}").has("agent_id"));
        assertFalse(reduced("claude", "{\"session_id\":\"s\",\"tool_name\":\"Bash\"}").has("agent_type"));
        assertEquals(json("{\"session_id\":\"s\",\"model\":\"m\",\"status\":\"ok\"}"), reduced("codex", "{\"session_id\":\"s\",\"model\":\"m\",\"status\":\"ok\",\"cli_version\":\"1\"}"));
    }

    @Test
    void aLabelNoHostHasAFieldsFileForIsSentAsItCame() throws IOException {
        byte[] input = bytes("{\"session_id\":\"s\",\"unknown\":[1,2,3]}");

        assertEquals(Optional.empty(), InputFields.of("a-host-without-an-entry"));
        assertSame(input, reduce("a-host-without-an-entry", input));
        for (String label : List.of("", "../claude", "claude/../codex", "a b", "claude.fields", "claude!")) {
            assertEquals(Optional.empty(), InputFields.of(label), "a label that could name another resource has no fields: " + label);
        }
    }

    private static final List<String> HOSTS = List.of("claude", "codex", "agy", "gemini");

    @Test
    void anInputTheServerRefusesIsRefusedByTheReductionForTheSameReason() throws IOException {
        List<byte[]> refused = new ArrayList<>();
        refused.add(new byte[0]);
        refused.add(bytes("   "));
        refused.add(bytes("[]"));
        refused.add(bytes("\"text\""));
        refused.add(bytes("42"));
        refused.add(bytes("null"));
        refused.add(bytes("{\"session_id\":"));
        refused.add(new byte[] {'{', '"', 's', 'e', 's', 's', 'i', 'o', 'n', '_', 'i', 'd', '"', ':', '"', (byte) 0xFF, '"', '}'});
        for (String host : HOSTS) {
            HostHookAdapter adapter = HookAdapterGoldenTest.ADAPTERS.get(host);
            for (byte[] input : refused) {
                Object original = readOriginal(adapter, "SessionStart", input);
                Object reduced = readReduced(host, adapter, "SessionStart", input);
                String situation = host + " " + new String(input, StandardCharsets.ISO_8859_1);
                assertTrue(original instanceof String text && text.startsWith("REJECT 400 HOOK_BODY_"), situation + ": the adapter refuses the body: " + original);
                assertEquals(original, reduced, situation);
            }
        }
    }

    /** The host's own name of the session in its input, and an event it reads; what an input of the grammar table must carry so that an adapter reads it as an event. */
    private static String sessionMember(String host) {
        return host.equals("agy") ? "conversationId" : "session_id";
    }

    /** The members of the input, in the host's form, around the part of the grammar a case is about. */
    private static String around(String host, String members) {
        String session = "\"" + sessionMember(host) + "\":\"s1\"";
        return "{" + session + (members.isEmpty() ? "" : "," + members) + "}";
    }

    /**
     * A case of the grammar table: the input as a function of the host (the session member differs), and whether the server accepts it. {@code accepted} is what Jackson's defaults
     * do (the strict grammar of RFC 8259, nothing but white space after the closing brace, nesting up to its limit); the two sides are compared to each other for every case, and to this
     * for each.
     */
    private record Grammar(String name, java.util.function.Function<String, String> input, boolean accepted) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Grammar members(String name, String members, boolean accepted) {
        return new Grammar(name, host -> around(host, members), accepted);
    }

    private static Grammar whole(String name, String text, boolean accepted) {
        return new Grammar(name, host -> text.replace("SESSION", "\"" + sessionMember(host) + "\":\"s1\""), accepted);
    }

    private static List<Grammar> grammar() {
        List<Grammar> cases = new ArrayList<>();
        cases.add(members("baseline: only the session", "", true));
        // Accepted by the grammar of JSON.
        cases.add(members("an unread member of every JSON type", "\"a\":1,\"b\":-0.5e+3,\"c\":true,\"d\":false,\"e\":null,\"f\":\"t\",\"g\":[1,[2]],\"h\":{\"i\":{}}", true));
        cases.add(members("numbers: zero, minus zero, exponents", "\"a\":0,\"b\":-0,\"c\":0.5,\"d\":1e5,\"e\":1E+5,\"f\":1.5e-3,\"g\":10", true));
        cases.add(members("whitespace of JSON between every token", " \t\r\n\"a\" \t\r\n:\t[ 1 ,\n2 ]\r\n", true));
        cases.add(members("an escape of every kind in an unread text", "\"a\":\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u00e9 \\u0041 \\uD83D\\uDE00\"", true));
        cases.add(members("raw characters a text may hold: U+2028, DEL, an emoji", "\"a\":\"\u2028\u007f\uD83D\uDE00\u00e9\"", true));
        cases.add(members("an empty key, an empty object and an empty array", "\"\":1,\"a\":{},\"b\":[]", true));
        cases.add(members("a repeated unread key", "\"a\":1,\"a\":2", true));
        cases.add(members("a repeated read key: the last one counts in both", "\"" + "SESSION_MEMBER" + "\":\"s2\"", true));
        cases.add(new Grammar("the session key written with an escape", host -> "{\"\\u" + String.format("%04x", (int) sessionMember(host).charAt(0)) + sessionMember(host).substring(1) + "\":\"s1\"}", true));
        cases.add(whole("white space after the closing brace: a line end", "{SESSION}\n", true));
        cases.add(whole("white space after the closing brace: every kind", "{SESSION} \t\r\n ", true));
        cases.add(members("nesting of 100 arrays in an unread member", "\"a\":" + "[".repeat(100) + "]".repeat(100), true));
        cases.add(members("nesting of 100 objects in an unread member", "\"a\":" + "{\"a\":".repeat(100) + "1" + "}".repeat(100), true));
        cases.add(members("nesting of 100 arrays in tool_input (read by three of the four hosts)","\"" + "tool_input" + "\":" + "[".repeat(100) + "]".repeat(100), true));
        // Refused by the grammar of JSON.
        cases.add(whole("a text after the closing brace: words", "{SESSION}xyz", false));
        cases.add(whole("a text after the closing brace: a second object", "{SESSION}{SESSION}", false));
        cases.add(whole("a text after the closing brace: a broken one", "{SESSION} {\"a\":", false));
        cases.add(whole("a text after the closing brace: a closing brace", "{SESSION}}", false));
        cases.add(whole("a text after the closing brace: a comment", "{SESSION} // c", false));
        cases.add(whole("a line comment", "{SESSION, // c\n\"a\":1}", false));
        cases.add(whole("a block comment", "{SESSION,/* c */\"a\":1}", false));
        cases.add(members("a single-quoted key", "'a':1", false));
        cases.add(members("a single-quoted text", "\"a\":'t'", false));
        cases.add(members("an unquoted key", "a:1", false));
        cases.add(members("a trailing comma in the object", "\"a\":1,", false));
        cases.add(members("a trailing comma in an array", "\"a\":[1,]", false));
        cases.add(members("a leading comma in an array", "\"a\":[,1]", false));
        cases.add(members("two commas in the object", ",\"a\":1", false));
        cases.add(members("a missing value", "\"a\":", false));
        cases.add(members("a missing colon", "\"a\" 1", false));
        cases.add(members("a missing comma", "\"a\":1 \"b\":2", false));
        cases.add(members("a leading zero", "\"a\":01", false));
        cases.add(members("a leading zero after a minus", "\"a\":-01", false));
        cases.add(members("a double zero", "\"a\":00", false));
        cases.add(members("a plus sign", "\"a\":+1", false));
        cases.add(members("a hexadecimal number", "\"a\":0x10", false));
        cases.add(members("a point with no digit after it", "\"a\":1.", false));
        cases.add(members("a point with no digit before it", "\"a\":.5", false));
        cases.add(members("an exponent with no digit", "\"a\":1e", false));
        cases.add(members("a minus alone", "\"a\":-", false));
        cases.add(members("NaN", "\"a\":NaN", false));
        cases.add(members("Infinity", "\"a\":Infinity", false));
        cases.add(members("minus Infinity", "\"a\":-Infinity", false));
        cases.add(members("a raw tab in a text", "\"a\":\"x\ty\"", false));
        cases.add(members("a raw line feed in a text", "\"a\":\"x\ny\"", false));
        cases.add(members("a raw control character U+0001 in a text", "\"a\":\"x\u0001y\"", false));
        cases.add(members("a raw control character in a key", "\"a\u0001\":1", false));
        cases.add(members("a raw tab in the member that is read", "\"prompt\":\"x\ty\"", false));
        cases.add(members("an unknown escape", "\"a\":\"\\q\"", false));
        cases.add(members("a short unicode escape", "\"a\":\"\\u12\"", false));
        cases.add(members("a unicode escape with a letter that is not hexadecimal", "\"a\":\"\\u12G4\"", false));
        cases.add(members("a backslash that ends a text", "\"a\":\"x\\", false));
        cases.add(members("an unterminated text", "\"a\":\"x", false));
        cases.add(members("a form feed as white space", "\f\"a\":1", false));
        cases.add(members("a no-break space as white space", "\u00a0\"a\":1", false));
        cases.add(members("a NUL outside a text", "\u0000\"a\":1", false));
        cases.add(members("a literal that is cut: tru", "\"a\":tru", false));
        cases.add(members("a literal in capitals: True", "\"a\":True", false));
        cases.add(members("a literal with more after it: nulll", "\"a\":nulll", false));
        cases.add(members("two literals joined: truefalse", "\"a\":truefalse", false));
        cases.add(whole("the object cut off after the session", "{SESSION", false));
        cases.add(whole("the object cut off after a comma", "{SESSION,", false));
        cases.add(members("an array cut off", "\"a\":[1,2", false));
        cases.add(members("an array closed as an object", "\"a\":[1,2}", false));
        cases.add(members("an object closed as an array", "\"a\":{\"b\":1]", false));
        cases.add(members("nesting of 1000 arrays in an unread member", "\"a\":" + "[".repeat(1000) + "]".repeat(1000), false));
        cases.add(members("nesting of 1000 objects in an unread member", "\"a\":" + "{\"a\":".repeat(1000) + "1" + "}".repeat(1000), false));
        cases.add(members("nesting of 1000 arrays in tool_input (read by three of the four hosts)","\"" + "tool_input" + "\":" + "[".repeat(1000) + "]".repeat(1000), false));
        return cases;
    }

    @Test
    void theEdgesOfTheJsonGrammarAreAcceptedOrRefusedByTheServerAndTheClientAlike() throws IOException {
        int accepted = 0;
        int refused = 0;
        for (Grammar each : grammar()) {
            for (String host : HOSTS) {
                HostHookAdapter adapter = HookAdapterGoldenTest.ADAPTERS.get(host);
                // The member the table calls SESSION_MEMBER is the host's own name of the session.
                byte[] input = bytes(each.input().apply(host).replace("SESSION_MEMBER", sessionMember(host)));
                Object server = readOriginal(adapter, "SessionStart", input);
                Object client = readReduced(host, adapter, "SessionStart", input);
                String situation = each + " for " + host + ": " + (input.length <= 200 ? new String(input, StandardCharsets.UTF_8).replace("\n", "\\n").replace("\t", "\\t") : input.length + " bytes");

                assertEquals(server, client, "the client reads it as the server does: " + situation);
                assertEquals(each.accepted(), server instanceof HostEvent, "the server " + (each.accepted() ? "accepts" : "refuses with HOOK_BODY_NOT_A_JSON_OBJECT") + ": " + situation + " -> " + server);
                if (!each.accepted()) {
                    assertEquals(rejection(400, "HOOK_BODY_NOT_A_JSON_OBJECT"), server, situation);
                    refused++;
                } else {
                    accepted++;
                }
            }
        }
        assertTrue(accepted >= 15 * HOSTS.size() && refused >= 40 * HOSTS.size(), "the table holds both sides: " + accepted + " accepted, " + refused + " refused");
    }

    /** The product's host adapters, as the class path has them: every concrete {@link HostHookAdapter} under the server's hook package. */
    private static List<HostHookAdapter> productAdapters() throws ReflectiveOperationException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(HostHookAdapter.class));
        List<HostHookAdapter> adapters = new ArrayList<>();
        for (BeanDefinition found : scanner.findCandidateComponents("io.github.jaeseongs95.sennard.server.hook")) {
            Class<?> type = Class.forName(found.getBeanClassName());
            adapters.add((HostHookAdapter) BeanUtils.instantiateClass(type.getConstructor(tools.jackson.databind.json.JsonMapper.class), HookAdapters.MAPPER));
        }
        return adapters;
    }

    @Test
    void everyAdapterTheProductHasIsKnownByItsDefinitionWithTheRecordItReads() throws ReflectiveOperationException {
        List<HostHookAdapter> adapters = productAdapters();
        HostDefinitions definitions = HostDefinitions.load();
        Set<String> labels = new TreeSet<>();

        for (HostHookAdapter adapter : adapters) {
            String label = adapter.host().label();
            assertEquals(adapter.inputType(), definitions.ofLabel(label).map(HostDefinition::inputType).orElse(null), "the definition's record for " + label + " is the adapter's");
            HostDefinition definition = definitions.ofLabel(label).orElseThrow(() -> new AssertionError("no host definition is named in META-INF/services for the adapter " + label));
            assertEquals(adapter.host(), definition.host(), "the definition and the adapter are of one host: " + label);
            assertEquals(adapter.getClass(), definition.hookAdapter(HookAdapters.MAPPER).getClass(), "the definition makes the adapter's own class: " + label);
            labels.add(label);
        }

        assertEquals(adapters.size(), labels.size(), "the adapters' labels are distinct");
        assertTrue(adapters.size() >= 4, "the adapters found: " + adapters.size());
        assertEquals(labels, definitions.all().stream().map(definition -> definition.host().label()).collect(Collectors.toCollection(TreeSet::new)), "no host definition is without an adapter");
        assertEquals(adapters.size(), definitions.all().size(), "every definition is of an adapter");
    }

    /** A host that exists only in this test: its record has two of the fields its hook's input carries. */
    private record FakeHostInput(String session_id, String model) {
    }

    private static HostDefinition fakeHost(String label) {
        return new HostDefinition() {
            @Override
            public HostId host() {
                return new HostId(label);
            }

            @Override
            public Class<?> inputType() {
                return FakeHostInput.class;
            }

            @Override
            public HostHookAdapter hookAdapter(JsonMapper mapper) {
                throw new UnsupportedOperationException("This test host answers no hook.");
            }

            @Override
            public String packageDirectory() {
                return "fake-host";
            }
        };
    }

    @Test
    void twoDefinitionsOfOneLabelAreRefusedWhereTheHostsAreLoaded() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> HostDefinitions.of(List.of(fakeHost("same"), fakeHost("same"))));

        assertTrue(refused.getMessage().contains("same"), refused.getMessage());
    }

    @Test
    void twoAdaptersOfOneLabelStopTheStartOfTheHookPipeline() {
        HostHookAdapter claude = HookAdapters.claude();
        HostHookAdapter fourth = new HookTestSupport.FourthHostTestAdapter();
        HostHookAdapter fourthAgain = new HookTestSupport.FourthHostTestAdapter();
        ObjectProvider<HostEventHandler> none = new StaticListableBeanFactory().getBeanProvider(HostEventHandler.class);
        Messages messages = new Messages(new MessageSourceAccessor(new StaticMessageSource()));

        assertDoesNotThrow(() -> new HookPipeline(List.of(claude, fourth), none, messages), "adapters of distinct labels are all there is to a pipeline: a host is found by its adapter");
        assertThrows(IllegalStateException.class, () -> new HookPipeline(List.of(fourth, fourthAgain), none, messages));
    }
}
