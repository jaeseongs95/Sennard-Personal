// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.springframework.ai.mcp.annotation.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpMeta;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.journal.WorkflowPhase;
import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.server.approval.RootAction;
import io.github.jaeseongs95.sennard.server.config.SennardProperties;
import io.github.jaeseongs95.sennard.server.contract.ApiResult;
import io.github.jaeseongs95.sennard.server.contract.SennardToolResults;
import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.server.mcp.McpSessionResolver;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.mcp.SessionExecutionReader;
import io.github.jaeseongs95.sennard.server.mcp.SkillIntake;
import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.RunState;
import io.github.jaeseongs95.sennard.service.store.modules.SkillUsageService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thirteen workflow tools. Each method only resolves the calling session and hands the arguments to {@link WorkflowService}; the published schemas
 * come from {@link WorkflowToolSchemas}, the descriptions here are replaced by the catalog text.
 */
@Component
public class WorkflowTools {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowTools.class);
    private static final Set<WorkflowCode> START_INFRASTRUCTURE_FAILURES = EnumSet.of(WorkflowCode.MCP_UNAVAILABLE, WorkflowCode.CONTINUITY_UNAVAILABLE, WorkflowCode.BINDING_REQUIRED, WorkflowCode.BINDING_INVALID,
            WorkflowCode.INTEGRITY_FAILED);
    private static final Set<String> WORKFLOW_ENTRY_TOOLS = Set.of("resume_workflow", "start_guarded_workflow");
    private static final Set<String> CHANGE_TOOLS = Set.of("plan_workflow", "start_workflow", "start_guarded_workflow", "record_stage_result", "claim_workflow_attempt", "resolve_convergence_gate",
            "open_convergence_root", "release_convergence_root", "finalize_workflow", "abort_workflow", "pause_workflow", "resume_workflow", "get_workflow_status", "get_convergence_status");

    /** The tools after which the change journal's comparison is reported: a stage result and the finish. */
    private static final Set<String> JOURNAL_TOOLS = Set.of("record_stage_result", "finalize_workflow");
    private static final Set<String> ATTESTED_TOOLS =Set.of("plan_workflow", "start_workflow", "record_stage_result");

    private final KindStartInputs kinds;
    private final WorkflowService service;
    private final SkillUsageService skillUsage;
    private final Map<String, SessionExecutionReader> executionReaders;
    private final McpSessionResolver sessions;
    private final ClassificationAdvice classification;
    private final Messages messages;
    private final JsonMapper mapper;
    private final DocumentDigests digests;
    private final ToolInputs inputs;
    private final AdminPageAddress address;
    private final ChangePlanService plans;
    private final SkillRegistry registry;
    private final WorkflowFileReader reader;
    private final WorkflowReceipts operations;
    private final SennardToolResults results;
    private final String skillsDirectory;
    private final List<HostWorkFacts> hostFacts;

    public WorkflowTools(WorkflowService service, SkillRegistry registry, WorkflowFileReader reader, SkillUsageService skillUsage, List<SessionExecutionReader> executionReaders, McpSessionResolver sessions, Messages messages, JsonMapper mapper,
            AdminPageAddress address, ClassificationAdvice classification, DocumentDigests digests, ToolInputs inputs, ChangePlanService plans, WorkflowReceipts operations, KindStartInputs kinds,
            SennardToolResults results, SennardProperties properties, List<HostWorkFacts> hostFacts) {
        this.results = results;
        this.skillsDirectory = properties.skillsDirectory();
        this.hostFacts = List.copyOf(hostFacts);
        this.kinds = kinds;
        this.operations = operations;
        this.reader = reader;
        this.registry = registry;
        this.plans = plans;
        this.digests = digests;
        this.inputs = inputs;
        this.classification = classification;
        this.service = service;
        this.skillUsage = skillUsage;
        this.executionReaders = executionReaders.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(SessionExecutionReader::host, Function.identity()));
        this.sessions = sessions;
        this.messages = messages;
        this.mapper = mapper;
        this.address = address;
    }

    // Every tool here touches only this server's own store, so none is open-world. A tool that ends something for good (abort_workflow, release_convergence_root) is destructive;
    // the others add or advance a record. None is idempotent: a repeat is a conflict on the revision or another record, not the same answer.

    @McpTool(name = "plan_workflow", description = "Plans a workflow.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult planWorkflow(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return runWrite("plan_workflow", request, meta, context, null, null, WorkflowPlan.class, UnaryOperator.identity(), (args, call, operation) -> service.planWorkflow(args, call, registry.read(), operation));
    }

    @McpTool(name = "open_convergence_root", description = "Opens a convergence root.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult openConvergenceRoot(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return runWrite("open_convergence_root", request, meta, context, "responseMode", ConvergenceRootHandle::of, ConvergenceRoot.class, UnaryOperator.identity(), (args, call, operation) -> {
            // The locations of the roots are resolved here, before the transaction of the use case, which reads no file.
            FileReads reads = reader.read(service.planOpenRootReads(args));
            return rootResult(RootAction.REPLACE_ROOT, service.openConvergenceRoot(args, call, reads, operation));
        });
    }

    @McpTool(name = "claim_workflow_attempt", description = "Claims a workflow attempt.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult claimWorkflowAttempt(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return runWrite("claim_workflow_attempt", request, meta, context, null, null, AttemptLease.class, UnaryOperator.identity(),
                (args, call, operation) -> switch (service.claimWorkflowAttempt(args, call, registry.read(), operation)) {
                    case WorkflowService.ClaimOutcome.Leased leased -> leased.lease();
                    // The transaction has committed what the refusal recorded; the failure is answered now.
                    case WorkflowService.ClaimOutcome.Refused refused -> throw refused.failure();
                });
    }

    @McpTool(name = "start_guarded_workflow", description = "Starts a guarded workflow.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult startGuardedWorkflow(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return runWrite("start_guarded_workflow", request, meta, context, "responseMode", this::workflowSummary, WorkflowReceipt.class, UnaryOperator.identity(),
                (args, call, operation) -> service.startGuardedWorkflow(args, call, registry.installedSkillIds(registry.read()), operation));
    }

    @McpTool(name = "get_convergence_status", description = "Reads a convergence root.", annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult getConvergenceStatus(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return run("get_convergence_status", request, meta, context, "detail", ConvergenceStatusSummary::of,
                (args, call) -> service.getConvergenceStatus(inputs.read("get_convergence_status", args, RunRequest.RootLookup.class).id()));
    }

    @McpTool(name = "resolve_convergence_gate", description = "Resolves a convergence gate.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult resolveConvergenceGate(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return runWrite("resolve_convergence_gate", request, meta, context, "responseMode", ConvergenceStatusSummary::of, ConvergenceStatus.class, UnaryOperator.identity(),
                (args, call, operation) -> service.resolveConvergenceGate(args, call, operation));
    }

    /**
     * The tool that opens a workflow of the kind whose tool the call names ({@code request.name()}): one method serves every kind, because what differs between kinds is what the kind's
     * properties say ({@link WorkflowKind}). It is registered under its own name here, as the change's tool; {@link WorkflowToolConfiguration} registers it again under the name of every
     * other kind, with the kind's published schema.
     */
    @McpTool(name = "start_workflow", description = "Starts a minimal workflow.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult startWorkflow(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        String tool = request.name();
        WorkflowKind kind = WorkflowKind.ofTool(tool).orElse(WorkflowKind.CHANGE);
        // A start that comes again with its key is answered with the first answer (the start with its change journal and the notice).
        return runWrite(tool, request, meta, context, null, null, MinimalStartResult.class, UnaryOperator.identity(), (arguments, call, operation) -> {
            // What JEV advised for this request, asked for before anything of the start is read: it is waited for outside every transaction, and it is given back with the start or the refusal.
            Optional<ClassificationAdvice.StartAdvice> advised = kind.jevPreStage() ? classification.adviceFor(call.host(), call.sessionId(), arguments.path("goal").asString("")) : Optional.empty();
            JsonNode args = kinds.startInput(kind, arguments);
            try {
                // The registry, the root's locations and the baseline's walk are read first: no file is read inside the transaction, which writes the baseline's rows with the run, so a start that fails leaves nothing.
                List<SkillProvider> providers = registry.read();
                // The skills a start may name: the registry's and every other installed skill (the folder is read here too, outside the transaction).
                Set<String> installed = registry.installedSkillIds(providers);
                FileReads reads = reader.read(service.planStartReads(args, call));
                WorkflowService.StartBaseline baseline = plans.startBaseline(args, call);
                return advised.isPresent() ? service.startMinimalWorkflow(args, call, providers, installed, reads, baseline, advised.get(), operation)
                        : service.startMinimalWorkflow(args, call, providers, installed, reads, baseline, operation);
            } catch (WorkflowFailure failure) {
                if (advised.isPresent() && kinds.isSkillsRequired(failure)) {
                    // The refusal carries the advice, so the next call can fill the skills from it.
                    throw kinds.skillsRequiredWithAdvice(failure, advised.get());
                }
                // A Sennard infrastructure failure must not leave the session's edits refused: the start rolled back, the failure is noted on its own.
                if (START_INFRASTRUCTURE_FAILURES.contains(failure.code())) {
                    service.recordStartFailure(call, failure.code());
                }
                throw failure;
            }
        });
    }

    @McpTool(name = "resume_workflow", description = "Resumes an open workflow.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult resumeWorkflow(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        if (request.arguments() != null && (request.arguments().containsKey("expectedRevision") || request.arguments().containsKey(WorkflowReceipts.FIELD))) {
            return runWrite("resume_workflow", request, meta, context, null, null, WorkflowService.ResumeResult.class, UnaryOperator.identity(), (args, call, operation) -> {
                RunRequest.Resume resume = inputs.read("resume_workflow", args, RunRequest.Resume.class);
                return service.resumeWorkflow(resume.id(), resume.expectedRevision(), call, operation);
            });
        }
        return run("resume_workflow", request, meta, context, null, null, (args, call) -> service.resumeWorkflow(inputs.read("resume_workflow", args, RunRequest.Lookup.class).id(), call));
    }

    @McpTool(name = "pause_workflow", description = "Pauses a workflow until an explicit resume.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult pauseWorkflow(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return runWrite("pause_workflow", request, meta, context, "responseMode", this::workflowSummary, WorkflowReceipt.class, UnaryOperator.identity(), (args, call, operation) -> {
            RunRequest.Pause pause = inputs.read("pause_workflow", args, RunRequest.Pause.class);
            return service.pauseWorkflow(pause.id(), pause.expectedRevision(), pause.reason(), pause.resumeCondition(), call, operation);
        });
    }

    @McpTool(name = "submit_change_plan", description = "Submits the change plan of a run.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult submitChangePlan(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return runKeyed("submit_change_plan", request, meta, context, (args, call, operation) -> plans.submit(args, operation));
    }

    @McpTool(name = "record_stage_result", description = "Records a stage result.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult recordStageResult(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return runWrite("record_stage_result", request, meta, context, "responseMode", this::workflowSummary, WorkflowReceipt.class, UnaryOperator.identity(), (args, call, operation) -> {
            // The files the result names are read here, before the transaction of the use case, which reads no file.
            FileReads reads = reader.read(service.planStageReads(args));
            return service.recordStageResult(args, call, reads, operation);
        });
    }

    @McpTool(name = "get_workflow_status", description = "Reads a workflow run.", annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult getWorkflowStatus(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return run("get_workflow_status", request, meta, context, "detail", this::workflowSummary,
                (args, call) -> service.getWorkflowStatus(inputs.read("get_workflow_status", args, RunRequest.Lookup.class).id()));
    }

    @McpTool(name = "list_session_workflows", description = "Lists the calling session's retained runs and open roots.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public CallToolResult listSessionWorkflows(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return run("list_session_workflows", request, meta, context, null, null, (args, call) -> {
            inputs.read("list_session_workflows", args, RunRequest.SessionList.class);
            return service.listSessionWorkflows(call);
        });
    }

    @McpTool(name = "finalize_workflow", description = "Finalizes a workflow run.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = false, openWorldHint = false))
    public CallToolResult finalizeWorkflow(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return runWrite("finalize_workflow", request, meta, context, "responseMode", this::workflowSummary, WorkflowReceipt.class, UnaryOperator.identity(), (args, call, operation) -> {
            RunRequest.Change change = inputs.read("finalize_workflow", args, RunRequest.Change.class);
            // The files the finish judges again are read here, before the transaction of the use case, which reads no file.
            FileReads reads = reader.read(service.planFinalizeReads(change.id(), change.expectedRevision()));
            // The journal is compared here too, outside the transaction (a scan reads files): the finish refuses a change outside the plan, by the same judgment that the notice after a stage result shows.
            List<String> unplanned = plans.observe(change.id()).map(ChangePlanService.Observation::unplanned).orElse(List.of());
            return service.finalizeWorkflow(change.id(), change.expectedRevision(), observedSkillCalls(call.sessionId()), reads, unplanned, call, operation);
        });
    }

    /** The skills the hooks saw this session call; null when that record cannot be read, which the receipt says rather than blocking the finish. */
    private Set<String> observedSkillCalls(String sessionId) {
        try {
            return Set.copyOf(skillUsage.calledInSession(sessionId));
        } catch (DataAccessException unreadable) {
            LOG.warn("The skills called in session {} could not be read", sessionId, unreadable);
            return null;
        }
    }

    @McpTool(name = "abort_workflow", description = "Aborts a workflow run.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = true, openWorldHint = false))
    public CallToolResult abortWorkflow(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return runWrite("abort_workflow", request, meta, context, "responseMode", this::workflowSummary, WorkflowReceipt.class, UnaryOperator.identity(), (args, call, operation) -> {
            RunRequest.Change change = inputs.read("abort_workflow", args, RunRequest.Change.class);
            return service.abortWorkflow(change.id(), change.expectedRevision(), call, operation);
        });
    }

    @McpTool(name = "release_convergence_root", description = "Releases a convergence root.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = true, openWorldHint = false))
    public CallToolResult releaseConvergenceRoot(CallToolRequest request, McpMeta meta, McpTransportContext context) {
        return runWrite("release_convergence_root", request, meta, context, null, null, ReleaseResult.class, UnaryOperator.identity(),
                (args, call, operation) -> rootResult(RootAction.RELEASE_ROOT, service.releaseConvergenceRoot(args, call, operation)));
    }

    /** No decision yet: the request is stored for the admin page and the caller is told where the user decides, then repeats the same call. */
    private <T> T rootResult(RootAction action, WorkflowService.RootOutcome<T> outcome) {
        return switch (outcome) {
            case WorkflowService.RootOutcome.Done<T> done -> done.result();
            // The transaction has committed what the refusal observed; the failure is answered now.
            case WorkflowService.RootOutcome.Refused<T> refused -> throw refused.failure();
            case WorkflowService.RootOutcome.ApprovalRequired<T> required -> {
                String rootId = required.pending().scope().rootId();
                Map<String, Object> failure = details("reason", "USER_APPROVAL_REQUIRED", "field", "userApprovalRefs", "rootId", rootId, "adminPage", address.origin(), "scopeDigest", required.scopeDigest());
                throw new WorkflowFailure(action == RootAction.REPLACE_ROOT ? WorkflowCode.BINDING_INVALID : WorkflowCode.INVALID_INPUT, msg("workflow.service.rootApprovalAdminPageRequired", "rootId", rootId, "adminPage", address.origin()), failure);
            }
        };
    }

    /**
     * What a tool does with its arguments: the record it answers with. The arguments are a tree, because the contract schema of a tool judges one; the tools
     * whose input is a record of its own bind it from the tree ({@link ToolInputs}).
     */
    @FunctionalInterface
    private interface Body<R> {
        R apply(JsonNode args, WorkflowCall call);
    }

    /** What a write tool does with its arguments, and with the operation of its call (the key and the digest of the content). */
    @FunctionalInterface
    private interface WriteBody<R> {
        R apply(JsonNode args, WorkflowCall call, OperationKey operation);
    }

    private <R> CallToolResult run(String tool, CallToolRequest request, McpMeta meta, McpTransportContext context, String modeField, Function<R, ?> compact, Body<R> body) {
        return execute(tool, request, meta, context, modeField, compact, false, null, null, (args, call, operation) -> body.apply(args, call));
    }

    /**
     * A write tool: its operationKey is required and is not an argument of the contract the use case judges, so it is taken out of the arguments here. The use case that does the work
     * records its result as the receipt, in its own transaction; the call then makes its answer ({@code finish} completes the result with what is done after the use case commits, and the
     * change notice follows) and keeps it in the receipt ({@link WorkflowReceipts#completeAnswer}). A request whose key has already succeeded for the same content is answered with that
     * answer, as it was given, before any file is planned for reading, and does nothing else ({@link WorkflowReceipts}).
     */
    private <R> CallToolResult runWrite(String tool, CallToolRequest request, McpMeta meta, McpTransportContext context, String modeField, Function<R, ?> compact, Class<R> type,
            UnaryOperator<R> finish, WriteBody<R> body) {
        return execute(tool, request, meta, context, modeField, compact, true, type, finish, body);
    }

    /**
     * A write tool whose receipt is not its result: the key is required and taken out of the arguments, but the replay is the use case's own (it stores a part of what the tool
     * answers, in the transaction that writes it), so nothing is answered before the use case runs.
     */
    private <R> CallToolResult runKeyed(String tool, CallToolRequest request, McpMeta meta, McpTransportContext context, WriteBody<R> body) {
        return execute(tool, request, meta, context, null, null, true, null, null, body);
    }

    private <R> CallToolResult execute(String tool, CallToolRequest request, McpMeta meta, McpTransportContext context, String modeField, Function<R, ?> compact, boolean keyed, Class<R> type,
            UnaryOperator<R> finish, WriteBody<R> body) {
        ApiResult shown;
        String locale = Messages.MODEL_LOCALE;
        try {
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            boolean compactMode = false;
            Map<String, Object> inputArguments = arguments;
            if (modeField != null && arguments.containsKey(modeField)) {
                ResponseMode mode = ResponseMode.of(arguments.get(modeField));
                if (mode == null) {
                    throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("server.call.responseModeInvalid", "field", modeField));
                }
                compactMode = mode == ResponseMode.COMPACT;
                inputArguments = new LinkedHashMap<>(arguments);
                inputArguments.remove(modeField);
            }
            Object sentKey = null;
            if (keyed) {
                inputArguments = new LinkedHashMap<>(inputArguments);
                sentKey = inputArguments.remove(WorkflowReceipts.FIELD);
            }
            // The one place the arguments the MCP layer hands over (a map) become the tree the tools' schemas judge.
            JsonNode args = mapper.valueToTree(inputArguments);
            SelfSession session = sessions.bind(tool, arguments, meta, context, null).require();
            WorkflowCall call = WorkflowCall.of(session, locale);
            if (ATTESTED_TOOLS.contains(tool) || WorkflowKind.ofTool(tool).isPresent()) {
                call = withObservedExecution(call, session);
            }
            OperationKey operation = keyed ? operations.of(tool, session.sessionRef(), sentKey, args) : null;
            Optional<WorkflowReceipts.Stored<R>> stored = type == null ? Optional.empty() : operations.replayStored(operation, type);
            // A request that comes again after the first one finished is answered with that answer as it was given: its result and the lines shown after it. It reads nothing of the run as it is now
            // and writes nothing (the notes of a stage result or a finish scan the files and record the scan).
            if (stored.isPresent() && stored.get() instanceof WorkflowReceipts.Stored.Answered<R> answered) {
                return results.answer(ApiResult.ok(published(compactMode, compact, answered.result())), answered.notes());
            }
            R data = stored.isPresent() ? finish.apply(stored.get().result()) : body.apply(args, call, operation);
            List<String> notes = CHANGE_TOOLS.contains(tool) || WorkflowKind.ofTool(tool).isPresent() ? changeNotice(tool, data, call) : List.of();
            if (type != null) {
                // The answer is kept after the use case's transaction, as the change plan's is: the notes read the files, and a file read stays outside every transaction. A call that stops before this
                // leaves the use case's result in the receipt, and the request that comes again makes the answer from it (the one case in which a request that comes again makes notes).
                operations.completeAnswer(operation, data, notes);
            }
            return results.answer(ApiResult.ok(published(compactMode, compact, data)), notes);
        } catch (WorkflowFailure failure) {
            shown = ApiResult.failed(WorkflowResults.errorBody(messages, failure));
        } catch (BindingException failure) {
            shown = ApiResult.failed(WorkflowResults.errorBody(messages, bindingFailure(tool, failure)));
        } catch (ConflictException conflict) {
            // The conflicts of the operationKey (the content differs; or the same request raced and met the first one's receipt, which is retryable) are the common REQUEST_CONFLICT whose
            // reason and field the exception carries, the same details every write tool shows. Another conflict is a server failure here, as any other.
            WorkflowFailure shownFailure = conflict.isOperationKey()
                    ? new WorkflowFailure(WorkflowCode.REQUEST_CONFLICT, msg(conflict.messageKey()), conflict.details(), conflict)
                    : serverFailure(tool, conflict);
            shown = ApiResult.failed(WorkflowResults.errorBody(messages, shownFailure));
        } catch (RuntimeException failure) {
            shown = ApiResult.failed(WorkflowResults.errorBody(messages, serverFailure(tool, failure)));
        }
        return results.answer(shown);
    }

    /** What the caller is shown of a result: the result itself, or its compact form when the caller asked for it. */
    private static <R> Object published(boolean compactMode, Function<R, ?> compact, R data) {
        return compactMode && compact != null ? compact.apply(data) : data;
    }

    /**
     * Boundary (MCP tool entrance): a non-contract failure is a server failure, never the caller's invalid input: fixed message and a cause without the original text. The reason is the
     * store's own code when the failure is a {@link StoreException} (as {@code SennardToolResults#unexpected} does), so a first-use race that ended in {@code STORE_UNIQUE_VIOLATION} says so.
     */
    private static WorkflowFailure serverFailure(String tool, RuntimeException failure) {
        LOG.warn("Workflow tool {} failed", tool, failure);
        String reason = failure instanceof StoreException store ? store.code().name() : "INTERNAL_FAILURE";
        return new WorkflowFailure(WorkflowCode.MCP_UNAVAILABLE, msg("services.failure.serverUnavailable"), details("reason", reason, "cause", failure.getClass().getSimpleName()));
    }

    /** The hook may not have reported the model; the host's adapter reads it from its own records, before any transaction opens. */
    private WorkflowCall withObservedExecution(WorkflowCall call, SelfSession session) {
        SessionExecutionReader reader = executionReaders.get(session.host());
        if (reader == null || !(session.execution() instanceof SelfSession.Execution.Unobserved)) {
            return call;
        }
        return call.withExecution(reader.read(session));
    }

    /**
     * The session binding refuses a call with {@code BINDING_REQUIRED} (no session could be told) or {@code BINDING_INVALID} (the host's metadata or the row left for the call is wrong),
     * and a workflow tool publishes them under those names; any other binding refusal that comes up under a tool is a server failure here, as every other exception is.
     */
    private static WorkflowFailure bindingFailure(String tool, BindingException failure) {
        if (failure.code() == BindingCode.BINDING_REQUIRED) {
            return new WorkflowFailure(WorkflowCode.BINDING_REQUIRED, msg(failure.messageKey()), details("reason", failure.code().name()), failure);
        }
        if (failure.code() == BindingCode.BINDING_INVALID) {
            return new WorkflowFailure(WorkflowCode.BINDING_INVALID, msg(failure.messageKey()), details("reason", failure.code().name()), failure);
        }
        return serverFailure(tool, failure);
    }

    /** The cue after a workflow change: the stage to run next and the reminders that go with it. */
    private List<String> changeNotice(String tool, Object answer, WorkflowCall call) {
        List<String> notes = workflowChangeNotice(tool, answer, call);
        if (answer instanceof MinimalStartResult || WORKFLOW_ENTRY_TOOLS.contains(tool)) {
            List<String> entry = new ArrayList<>(notes);
            entry.addAll(entryIntake(call));
            return entry;
        }
        return notes;
    }

    private List<String> workflowChangeNotice(String tool, Object answer, WorkflowCall call) {
        String locale = call.locale();
        WorkflowReceipt run = answer instanceof WorkflowReceipt receipt ? receipt
                : answer instanceof MinimalStartResult started ? service.getWorkflowStatus(started.runId())
                : answer instanceof WorkflowService.ResumeResult resumed ? service.getWorkflowStatus(resumed.runId()) : null;
        List<String> lifecycle = lifecycleNotice(answer, run, locale);
        if (run != null && run.state() != RunState.RUNNING) {
            List<String> lines = new ArrayList<>(lifecycle);
            if (JOURNAL_TOOLS.contains(tool)) {
                lines.addAll(journalNotes(run.runId(), locale));
            }
            return lines;
        }
        if (answer instanceof WorkflowService.ResumeResult resumed && WorkflowKind.OPERATION.name().equals(resumed.kind())) {
            List<String> lines = new ArrayList<>(lifecycle);
            lines.add(messages.render(msg("server.workflowChange.verification"), locale));
            return lines;
        }
        if (Set.of("get_workflow_status", "get_convergence_status", "pause_workflow", "resume_workflow", "release_convergence_root").contains(tool)) {
            return lifecycle;
        }
        Optional<StageView> stage = Optional.empty();
        // A plan names its stage itself, and a run (a receipt, or the start that opened one) is read for the plan it holds; a root, a lease and a status name none.
        if (answer instanceof WorkflowPlan plan) {
            stage = stageNamed(plan);
        } else if (answer instanceof MinimalStartResult started) {
            stage = stageNamed(service.getWorkflowStatus(started.runId()).plan());
        } else if (answer instanceof WorkflowReceipt receipt) {
            stage = stageNamed(receipt.plan());
        }
        List<String> lines = new ArrayList<>();
        if (answer instanceof MinimalStartResult || Set.of("open_convergence_root", "start_guarded_workflow").contains(tool)) {
            WorkflowContinuity.Capacity capacity = answer instanceof MinimalStartResult started ? started.sessionCapacity() : service.sessionCapacity(call);
            lines.add(messages.render(msg("workflow.capacity.available", "openCount", capacity.openCount(), "limit", capacity.limit(), "remaining", capacity.remaining()), locale));
            lines.add(messages.render(msg("workflow.capacity.followUp"), locale));
        }
        lines.addAll(lifecycle);
        stage.ifPresent(named -> lines.add(messages.render(msg("server.workflowChange.stage", "stageId", named.stageId(), "capability", named.requiredCapability(), "provider", named.skillId()), locale)));
        // A run that starts in its planning phase refuses every edit inside its write targets: the start says so and what to call next, with the values to call it with.
        // Only a kind that owns files has a planning phase to say so for.
        if (answer instanceof MinimalStartResult started && started.changeJournal().phase() == WorkflowPhase.PLANNING && WorkflowKind.ofTool(tool).orElse(WorkflowKind.CHANGE).allowsFileEdits()) {
            lines.add(messages.render(msg("server.workflowChange.planning", "runId", started.runId(), "revision", String.valueOf(started.revision())), locale));
        }
        lines.add(messages.render(msg("server.workflowChange.skills"), locale));
        lines.add(messages.render(msg("hooks.checkpointCue.refresh"), locale));
        if (stage.isPresent() || WorkflowKind.OPERATION.toolName().equals(tool)) {
            lines.add(messages.render(msg("server.workflowChange.verification"), locale));
        }
        if (classification.adviceReady()) {
            lines.add(messages.render(msg("server.workflowChange.classification"), locale));
        }
        if (answer instanceof WorkflowReceipt receipt && JOURNAL_TOOLS.contains(tool)) {
            lines.addAll(journalNotes(receipt.runId(), locale));
        }
        return lines;
    }

    /** The installed intake and skill targets are guidance for a workflow the caller has entered. */
    private List<String> entryIntake(WorkflowCall call) {
        try {
            var intake = SkillIntake.deployedIntake(skillsDirectory);
            String targets = SkillIntake.installedSkillTargets(skillsDirectory, mapper);
            if (intake.isEmpty() || targets.isEmpty()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            hostFacts.stream().filter(facts -> facts.host().sessionHost().equals(call.host())).findFirst()
                    .flatMap(HostWorkFacts::skillInvocationMessage).ifPresent(key -> lines.add(messages.render(msg(key), call.locale())));
            lines.add(messages.render(msg("instructions.intake.pointer", "path", intake.get().toString()), call.locale()));
            lines.add(messages.render(msg("instructions.intake.skills", "targets", targets), call.locale()));
            return lines;
        } catch (IOException | RuntimeException unreadable) {
            LOG.warn("SKILL_INTAKE_UNREADABLE", unreadable);
            return List.of();
        }
    }

    /** State is accompanied by the concrete next call; a finished attempt and its root are separate decisions. */
    private List<String> lifecycleNotice(Object answer, WorkflowReceipt run, String locale) {
        if (answer instanceof ReleaseResult released) {
            return List.of(messages.render(msg("workflow.lifecycle.cancelled", "rootId", released.rootId()), locale));
        }
        Optional<ConvergenceRoot> selectedRoot = answer instanceof ConvergenceStatus status
                ? Optional.ofNullable(status.root())
                : Optional.ofNullable(run).flatMap(receipt -> service.workflowRoot(receipt.runId()));
        if (selectedRoot.isPresent()) {
            ConvergenceRoot root = selectedRoot.orElseThrow();
            if (root.state() == RootState.ABANDONED) {
                return List.of(messages.render(msg("workflow.lifecycle.cancelled", "rootId", root.rootId()), locale));
            }
            if (root.state() == RootState.NEEDS_REVIEW || root.state() == RootState.NEEDS_USER) {
                return List.of(messages.render(msg("workflow.lifecycle.rootGate", "rootId", root.rootId(), "rootState", root.state()), locale));
            }
        }
        if (run == null) {
            return List.of();
        }
        if (run.state() == RunState.PAUSED) {
            return List.of(messages.render(msg("workflow.lifecycle.paused", "runId", run.runId(), "revision", String.valueOf(run.revision()), "reason", run.pause().reason(), "resumeCondition", run.pause().resumeCondition()), locale));
        }
        if (run.state() == RunState.PASSED) {
            return List.of(messages.render(msg("workflow.lifecycle.completed", "runId", run.runId()), locale));
        }
        if (run.state().isTerminal() || run.state().isUserGate()) {
            return List.of(messages.render(msg("workflow.lifecycle.attemptEnded", "runId", run.runId(), "state", run.state(),
                    "rootId", selectedRoot.map(ConvergenceRoot::rootId).orElse(""),
                    "rootState", selectedRoot.map(value -> value.state().name()).orElse("")), locale));
        }
        boolean ready = !run.isOpen() && run.plan().stages().stream().allMatch(stage -> run.resultOf(stage.stageId()).map(result -> result.state() == RunState.PASSED).orElse(false));
        return ready ? List.of(messages.render(msg("workflow.lifecycle.finalize", "runId", run.runId(), "revision", String.valueOf(run.revision())), locale)) : List.of();
    }

    /**
     * What the run's change journal found since its last observation: the changes outside the plan, and the paths it could not read. A journal that cannot be read is said in
     * the result and logged with its cause; the stage result or the finish, which are already committed, are not failed by it. A finish with a change outside the plan is not committed:
     * {@code finalize_workflow} refuses it before ({@link WorkflowService#finalizeWorkflow}), by the same judgment ({@link ChangePlanService#observe}), so after a finish this notice has none to show.
     */
    private List<String> journalNotes(String runId, String locale) {
        try {
            Optional<ChangePlanService.Observation> seen = plans.observe(runId);
            if (seen.isEmpty()) {
                return List.of();
            }
            ChangePlanService.Observation observation = seen.get();
            List<String> lines = new ArrayList<>();
            List<String> unplanned = observation.unplanned();
            if (!unplanned.isEmpty()) {
                lines.add(messages.render(msg("server.changeJournal.unplanned", "count", unplanned.size(), "paths", String.join(", ", unplanned.subList(0, Math.min(ChangePlanService.UNPLANNED_SHOWN, unplanned.size())))), locale));
                if (unplanned.size() > ChangePlanService.UNPLANNED_SHOWN) {
                    lines.add(messages.render(msg("server.changeJournal.unplannedMore", "more", unplanned.size() - ChangePlanService.UNPLANNED_SHOWN), locale));
                }
            }
            if (!observation.unreadable().isEmpty()) {
                lines.add(messages.render(msg("server.changeJournal.unreadable", "paths", String.join(", ", observation.unreadable())), locale));
            }
            if (unplanned.isEmpty() && observation.unreadable().isEmpty()) {
                lines.add(messages.render(msg("server.changeJournal.clean", "changed", observation.plannedChanged(), "unchanged", observation.plannedUnchanged()), locale));
            }
            return lines;
        } catch (RuntimeException failure) {
            // Boundary (MCP tool entrance): the journal is read after the call committed, so its failure is shown with its cause class and logged, not raised.
            LOG.warn("The change journal of run {} could not be read", runId, failure);
            return List.of(messages.render(msg("server.changeJournal.unavailable", "cause", failure.getClass().getSimpleName()), locale));
        }
    }

    /** What a change notice shows of a stage. */
    private record StageView(String stageId, String requiredCapability, String skillId) {
    }

    /** The stage a plan is on: the current one, or the next one when none is current. */
    private static Optional<StageView> stageNamed(WorkflowPlan plan) {
        String stageId = plan.currentStageId() != null ? plan.currentStageId() : plan.nextStageId();
        return stageId == null ? Optional.empty() : plan.stage(stageId).map(stage -> new StageView(stage.stageId(), stage.requiredCapability(), stage.skillId()));
    }

    /** The compact answer for a receipt: its place, its counts, and the digest of the receipt it summarizes. */
    private WorkflowStatusSummary workflowSummary(WorkflowReceipt receipt) {
        return WorkflowStatusSummary.of(receipt, digests.of(receipt));
    }
}
