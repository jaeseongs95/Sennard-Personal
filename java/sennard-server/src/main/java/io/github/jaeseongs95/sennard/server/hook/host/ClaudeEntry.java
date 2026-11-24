// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.jaeseongs95.sennard.domain.transcript.TranscriptEntry;
import io.github.jaeseongs95.sennard.domain.transcript.TranscriptItem;
import tools.jackson.databind.JsonNode;

/**
 * One line of a Claude Code transcript (a JSON object): what kind of entry it is, who wrote it, and what it holds. The host's own format is read here and nowhere else (see
 * {@link ClaudeTranscriptFile}); a field the host wrote as another type is the same as absent, as {@link HookJson} treats the lines of a transcript. What Claude Code writes
 * (code.claude.com/docs/en/hooks, "transcript_path"; the shape of the lines is not in its documentation, so only the fields below are relied on): {@code type}
 * ({@code user} or {@code assistant} for the conversation), {@code uuid}, {@code sessionId}, {@code timestamp} (ISO-8601), {@code isSidechain} (a subagent's line),
 * {@code isMeta} (a line the host injects, not the user's), {@code agentId} (a subagent's line), {@code promptId}, {@code parentUuid} and {@code logicalParentUuid} (the boundary of
 * a compaction), and {@code message.content}, which is a string or a list of blocks ({@code text}, {@code tool_use}, {@code tool_result}, {@code thinking}, ...).
 *
 * @param node the line
 * @param at the line's {@code timestamp} when it has one that reads as an instant, otherwise null
 */
record ClaudeEntry(JsonNode node, Instant at) {
    /** What an entry is to a reader of a turn. */
    enum Kind {
        /** A message of the user: the start of a turn (the line itself may still be one the host injected: {@link #meta()}). */
        PROMPT,
        /** What tools answered: the host writes it as a user line. */
        TOOL_RESULTS,
        /** What the model wrote: text and tool calls. */
        ASSISTANT,
        /** Anything else (a summary, a snapshot, a system line). */
        OTHER
    }

    Kind kind() {
        String type = HookJson.text(node, "type");
        if ("assistant".equals(type)) {
            return Kind.ASSISTANT;
        }
        if (!"user".equals(type)) {
            return Kind.OTHER;
        }
        return holdsBlock(content(), "tool_result") ? Kind.TOOL_RESULTS : Kind.PROMPT;
    }

    /** The line's identifier; empty text for a line that has none, as for any other field the host did not write. */
    String uuid() {
        return HookJson.present(node, "uuid").orElse("");
    }

    Optional<String> sessionId() {
        return HookJson.present(node, "sessionId");
    }

    /** The subagent's id on a line of a subagent; empty text on the main thread's. */
    String agentId() {
        return HookJson.text(node, "agentId");
    }

    /** Whether the line is a subagent's, which is not the main conversation's. */
    boolean sidechain() {
        return node.path("isSidechain").asBoolean(false);
    }

    /** Whether the host injected the line (a reminder, a caveat): it is not what the user typed. */
    boolean meta() {
        return node.path("isMeta").asBoolean(false);
    }

    Optional<Instant> timestamp() {
        return Optional.ofNullable(at);
    }

    /** Whether this line is the user's own message: a line of the user on the main thread that the host did not inject and that carries no tool results. It opens a turn and ends the one before it. */
    boolean startsTurn() {
        return kind() == Kind.PROMPT && !sidechain() && !meta();
    }

    /** Whether the line was written before the instant; a line with no usable timestamp counts as written before, so it is never taken for a recent one. */
    boolean writtenBefore(Instant earliest) {
        return timestamp().map(written -> written.isBefore(earliest)).orElse(true);
    }

    Optional<String> model() {
        return HookJson.present(node.path("message"), "model");
    }

    /** The reasoning effort the line names, or null when it names none (it is read into the {@code effort} component of an execution, which is never an {@code Optional}). */
    String effort() {
        return HookJson.presentOrNull(HookJson.text(node, "effort"));
    }

