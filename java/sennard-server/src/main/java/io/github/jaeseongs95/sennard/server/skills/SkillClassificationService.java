// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.domain.board.BoardEntry;
import io.github.jaeseongs95.sennard.server.context.AdminLog;
import io.github.jaeseongs95.sennard.server.context.ContextLayerService;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.workflow.ClassificationAdvice;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.AdvisedSkill;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ApplicabilityCheck;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Attempt;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.BlockReason;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.BlockedItem;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.CallObservation;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ClassificationMode;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ClassifyInput;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Config;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ConfirmedContext;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ContextField;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ContextSource;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Decision;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Discovery;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.DispatchState;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.HostReceipt;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.IndependentSelection;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Intake;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Inventory;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.InventoryAnswer;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.InventoryDetail;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.InventoryDetails;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.InventoryList;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Issue;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.JevSettings;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ObservedInventory;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Profile;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ProviderKind;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.QualificationStatus;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.RateLimit;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ReasonCode;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Registry;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Request;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Response;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ResponseError;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ResponseStatus;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Result;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ResultView;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Runtime;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.SelectionInput;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.SelectionSource;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.SelectionStatus;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Skill;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Skipped;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Snapshot;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.SourceRef;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.StageStatus;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.TaskObservation;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Unresolved;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Usage;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Verdict;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Packages the prompt and context, obtains advice from JEV, and validates the agent's own selection against it. Classification artifacts live in this
 * server's memory, per session. The JEV call runs outside any transaction, synchronously on the tool's virtual thread. The agent makes the selection;
 * this service never creates or replaces it.
 *
 * <p>The operationKey of {@code classify_skills} and {@code record_skill_selection} (AGS3-D-0267) is kept here, in the server's memory, and not in the database receipt that the workflow tools use
 * ({@code OperationReceipts}): the classification is advice for the session's skill choice and is needed only for the life of the server, so a stored receipt would keep it longer than it is
 * of use. The same key with the same content is the same operation (it joins the one that is running or answers its result); the same key with other content is
 * {@code OPERATION_DIGEST_CONFLICT}.
 *
 * <p>The agent does not have to ask: when JEV can be asked, the hook of a user prompt starts a classification of that prompt itself ({@link #startAdvice}, on its own virtual thread, so the hook
 * does not wait for it) and the next hook that can carry context takes its short result once ({@link #takeAdvice}). It is the same {@link #run} the tool uses, with the same signed
 * permission to send, key and budget. The advice is kept per session for the latest prompt only; a {@code classify_skills} call of the agent for that same prompt adopts it
 * ({@link #reused}) and does not ask JEV, and so does not pay, a second time.
 */
@Service
public class SkillClassificationService implements ClassificationAdvice {
    private static final Logger LOG = LoggerFactory.getLogger(SkillClassificationService.class);
    /** The most skills an automatic advice names to the agent: the context it adds stays a few lines, whatever the inventory holds. */
    public static final int MAX_ADVISED = 8;
    /** The most skills one {@code get_skill_inventory} call may ask to read whole: the answer of six is about 25 KB, which a host takes in one piece. */
    public static final int MAX_DETAIL_SKILLS = 6;
    private static final int MAX_SKILL_ID_CHARS = 80;
    private static final int MAX_OPERATIONS = 256;
    private static final long MAX_FENCE_BYTES = 8L * 1024 * 1024;
    private static final String CRITERIA_REF = "skills/orchestrator/references/skill-classification.md";
    /**
     * Whether the agent may choose without the helper's advice after a call that ended with this reason: every failure of the provider's side, a communication error that did
     * not clear and one that retrying or another evaluator would not mend alike (no key, a refused key, another 4xx, a wrong answer). Only the failures that are not the
     * provider's ({@link JevFailureKind#TERMINAL}: not allowed to send, the budget, the task moved, cancelled) are not an advice failure the agent continues after.
     */
    private static boolean continuation(ReasonCode code) {
        return JevFailureKind.of(code) != JevFailureKind.TERMINAL;
    }

    /** Which of the caller's own faults a refused request is: the input it gave is wrong, or it is right but not for the state the operation is in now. Both are the caller's to fix. */
    public enum Category {
        INPUT("input"), REQUEST_STATE("request-state");

        private final String wire;

        Category(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** Why a request is refused, and which fault of the caller's it is; the server being unable is not a refusal of the request (it is {@code UnavailableException}). */
    public enum Rejection {
        INVALID_CONTEXT_SOURCE(Category.INPUT), CONTEXT_SOURCE_MISSING(Category.INPUT), OPERATION_DIGEST_CONFLICT(Category.REQUEST_STATE), STALE_CLASSIFICATION_OPERATION(Category.REQUEST_STATE),
        UNKNOWN_CLASSIFICATION_OPERATION(Category.REQUEST_STATE), CALLER_SELECTION_RECEIPT_REJECTED(Category.REQUEST_STATE), AGENT_SELECTION_MISSING(Category.REQUEST_STATE),
        REQUIRED_SKILL_SNAPSHOT_CHANGED(Category.REQUEST_STATE), SELECTION_ALREADY_RECORDED(Category.REQUEST_STATE),
        UNKNOWN_SKILL_ID(Category.INPUT, InputCode.INVENTORY_SKILL_IDS_REJECTED), TOO_MANY_SKILL_IDS(Category.INPUT, InputCode.INVENTORY_SKILL_IDS_REJECTED);

        private final Category category;
        private final InputCode code;

        Rejection(Category category) {
            this(category, InputCode.CLASSIFICATION_REQUEST_REJECTED);
        }

        Rejection(Category category, InputCode code) {
            this.category = category;
            this.code = code;
        }

        public Category category() {
            return category;
        }

        /** The error the tool answers with: a classification request refused, or the inventory's skill ids refused. */
        public InputCode code() {
            return code;
        }
    }

    /** A request the current state refuses; {@link SkillTools} answers it by its {@link Rejection}. */
    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Rejection reason;
        private final transient Map<String, Object> details;

        Rejected(Rejection reason) {
            this(reason, null);
        }

        Rejected(Rejection reason, Throwable cause) {
            this(reason, Map.of(), cause);
        }

        /** @param details what the caller needs to mend the request (the skill ids that are unknown, the limit), beside the reason */
        Rejected(Rejection reason, Map<String, Object> details, Throwable cause) {
            super(reason.name(), cause, false, false);
            this.reason = reason;
            this.details = details;
        }

        public Rejection reason() {
            return reason;
        }

        public Map<String, Object> details() {
            return details;
        }
    }

    /** What {@code classify_skills} answers. */
    public sealed interface ClassifyAnswer permits NeedsInput, Classified {
    }

    public record NeedsInput(SelectionStatus status, List<Issue> errors, Discovery discovery, Response response, List<String> agentSelectedSkillIds) implements ClassifyAnswer {
    }

    /**
     * What the tool answers when the evaluators were asked (or could not be): the result with the request as digests and a count (the skills the server added are not sent back), and
     * {@code advice}, each skill's judgment beside the evaluator's confidence in it, the needed ones first.
     */
    public record Classified(ResultView result, List<AdvisedSkill> advice, QualificationStatus adviceQualification, String classificationResponseRef, List<String> agentSelectedSkillIds,
            SelectionStatus selectionStatus, boolean adviceApplied, SelectionSource selectionSource, IndependentSelection independentSelection, Discovery discovery) implements ClassifyAnswer {
    }

    public record Selection(boolean valid, List<String> errors, List<String> neededSkillIds, List<String> runnableSkillIds, List<BlockedItem> blockedItems, List<String> agentSelectedSkillIds,
            SelectionSource selectionSource, IndependentSelection independentSelection, Decision decision, Discovery discovery, StageStatus admissionStatus, StageStatus readStatus,
            StageStatus appliedStatus, StageStatus verifiedStatus) {
        static Selection refused(List<String> errors) {
            return new Selection(false, errors, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /** The failure the agent may continue after; {@code profileId} is the evaluator that failed last. */
    private record HelperFailure(String profileId, ReasonCode errorCode, String helperResultDigest) {
    }

    private record Outcome(Result result, HelperFailure helperFailure) {
    }

    /** What the agent's accepted selection of one operation is, kept together so that a reader never sees half of it. */
    private record Chosen(Decision decision, SelectionSource source, IndependentSelection proof) {
    }

    /** One classification of a session: reserved before the provider is asked, so a concurrent identical call joins it and cannot replace it. */
    private static final class Operation {
        final String intakeDigest;
        final String runtimeDigest;
        final TaskObservation task;
        final List<String> explicit;
        final List<String> required;
        final String actorId;
        final BooleanSupplier sourcesCurrent;
        final FutureTask<Outcome> flight;
        volatile Chosen chosen;

        Operation(String intakeDigest, String runtimeDigest, TaskObservation task, List<String> explicit, List<String> required, String actorId, BooleanSupplier sourcesCurrent,
                FutureTask<Outcome> flight) {
            this.intakeDigest = intakeDigest;
            this.runtimeDigest = runtimeDigest;
            this.task = task;
            this.explicit = explicit;
            this.required = required;
            this.actorId = actorId;
            this.sourcesCurrent = sourcesCurrent;
            this.flight = flight;
        }
    }

    private static final class Operations {
        final Map<String, Operation> byId = new ConcurrentHashMap<>();
        final Map<String, String> requestToOperation = new ConcurrentHashMap<>();
        /** The calls that were answered before any classification ran (the installed skills could not be read): their key, with the skills the caller named as required. */
        final Map<String, List<String>> unclassified = new ConcurrentHashMap<>();
        /** The operationKey of the session's latest classify_skills call. */
        volatile String latestKey;
    }

    /** One skill an automatic advice names, with the evaluator's confidence in that judgment. */
    public record Advised(String skillId, double score) {
    }

    /**
     * What an automatic classification advised, kept without its request (which carries the whole inventory): the answer, the tries that made it, and what it is bound to, so that
     * {@code classify_skills} can adopt it only for the same inventory and the same signed JEV setting. The scores are the answer's own diagnostics, by skill.
     */
    private record AutoAdvice(Response response, List<Attempt> attempts, List<Skipped> skipped, Map<String, Double> scores, String inventoryDigest, String runtimeDigest) {
    }

    /** The classification the hook started for a session's latest prompt; present only when it advised (a failure is logged where it is met and leaves nothing to take). */
    private static final class AutoRun {
        final String promptDigest;
        final FutureTask<Optional<AutoAdvice>> flight;
        final AtomicBoolean taken = new AtomicBoolean();

        AutoRun(String promptDigest, FutureTask<Optional<AutoAdvice>> flight) {
            this.promptDigest = promptDigest;
            this.flight = flight;
        }
    }

    /** What asking one evaluator came to: the profile asked, every try of it (a retried call is several), and the last answer. */
    private record Dispatched(Profile profile, List<Attempt> attempts, Response response, ReasonCode helperFailureCode) {
    }

    private record Checked(List<String> errors, List<String> needed, List<String> runnable, List<BlockedItem> blocked) {
    }

    private final SkillInventoryReader reader;
    private final ContextLayerService context;
    private final JevProviders providers;
    private final JevRetry retry;
    private final JevReadiness readiness;
    private final JevBudget budget;
    private final BoardService board;
    private final JsonMapper mapper;
    private final Clock clock;
    private final Cache<String, Operations> sessions = Caffeine.newBuilder().maximumSize(4096).expireAfterAccess(Duration.ofHours(24)).build();
    /** The latest automatic classification of each session; one entry per session, replaced by the next prompt's. */
    private final Cache<String, AutoRun> autos = Caffeine.newBuilder().maximumSize(1024).expireAfterAccess(Duration.ofHours(1)).build();

    public SkillClassificationService(SkillInventoryReader reader, ContextLayerService context, JevProviders providers, JevRetry retry, JevReadiness readiness, JevBudget budget,
            BoardService board, JsonMapper mapper, Clock clock) {
        this.readiness = readiness;
        this.clock = clock;
        this.reader = reader;
        this.context = context;
        this.providers = providers;
        this.retry = retry;
        this.budget = budget;
        this.board = board;
        this.mapper = mapper;
    }

    private String digest(Object value) {
        return Checks.digest(mapper, value);
    }

    private static String sessionKey(SelfSession session, String sessionId) {
        return session.host() + "\u0000" + sessionId;
    }

    private static String actorId(SelfSession session, String sessionId) {
        String root = session.host() + ":" + sessionId;
        return session.actor().agentKey() == null ? root : root + ":" + session.actor().agentKey();
    }

    private CallObservation observation(SelfSession session, String sessionId) {
        return session.provenance() != SelfSession.Provenance.HOST_OBSERVED ? null
                : new CallObservation(actorId(session, sessionId), session.host(), session.callId() != null ? session.callId() : UUID.randomUUID().toString(), StoreValues.iso(clock.millis()));
    }

    private ObservedInventory observed() throws IOException {
        return reader.observed();
    }

    /**
     * The installed skills. Without {@code skillIds} every skill comes as a summary, so the answer stays the size of a list however many lines the skills apply by; with them, the named
     * skills come whole (at most {@link #MAX_DETAIL_SKILLS}), in the order named. The inventory the digest names is the same either way, and classification reads it by itself.
     *
     * @throws Rejected {@link Rejection#TOO_MANY_SKILL_IDS} or {@link Rejection#UNKNOWN_SKILL_ID}, which lists the ids that are not installed
     */
    public InventoryAnswer inventory(List<String> skillIds) throws IOException {
        ObservedInventory seen = observed();
        Inventory inventory = seen.inventory();
        if (skillIds == null || skillIds.isEmpty()) {
            return new InventoryList(InventoryDetail.SUMMARY, inventory.skills().stream().map(Skill::summary).toList(), inventory.inventoryDigest(), inventory.taxonomyRevision(),
                    inventory.issues(), seen.discovery());
        }
        List<String> named = skillIds.stream().distinct().toList();
        if (named.size() > MAX_DETAIL_SKILLS) {
            throw new Rejected(Rejection.TOO_MANY_SKILL_IDS, Map.of("limit", MAX_DETAIL_SKILLS, "given", named.size()), null);
        }
        Map<String, Skill> installed = new LinkedHashMap<>();
        inventory.skills().forEach(skill -> installed.put(skill.skillId(), skill));
        List<String> unknown = named.stream().filter(id -> !installed.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new Rejected(Rejection.UNKNOWN_SKILL_ID, Map.of("skillIds", unknown), null);
        }
        return new InventoryDetails(InventoryDetail.DETAIL, named.stream().map(id -> installed.get(id).detail()).toList(), inventory.inventoryDigest(), inventory.taxonomyRevision(),
                inventory.issues(), seen.discovery());
    }

    /** Each skill's judgment beside the confidence the evaluator gave it: the needed skills first, the most confident first within a judgment. */
    private static List<AdvisedSkill> advisedSkills(Result result) {
        Map<String, Double> scores = scoresOf(result.attempts());
        return result.response().judgments().stream().map(judgment -> new AdvisedSkill(judgment.skillId(), judgment.judgment(), scores.get(judgment.skillId())))
                .sorted(Comparator.comparing(AdvisedSkill::judgment).thenComparing(Comparator.comparing((AdvisedSkill skill) -> skill.confidence() == null ? 0.0 : skill.confidence()).reversed())
                        .thenComparing(AdvisedSkill::skillId))
                .toList();
    }

    /** The confidence by skill of the last try that scored (the answer's own diagnostics); the first score that names a skill is kept. */
    private static Map<String, Double> scoresOf(List<Attempt> attempts) {
        Map<String, Double> scores = new HashMap<>();
        attempts.stream().map(Attempt::diagnostics).filter(Objects::nonNull).reduce((first, last) -> last)
                .ifPresent(diagnostics -> diagnostics.scores().forEach(score -> scores.putIfAbsent(score.skillId(), score.value())));
        return scores;
    }

    /** JEV as the user signed it on the admin page; with no signed record JEV is off. */
    private Runtime runtime() {
        AdminLog.JevState signed = context.verifiedState().jev;
        boolean preset = signed == null || signed.presets().getOrDefault("skill-classification", true);
        boolean enabled = signed != null && signed.enabled() && preset;
        // The response shows what the user signed: the permission to send and the provider of the chosen model ("unconfigured" while none is signed or chosen).
        boolean transmission = signed != null && signed.transmissionAllowed();
        String providerRef = signed == null || signed.model() == null ? "unconfigured" : signed.model().providerId();
        Config config = new Config(enabled, ClassificationMode.SELECT, providerRef, transmission, "unconfigured", JevProvider.CALL_TIMEOUT_MS);
        Registry registry = new Registry("1.0.0", "unconfigured", enabled ? evaluators(signed) : List.of());
        return new Runtime(config, registry, new JevSettings(enabled && transmission));
    }

    /**
     * The evaluators a signed setting names, in the order they are asked: the chosen model, then the fallback evaluator when the user chose one. A model its provider no longer
     * offers is no evaluator, and with no chosen model there is none to fall back from.
     */
    private List<Profile> evaluators(AdminLog.JevState signed) {
        return providers.profile(signed.model()).map(primary -> providers.profile(signed.fallbackModel()).filter(fallback -> !fallback.profileId().equals(primary.profileId()))
                .map(fallback -> List.of(primary, fallback)).orElse(List.of(primary))).orElse(List.of());
    }

    /** The current task as the host's own prompt marker shows it; a child caller's actor extends its root session's, and both read the root session's prompt. */
    private TaskObservation observeTask(Request request, CallObservation observation, SelfSession session, String sessionId) {
        if (observation == null) {
            return TaskObservation.NOT_OBSERVED;
        }
        return board.readEntry(session.host(), sessionId).filter(entry -> entry.lastPromptAt() != null)
                .map(entry -> new TaskObservation(request.confirmedContext().taskRevision(), request.requestDigest(), false, "host-prompt:" + observation.actorId() + ":" + Timestamps.format(entry.lastPromptAt())))
                .orElse(TaskObservation.NOT_OBSERVED);
    }

    private Request request(ClassifyInput input, Inventory inventory) {
        ConfirmedContext confirmed = input.confirmedContext();
        List<ContextSource> sources = input.contextSources();
        for (ContextSource source : sources) {
            if (ContextField.named(source.field()) == null) {
                throw new Rejected(Rejection.INVALID_CONTEXT_SOURCE);
            }
        }
        for (ContextField field : ContextField.values()) {
            if (confirmed.supplied(field) && sources.stream().noneMatch(source -> Objects.equals(field.wire(), source.field()))) {
                throw new Rejected(Rejection.CONTEXT_SOURCE_MISSING);
            }
        }
        String prompt = input.originalPrompt();
        Request unsigned = new Request("1.0.0", input.requestId(), input.operationKey(), prompt, Checks.hash(prompt), confirmed, sources, inventory.skills(), inventory.inventoryDigest(),
                inventory.taxonomyRevision(), CRITERIA_REF, null);
        return unsigned.signed(digest(unsigned));
    }

    private static Response unavailable(Request request, ReasonCode code, DispatchState dispatchState, ResponseStatus status) {
        return new Response("1.0.0", request.requestId(), request.operationKey(), request.requestDigest(), request.inventoryDigest(), status, List.of(), List.of(new Unresolved(null, code)),
                new ResponseError(code, false, dispatchState));
    }

    /** Pins every source the inventory read. A pin that no longer matches its digest means the skills changed under the classification. */
    private BooleanSupplier sourceFence(Request request) {
        Map<String, String> references = new LinkedHashMap<>();
        for (Skill skill : request.skills()) {
            for (SourceRef source : skill.sourceRefs()) {
                String prior = references.putIfAbsent(source.path(), source.digest());
                if (prior != null && !Objects.equals(prior, source.digest())) {
                    return () -> false;
                }
            }
        }
        if (references.size() > 1024) {
            return () -> false;
        }
        Map<Path, String> pins = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, String> reference : references.entrySet()) {
                pins.put(reader.root().resolve(reference.getKey()).toRealPath(), reference.getValue());
            }
        } catch (IOException | InvalidPathException unavailable) {
            LOG.warn("The sources of a classification cannot be pinned, so the classification counts as stale", unavailable);
            return () -> false;
        }
        return () -> {
            try {
                long total = 0;
                for (Map.Entry<Path, String> pin : pins.entrySet()) {
                    if (!Files.isRegularFile(pin.getKey())) {
                        return false;
                    }
                    byte[] bytes = Files.readAllBytes(pin.getKey());
                    total += bytes.length;
                    if (total > MAX_FENCE_BYTES || !Objects.equals(Checks.hash(bytes), pin.getValue())) {
                        return false;
                    }
                }
                return true;
            } catch (IOException unavailable) {
                LOG.warn("The sources of a classification cannot be read, so the classification counts as stale", unavailable);
                return false;
            }
        };
    }

    private static boolean taskMoved(TaskObservation task, TaskObservation current) {
        return task.observed() && (!current.observed() || current.cancelled() || !Objects.equals(current.sourceRef(), task.sourceRef()) || !Objects.equals(current.requestDigest(), task.requestDigest())
                || !Objects.equals(current.taskRevision(), task.taskRevision()));
    }

    /** The whole of one classification, given the staleness check to run around the provider. */
    private Outcome run(Request request, Runtime runtime, Snapshot snapshot, BooleanSupplier stale, Double limitUsd, Map<String, SecretValue> keys) {
        Config config = runtime.config();
        List<Attempt> attempts = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        JevSettings signed = config.jevEnabled() ? runtime.jev() : null;
        if (stale.getAsBoolean()) {
            return new Outcome(failure(request, runtime, snapshot, ReasonCode.STALE_CLASSIFICATION, attempts, ResponseStatus.UNAVAILABLE, skipped), null);
        }
        List<Profile> evaluators = config.jevEnabled() ? runtime.registry().profiles() : List.of();
        if (evaluators.isEmpty()) {
            if (config.jevEnabled()) {
                skipped.add(new Skipped(ProviderKind.JEV, null, ReasonCode.PROFILE_UNAVAILABLE, DispatchState.NOT_STARTED));
            }
            return new Outcome(failure(request, runtime, snapshot, ReasonCode.PROFILE_UNAVAILABLE, attempts, ResponseStatus.UNAVAILABLE, skipped), null);
        }
        Instant started = retry.start();
        Dispatched dispatched = null;
        for (Profile profile : evaluators) {
            JevProvider provider = providers.of(profile).orElseThrow(() -> new IllegalStateException("No JEV provider for the profile " + profile.profileId()));
            if (provider.projectionBytes(request) > profile.maximumInputBytes()) {
                // This evaluator cannot take the request; the next one may.
                skipped.add(new Skipped(ProviderKind.JEV, profile.profileId(), ReasonCode.INPUT_TOO_LONG, DispatchState.NOT_STARTED));
                continue;
            }
            dispatched = dispatch(request, profile, provider, signed, stale, limitUsd, keys.get(profile.vendorId()), started, skipped);
            attempts.addAll(dispatched.attempts);
            if (!replaceable(dispatched.response)) {
                break;
            }
        }
        if (dispatched == null) {
            return new Outcome(failure(request, runtime, snapshot, ReasonCode.INPUT_TOO_LONG, attempts, ResponseStatus.UNAVAILABLE, skipped), null);
        }
        Result result = new Result(request, dispatched.response, config, runtime.registry().profileRevision(), attempts, skipped, snapshot);
        if (stale.getAsBoolean()) {
            return new Outcome(failure(request, runtime, snapshot, ReasonCode.STALE_CLASSIFICATION, attempts, ResponseStatus.UNAVAILABLE, skipped), null);
        }
        if (result.response().status().advised() && result.response().error() == null) {
            return new Outcome(result, null);
        }
        HelperFailure helper = dispatched.helperFailureCode == null ? null : new HelperFailure(dispatched.profile.profileId(), dispatched.helperFailureCode, digest(result));
        return new Outcome(result, helper);
    }

    /** Whether the next evaluator (when there is one) is asked after this answer: only when it failed for a reason that asking the same target again would not mend. */
    private static boolean replaceable(Response response) {
        return response.error() != null && JevFailureKind.of(response.error().code()) == JevFailureKind.NON_COMMUNICATION;
    }

    private static Result failure(Request request, Runtime runtime, Snapshot snapshot, ReasonCode code, List<Attempt> attempts, ResponseStatus status, List<Skipped> skipped) {
        return new Result(request, unavailable(request, code, DispatchState.NOT_STARTED, status), runtime.config(), runtime.registry().profileRevision(), attempts, skipped, snapshot);
    }

    /** Why a call may not be sent at all, null when nothing forbids it. */
    private static ReasonCode refusal(JevSettings signed, SecretValue apiKey, BooleanSupplier stale) {
        if (signed == null || !signed.transmissionAllowed()) {
            return ReasonCode.REMOTE_CONTENT_NOT_APPROVED;
        }
        if (apiKey == null) {
            return ReasonCode.CREDENTIAL_UNAVAILABLE;
        }
        return stale.getAsBoolean() ? ReasonCode.STALE_CLASSIFICATION : null;
    }

    /**
     * Asks one evaluator. A call that failed in communication is made again at the same target ({@link JevRetry}); every try reserves its own cost against the budget before
     * it is sent and settles it after, so a retried call is recorded like any other and the tries stop where the budget does. A failure that is not communication is not made
     * again: it is what the caller sees and decides on ({@link #replaceable}).
     */
    private Dispatched dispatch(Request request, Profile profile, JevProvider provider, JevSettings signed, BooleanSupplier stale, Double limitUsd, SecretValue apiKey, Instant started,
            List<Skipped> skipped) {
        ReasonCode refused = refusal(signed, apiKey, stale);
        if (refused != null) {
            Response response = unavailable(request, refused, DispatchState.NOT_STARTED, ResponseStatus.UNAVAILABLE);
            // No key is a failure of this evaluator the agent continues after; not being allowed to send, or a task that moved, is not.
            return new Dispatched(profile, List.of(attempt(profile, DispatchState.NOT_STARTED, response, false, Usage.unknown(), false, null, null)), response,
                    continuation(refused) ? refused : null);
        }
        Tries tries = new Tries(request, profile, provider, stale, limitUsd, apiKey, skipped);
        Reached reached;
        try {
            reached = retry.execute(started, tries);
        } catch (RetryException ended) {
            // The deadline passed, or the budget or the task stopped the tries: what the evaluator last said is the answer.
            reached = switch (ended.getLastException()) {
                case Failed failed -> failed.reached;
                case Stopped stopped -> stopped.reached;
                default -> throw new IllegalStateException("A JEV call ended in a way the retry does not know", ended);
            };
        }
        return new Dispatched(profile, tries.attempts, reached.response, reached.failureCode);
    }

    /** One call that failed in communication; the retry tries again. */
    private static final class Failed extends JevRetry.Communication {
        private static final long serialVersionUID = 1L;
        final transient Reached reached;

        Failed(Reached reached) {
            super(reached.retryAfter);
            this.reached = reached;
        }
    }

    /** The tries stop here: the budget cannot cover another one, or the task moved. */
    private static final class Stopped extends Exception {
        private static final long serialVersionUID = 1L;
        final transient Reached reached;

        Stopped(Reached reached) {
            super("the JEV tries stopped", null, false, false);
            this.reached = reached;
        }
    }

    /** The tries of one evaluator, in order, each as the retry runs it. */
    private final class Tries implements Retryable<Reached> {
        private final Request request;
        private final Profile profile;
        private final JevProvider provider;
        private final BooleanSupplier stale;
        private final Double limitUsd;
        private final SecretValue apiKey;
        private final List<Skipped> skipped;
        final List<Attempt> attempts = new ArrayList<>();
        private Reached last;

        Tries(Request request, Profile profile, JevProvider provider, BooleanSupplier stale, Double limitUsd, SecretValue apiKey, List<Skipped> skipped) {
            this.request = request;
            this.profile = profile;
            this.provider = provider;
            this.stale = stale;
            this.limitUsd = limitUsd;
            this.apiKey = apiKey;
            this.skipped = skipped;
        }

        @Override
        public Reached execute() throws Failed, Stopped {
            int number = attempts.size() + 1;
            String reservationId = request.operationKey() + "\u0000" + request.requestDigest() + "\u0000" + profile.profileId() + "\u0000" + number;
            if (number > 1 && stale.getAsBoolean()) {
                throw new Stopped(last);
            }
            if (!budget.reserve(profile, reservationId, limitUsd)) {
                if (number > 1) {
                    skipped.add(new Skipped(ProviderKind.JEV, profile.profileId(), ReasonCode.BUDGET_UNAVAILABLE, DispatchState.NOT_STARTED));
                    throw new Stopped(last);
                }
                Response response = unavailable(request, ReasonCode.BUDGET_UNAVAILABLE, DispatchState.NOT_STARTED, ResponseStatus.UNAVAILABLE);
                attempts.add(attempt(profile, DispatchState.NOT_STARTED, response, false, Usage.unknown(), false, null, null));
                return new Reached(DispatchState.NOT_STARTED, Usage.unknown(), null, response, false, null, null, null);
            }
            Reached reached;
            try {
                reached = switch (provider.classify(request, profile, apiKey)) {
                    case JevProvider.Evaluation evaluation -> answered(request, profile, evaluation);
                    case JevProvider.Failure failure -> failed(request, failure);
                };
                budget.settle(reservationId, reached.dispatchState, profile, reached.usage);
            } finally {
                // Whatever the call came to, even an exception a provider threw, the reservation covers only the time the call is in flight; it is released here (a second release is a no-op).
                budget.release(reservationId);
            }
            attempts.add(attempt(profile, reached.dispatchState, reached.response, true, reached.usage, reached.timedOut, reached.diagnostics, reached.rateLimit));
            last = reached;
            if (reached.response.error() != null && JevFailureKind.of(reached.response.error().code()) == JevFailureKind.COMMUNICATION) {
                throw new Failed(reached);
            }
            return reached;
        }
    }

    /** What a call that was sent came to, before it is settled against the budget. {@code retryAfter} is the provider's header as it was sent. */
    private record Reached(DispatchState dispatchState, Usage usage, SkillTypes.Diagnostics diagnostics, Response response, boolean timedOut, RateLimit rateLimit, ReasonCode failureCode,
            String retryAfter) {
    }

    private static Reached answered(Request request, Profile profile, JevProvider.Evaluation evaluation) {
        Usage usage = evaluation.usage();
        if (usage.outputTokens() != null && usage.outputTokens() > profile.maximumOutputTokens()) {
            return new Reached(evaluation.dispatchState(), usage, evaluation.diagnostics(), unavailable(request, ReasonCode.OUTPUT_TOO_LONG, evaluation.dispatchState(), ResponseStatus.INVALID),
                    false, null, ReasonCode.OUTPUT_TOO_LONG, null);
        }
        return new Reached(evaluation.dispatchState(), usage, evaluation.diagnostics(), evaluation.response(), false, null, null, null);
    }

    private static Reached failed(Request request, JevProvider.Failure failure) {
        boolean timedOut = failure.code() == ReasonCode.PROVIDER_TIMEOUT;
        ResponseStatus status = failure.invalid() ? ResponseStatus.INVALID
                : timedOut && failure.dispatchState() != DispatchState.NOT_STARTED ? ResponseStatus.UNCERTAIN : ResponseStatus.UNAVAILABLE;
        RateLimit rateLimit = failure.httpStatus() == 429 || failure.httpStatus() == 529 ? new RateLimit(failure.httpStatus(), failure.retryAfter()) : null;
        return new Reached(failure.dispatchState(), Usage.unknown(), null, unavailable(request, failure.code(), failure.dispatchState(), status), timedOut, rateLimit,
                continuation(failure.code()) ? failure.code() : null, failure.retryAfter());
    }

    private static Attempt attempt(Profile profile, DispatchState dispatchState, Response response, boolean reserved, Usage usage, boolean timedOut, SkillTypes.Diagnostics diagnostics,
            RateLimit rateLimit) {
        return new Attempt(profile.providerKind(), profile.profileId(), profile.modelId(), profile.reasoningEffort(), dispatchState, response.status(),
                response.error() == null ? null : response.error().code(), timedOut, usage, reserved ? profile.maximumCostUsd() : 0, diagnostics, rateLimit);
    }

    public ClassifyAnswer classify(ClassifyInput input, SelfSession session, String sessionId) throws IOException {
        CallObservation observation = observation(session, sessionId);
        Runtime runtime = runtime();
        ObservedInventory seen = observed();
        if (!seen.inventory().issues().isEmpty()) {
            // The call is a call all the same: a start that cites its key is told no skill was judged needed, not that the session never asked.
            Operations unanswered = sessions.get(sessionKey(session, sessionId), key -> new Operations());
            if (unanswered.unclassified.size() < MAX_OPERATIONS) {
                unanswered.unclassified.put(input.operationKey(), union(input.explicitSkillIds(), input.ruleRequiredSkillIds()));
                unanswered.latestKey = input.operationKey();
            }
            return new NeedsInput(SelectionStatus.NEEDS_INPUT, seen.inventory().issues(), seen.discovery(), null, null);
        }
        Request request = request(input, seen.inventory());
        TaskObservation task = observeTask(request, observation, session, sessionId);
        String actorId = observation == null ? null : observation.actorId();
        List<String> explicit = input.explicitSkillIds();
        List<String> required = input.ruleRequiredSkillIds();
        String intakeDigest = digest(new Intake(request.requestDigest(), explicit, required, input.vendorContext(), actorId));
        String runtimeDigest = digest(runtime);
        Operations operations = sessions.get(sessionKey(session, sessionId), key -> new Operations());
        Operation existing = operations.byId.get(request.operationKey());
        if (existing != null) {
            if (!Objects.equals(existing.intakeDigest, intakeDigest)) {
                throw new Rejected(Rejection.OPERATION_DIGEST_CONFLICT);
            }
            if (!Objects.equals(existing.runtimeDigest, runtimeDigest) || taskMoved(existing.task, task)) {
                throw new Rejected(Rejection.STALE_CLASSIFICATION_OPERATION);
            }
        } else if (operations.byId.size() >= MAX_OPERATIONS) {
            throw new UnavailableException(UnavailableCode.CLASSIFICATION_CAPACITY_EXCEEDED, Map.of(), Map.of("reason", UnavailableCode.CLASSIFICATION_CAPACITY_EXCEEDED.name()), null);
        }
        String prior = operations.requestToOperation.putIfAbsent(request.requestId(), request.operationKey());
        Snapshot snapshot = new Snapshot(request.confirmedContext().taskRevision(), runtime.config().configRevision(), runtime.registry().profileRevision(), request.inventoryDigest(),
                request.requestDigest(), task.cancelled());
        if (prior != null && !Objects.equals(prior, request.operationKey())) {
            return classified(new Outcome(failure(request, runtime, snapshot, ReasonCode.REQUEST_ID_CONFLICT, List.of(), ResponseStatus.INVALID, List.of()), null), runtime, null, seen);
        }
        BooleanSupplier sources = sourceFence(request);
        BooleanSupplier stale = () -> task.observed() && taskMoved(task, observeTask(request, observation, session, sessionId)) || !sources.getAsBoolean();
        Double limitUsd = limitUsd();
        Map<String, SecretValue> keys = keys(runtime);
        AutoRun auto = autos.getIfPresent(sessionKey(session, sessionId));
        Operation mine = new Operation(intakeDigest, runtimeDigest, task, explicit, required, actorId, sources,
                new FutureTask<>(() -> reused(auto, request, runtime, runtimeDigest, snapshot).orElseGet(() -> run(request, runtime, snapshot, stale, limitUsd, keys))));
        Operation winner = operations.byId.putIfAbsent(request.operationKey(), mine);
        operations.latestKey = request.operationKey();
        if (winner == null) {
            mine.flight.run();
            winner = mine;
        } else if (!Objects.equals(winner.intakeDigest, intakeDigest)) {
            throw new Rejected(Rejection.OPERATION_DIGEST_CONFLICT);
        }
        Outcome outcome;
        try {
            outcome = winner.flight.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UnavailableException(UnavailableCode.CLASSIFICATION_CANCELLED, Map.of(), Map.of("reason", UnavailableCode.CLASSIFICATION_CANCELLED.name()), interrupted);
        } catch (ExecutionException failed) {
            throw new UnavailableException(UnavailableCode.CLASSIFICATION_FAILED, Map.of(), Map.of("reason", UnavailableCode.CLASSIFICATION_FAILED.name()), failed.getCause());
        }
        // A finished classification stays usable only while what it was bound to has not moved.
        Result result = outcome.result();
        if (winner != mine && stale.getAsBoolean()) {
            result = failure(request, runtime, result.snapshot(), ReasonCode.STALE_CLASSIFICATION, result.attempts(), ResponseStatus.UNAVAILABLE, result.skipped());
        }
        return classified(new Outcome(result, outcome.helperFailure()), runtime, winner, seen);
    }

    private Classified classified(Outcome outcome, Runtime runtime, Operation operation, ObservedInventory seen) {
        Result result = outcome.result();
        boolean advised = result.response().error() == null && result.response().status().advised();
        if (advised && !result.attempts().isEmpty()) {
            String profileId = result.attempts().get(result.attempts().size() - 1).profileId();
            return runtime.registry().profiles().stream().filter(profile -> Objects.equals(profile.profileId(), profileId)).findFirst().map(profile -> profile.qualification().status())
                    .map(qualification -> classified(result, qualification, operation, seen)).orElseGet(() -> classified(result, null, operation, seen));
        }
        return classified(result, null, operation, seen);
    }

    /** @param qualification the qualification of the profile that answered, null when no profile answered or the answer is not advice */
    private Classified classified(Result result, QualificationStatus qualification, Operation operation, ObservedInventory seen) {
        Chosen chosen = operation == null ? null : operation.chosen;
        return new Classified(ResultView.of(result), advisedSkills(result), qualification, digest(result.response()), chosen == null ? null : chosen.decision().agentSelectedSkillIds(),
                chosen == null ? SelectionStatus.PROPOSED : chosen.decision().selectionStatus(), chosen != null && chosen.decision().adviceApplied(), chosen == null ? null : chosen.source(),
                chosen == null ? null : chosen.proof(), seen.discovery());
    }

    public Selection accept(SelectionInput input, SelfSession session, String sessionId) throws IOException {
        Operations operations = sessions.getIfPresent(sessionKey(session, sessionId));
        Operation operation = operations == null ? null : operations.byId.get(input.operationKey());
        if (operation == null || !operation.flight.isDone()) {
            throw new Rejected(Rejection.UNKNOWN_CLASSIFICATION_OPERATION);
        }
        Outcome outcome;
        try {
            outcome = operation.flight.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Rejected(Rejection.UNKNOWN_CLASSIFICATION_OPERATION, interrupted);
        } catch (ExecutionException failed) {
            throw new Rejected(Rejection.UNKNOWN_CLASSIFICATION_OPERATION, failed);
        }
        CallObservation observation = observation(session, sessionId);
        Decision decision = input.decision();
        if (observation == null) {
            return Selection.refused(List.of("HOST_SELECTION_NOT_OBSERVED"));
        }
        Result result = outcome.result();
        TaskObservation observedTask = observeTask(result.request(), observation, session, sessionId);
        if (!operation.task.observed() || taskMoved(operation.task, observedTask) || !Objects.equals(operation.actorId, observation.actorId())) {
            return Selection.refused(List.of("HOST_TASK_CHANGED_OR_NOT_OBSERVED"));
        }
        if (decision.hostReceipt() != null) {
            throw new Rejected(Rejection.CALLER_SELECTION_RECEIPT_REJECTED);
        }
        if (decision.agentSelectedSkillIds() == null) {
            throw new Rejected(Rejection.AGENT_SELECTION_MISSING);
        }
        if (!operation.explicit.equals(decision.explicitSkillIds()) || !operation.required.equals(decision.ruleRequiredSkillIds())) {
            throw new Rejected(Rejection.REQUIRED_SKILL_SNAPSHOT_CHANGED);
        }
        Runtime runtime = runtime();
        ObservedInventory seen = observed();
        Snapshot base = result.snapshot();
        Snapshot current = new Snapshot(base.taskRevision(), runtime.config().configRevision(), runtime.registry().profileRevision(), seen.inventory().inventoryDigest(), base.requestDigest(),
                base.cancelled());
        Decision bound = decision.withReceipt(new HostReceipt(observation.observationId(), observation.host(), decision.requestDigest(), seen.inventory().inventoryDigest(),
                decision.agentSelectedSkillIds(), observation.observedAt()));
        SelectionSource source = input.selectionSource() == null ? SelectionSource.HELPER_ADVICE : input.selectionSource();
        boolean independent = source == SelectionSource.INDEPENDENT_AGENT_AFTER_HELPER_FAILURE;
        Checked checked = check(result, bound, current, independent);
        Set<String> errors = new LinkedHashSet<>(checked.errors);
        if (independent && outcome.helperFailure() == null) {
            errors.add("HELPER_FAILURE_NOT_ELIGIBLE");
        }
        if (!Objects.equals(operation.runtimeDigest, digest(runtime))) {
            errors.add("RUNTIME_SOURCE_CHANGED");
        }
        if (!seen.inventory().issues().isEmpty()) {
            errors.add("CURRENT_INVENTORY_INVALID");
        }
        if (!errors.isEmpty()) {
            return new Selection(false, List.copyOf(errors), checked.needed, checked.runnable, checked.blocked, null, null, null, null, null, null, null, null, null);
        }
        synchronized (operation) {
            Chosen recorded = operation.chosen;
            if (recorded != null && (source != recorded.source() || !recorded.decision().withReceipt(null).equals(decision))) {
                throw new Rejected(Rejection.SELECTION_ALREADY_RECORDED);
            }
            TaskObservation finalTask = observeTask(result.request(), observation, session, sessionId);
            if (taskMoved(operation.task, finalTask)) {
                return Selection.refused(List.of("HOST_TASK_CHANGED_OR_NOT_OBSERVED"));
            }
            boolean successful = result.response().error() == null && result.response().status().advised();
            if (successful || independent) {
                String profileId = independent && outcome.helperFailure() != null ? outcome.helperFailure().profileId() : result.attempts().isEmpty() ? null : result.attempts().get(result.attempts().size() - 1).profileId();
                if (runtime.registry().profiles().stream().noneMatch(profile -> Objects.equals(profile.profileId(), profileId))) {
                    return Selection.refused(List.of("PROFILE_UNAVAILABLE"));
                }
            }
            if (!operation.sourcesCurrent.getAsBoolean()) {
                return Selection.refused(List.of("CLASSIFICATION_SOURCE_CHANGED"));
            }
            if (recorded == null) {
                recorded = new Chosen(bound, source, independent ? independentSelection(input, outcome, observation, finalTask, current, operation, decision) : null);
                operation.chosen = recorded;
            }
            return new Selection(true, List.of(), checked.needed, checked.runnable, checked.blocked, recorded.decision().agentSelectedSkillIds(), recorded.source(), recorded.proof(),
                    recorded.decision(), seen.discovery(), StageStatus.NOT_EVALUATED, StageStatus.NOT_OBSERVED, StageStatus.NOT_OBSERVED, StageStatus.NOT_RUN);
        }
    }

    private IndependentSelection independentSelection(SelectionInput input, Outcome outcome, CallObservation observation, TaskObservation finalTask, Snapshot current, Operation operation,
            Decision decision) {
        return new IndependentSelection(SelectionSource.INDEPENDENT_AGENT_AFTER_HELPER_FAILURE, input.operationKey(), observation.actorId(), finalTask.sourceRef(), observation.observationId(),
                current.requestDigest(), current.inventoryDigest(), operation.runtimeDigest, outcome.helperFailure().helperResultDigest(), digest(outcome.result().response()),
                digest(outcome.result().attempts()), outcome.helperFailure().errorCode(), digest(decision));
    }

    /** Reports omissions; never creates or replaces the agent's final selection set. */
    private Checked check(Result result, Decision decision, Snapshot current, boolean independent) {
        Set<String> errors = new LinkedHashSet<>();
        Set<String> needed = new LinkedHashSet<>();
        if (!independent) {
            result.response().judgments().stream().filter(item -> item.judgment() == Verdict.NEEDED).forEach(item -> needed.add(item.skillId()));
        }
        if (!Objects.equals(digest(result.response()), decision.classificationResponseRef())) {
            errors.add("CLASSIFICATION_RESPONSE_REF_MISMATCH");
        }
        SelectionStatus status = decision.selectionStatus();
        if (!independent && (result.response().error() != null || result.response().status().failed()) && status == SelectionStatus.SELECTED) {
            errors.add("CLASSIFICATION_UNAVAILABLE_CANNOT_COMPLETE_SELECTION");
        }
        List<String> explicit = decision.explicitSkillIds();
        List<String> required = decision.ruleRequiredSkillIds();
        needed.addAll(explicit);
        needed.addAll(required);
        Snapshot snap = result.snapshot();
        stale(errors, "STALE_taskRevision", snap.taskRevision(), current.taskRevision(), decision.taskRevision());
        stale(errors, "STALE_configRevision", snap.configRevision(), current.configRevision(), decision.configRevision());
        stale(errors, "STALE_profileRevision", snap.profileRevision(), current.profileRevision(), decision.profileRevision());
        stale(errors, "STALE_inventoryDigest", snap.inventoryDigest(), current.inventoryDigest(), decision.inventoryDigest());
        stale(errors, "STALE_requestDigest", snap.requestDigest(), current.requestDigest(), decision.requestDigest());
        if (current.cancelled()) {
            errors.add("TASK_CANCELLED");
        }
        Map<String, Skill> inventory = new LinkedHashMap<>();
        result.request().skills().forEach(skill -> inventory.put(skill.skillId(), skill));
        List<String> selected = decision.agentSelectedSkillIds();
        Set<String> selectedSet = new HashSet<>(selected);
        List<BlockedItem> blocked = new ArrayList<>();
        Set<String> examined = new LinkedHashSet<>(needed);
        examined.addAll(selected);
        for (String id : examined) {
            Skill skill = inventory.get(id);
            if (skill != null && (!skill.enabled() || !skill.installed() || !skill.hostSupported())) {
                blocked.add(new BlockedItem(id, !skill.enabled() ? BlockReason.DISABLED : !skill.installed() ? BlockReason.NOT_INSTALLED : BlockReason.HOST_UNSUPPORTED));
            }
        }
        if (status != SelectionStatus.SELECTED && status != SelectionStatus.PARTIAL) {
            errors.add("SELECTED_STATUS_MISMATCH");
        }
        if (selectedSet.size() != selected.size()) {
            errors.add("DUPLICATE_SELECTED_SKILL");
        }
        for (String id : needed) {
            if ((explicit.contains(id) || required.contains(id)) && !selectedSet.contains(id)) {
                errors.add("REQUIRED_SKILL_OMITTED:" + id);
            }
        }
        Map<String, List<ApplicabilityCheck>> checksBySkill = decision.applicabilityChecks().stream().collect(Collectors.groupingBy(ApplicabilityCheck::skillId));
        Set<String> reasoned = decision.selectionReasons().stream().map(SkillTypes.SelectionReason::skillId).collect(Collectors.toSet());
        for (String id : selected) {
            Skill skill = inventory.get(id);
            if (skill == null) {
                errors.add("UNKNOWN_SELECTED_SKILL:" + id);
                continue;
            }
            List<ApplicabilityCheck> checks = checksBySkill.getOrDefault(id, List.of());
            if (checks.size() != 1 || !Boolean.TRUE.equals(checks.get(0).applies()) || checks.get(0).excluded() == null || checks.get(0).excluded() || checks.get(0).reasonRefs() == null
                    || checks.get(0).reasonRefs().isEmpty()) {
                errors.add("APPLICABILITY_UNRESOLVED:" + id);
            }
            if (!reasoned.contains(id)) {
                errors.add("SELECTION_REASON_MISSING:" + id);
            }
            for (String dependency : skill.dependencies()) {
                if (!selectedSet.contains(dependency)) {
                    errors.add("DEPENDENCY_OMITTED:" + id + ":" + dependency);
                }
            }
        }
        boolean blockedSelected = blocked.stream().anyMatch(item -> selectedSet.contains(item.skillId()) || explicit.contains(item.skillId()) || required.contains(item.skillId()));
        if (status == SelectionStatus.SELECTED && (blockedSelected || !decision.unresolvedSkillReferences().isEmpty())) {
            errors.add("UNRESOLVED_SELECTION_MARKED_COMPLETE");
        }
        if (independent && decision.adviceApplied()) {
            errors.add("INDEPENDENT_SELECTION_CANNOT_APPLY_FAILED_ADVICE");
        }
        needed.stream().filter(id -> !inventory.containsKey(id)).forEach(id -> blocked.add(new BlockedItem(id, BlockReason.UNKNOWN_REQUIRED_SKILL)));
        Set<String> blockedIds = blocked.stream().map(BlockedItem::skillId).collect(Collectors.toSet());
        List<String> runnable = selected.stream().filter(id -> !blockedIds.contains(id)).sorted().toList();
        return new Checked(new ArrayList<>(errors), needed.stream().sorted().toList(), runnable, blocked);
    }

    /** The error is named by the member that moved; the member counts as moved when it is not what was classified or not what the agent declared. */
    private static void stale(Set<String> errors, String error, String original, String current, String declared) {
        if (!Objects.equals(original, current) || !Objects.equals(declared, current)) {
            errors.add(error);
        }
    }

    /** The signed budget limit; null when none is signed. */
    private Double limitUsd() {
        AdminLog.JevState signed = context.verifiedState().jev;
        return signed == null ? null : signed.limitUsd();
    }

    /** The key of each provider the signed evaluators belong to, by provider id; null where no usable key is registered. */
    private Map<String, SecretValue> keys(Runtime runtime) {
        Map<String, SecretValue> keys = new HashMap<>();
        for (Profile profile : runtime.registry().profiles()) {
            providers.of(profile).ifPresent(provider -> keys.put(provider.providerId(), context.jevKey(provider.credentialName())));
        }
        return keys;
    }

    private static String sessionKey(String host, String sessionId) {
        return host + "\u0000" + sessionId;
    }

    /**
     * A user prompt of a main session: when JEV can be asked, starts its classification on a virtual thread and returns at once (the hook must not wait for a provider). JEV off, a
     * condition unmet or a spent budget is the state the user set and the admin page shows: nothing is done and nothing is logged. The same prompt again in a session keeps the
     * classification it already has (asking again would be paid again); another prompt drops the earlier one, so advice is never given for a prompt that is no longer the latest.
     */
    public void startAdvice(String host, String sessionId, String prompt) {
        String key = sessionKey(host, sessionId);
        if (prompt == null || prompt.isBlank() || !adviceAsked(host)) {
            autos.invalidate(key);
            return;
        }
        String promptDigest = Checks.hash(prompt);
        AutoRun fresh = new AutoRun(promptDigest, new FutureTask<>(() -> advise(host, prompt)));
        AutoRun current = autos.asMap().compute(key, (ignored, earlier) -> earlier != null && earlier.promptDigest.equals(promptDigest) ? earlier : fresh);
        if (current == fresh) {
            Thread.startVirtualThread(fresh.flight);
        }
    }

    private boolean adviceAsked(String host) {
        try {
            return adviceReady();
        } catch (DataAccessException | JacksonException | IllegalStateException unreadable) {
            // Boundary (hook entrance): the signed setting could not be read, so JEV is not asked for this prompt; the hook itself still answers.
            LOG.warn("Whether JEV can be asked could not be read for a prompt: host={}", host, unreadable);
            return false;
        }
    }

    /**
     * The advice of the session's latest prompt, once: the skills JEV judged needed, the most confident first, at most {@link #MAX_ADVISED}. Empty while the classification is still
     * running (the next hook asks again), after it was taken, and when it did not advise.
     */
    public List<Advised> takeAdvice(String host, String sessionId) {
        AutoRun run = autos.getIfPresent(sessionKey(host, sessionId));
        if (run == null || !run.flight.isDone() || !run.taken.compareAndSet(false, true)) {
            return List.of();
        }
        return run.flight.resultNow().map(advice -> advice.response().judgments().stream().filter(judgment -> judgment.judgment() == Verdict.NEEDED)
                .map(judgment -> new Advised(clip(judgment.skillId()), advice.scores().getOrDefault(judgment.skillId(), 0.0)))
                .sorted(Comparator.<Advised>comparingDouble(Advised::score).reversed().thenComparing(Advised::skillId)).limit(MAX_ADVISED).toList()).orElse(List.of());
    }

    private static String clip(String skillId) {
        return skillId.length() <= MAX_SKILL_ID_CHARS ? skillId : skillId.substring(0, MAX_SKILL_ID_CHARS);
    }

    /**
     * The classification of one prompt, off the hook's thread. It is {@link #run} with the same signed permission, key and budget as the tool; a request is made of the prompt alone
     * (the agent's confirmed context is not known to a hook). It ends in nothing to take when JEV did not advise, and says why only when a call was actually made and failed.
     */
    private Optional<AutoAdvice> advise(String host, String prompt) {
        try {
            Runtime runtime = runtime();
            if (!runtime.config().jevEnabled()) {
                return Optional.empty(); // switched off since the hook: the user's setting, nothing to ask and nothing to say
            }
            ObservedInventory seen = observed();
            if (!seen.inventory().issues().isEmpty()) {
                LOG.warn("JEV was not asked for a prompt because the skill inventory has issues: host={} issues={}", host, seen.inventory().issues().size());
                return Optional.empty();
            }
            String id = "auto-" + UUID.randomUUID();
            ClassifyInput input = new ClassifyInput("1.0.0", id, id, prompt, new ConfirmedContext(null, null, null, null, null, null, null), List.of(), List.of(), List.of(), null, false, null);
            Request request = request(input, seen.inventory());
            BooleanSupplier sources = sourceFence(request);
            Snapshot snapshot = new Snapshot(null, runtime.config().configRevision(), runtime.registry().profileRevision(), request.inventoryDigest(), request.requestDigest(), false);
            Result result = run(request, runtime, snapshot, () -> !sources.getAsBoolean(), limitUsd(), keys(runtime)).result();
            Response response = result.response();
            if (response.error() != null || !response.status().advised()) {
                // A refusal before any call (no key, budget, not allowed to send, too long) is a state, as the page shows it; a call that was made and failed is the one thing to log.
                if (response.error() != null && response.error().dispatchState() != DispatchState.NOT_STARTED) {
                    LOG.warn("JEV advice for a prompt was not given: host={} reason={} status={} tries={}", host, response.error().code().name(), response.status(), result.attempts().size());
                }
                return Optional.empty();
            }
            return Optional.of(new AutoAdvice(response, result.attempts(), result.skipped(), Map.copyOf(scoresOf(result.attempts())), request.inventoryDigest(), digest(runtime)));
        } catch (IOException | RuntimeException failed) {
            // Boundary (background task of a hook): nobody waits on this thread, so the failure is logged with its cause here and there is no advice.
            LOG.warn("JEV advice for a prompt failed: host={}", host, failed);
            return Optional.empty();
        }
    }

    /**
     * The advice the hook already asked for, as the answer to this {@code classify_skills} call: the same prompt, inventory and signed JEV setting, so asking JEV again would give the
     * same advice and cost the same again. A classification that is still running is waited for for the same reason. The answer is rebound to this call's request; the tries that
     * made it are kept as they were, so what was spent stays what it was.
     */
    private Optional<Outcome> reused(AutoRun auto, Request request, Runtime runtime, String runtimeDigest, Snapshot snapshot) {
        if (auto == null || !auto.promptDigest.equals(request.promptDigest())) {
            return Optional.empty();
        }
        Optional<AutoAdvice> advised;
        try {
            advised = auto.flight.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException failed) {
            LOG.warn("The automatic classification of a prompt ended in a failure, so it is asked again", failed);
            return Optional.empty();
        }
        return advised.filter(advice -> advice.inventoryDigest().equals(request.inventoryDigest()) && advice.runtimeDigest().equals(runtimeDigest)).map(advice -> {
            Response first = advice.response();
            Response bound = new Response(first.schemaVersion(), request.requestId(), request.operationKey(), request.requestDigest(), request.inventoryDigest(), first.status(), first.judgments(),
                    first.unresolvedItems(), first.error());
            return new Outcome(new Result(request, bound, runtime.config(), runtime.registry().profileRevision(), advice.attempts(), advice.skipped(), snapshot), null);
        });
    }

    /** Whether advice can be asked for now. */
    @Override
    public boolean adviceReady() {
        return readiness.ready(context.verifiedState().jev, context::jevKeyUsable);
    }

    /** How long a start waits for the classification of its request: the request is one the prompt hook already sent to JEV, so this is the rest of that call, not a new one. */
    private static final Duration START_ADVICE_WAIT = Duration.ofSeconds(90);

    @Override
    public Optional<StartAdvice> adviceFor(String host, String sessionId, String goal) {
        if (!adviceAsked(host)) {
            return Optional.empty();
        }
        String key = sessionKey(host, sessionId);
        AutoRun run = autos.getIfPresent(key);
        if (run == null) {
            // The prompt hook started none for this request (a host without that hook, or an entry after it was dropped): the classification of the goal is the one for this start.
            startAdvice(host, sessionId, goal);
            run = autos.getIfPresent(key);
        }
        if (run == null) {
            return Optional.empty();
        }
        Optional<AutoAdvice> advised;
        try {
            advised = run.flight.get(START_ADVICE_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            // Boundary (a start): the advice is optional, so a classification that failed or is still running does not stop the start; it is logged with its cause.
            LOG.warn("The classification a start waited for did not finish: host={}", host, unavailable);
            return Optional.empty();
        }
        // The start gives the advice itself, so the hook that gives it at a step boundary has nothing left to give.
        run.taken.set(true);
        return advised.map(advice -> new StartAdvice(advice.response().operationKey(),
                advice.response().judgments().stream().filter(judgment -> judgment.judgment() == Verdict.NEEDED)
                        .map(judgment -> new StartAdvice.Skill(clip(judgment.skillId()), advice.scores().getOrDefault(judgment.skillId(), 0.0)))
                        .sorted(Comparator.comparingDouble(StartAdvice.Skill::score).reversed().thenComparing(StartAdvice.Skill::skillId)).limit(MAX_ADVISED).toList()))
                .filter(advice -> !advice.skills().isEmpty());
    }

    @Override
    public Optional<Latest> latest(String host, String sessionId) {
        Operations operations = sessions.getIfPresent(sessionKey(host, sessionId));
        String key = operations == null ? null : operations.latestKey;
        if (key == null) {
            return Optional.empty();
        }
        return neededOf(operations, key).map(needed -> new Latest(key, needed));
    }

    /**
     * The skills one call named needed: what the evaluator judged needed (when it advised), and the skills the caller named as explicit or rule-required. A skill the server knows
     * to be unusable (disabled, not installed, not supported by the host, not in the inventory the call saw) is no skill a start can declare, so it is left out.
     */
    private Optional<List<String>> neededOf(Operations operations, String operationKey) {
        Operation operation = operations.byId.get(operationKey);
        if (operation == null) {
            List<String> unclassified = operations.unclassified.get(operationKey);
            return Optional.ofNullable(unclassified);
        }
        Set<String> needed = new LinkedHashSet<>(union(operation.explicit, operation.required));
        if (!operation.flight.isDone()) {
            return Optional.of(List.copyOf(needed));
        }
        Result result;
        try {
            result = operation.flight.get().result();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.of(List.copyOf(needed));
        } catch (ExecutionException failed) {
            // The call itself ended in a failure the caller was told: it judged nothing, so only what the caller named is needed.
            LOG.warn("A classification that ended in a failure is cited by a start: operationKey={}", operationKey, failed);
            return Optional.of(List.copyOf(needed));
        }
        if (result.response().error() == null && result.response().status().advised()) {
            result.response().judgments().stream().filter(item -> item.judgment() == Verdict.NEEDED).forEach(item -> needed.add(item.skillId()));
        }
        Map<String, Skill> inventory = new HashMap<>();
        result.request().skills().forEach(skill -> inventory.put(skill.skillId(), skill));
        needed.removeIf(id -> {
            Skill skill = inventory.get(id);
            return skill == null || !skill.enabled() || !skill.installed() || !skill.hostSupported();
        });
        return Optional.of(List.copyOf(needed));
    }

    private static List<String> union(List<String> first, List<String> second) {
        Set<String> all = new LinkedHashSet<>();
        if (first != null) {
            all.addAll(first);
        }
        if (second != null) {
            all.addAll(second);
        }
        return List.copyOf(all);
    }
}
