// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.message.MessageRef;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which model did the work, as the host observed it (never as the caller says). The model, its class and its reasoning effort are recorded as
 * the host spelled them, whatever they are: no list of models, classes or efforts is kept here, and no value is judged against a floor. Choosing
 * the model and the effort is the model's own judgment, so a host, model or effort Sennard has never seen runs the same code.
 */
@Component
public class ExecutionAssurance {
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    private final Clock clock;
    private final JsonMapper mapper;

    public ExecutionAssurance(Clock clock, JsonMapper mapper) {
        this.clock = clock;
        this.mapper = mapper;
    }

    /** What an execution check is about; each message renders it through its {@code subject} select. */
    record Subject(String subject, String stageId) {
        Object[] params() {
            return new Object[] {"subject", subject, "stageId", stageId};
        }

        /** Whether the check is about one stage of a plan (it names the stage); the bootstrap subjects name none. */
        boolean aboutStage() {
            return stageId != null && !stageId.isEmpty();
        }
    }

    static final Subject BOOTSTRAP = new Subject("bootstrapOrchestration", "");

    static Subject stageSubject(String stageId) {
        return new Subject("stage", stageId);
    }

    record Binding(String phase, String taskId, String runId, String stageId, Long revision) {
        Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("phase", phase);
            out.put("taskId", taskId);
            out.put("runId", runId);
            out.put("stageId", stageId);
            out.put("revision", revision);
            return out;
        }

