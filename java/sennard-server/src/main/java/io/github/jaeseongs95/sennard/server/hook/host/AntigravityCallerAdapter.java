// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.server.hook.HostContext.SessionActor;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession.Execution;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Antigravity, class B: {@code _meta} names the conversation and its root but gives the call no id, so a call is matched to its hook by the
 * conversation, the tool and the arguments before the actor can be used. The hook row also records the model.
 */
@Component
public final class AntigravityCallerAdapter implements HostCallerAdapter {
    private static final String PREFIX = "antigravity.google/";
    private static final SessionActor UNCLASSIFIED = SessionActor.root(Basis.PROVISIONAL, "ANTIGRAVITY_HOOK_ACTOR_UNCORRELATED");

    private final JsonMapper sorted;
    private final Cache<String, SessionActor> hookActors = Caffeine.newBuilder().maximumSize(4096).build();

    /** @param sorted the application's mapper with sorted properties ({@link SortedJsonConfiguration}), so the same arguments give the same key whatever order the host wrote them in */
    AntigravityCallerAdapter(@Qualifier(SortedJsonConfiguration.SORTED_JSON_MAPPER) JsonMapper sorted) {
        this.sorted = sorted;
    }

    @Override
    public HostId host() {
        return AntigravityHost.ID;
    }

    /**
     * A conversation's hooks look the same for a child as for a root; only a tool call's metadata says which. The host documents no hook field that names a parent, a root
     * or an agent (antigravity.google/docs/hooks lists {@code conversationId}, {@code workspacePaths}, {@code transcriptPath}, {@code artifactDirectoryPath},
     * {@code modelName}, {@code lastUserInput} and the event's own fields). An unknown conversation is told to make a Sennard tool call
     * ({@code hooks.contextLayers.parentUnknown}). We apply the call's actor to later hooks when the call matches an admission for that conversation.
     */
    @Override
    public boolean hooksMarkSubagents() {
        return false;
    }

    @Override
    public CallerFacts read(Map<String, Object> meta) {
        String conversation = textOrNull(meta.get(PREFIX + "conversation_id"));
        String root = textOrNull(meta.get(PREFIX + "root_conversation_id"));
        if (conversation == null || root == null) {
            return new CallerFacts.Unavailable("ANTIGRAVITY_CALL_META_MISSING");
        }
        Execution unclaimed = new Execution.Unobserved("ANTIGRAVITY_OBSERVATION_MISSING");
        Object rawParent = meta.get(PREFIX + "parent_conversation_id");
        if (rawParent == null) {
            if (!conversation.equals(root)) {
                throw invalid("ANTIGRAVITY_ROOT_CONVERSATION_MISMATCH");
            }
            return new CallerFacts.Observed(root, conversation, SessionActor.root(Basis.OBSERVED, "ANTIGRAVITY_MCP_META_ROOT"), null, null, unclaimed);
        }
        String parent = textOrNull(rawParent);
        if (parent == null || conversation.equals(root) || parent.equals(conversation)) {
            throw invalid("ANTIGRAVITY_CALL_META_INVALID");
        }
        return new CallerFacts.Observed(root, conversation, SessionActor.child(conversation, "ANTIGRAVITY_MCP_META_CHILD_CONVERSATION"), parent, null, unclaimed);
    }

    /** Each hook gets its own row under the call's key, so a retried call leaves two and the claim takes the newest. */
    @Override
    public HookAdmission admission(HostEvent.ToolRequested request) {
        String tool = Objects.requireNonNull(request.tool().sennardTool(), "A PreToolUse of a Sennard tool names the tool.");
        CallKeys keys = keys(request.context().sessionId(), tool, request.tool().input());
        return new HookAdmission.Row(keys.call() + ":" + UUID.randomUUID());
    }

    @Override
    public Optional<CallKeys> callKeys(CallerFacts.Observed facts, String tool, JsonNode arguments) {
        return Optional.of(keys(facts.hookSessionId(), tool, arguments));
    }

    @Override
    public boolean matchedHook(CallerFacts.Observed facts) {
        SessionActor accepted = hookActors.asMap().merge(facts.hookSessionId(), facts.actor(),
                (previous, observed) -> previous.role() == Role.CHILD || previous.equals(observed) ? previous : observed);
        return accepted.equals(facts.actor());
    }

    @Override
    public SessionActor currentHookActor(String conversation) {
        SessionActor actor = hookActors.getIfPresent(conversation);
        return actor == null ? UNCLASSIFIED : actor;
    }

    static SessionActor unclassifiedHook() {
        return UNCLASSIFIED;
    }

    private CallKeys keys(String conversation, String tool, JsonNode arguments) {
        String caller = Digests.sha256Hex(conversation + "\0" + tool);
        return new CallKeys(caller + ":" + Digests.sha256Hex(sorted.writeValueAsString(arguments)), caller);
    }

    /** The text of a meta value, or null when it is not a non-blank text. */
    private static String textOrNull(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static BindingException invalid(String reasonCode) {
        return new BindingException(BindingCode.BINDING_INVALID, Map.of(), SennardException.args("reasonCode", reasonCode));
    }
}
