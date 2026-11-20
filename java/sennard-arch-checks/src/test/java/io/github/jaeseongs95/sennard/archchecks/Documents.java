// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

/**
 * A JSON or YAML document read as Jackson's token stream, for the rules that need the line of a place (a tree read by {@code readTree} does not keep it).
 * The same walk serves both formats because {@code JsonMapper} and {@code YAMLMapper} both hand out a {@link JsonParser}. Only what the rules look at is kept:
 * for every object the line it starts on, the name of the field (or of the array's field) it sits in, its string fields and its fields that are arrays of strings.
 */
final class Documents {
    private Documents() {
    }

    /**
     * One object of a document. {@code owner} is the name of the field the object, or the array holding it, is the value of; the root has "".
     * {@code path} is the names of the fields from the root down to the object, joined by {@code /} (an array adds no segment): the root is "", the {@code build} job of a
     * workflow is {@code jobs/build} and its steps are {@code jobs/build/steps}.
     */
    record Obj(int line, String owner, String path, Map<String, String> strings, Map<String, List<String>> lists) {
        String string(String field) {
            return strings.get(field);
        }
    }

    private static final class Frame {
        final boolean object;
        final String owner;
        final String path;
        final int line;
        final Map<String, String> strings = new LinkedHashMap<>();
        final Map<String, List<String>> lists = new LinkedHashMap<>();
        final List<String> items = new ArrayList<>();
        boolean allStrings = true;
        String field = "";

        Frame(boolean object, String owner, String path, int line) {
            this.object = object;
            this.owner = owner;
            this.path = path;
            this.line = line;
        }
    }

    /** Every object of the document in the order they end (an inner object before the one that holds it). */
    static List<Obj> objects(ObjectMapper mapper, String text) {
        List<Obj> found = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        try (JsonParser parser = mapper.createParser(text)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                Frame top = stack.peek();
                switch (token) {
                    case START_OBJECT, START_ARRAY -> {
                        String owner = top == null ? "" : top.object ? top.field : top.owner;
                        String path = top == null ? "" : top.object ? (top.path.isEmpty() ? top.field : top.path + "/" + top.field) : top.path;
                        stack.push(new Frame(token == JsonToken.START_OBJECT, owner, path, parser.currentTokenLocation().getLineNr()));
                    }
                    case PROPERTY_NAME -> top.field = parser.currentName();
                    case VALUE_STRING -> {
                        if (top.object) {
                            top.strings.put(top.field, parser.getString());
                        } else {
                            top.items.add(parser.getString());
                        }
                    }
                    case END_OBJECT -> {
                        Frame done = stack.pop();
                        found.add(new Obj(done.line, done.owner, done.path, done.strings, done.lists));
                    }
                    case END_ARRAY -> {
                        Frame done = stack.pop();
                        Frame holder = stack.peek();
                        if (holder != null && holder.object && done.allStrings) {
                            holder.lists.put(done.owner, done.items);
                        }
                    }
                    default -> {
                        if (top != null && !top.object) {
                            top.allStrings = false; // a number, boolean or null among the items: not a list of strings
                        }
                    }
                }
                if ((token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) && top != null && !top.object) {
                    top.allStrings = false; // an object or array among the items
                }
            }
        }
        return found;
    }
}