    /** The {@code promptId} the host puts on its lines: the user input one turn answers; empty when the line has none. */
    Optional<String> promptId() {
        return HookJson.present(node, "promptId");
    }

    /**
     * Whether this line is a boundary of a compaction: the host gives the line that opens the compacted conversation no {@code parentUuid} and names the line it continues in
     * {@code logicalParentUuid}. What came before it is no longer what the model is sent.
     */
    boolean compactBoundary() {
        return HookJson.present(node, "logicalParentUuid").isPresent();
    }

    /** Whether the user's line is the notice of a background task ({@code <task-notification>}): the host hands it to the model as a new turn. */
    boolean announcesBackgroundTask() {
        return kind() == Kind.PROMPT && promptTexts().stream().anyMatch(text -> text.stripLeading().startsWith("<task-notification>"));
    }

    /**
     * Whether this line is the host's record of a model request ({@code type} {@code api-request}; a line of another type that begins the same, such as {@code api-request-resumed}, is
     * not). The host queues it right after the response's headers arrive and a timer writes the queue out about every 100 ms, so it is in the file soon after the model starts to
     * answer, but a hook is not given that wait. The assistant line of a message with several tool calls is not written until its tools have finished. The request line names the
     * {@code params.model} and {@code params.output_config.effort} the request was sent with, the {@code querySource} of the thread that sent it ({@code repl_main_thread} for the main
     * agent) and the {@code sessionId}.
     */
    boolean isApiRequest() {
        return "api-request".equals(HookJson.text(node, "type"));
    }

    /** The {@code querySource} of a request record: which thread sent the request; empty when the line does not say. */
    Optional<String> requestSource() {
        return HookJson.present(node, "querySource");
    }

    /**
     * Which kind of thread sends a request, told by its {@code querySource} the way the host classifies it (Claude Code 2.1.330, read from the program; not every value has been seen in a
     * transcript): the main thread is {@code repl_main_thread}, with or without an output style after a colon ({@code repl_main_thread:outputStyle:Learning}), and {@code sdk}; a subagent is
     * {@code agent:...} ({@code agent:builtin:<type>}, {@code agent:custom:<type>}, {@code agent:default}) and {@code hook_agent}; anything else ({@code compact}, {@code agent_summary},
     * {@code generate_session_title}, {@code auto_mode}, ...) is the host's own side work, which no thread of the conversation has sent.
     */
    enum RequestThread {
        MAIN,
        SUBAGENT,
        AUXILIARY;

        static RequestThread of(String querySource) {
            if ("repl_main_thread".equals(querySource) || querySource.startsWith("repl_main_thread:") || "sdk".equals(querySource)) {
                return MAIN;
            }
            if (querySource.startsWith("agent:") || "hook_agent".equals(querySource)) {
                return SUBAGENT;
            }
            return AUXILIARY;
        }
    }

    /** The model a request record was sent with. */
    Optional<String> requestModel() {
        return HookJson.present(node.path("params"), "model");
    }

    /** The reasoning effort a request record was sent with, or null when it names none. */
    String requestEffort() {
        return HookJson.presentOrNull(HookJson.text(node.path("params").path("output_config"), "effort"));
    }

