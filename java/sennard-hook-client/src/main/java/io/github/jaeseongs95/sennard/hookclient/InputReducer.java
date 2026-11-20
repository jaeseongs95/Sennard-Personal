// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.github.jaeseongs95.sennard.hookclient.HookNoAnswer.Reason;
import io.github.jaeseongs95.sennard.hookclient.InputFields.Field;

/**
 * What the client sends of a host's hook input: the input read once, as JSON, and written again with only the members the server reads ({@link InputFields}); each of them
 * is copied as the host wrote it, so the server reads it as it would the original. A small reader of JSON does it, because the client may not load a JSON library: its
 * start is the hook's delay.
 *
 * <p>The input is refused for the reasons the server refuses it ({@link Reason}): empty, not UTF-8, not a JSON object. What counts as JSON is what the server's reader accepts
 * ({@code HookBody.Posted.bind}: Jackson's defaults): the strict grammar of RFC 8259, with no comment, quote, trailing comma, leading zero or raw control character, and nesting up to
 * {@value #MAX_DEPTH} levels (Jackson's {@code StreamReadConstraints} default); anything but white space after the closing brace of the top object is refused as well (the server's reader refuses it: measured by HostInputReductionTest, Jackson 3's default {@code FAIL_ON_TRAILING_TOKENS}). The whole object is read, also the members
 * that are dropped, so a malformed input is refused wherever its fault is. A test holds the two readers to the same verdict on the same inputs.
 */
public final class InputReducer {
    /** The nesting depth Jackson's default {@code StreamReadConstraints} allows; the top object is level 1. */
    static final int MAX_DEPTH = 500;
    private static final String HEX = "0123456789abcdefABCDEF";

    private final String text;
    private final StringBuilder out = new StringBuilder();
    /** The top object's members that are kept whole, as the input wrote them, by name: set only for {@link #rawMembers}. */
    private final Map<String, String> topMembers;
    private int position;

