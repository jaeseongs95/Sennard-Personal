// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.context.ChildSessionRoots;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.server.hook.HookRejection;
import io.github.jaeseongs95.sennard.server.hook.HookModule;
import io.github.jaeseongs95.sennard.server.hook.HookVerdict;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.github.jaeseongs95.sennard.server.hook.HostEvent;
import io.github.jaeseongs95.sennard.server.hook.HostEventHandler.HookCall;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.mcp.SkillIntake;
import io.github.jaeseongs95.sennard.server.session.HookDomain;
import io.github.jaeseongs95.sennard.server.workflow.ClassificationAdvice;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowKind;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import tools.jackson.databind.json.JsonMapper;

/**
 * The task continuity rules at each host event: saved state at a context start, the edit gate before a file changes, and the phase check at a
 * turn's end. A child agent's hooks name its parent session and itself: its edits count in the session, its phase check runs at its own end.
 */
@Component
@Order(10)
public class ContinuityHookHandler implements HookDomain {
    private static final Logger LOG = LoggerFactory.getLogger(ContinuityHookHandler.class);
    private static final HookVerdict PROCEED = new HookVerdict.Proceed();
    private static final Set<String> PROGRESS_TOOLS = Set.of("start_workflow", "start_guarded_workflow", "record_stage_result", "finalize_workflow", "abort_workflow", "release_convergence_root");
    /** What a host takes of the context a hook adds at one event (Claude Code: 10,000 characters); the start of a subagent is composed to it. */
    private static final int HOOK_CONTEXT_MAX_CHARACTERS = 10_000;
    /** The blank line between the blocks of one hook answer. */
    private static final String BLOCK_SEPARATOR = "\n\n";
    private static final int FAILURE_CONTEXT_CHARACTERS = 80;
    /** The digits of a failure's text, which differ between runs of the same failure. */
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    /** The operating-system and runtime messages that say the run's environment lacked something, whichever host or task produced them. */
    private static final Pattern ENVIRONMENT_FAILURE = Pattern.compile(
            "ENOENT|EACCES|command not found|is not recognized as|No such file or directory|Permission denied|FileNotFoundError|ModuleNotFoundError|\\b(?:Python \\d|executable|program)\\b.{0,40}\\bnot found|cannot find the (?:file|path)|지정된 (?:파일|경로)를 찾을 수 없습니다",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * The {@code start_workflow} arguments the edit gate shows as the smallest way to open a workflow; what the hook prints is this record as JSON. The skills are the ones the
     * session's latest classify_skills judged needed, with that call's operationKey, or placeholders when the session made no such call: the example names no skill of its own.
     */
    record StartWorkflowExample(String goal, List<String> stages, List<SkillReason> skills, List<String> acceptanceCriteria, List<String> writeTargets, String operationKey,
            @JsonInclude(JsonInclude.Include.NON_NULL) String classificationOperationKey) {
    }

    record SkillReason(String skillId, String reason) {
    }

    private final SharedContextHookHandler sharedContext;
    private final ContinuityService continuity;
    private final ProductEditTargets editTargets;
    private final Messages messages;
    private final String skillsDirectory;
    private final JsonMapper mapper;
    private final Map<HostId, HostWorkFacts> facts;
    private final JournalHookRecorder journal;
    private final ClassificationAdvice classification;
    private final WorkflowGate workflowGate;
    private final ChildSessionRoots childRoots;

    public ContinuityHookHandler(ContinuityService continuity, ProductEditTargets editTargets, Messages messages,
            SennardProperties skills, JsonMapper mapper, List<HostWorkFacts> facts, SharedContextHookHandler sharedContext, JournalHookRecorder journal, ClassificationAdvice classification,
            WorkflowGate workflowGate, ChildSessionRoots childRoots) {
        this.workflowGate = workflowGate;
        this.childRoots = childRoots;
        this.classification = classification;
        this.journal = journal;
        this.sharedContext = sharedContext;
        this.continuity = continuity;
        this.editTargets = editTargets;
        this.messages = messages;
        this.skillsDirectory = skills.skillsDirectory();
        this.mapper = mapper;
        this.facts = facts.stream().collect(Collectors.toUnmodifiableMap(HostWorkFacts::host, Function.identity()));
    }

    @Override
    public HookVerdict onEvent(HookCall call) {
        if (call.isForAnotherThan(HookModule.CONTINUITY)) {
            return PROCEED;
        }
        try {
            String session = workflowSession(call.event().context());
            HookVerdict verdict = decide(call.event(), session);
            return call.event() instanceof HostEvent.ToolFinished finished ? withJournal(finished, session, verdict) : verdict;
        } catch (SennardException failure) {
            if (failure.code() == ConflictCode.DUPLICATE) {
                // A lost first creation is a conflict of this call, whichever domain made it: the one boundary of the hook path (SessionEventHandler) answers it.
                throw failure;
            }
            throw new HookRejected(HookRejection.HOOK_HANDLER_FAILED, failure.code().wireCode(), failure);
        }
    }

    /** A separately addressed child uses the same workflow session as its matched MCP calls. */
    private String workflowSession(HostContext context) {
        String conversation = context.sessionId();
        var actor = context.actor();
        if (actor.role() == Role.CHILD && actor.basis() == Basis.OBSERVED && conversation.equals(actor.agentKey())) {
            return childRoots.rootOf(context.hostLabel().sessionHost(), conversation).orElse(conversation);
        }
        return conversation;
    }

    /**
     * A finished edit tool is recorded in the change journals of the session's workflows, for the root and for a subagent alike (an edit counts in the session). What could not
     * be recorded is said in the answer on a host that takes context after a tool, and is logged either way ({@link JournalHookRecorder}).
     */
    private HookVerdict withJournal(HostEvent.ToolFinished finished, String session, HookVerdict verdict) {
        HostContext context = finished.context();
        List<MessageRef> lines = journal.record(finished, facts.getOrDefault(context.hostLabel(), HostWorkFacts.NONE), session, context.workspace());
        if (lines.isEmpty() || !finished.stepBoundary()) {
            return verdict;
        }
        String text = messages.renderLines(lines);
        return verdict instanceof HookVerdict.AddContext added ? context(added.blocks().getFirst().text() + BLOCK_SEPARATOR + text, false) : verdict instanceof HookVerdict.Proceed ? context(text, false) : verdict;
    }

    private HookVerdict decide(HostEvent event, String session) {
        HostContext context = event.context();
        HostWorkFacts host = facts.getOrDefault(context.hostLabel(), HostWorkFacts.NONE);
        String sessionHost = context.hostLabel().sessionHost();
        // A hook opens no transaction: each branch calls service methods, and the one that reads and changes the session's task makes it when the session has none.
        if (event instanceof HostEvent.SubagentStarted) {
            return subagentStart(session, sessionHost, context);
        }
        if (context.actor().role() == Role.CHILD && context.actor().basis() == Basis.OBSERVED) {
            return child(event, host, session, ContinuityTools.actorId(sessionHost, session, context.actor().agentKeyOr("unidentified-child")));
        }
        if (context.actor().role() != Role.ROOT || context.actor().basis() == Basis.UNAVAILABLE) {
            return PROCEED;
        }
        return root(event, host, session, ContinuityTools.actorId(sessionHost, session, null));
    }

    private HookVerdict root(HostEvent event, HostWorkFacts host, String session, String rootActor) {
        if (host.checksTurnWork() && event instanceof HostEvent.ToolFinished finished) {
            noteTurnWork(finished, host, session);
        }
        return switch (event) {
            case HostEvent.SessionStarted started -> started(started, session);
            case HostEvent.CompactionStarting ignored -> {
                continuity.markPreCompact(session);
                yield PROCEED;
            }
            case HostEvent.CompactionFinished finished -> {
                continuity.recordPostCompact(session, finished.turnId(), finished.successful());
                yield PROCEED;
            }
            case HostEvent ignored when event.startsTurn() -> context(promptLines(host, session, event, continuity.onPromptSubmitted(session, host.checksTurnWork(), host.restoresAtTurnStart(), rootActor)), true);
            case HostEvent.ToolFinished finished when hasNoToolBoundary(host) -> PROCEED;
            case HostEvent.ToolFinished finished when finished.tool().sennardTool() != null && PROGRESS_TOOLS.contains(finished.tool().sennardTool()) ->
                context(continuity.checkpointCueAfterProgress(session, finished.tool().callId()), false);
            case HostEvent.ToolFinished finished -> environmentFailure(session, finished);
            case HostEvent.ToolRequested request -> gate(request, host, session, null);
            case HostEvent.TurnEnded ended -> continueTurn(turnEndLines(ended, host, session, rootActor));
            default -> PROCEED;
        };
    }

    /** What a finished tool did for the evidence of the turn's work; the turn's start is recorded by the prompt's use case. */
    private void noteTurnWork(HostEvent.ToolFinished finished, HostWorkFacts host, String session) {
        host.turnWork(finished.tool(), finished.reportedFailure()).ifPresent(work -> continuity.recordTurnWork(session, work));
    }

    /**
     * A turn that ended in a way that cannot be continued is not continued, and is not counted as the turn's one continuation. What the stage check, the turn evidence and the save
     * check each find is said in one continuation ({@link ContinuityService#onTurnEnded}).
     */
    private List<MessageRef> turnEndLines(HostEvent.TurnEnded ended, HostWorkFacts host, String session, String rootActor) {
        // Whether an instance serves the request is asked before the instances of the kind that closes with the turn are closed: a closed instance serves no request.
        List<MessageRef> held = workflowGate.stopLines(session, null, ended.alreadyContinued(), ended.continuable());
        workflowGate.closeTurn(session);
        return joined(held, continuity.onTurnEnded(session, rootActor, ended.alreadyContinued(), ended.continuable(), host.checksTurnWork()));
    }

    /** What a prompt adds: the workflow gate's lines (the request needs a workflow, the open ones, how to open one), then what the prompt's use case says. */
    private List<MessageRef> promptLines(HostWorkFacts host, String session, HostEvent event, List<MessageRef> usecase) {
        return joined(workflowGate.promptLines(session, host, exampleFor(event.context(), session)), usecase);
    }

    /** The change's start example, made when a refusal or a line needs it: it reads the session's latest classification, which a tool call that is let through has no use for. */
    private java.util.function.Supplier<String> exampleFor(HostContext context, String session) {
        return () -> minimalWorkflowStartExample(context.hostLabel().sessionHost(), session);
    }

    /** The lines of both, in order; null when there are none. */
    private static List<MessageRef> joined(List<MessageRef> first, List<MessageRef> second) {
        List<MessageRef> all = new ArrayList<>(orEmpty(first));
        all.addAll(orEmpty(second));
        return all.isEmpty() ? null : all;
    }

    /** A host with no message boundary after a tool takes context from its model calls only, so nothing can be said after one. */
    private static boolean hasNoToolBoundary(HostWorkFacts host) {
        return host.restoresAtTurnStart();
    }

    private HookVerdict started(HostEvent.SessionStarted started, String session) {
        switch (started.kind()) {
            case STARTUP -> continuity.startSession(session); // A new session has nothing saved to restore; its start makes its task, so a refused tool call has none to roll back.
            case FORK -> continuity.startFork(session);
            case CLEAR -> continuity.clear(session);
            case RESUME -> {
                return context(continuity.resumeContext(session), false);
            }
            case COMPACT -> {
                return context(continuity.compactContext(session), false);
            }
            case OTHER -> {
            }
        }
        return PROCEED;
    }

    private HookVerdict child(HostEvent event, HostWorkFacts host, String session, String actor) {
        if (event instanceof HostEvent.ToolRequested request) {
            return gate(request, host, session, actor);
        }
        boolean turnEnd = event instanceof HostEvent.TurnEnded || event instanceof HostEvent.SubagentEnded;
        if (turnEnd) {
            boolean quiet = event instanceof HostEvent.TurnEnded ended ? ended.alreadyContinued() : ((HostEvent.SubagentEnded) event).alreadyContinued();
            // A subagent is held to the same rule: its own end needs an instance it opened or resumed.
            List<MessageRef> held = workflowGate.stopLines(session, actor, quiet, true);
            // What the subagent opened and the turn closes is closed with the subagent, not with the main agent's turn: the instances are independent.
            workflowGate.closeSubagent(session, actor);
            return continueTurn(orEmpty(joined(held, continuity.phaseCheck(session, quiet, actor))));
        }
        HookVerdict failure = event instanceof HostEvent.ToolFinished finished ? environmentFailure(session, finished) : PROCEED;
        boolean step = event instanceof HostEvent.ToolFinished finished && finished.stepBoundary();
        return step ? withSituation(failure, session, actor) : failure;
    }

    private static List<MessageRef> orEmpty(List<MessageRef> lines) {
        return lines == null ? List.of() : lines;
    }

    /** A failure here takes only this text away. */
    private HookVerdict withSituation(HookVerdict failure, String session, String actor) {
        try {
            String text = messages.renderLines(continuity.situationForActor(session, actor, candidate -> true));
            if (text == null) {
                return failure;
            }
            return failure instanceof HookVerdict.AddContext added ? context(added.blocks().getFirst().text() + "\n\n" + text, false) : context(text, false);
        } catch (RuntimeException unavailable) {
            // Boundary (hook entrance): this optional text must not fail the hook's answer; the failure is logged with its cause.
            LOG.warn("SUBAGENT_SITUATION_UNAVAILABLE", unavailable);
            return failure;
        }
    }

    private HookVerdict gate(HostEvent.ToolRequested request, HostWorkFacts host, String session, String actor) {
        // First the workflow gate: no tool but the Sennard tools, the host's skill and tool loading runs for a request that no workflow instance serves.
        Optional<String> unserved = workflowGate.refusal(request.tool(), host, session, actor, exampleFor(request.context(), session));
        if (unserved.isPresent()) {
            return new HookVerdict.Refuse(unserved.get());
        }
        return host.editTargets(request.tool()).map(targets -> gateTargets(request, host, session, actor, targets)).orElse(PROCEED);
    }

    private HookVerdict gateTargets(HostEvent.ToolRequested request, HostWorkFacts host, String session, String actor, HostWorkFacts.EditTargets targets) {
        ProductEditTargets.Read read = editTargets.read(request.context(), request.tool(), targets, host);
        if (read.product().isEmpty()) {
            return PROCEED;
        }
        MessageRef refusal = continuity.gateEdit(session, new ArrayList<>(read.product().keySet()), new ArrayList<>(read.product().values()), minimalWorkflowStartExample(request.context().hostLabel().sessionHost(), session), actor,
                read.unread(), read.unreadPlaces(), host, targets.opaqueExecutionOnly());
        return refusal == null ? PROCEED : new HookVerdict.Refuse(messages.render(refusal));
    }

    /**
     * What a model copies it sends back, so the example holds no value of its own for what the model has to decide from its work: the skills are those the session's latest
     * classify_skills judged needed (with its operationKey), and without such a call a placeholder that asks for the call first. The server refuses the placeholders if they come back
     * ({@code PlaceholderTexts}).
     */
    private String minimalWorkflowStartExample(String sessionHost, String session) {
        Optional<ClassificationAdvice.Latest> latest = classification.latest(sessionHost, session);
        String reason = messages.render(MessageRef.of("hooks.editGate.skillReasonPlaceholder"));
        List<String> needed = latest.map(ClassificationAdvice.Latest::needed).orElse(List.of());
        List<SkillReason> skills = (needed.isEmpty() ? List.of(messages.render(MessageRef.of("hooks.editGate.skillPlaceholder"))) : needed).stream().map(skill -> new SkillReason(skill, reason)).toList();
        // With a classify_skills call the example cites its operationKey; without one the field is left out of the example (the record's null is no value, the JSON omits it).
        StartWorkflowExample example = latest.isPresent() ? startExample(skills, latest.get().operationKey()) : startExample(skills, null);
        return "start_workflow " + mapper.writeValueAsString(example);
    }

    /**
     * The stages of the example: one placeholder for each phase of the change's process that records the result of a stage, so what the example shows follows the kind's definition and
     * names no capability of its own. The model chooses the capabilities from the skill registry, and the server refuses the placeholder if it comes back ({@code PlaceholderTexts}).
     */
    private List<String> stages() {
        String placeholder = messages.render(MessageRef.of("hooks.editGate.stagePlaceholder"));
        return WorkflowKind.CHANGE.process().stream().filter(WorkflowKind.Phase::recordsStage).map(phase -> placeholder).distinct().toList();
    }

    private StartWorkflowExample startExample(List<SkillReason> skills, String classificationOperationKey) {
        return new StartWorkflowExample(messages.render(MessageRef.of("hooks.editGate.goalPlaceholder")), stages(), skills,
                List.of(messages.render(MessageRef.of("hooks.editGate.criterionPlaceholder"))), List.of(messages.render(MessageRef.of("hooks.editGate.writeTargetPlaceholder"))),
                messages.render(MessageRef.of("hooks.editGate.operationKeyPlaceholder")), classificationOperationKey);
    }

    /**
     * Only a call the host reported as failed can be a run that lacked something: the same words in a result the host reported as successful are what the call printed or read
     * (a file about ENOENT, a grep for it), not a failure.
     */
    private HookVerdict environmentFailure(String session, HostEvent.ToolFinished finished) {
        if (!finished.reportedFailure()) {
            return PROCEED;
        }
        return Optional.ofNullable(finished.resultTail()).flatMap(ContinuityHookHandler::environmentFailureKey).map(key -> context(continuity.environmentFailureCue(session, key), false)).orElse(PROCEED);
    }

    /** The text that tells one failure from another (digits differ between runs of the same failure). */
    private static Optional<String> environmentFailureKey(String result) {
        Matcher match = ENVIRONMENT_FAILURE.matcher(result);
        if (!match.find()) {
            return Optional.empty();
        }
        int start = Math.max(0, match.start() - FAILURE_CONTEXT_CHARACTERS);
        int end = Math.min(result.length(), match.end() + FAILURE_CONTEXT_CHARACTERS);
        return Optional.of(DIGITS.matcher(result.substring(start, end)).replaceAll("#"));
    }

    private HookVerdict subagentStart(String session, String sessionHost, HostContext context) {
        List<MessageRef> cueLines = new ArrayList<>(List.of(MessageRef.of("hooks.subagentCue.skills", "targets", installedSkillTargets()), MessageRef.of("hooks.subagentCue.scope")));
        // The host may hand a subagent the MCP instructions only after its first tool search, so the start names where the intake is, as a session start does.
        intakePointer().ifPresent(cueLines::add);
        StringBuilder text = new StringBuilder(messages.renderLines(cueLines));
        String actor = ContinuityTools.actorId(sessionHost, session, context.actor().agentKeyOr("unidentified-child"));
        // The whole start text is held to the host's limit: what the start must say (the skill cue, the scope, the intake pointer) comes first, then the layers (the global context
        // before the role context, each replaced by a pointer when it does not fit), then the workflow card, then the saved situation; each takes only the room the earlier left.
        String layers = sharedContext.subagentLayers(sessionHost, session, actor, HOOK_CONTEXT_MAX_CHARACTERS - text.length() - BLOCK_SEPARATOR.length());
        if (layers != null) {
            text.append(BLOCK_SEPARATOR).append(layers);
        }
        try {
            int cardUsed = text.length();
            String card = messages.renderLines(continuity.workflowCardForSubagent(session, candidate -> cardUsed + BLOCK_SEPARATOR.length() + candidate.length() <= HOOK_CONTEXT_MAX_CHARACTERS));
            if (card != null) {
                text.append(BLOCK_SEPARATOR).append(card);
            }
            int used = text.length();
            String situation = messages.renderLines(continuity.situationForActor(session, actor, candidate -> used + BLOCK_SEPARATOR.length() + candidate.length() <= HOOK_CONTEXT_MAX_CHARACTERS));
            if (situation != null) {
                text.append(BLOCK_SEPARATOR).append(situation);
            }
        } catch (RuntimeException unavailable) {
            // Boundary (hook entrance): this optional text must not fail the hook's answer; the failure is logged with its cause.
            LOG.warn("SUBAGENT_SITUATION_UNAVAILABLE", unavailable);
        }
        return context(text.toString(), false);
    }

    /** The line that says where the installed intake is, or nothing when none is deployed. */
    private Optional<MessageRef> intakePointer() {
        try {
            return SkillIntake.deployedIntake(skillsDirectory).map(path -> MessageRef.of("instructions.intake.pointer", "path", path.toString()));
        } catch (IOException unreadable) {
            LOG.warn("SKILL_INTAKE_UNREADABLE", unreadable);
            return Optional.empty();
        }
    }

    /** The installed skills' declared targets, one line each. */
    private String installedSkillTargets() {
        try {
            return SkillIntake.installedSkillTargets(skillsDirectory, mapper);
        } catch (IOException unreadable) {
            LOG.warn("SKILL_TARGETS_UNREADABLE", unreadable);
            return "";
        }
    }

    private HookVerdict context(List<MessageRef> lines, boolean repeatsEachTurn) {
        String text = messages.renderLines(lines);
        return text == null ? PROCEED : new HookVerdict.AddContext(List.of(new HookVerdict.ContextBlock(text, repeatsEachTurn)));
    }

    private static HookVerdict context(String text, boolean repeatsEachTurn) {
        return new HookVerdict.AddContext(List.of(new HookVerdict.ContextBlock(text, repeatsEachTurn)));
    }

    private HookVerdict continueTurn(List<MessageRef> lines) {
        String text = messages.renderLines(lines);
        return text == null ? PROCEED : new HookVerdict.ContinueTurn(text);
    }
}