        static Binding bootstrap(String taskId) {
            return new Binding("bootstrap", taskId, null, null, null);
        }
    }

    /** What the host observed for this call, or null. One host call yields one consumable observation, however often the call is retried. */
    ExecutionContext observe(WorkflowCall call, Binding binding) {
        if (!(call.execution() instanceof SelfSession.Execution.Observed observed) || call.callId() == null) {
            return null;
        }
        String effort = observed.effort() != null && !observed.effort().isBlank() ? observed.effort() : null;
        List<String> notes = new ArrayList<>();
        if (effort == null) {
            notes.add("HOST_EFFORT_UNREPORTED");
        }
        // The server keeps no model catalog: every model is recorded with its class unknown, none is refused.
        notes.add("MODEL_NOT_IN_CATALOG");
        if (observed.fromRequestRecord()) {
            // The model is the one the host sent the request with, which produced the call, not the one on the line that issued it.
            notes.add("MODEL_FROM_REQUEST_RECORD");
        }
        return new ExecutionContext(ContractValidator.CONTRACT_VERSION, observed.model(), null, effort, notes, ExecutionSource.RUNTIME, clock.instant(), observationId(call), binding.taskId(), binding.runId(),
                binding.stageId(), binding.revision(), call.actorId(), null);
    }

    private String observationId(WorkflowCall call) {
        String text = mapper.writeValueAsString(List.of(call.host(), call.sessionId(), call.actorId(), call.callId()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Digests.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    ExecutionContext observeTrusted(WorkflowCall call, Binding binding, Subject subject) {
        ExecutionContext context = observe(call, binding);
        if (context == null) {
            Map<String, Object> diagnosis = new LinkedHashMap<>();
            diagnosis.put("status", "unavailable");
            String reason = null;
            if (call.execution() instanceof SelfSession.Execution.Unobserved unobserved) {
                reason = unobserved.reason();
                // What the host's reader found when it looked (transcriptReason, linesSeen): codes and numbers only.
                unobserved.facts().forEach((key, value) -> diagnosis.putIfAbsent(key, value));
            } else if (call.callId() == null) {
                reason = "HOST_CALL_ID_UNAVAILABLE";
            }
            if (reason != null) {
                diagnosis.put("reasonCode", reason);
            }
            diagnosis.put("required", List.of("host-owned model", "reasoningEffort", "session", "current tool call"));
            diagnosis.put("connection", "The host did not report the model, reasoning effort or call ID for this call. Caller arguments cannot supply them.");
            // A subagent that cannot be observed is not stuck: its next call is observed anew, and the agent that started it records from its own session.
            boolean subagentStage = subject.aboutStage() && !call.observedCaller().parentChain().isEmpty();
            MessageRef refusal = subagentStage ? msg("workflow.service.subagentStageUnobserved", "stageId", subject.stageId(), "reason", reason == null ? "NOT_OBSERVED" : reason)
                    : msg("workflow.service.requiresTrustedAttestation", subject.params());
            throw new WorkflowFailure(WorkflowCode.BINDING_REQUIRED, refusal, details("binding", binding.asMap(), "observation", diagnosis));
        }
        return context;
    }

    void assertTrusted(ExecutionRequirement requirement, ExecutionContext context, Subject subject, Binding binding) {
        assertContext(requirement, context, subject);
        assertBinding(context, subject, binding);
        Instant observedAt = context.observedAt();
        if (observedAt == null || observedAt.isAfter(clock.instant().plus(CLOCK_SKEW))) {
            throw new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.service.attestationObservationTime", subject.params()),
                    details("reasonCode", "EXECUTION_OBSERVATION_TIME_INVALID", "observedAt", observedAt));
        }
    }

    void assertBinding(ExecutionContext context, Subject subject, Binding binding) {
        String observationId = context.observationId();
        String actorId = context.actorId();
        if (observationId == null || observationId.length() < 16 || !Objects.equals(context.taskId(), binding.taskId()) || !Objects.equals(context.runId(), binding.runId())
                || !Objects.equals(context.stageId(), binding.stageId()) || !Objects.equals(context.revision(), binding.revision()) || actorId == null || actorId.isEmpty()) {
            throw new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.service.attestationNotBound", subject.params()), details("reasonCode", "EXECUTION_OBSERVATION_UNBOUND", "binding", binding.asMap(), "context", context));
        }
    }

    void assertContext(ExecutionRequirement requirement, ExecutionContext context, Subject subject) {
        if (!requirement.semantic()) {
            return;
        }
        if (context == null) {
            throw new WorkflowFailure(WorkflowCode.BINDING_REQUIRED, msg("workflow.service.requiresObservedModel", subject.params()), details("requirement", requirement));
        }
        if (!ContractValidator.CONTRACT_VERSION.equals(context.schemaVersion()) || isEmpty(context.model()) || (context.modelClass() != null && context.modelClass().isEmpty())
                || (context.reasoningEffort() != null && context.reasoningEffort().isEmpty()) || context.source() == null || context.observedAt() == null) {
            throw malformed(context, subject, null);
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static WorkflowFailure malformed(Object context, Subject subject, Throwable cause) {
        return new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg("workflow.service.executionContextMalformed", subject.params()), details("reasonCode", "EXECUTION_CONTEXT_MALFORMED", "context", context),
                cause);
    }

    /** The plan's bootstrap observation is intact and every stage that is not a deterministic one carries a requirement. */
    void assertStrictPlan(WorkflowPlan plan, String subject) {
        WorkflowPlan.BootstrapExecution bootstrap = plan.bootstrapExecution();
        if (bootstrap == null) {
            throw new WorkflowFailure(WorkflowCode.BINDING_REQUIRED, msg("workflow.service.planRequiresBootstrapAssurance", "subject", subject));
        }
        Subject bootstrapSubject = new Subject(subject + "Bootstrap", "");
        assertContext(bootstrap.requirement(), bootstrap.context(), bootstrapSubject);
        assertBinding(bootstrap.context(), bootstrapSubject, Binding.bootstrap(plan.taskId()));
        for (WorkflowPlan.Stage stage : plan.stages()) {
            if (stage.executionRequirement() == null && !PlanBuilder.deterministic(stage.satisfiedCapabilities())) {
                throw new WorkflowFailure(WorkflowCode.BINDING_REQUIRED, msg("workflow.service.planStageWithoutExecutionRequirement", "subject", subject, "stageId", stage.stageId()));
            }
        }
    }
}