    /** The reader's own end: the input is not JSON of the shape the server accepts. */
    private static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Malformed(String problem, int at) {
            super(problem + " at " + at);
        }
    }

    private InputReducer(String text, Map<String, String> topMembers) {
        this.text = text;
        this.topMembers = topMembers;
    }

    /**
     * @return the input with only the members the fields name, as UTF-8 JSON
     * @throws HookNoAnswer when the server would refuse the input: {@link Reason#HOOK_BODY_EMPTY}, {@link Reason#HOOK_BODY_NOT_UTF8} or {@link Reason#HOOK_BODY_NOT_A_JSON_OBJECT}
     */
    public static byte[] reduce(InputFields fields, byte[] body) throws HookNoAnswer {
        return read(fields, body, null).out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The members of the top object that {@code names} lists, each as the JSON text it was written in (a string with its quotes, an object whole), for a member the object has: what
     * the MCP relay needs of a JSON-RPC message ({@code id} and {@code method}, to answer a request it cannot deliver, and what an answer says of the protocol) without a JSON
     * library. The whole object is read and refused as {@link #reduce} refuses it, so a malformed message is found wherever its fault is.
     *
     * @param names the names of top members, each letters, digits and underscores ({@link InputFields#parse})
     * @throws HookNoAnswer when {@code json} is not a JSON object, for the reasons {@link #reduce} refuses an input
     */
    public static Map<String, String> rawMembers(String json, Set<String> names) throws HookNoAnswer {
        Map<String, String> found = new HashMap<>();
        read(InputFields.parse(String.join("\n", names)), json.getBytes(StandardCharsets.UTF_8), found);
        return found;
    }

    private static InputReducer read(InputFields fields, byte[] body, Map<String, String> topMembers) throws HookNoAnswer {
        if (body.length == 0) {
            throw new HookNoAnswer(Reason.HOOK_BODY_EMPTY, null);
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException notUtf8) {
            throw new HookNoAnswer(Reason.HOOK_BODY_NOT_UTF8, null, notUtf8);
        }
        InputReducer reducer = new InputReducer(decoded, topMembers);
        try {
            reducer.skipWhitespace();
            reducer.expect('{');
            reducer.position--;
            reducer.object(fields.members(), 1);
            reducer.skipWhitespace();
            if (reducer.position < decoded.length()) {
                throw new Malformed("a text after the object", reducer.position);
            }
        } catch (Malformed notAnObject) {
            throw new HookNoAnswer(Reason.HOOK_BODY_NOT_A_JSON_OBJECT, null, notAnObject);
        }
        return reducer;
    }

    /** Reads the object at the position and writes the members {@code kept} names; the object is at nesting level {@code level}. */
    private void object(Map<String, Field> kept, int level) {
        enter(level);
        expect('{');
        out.append('{');
        boolean wrote = false;
        skipWhitespace();
        if (peek() == '}') {
            position++;
            out.append('}');
            return;
        }
        while (true) {
            skipWhitespace();
            expect('"');
            position--;
            String name = string(new StringBuilder());
            skipWhitespace();
            expect(':');
            skipWhitespace();
            Field field = kept.get(name);
            if (field == null) {
                skipValue(level + 1);
            } else {
                int mark = out.length();
                if (wrote) {
                    out.append(',');
                }
                out.append('"').append(name).append("\":");
                if (member(name, field, level + 1)) {
                    wrote = true;
                } else {
                    out.setLength(mark);
                }
            }
            skipWhitespace();
            char next = peek();
            position++;
            if (next == '}') {
                out.append('}');
                return;
            }
            if (next != ',') {
                throw new Malformed("expected , or }", position - 1);
            }
        }
    }

    /** Reads the value at the position as the field says and writes what is kept of it; false when none of it is. */
    private boolean member(String name, Field field, int level) {
        return switch (field) {
            case Field.Value value -> {
                int start = position;
                skipValue(level);
                out.append(text, start, position);
                if (topMembers != null && level == 2) { // a member of the top object (level 1)
                    topMembers.put(name, text.substring(start, position));
                }
                yield true;
            }
            case Field.Section section -> {
                if (peek() == '{') {
                    object(section.members(), level);
                    yield true;
                }
                skipValue(level);
                yield false;
            }
            case Field.FirstText first -> firstText(level);
        };
    }

    /** The first element of the list at the position, when it is a text, written as a list of it; the rest of the list is read and dropped. */
    private boolean firstText(int level) {
        if (peek() != '[') {
            skipValue(level);
            return false;
        }
        enter(level);
        position++;
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return false;
        }
        int start = position;
        boolean isText = peek() == '"';
        skipValue(level + 1);
        if (isText) {
            out.append('[').append(text, start, position).append(']');
        }
        while (true) {
            skipWhitespace();
            char next = peek();
            position++;
            if (next == ']') {
                return isText;
            }
            if (next != ',') {
                throw new Malformed("expected , or ]", position - 1);
            }
            skipWhitespace();
            skipValue(level + 1);
        }
    }

    /** Reads one value at the position and writes nothing; a value that is not JSON is {@link Malformed}. The value would be at nesting level {@code level}. */
    private void skipValue(int level) {
        char first = peek();
        switch (first) {
            case '{' -> skipObject(level);
            case '[' -> skipArray(level);
            case '"' -> string(null);
            case 't' -> literal("true");
            case 'f' -> literal("false");
            case 'n' -> literal("null");
            default -> {
                if (first != '-' && (first < '0' || first > '9')) {
                    throw new Malformed("not a value", position);
                }
                number();
            }
        }
    }

    private void skipObject(int level) {
        enter(level);
        position++;
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return;
        }
        while (true) {
            skipWhitespace();
            expect('"');
            position--;
            string(null);
            skipWhitespace();
            expect(':');
            skipWhitespace();
            skipValue(level + 1);
            skipWhitespace();
            char next = peek();
            position++;
            if (next == '}') {
                return;
            }
            if (next != ',') {
                throw new Malformed("expected , or }", position - 1);
            }
        }
    }

    private void skipArray(int level) {
        enter(level);
        position++;
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return;
        }
        while (true) {
            skipWhitespace();
            skipValue(level + 1);
            skipWhitespace();
            char next = peek();
            position++;
            if (next == ']') {
                return;
            }
            if (next != ',') {
                throw new Malformed("expected , or ]", position - 1);
            }
        }
    }

    private void enter(int level) {
        if (level > MAX_DEPTH) {
            throw new Malformed("nesting too deep", position);
        }
    }

    private void literal(String word) {
        if (!text.startsWith(word, position)) {
            throw new Malformed("not a value", position);
        }
        position += word.length();
    }

    /** {@code -?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)?}, the grammar of JSON; a leading zero is left to the caller to find fault with, as the next character is not one a value is followed by. */
    private void number() {
        if (peek() == '-') {
            position++;
        }
        if (peek() == '0') {
            position++;
        } else {
            digits();
        }
        if (position < text.length() && text.charAt(position) == '.') {
            position++;
            digits();
        }
        if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            position++;
            if (peek() == '+' || peek() == '-') {
                position++;
            }
            digits();
        }
    }

    /** One or more decimal digits. */
    private void digits() {
        int start = position;
        while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') {
            position++;
        }
        if (position == start) {
            throw new Malformed("a digit is expected", position);
        }
    }

    /**
     * Reads the text at the position, which starts with a quote, and returns its characters (escapes resolved) when {@code into} is given; the position is after the closing quote.
     * A raw control character, an unknown escape and an escape with fewer than four hexadecimal digits are {@link Malformed}.
     */
    private String string(StringBuilder into) {
        position++;
        while (true) {
            char c = peek();
            position++;
            if (c == '"') {
                return into == null ? null : into.toString();
            }
            if (c < ' ') {
                throw new Malformed("a control character in a text", position - 1);
            }
            if (c != '\\') {
                if (into != null) {
                    into.append(c);
                }
                continue;
            }
            char escaped = peek();
            position++;
            switch (escaped) {
                case '"', '\\', '/' -> append(into, escaped);
                case 'b' -> append(into, '\b');
                case 'f' -> append(into, '\f');
                case 'n' -> append(into, '\n');
                case 'r' -> append(into, '\r');
                case 't' -> append(into, '\t');
                case 'u' -> {
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int digit = HEX.indexOf(peek());
                        if (digit < 0) {
                            throw new Malformed("an escape is \\u and four hexadecimal digits", position);
                        }
                        code = code * 16 + Character.digit(HEX.charAt(digit), 16);
                        position++;
                    }
                    append(into, (char) code);
                }
                default -> throw new Malformed("an unknown escape", position - 1);
            }
        }
    }

    private static void append(StringBuilder into, char c) {
        if (into != null) {
            into.append(c);
        }
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            position++;
        }
    }

    private char peek() {
        if (position >= text.length()) {
            throw new Malformed("the input ends", position);
        }
        return text.charAt(position);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw new Malformed("expected " + c, position);
        }
        position++;
    }
}