    /** Whether this assistant line holds the call of this identifier. */
    boolean issuesToolUse(String callId) {
        for (JsonNode block : content()) {
            if ("tool_use".equals(HookJson.text(block, "type")) && callId.equals(HookJson.text(block, "id"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this is the user's line that holds this prompt text: the whole text of the message or, for a message with several text blocks (a host may put what it
     * adds before the user's words), one block of it. A cut prompt ({@code truncated}) is found by its beginning.
     */
    boolean holdsPrompt(String promptText, boolean truncated) {
        for (String candidate : promptTexts()) {
            if (truncated ? candidate.startsWith(promptText) : candidate.equals(promptText)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What this line adds to a turn, in the shared shape, in the order it was written, each with its key. A line that adds nothing (thinking, bookkeeping) is an empty list.
     * The key of an entry is the line's {@code uuid}; a line that holds several entries (a text and a tool call, two tool results) gives the first the plain uuid and each later one the
     * uuid, a slash and its place in the line ({@code uuid/1}, {@code uuid/2}), so that every entry has a key of its own and a read can stop and continue between two entries of one
     * line. (A line that has no uuid, which the host does not write, gives empty keys; nothing can continue after such an entry.)
     */
    List<TranscriptItem> toItems() {
        String uuid = uuid();
        List<TranscriptEntry> entries = entries();
        List<TranscriptItem> items = new ArrayList<>(entries.size());
        for (int place = 0; place < entries.size(); place++) {
            items.add(new TranscriptItem(place == 0 ? uuid : uuid + "/" + place, entries.get(place)));
        }
        return items;
    }

    private List<TranscriptEntry> entries() {
        List<TranscriptEntry> entries = new ArrayList<>();
        JsonNode content = content();
        switch (kind()) {
            case ASSISTANT -> {
                if (content.isString()) {
                    addText(entries, content.stringValue());
                }
                for (JsonNode block : content) {
                    String type = HookJson.text(block, "type");
                    if ("text".equals(type)) {
                        addText(entries, HookJson.text(block, "text"));
                    } else if ("tool_use".equals(type)) {
                        entries.add(new TranscriptEntry.ToolCall(HookJson.text(block, "id"), HookJson.text(block, "name")));
                    }
                }
            }
            case TOOL_RESULTS -> {
                for (JsonNode block : content) {
                    if ("tool_result".equals(HookJson.text(block, "type"))) {
                        entries.add(TranscriptEntry.ToolResult.of(HookJson.text(block, "tool_use_id"), textOf(block.path("content"))));
                    }
                }
            }
            case PROMPT, OTHER -> {
                // The user's own message ends a turn and is read by nobody as part of it; any other line adds nothing.
            }
        }
        return entries;
    }

    private static void addText(List<TranscriptEntry> entries, String text) {
        if (!text.isBlank()) {
            entries.add(TranscriptEntry.AgentMessage.of(text));
        }
    }

    private JsonNode content() {
        return node.path("message").path("content");
    }

    private static boolean holdsBlock(JsonNode content, String type) {
        for (JsonNode block : content) {
            if (type.equals(HookJson.text(block, "type"))) {
                return true;
            }
        }
        return false;
    }

    /** The texts a user's line can be matched by: the text of a string content; of a list of blocks, the text blocks joined and each of them. */
    private List<String> promptTexts() {
        JsonNode content = content();
        if (content.isString()) {
            return List.of(content.stringValue());
        }
        List<String> blocks = new ArrayList<>();
        for (JsonNode block : content) {
            if ("text".equals(HookJson.text(block, "type"))) {
                blocks.add(HookJson.text(block, "text"));
            }
        }
        if (blocks.size() < 2) {
            return blocks;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(String.join("\n", blocks));
        candidates.addAll(blocks);
        return candidates;
    }

    /** The text of a tool's answer: a string, or the text blocks of a list (the images and other blocks are not text). */
    private static String textOf(JsonNode content) {
        if (content.isString()) {
            return content.stringValue();
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode block : content) {
            if ("text".equals(HookJson.text(block, "type"))) {
                texts.add(HookJson.text(block, "text"));
            } else if ("tool_reference".equals(HookJson.text(block, "type"))) {
                // The tool names that ToolSearch found come back as these blocks and no text: shown as the host wrote them (a short object), where an empty text said the tool
                // returned nothing. Any other block (an image holds its bytes) is not text and stays out.
                texts.add(block.toString());
            }
        }
        return String.join("\n", texts);
    }
}
