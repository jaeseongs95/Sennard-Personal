// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.hook.ToolCall;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.message.SessionText;
import io.github.jaeseongs95.sennard.server.workflow.ConvergenceLimitSource;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowEvents;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowKind;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowProcedure;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure;
import io.github.jaeseongs95.sennard.server.workflow.WorkflowService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The workflow gate every request passes: the agent chooses a procedure by opening or resuming a workflow instance, or by applying a transition to existing work.
 * The server holds back tools until a workflow serves the request and checks the selection again at the end of the turn. The common service supplies the attribution.
 * Host adapters describe what their tools do ({@link HostWorkFacts}) and how the hook delivers a refusal.
 *
 * <p>The refusal offers the procedures and their MCP tools. At the end of the request, the gate closes instances whose kind defines turn-end closure.
 */
@Component
public class WorkflowGate {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowGate.class);
    private static final String PROCEDURES = Arrays.stream(WorkflowProcedure.values()).map(WorkflowProcedure::name).collect(Collectors.joining(", "));
    private static final String TRANSITION_TOOLS = Arrays.stream(WorkflowEvents.Transition.values()).map(WorkflowEvents.Transition::toolName).collect(Collectors.joining(", "));
    private static final List<MessageRef> STOP_UNAVAILABLE = List.of(MessageRef.of("hooks.workflowGate.stopUnavailable", "procedures", PROCEDURES));
    private static final StopCheck NO_STOP_ACTION = new StopCheck(List.of(), null);
    private static final StopCheck STOP_EXHAUSTED = new StopCheck(List.of(), MessageRef.of("hooks.workflowGate.stopExhausted"));

    private final ContinuityService continuity;
    private final WorkflowService workflows;
    private final Messages messages;
    private final JsonMapper mapper;
    private final ConvergenceLimitSource limits;

    public WorkflowGate(ContinuityService continuity, WorkflowService workflows, Messages messages, JsonMapper mapper, ConvergenceLimitSource limits) {
        this.continuity = continuity;
        this.workflows = workflows;
        this.messages = messages;
        this.mapper = mapper;
        this.limits = limits;
    }

    /** What a prompt says: the request needs a workflow, the instances that are open (the agent resumes one or opens a new one), and how to open each kind. */
    public List<MessageRef> promptLines(String session, HostWorkFacts host, Supplier<String> changeExample) {
        List<MessageRef> lines = new ArrayList<>();
        lines.add(MessageRef.of("hooks.workflowGate.prompt"));
        lines.addAll(choices(session, host, changeExample));
        return lines;
    }

    /**
     * The refusal of a tool call, or empty when the call may run. Tool scripts are unsupported. Sennard tools and the host's tools that load one (or open a skill) need no workflow.
     * Other calls need an instance of the request, and a command needs one whose kind allows commands. An edit of a product file is judged further by the edit gate, which
     * asks which instance owns the file. {@code actor} is null for the session's own agent.
     */
    public Optional<String> refusal(ToolCall tool, HostWorkFacts host, String session, String actor, Supplier<String> changeExample) {
        if (host.runsToolScript(tool)) {
            return Optional.of(messages.render(MessageRef.of("hooks.workflowGate.unsupportedToolScript")));
        }
        if (tool.sennardTool() != null || host.toolsBeforeWorkflow().contains(tool.name())) {
            return Optional.empty();
        }
        ContinuityService.AttributionCheck check = continuity.attributionCheck(session, actor);
        if (!check.unavailable().isEmpty() && host.editTargets(tool).filter(targets -> !targets.opaqueExecutionOnly()).isPresent()) {
            List<MessageRef> unavailable = continuity.currentWorkflowProblems(session);
            if (unavailable.isEmpty()) {
                unavailable = check.unavailable().stream().map(continuity::workflowUnavailable).toList();
            }
            return Optional.of(messages.renderLines(unavailable));
        }
        ContinuityService.Attribution attributed = check.requireReadable();
        String reason = !attributed.any() ? "none" : host.runsCommand(tool) && !attributed.allows(WorkflowKind.Action.COMMAND) ? "command" : null;
        if (reason == null) {
            return Optional.empty();
        }
        List<MessageRef> lines = new ArrayList<>();
        lines.add(MessageRef.of("hooks.workflowGate.refused", "tool", tool.name(), "reason", reason, "kinds", attributed.kindNames()));
        lines.addAll(choices(session, host, changeExample));
        return Optional.of(messages.renderLines(lines));
    }

    /** Guidance for a continuation, or the reason to end the turn when no more continuations are allowed. */
    public record StopCheck(List<MessageRef> guidance, MessageRef terminationReason) {
    }

    /** Checks selection at every Stop. A turn that already ended has no rechecks left. */
    public StopCheck stopCheck(String session, String actor, boolean continuable) {
        try {
            return switch (continuity.checkStopSelection(session, actor, stopLimit(continuable))) {
                case SELECTED, UNAVAILABLE -> NO_STOP_ACTION;
                case EXHAUSTED -> STOP_EXHAUSTED;
                case RECHECK -> stopGuidance(session, false);
                case LAST_RECHECK -> stopGuidance(session, true);
            };
        } catch (DataAccessException | IllegalStateException unreadable) {
            LOG.warn("The workflow gate could not read what serves the request", unreadable);
            return NO_STOP_ACTION;
        }
    }

    private StopCheck stopGuidance(String session, boolean last) {
        List<MessageRef> guidance = new ArrayList<>();
        try {
            var open = continuity.openInstanceCount(session);
            if (!open.unavailable().isEmpty()) {
                guidance.addAll(STOP_UNAVAILABLE);
                guidance.addAll(open.unavailable());
            } else {
                guidance.add(MessageRef.of("hooks.workflowGate.stop", "count", open.count(), "procedures", PROCEDURES));
            }
        } catch (DataAccessException | IllegalStateException unreadable) {
            LOG.warn("The open workflows could not be counted for the stop gate", unreadable);
            guidance.addAll(STOP_UNAVAILABLE);
        }
        if (last) {
            guidance.add(MessageRef.of("hooks.workflowGate.stopLastRecheck"));
        }
        return new StopCheck(guidance, null);
    }

    /** Applies the Stop limit to each workflow that has a recorded violation. */
    public StopCheck stopWorkflows(List<ContinuityService.StopViolation> violations, boolean continuable) {
        if (violations.isEmpty()) {
            return NO_STOP_ACTION;
        }
        List<String> blocked = new ArrayList<>();
        List<String> last = new ArrayList<>();
        int pending = 0;
        try {
            int limit = stopLimit(continuable);
            for (ContinuityService.StopViolation violation : violations) {
                switch (workflows.recheckAtStop(violation.rootId(), violation.runId(), violation.kind(), limit)) {
                    case SETTLED -> { }
                    case RECHECK -> pending++;
                    case LAST_RECHECK -> {
                        pending++;
                        last.add(violation.runId());
                    }
                    case BLOCKED -> blocked.add(violation.runId());
                }
            }
        } catch (DataAccessException | JacksonException | IllegalStateException | WorkflowFailure unreadable) {
            LOG.warn("A workflow's Stop check could not be applied", unreadable);
            if (blocked.isEmpty()) {
                return NO_STOP_ACTION;
            }
        }
        if (!blocked.isEmpty()) {
            return new StopCheck(List.of(), MessageRef.of("hooks.phaseCheck.exhausted", "runs", String.join(", ", blocked)));
        }
        if (pending == 0) {
            return NO_STOP_ACTION;
        }
        var guidance = new ArrayList<MessageRef>();
        guidance.add(MessageRef.of("hooks.phaseCheck.summary", "count", pending));
        if (!last.isEmpty()) {
            guidance.add(MessageRef.of("hooks.phaseCheck.lastRecheck", "runs", String.join(", ", last)));
        }
        return new StopCheck(guidance, null);
    }

    private int stopLimit(boolean continuable) {
        return continuable ? limits.current().maxAttemptsPerEpoch() : 0;
    }

    /**
     * Closes the instances the session's own agent opened for this request whose kind closes with the turn. What a subagent opened is not the main agent's turn's: the workflow instances are
     * independent, and {@link #closeSubagent} closes it at the subagent's end. A failure to close one is logged with its cause and the instance stays open (the prompt shows it again),
     * because the end of a turn is not failed for it.
     */
    public void closeTurn(String session) {
        try {
            continuity.turnEndInstances(session).forEach(workflows::closeInstance);
        } catch (RuntimeException failure) {
            // Boundary (hook entrance): the close is the server's tidiness of an answer-only instance; the hook's answer does not depend on it.
            LOG.warn("The instances that close with the turn could not be closed", failure);
        }
    }

    /** Closes the instances of the kind that closes with the turn that this subagent took, at the end of the subagent; a failure is logged and the end goes on, as for {@link #closeTurn}. */
    public void closeSubagent(String session, String actor) {
        try {
            continuity.subagentEndInstances(session, actor).forEach(workflows::closeInstance);
        } catch (RuntimeException failure) {
            // Boundary (hook entrance): the same as the main agent's turn end.
            LOG.warn("The instances of the subagent that close with its end could not be closed", failure);
        }
    }

    /** The open instances the agent may resume, and one line per kind that opens a new one. */
    private List<MessageRef> choices(String session, HostWorkFacts host, Supplier<String> changeExample) {
        List<MessageRef> lines = new ArrayList<>();
        try {
            var open = continuity.openInstances(session);
            lines.addAll(open.unavailable());
            if (!open.instances().isEmpty()) {
                lines.add(MessageRef.of("hooks.workflowGate.open"));
                open.instances().forEach(instance -> lines.add(MessageRef.of("hooks.workflowGate.instance", "runId", instance.runId(), "kind", instance.kind().name(), "phase", instance.phase(),
                        "goal", SessionText.quoted(instance.goal()))));
                lines.add(MessageRef.of("hooks.workflowGate.resume", callParams(host, "resume_workflow")));
            }
        } catch (DataAccessException | IllegalStateException unreadable) {
            // Boundary (hook entrance): the list of open instances is a help; the way to open one is given without it.
            LOG.warn("The open workflows could not be listed for the workflow gate", unreadable);
        }
        for (WorkflowProcedure procedure : WorkflowProcedure.values()) {
            lines.add(procedure.startKind().map(kind -> startChoice(host, procedure, kind, changeExample))
                    .orElseGet(() -> MessageRef.of("hooks.workflowGate.transition", "procedure", procedure.name(), "tools", TRANSITION_TOOLS,
                            "listTool", "list_session_workflows", "runStatus", "get_workflow_status", "rootStatus", "get_convergence_status")));
        }
        return lines;
    }

    private MessageRef startChoice(HostWorkFacts host, WorkflowProcedure procedure, WorkflowKind kind, Supplier<String> changeExample) {
        Map<String, Object> params = callParams(host, kind.toolName());
        params.put("procedure", procedure.name());
        params.put("purpose", messages.render(MessageRef.of(kind.key("purpose"))));
        params.put("toolName", kind.toolName());
        params.put("example", startExample(kind, changeExample));
        return MessageRef.of("hooks.workflowGate.kind", params);
    }

    /** The smallest arguments that open the kind: the change's is the edit gate's own example, the others are made from what the kind requires. */
    private String startExample(WorkflowKind kind, Supplier<String> changeExample) {
        if (kind.allowsFileEdits()) {
            return changeExample.get();
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("goal", messages.render(MessageRef.of("hooks.editGate.goalPlaceholder")));
        if (kind.takesTarget()) {
            arguments.put("target", messages.render(MessageRef.of("hooks.workflowGate.targetPlaceholder")));
        }
        if (kind.skillsRequired()) {
            arguments.put("skills", List.of(Map.of("skillId", messages.render(MessageRef.of("hooks.editGate.skillPlaceholder")), "reason", messages.render(MessageRef.of("hooks.editGate.skillReasonPlaceholder")))));
        }
        arguments.put("operationKey", messages.render(MessageRef.of("hooks.editGate.operationKeyPlaceholder")));
        return mapper.writeValueAsString(arguments);
    }

    /** How this host's model calls a Sennard tool: by its own name, through a tool that calls MCP tools by server and tool name, or after loading it; the sentence of each form is the catalog's. */
    private Map<String, Object> callParams(HostWorkFacts host, String tool) {
        Map<String, Object> params = new LinkedHashMap<>();
        Optional<HostWorkFacts.SennardToolName> name = host.sennardToolName(tool);
        params.put("call", name.map(called -> called.wrapper() != null ? "wrapped" : called.loader() != null ? "loaded" : "named").orElse("none"));
        params.put("tool", tool);
        name.ifPresent(called -> {
            params.put("tool", called.name());
            params.put("wrapper", called.wrapper() == null ? "" : called.wrapper());
            params.put("server", called.server() == null ? "" : called.server());
            params.put("loader", called.loader() == null ? "" : called.loader());
            params.put("query", called.loaderQuery() == null ? "" : called.loaderQuery());
        });
        return params;
    }
}
