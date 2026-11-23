// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.context.ContextLayerService;
import io.github.jaeseongs95.sennard.server.hook.HookModule;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler.HookCall;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.mcp.HostCallerAdapter;
import io.github.jaeseongs95.sennard.server.session.HookDomain;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;

/**
 * The global and role context delivered beside the session's saved state.
 * A context start gets every non-empty layer; later hooks carry notices and can finish attaching a newly linked child to its role. All of it
 * is optional for the host, so a failure here takes only this text away. A subagent's start is composed with its cue and budget in {@link ContinuityHookHandler}.
 */
@Component
@Order(15)
public class SharedContextHookHandler implements HookDomain {
    private static final Logger LOG = LoggerFactory.getLogger(SharedContextHookHandler.class);
    private static final HookVerdict PROCEED = new HookVerdict.Proceed();
    /** The lines that carry a body, in the order a start with too little room keeps them: the layer the user approved by passkey before the one sessions saved. */
    private static final List<String> BODY_PRIORITY = List.of(ContextLayerService.GLOBAL_BLOCK, ContextLayerService.ROLE_BLOCK);

    private record Target(String actorId, boolean full, boolean turnStart) {
    }

    private final ContextLayerService layers;
    private final Messages messages;
    private final Map<HostId, HostWorkFacts> facts;
    /** The hosts whose hooks do not say which conversations are subagents, so nothing in a hook tells whose child a conversation is ({@link HostCallerAdapter#hooksMarkSubagents()}). */
    private final Set<HostId> hostsNamingNoParent;

    public SharedContextHookHandler(ContextLayerService layers, Messages messages, List<HostWorkFacts> facts, List<HostCallerAdapter> callers) {
        this.layers = layers;
        this.messages = messages;
        this.facts = facts.stream().collect(Collectors.toUnmodifiableMap(HostWorkFacts::host, Function.identity()));
        this.hostsNamingNoParent = callers.stream().filter(caller -> !caller.hooksMarkSubagents()).map(HostCallerAdapter::host).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public HookVerdict onEvent(HookCall call) {
        HostEvent event = call.event();
        if (event instanceof HostEvent.SubagentStarted || call.isForAnotherThan(HookModule.CONTINUITY)) {
            return PROCEED;
        }
        String sessionHost = event.context().hostLabel().sessionHost();
        Target target = target(event, sessionHost);
        if (target == null) {
            return PROCEED;
        }
        String text = layerText(sessionHost, event.context().sessionId(), target, hostsNamingNoParent.contains(event.context().hostLabel()));
        HostWorkFacts host = facts.getOrDefault(event.context().hostLabel(), HostWorkFacts.NONE);
        boolean ephemeral = event.startsTurn() && host.restoresAtTurnStart();
        return text == null ? PROCEED : new HookVerdict.AddContext(List.of(new HookVerdict.ContextBlock(text, ephemeral)));
    }

    /**
     * The layers for a subagent that is starting, in at most {@code room} characters. When they do not all fit, a layer that carries a body is replaced by a short line that says
     * to read it with {@code load_shared_context}, and the bodies that fit are put back in this order: the global context first (the user approved it by passkey and it is never
     * cut in part), then the role context (saved by sessions). The lines that are not bodies (a failed check, the assigned role) are always kept; they are short.
     */
    String subagentLayers(String sessionHost, String session, String actorId, int room) {
        // A subagent's start is an event of a host whose hooks mark subagents: its parent is the session the event names, so nothing is left to ask of it.
        return messages.renderLines(withinRoom(layerLines(sessionHost, session, new Target(actorId, true, false), false), room));
    }

    private List<MessageRef> withinRoom(List<MessageRef> lines, int room) {
        List<MessageRef> shown = new ArrayList<>(lines);
        if (rendered(shown) <= room) {
            return shown;
        }
        for (String key : BODY_PRIORITY) {
            int at = indexOfBlock(lines, key);
            if (at >= 0) {
                shown.set(at, omitted(lines.get(at)));
            }
        }
        for (String key : BODY_PRIORITY) {
            int at = indexOfBlock(lines, key);
            if (at >= 0) {
                MessageRef pointer = shown.set(at, lines.get(at));
                if (rendered(shown) > room) {
                    shown.set(at, pointer);
                }
            }
        }
        return shown;
    }

    private static int indexOfBlock(List<MessageRef> lines, String key) {
        for (int at = 0; at < lines.size(); at += 1) {
            if (lines.get(at).key().equals(key)) {
                return at;
            }
        }
        return -1;
    }

    /** The characters these lines take once rendered and joined. */
    private int rendered(List<MessageRef> lines) {
        String text = messages.renderLines(lines);
        return text == null ? 0 : text.length();
    }

    /** The line that stands for a body that does not fit; the role's name is the one thing it keeps of the line it replaces. */
    private static MessageRef omitted(MessageRef block) {
        return ContextLayerService.GLOBAL_BLOCK.equals(block.key()) ? MessageRef.of("hooks.contextLayers.globalOmitted") : MessageRef.of("hooks.contextLayers.roleOmitted", "role", block.params().get("role"));
    }

    /** Which actor gets the layers at this boundary and how much of them; null when this boundary carries none. */
    private static Target target(HostEvent event, String sessionHost) {
        HostContext context = event.context();
        if (context.sessionId().isEmpty()) {
            return null;
        }
        boolean child = context.actor().role() == Role.CHILD && context.actor().basis() == Basis.OBSERVED;
        boolean own = context.actor().role() == Role.ROOT && context.actor().basis() != Basis.UNAVAILABLE;
        if (!child && !own) {
            return null;
        }
        String actorId = ContinuityTools.actorId(sessionHost, context.sessionId(), child ? context.actor().agentKeyOr("unidentified-child") : null);
        return switch (event) {
            case HostEvent ignored when own && event.restoresContext() -> new Target(actorId, true, event.startsTurn());
            case HostEvent.SessionStarted started when own && started.kind() != HostEvent.StartKind.OTHER -> new Target(actorId, false, false);
            case HostEvent ignored when own && event.startsTurn() -> new Target(actorId, false, true);
            case HostEvent.ModelCallFinished finished when finished.carriesContext() -> new Target(actorId, false, false);
            case HostEvent.ToolFinished finished when finished.stepBoundary() -> new Target(actorId, false, false);
            default -> null;
        };
    }

    private String layerText(String sessionHost, String session, Target target, boolean parentUnnamed) {
        return messages.renderLines(layerLines(sessionHost, session, target, parentUnnamed));
    }

    private List<MessageRef> layerLines(String sessionHost, String session, Target target, boolean parentUnnamed) {
        try {
            return layers.hookText(sessionHost, session, target.actorId(), target.full(), target.turnStart(), parentUnnamed).stream()
                    .map(line -> new MessageRef(line.key(), line.params())).toList();
        } catch (RuntimeException unavailable) {
            // Boundary (hook entrance): the layers' text must not fail the hook's answer; the failure is logged with its cause and the answer says the layers are unreadable.
            LOG.warn("CONTEXT_LAYERS_UNAVAILABLE", unavailable);
            return List.of(MessageRef.of("hooks.contextLayers.unreadable", "layer", "admin-log"));
        }
    }
}
