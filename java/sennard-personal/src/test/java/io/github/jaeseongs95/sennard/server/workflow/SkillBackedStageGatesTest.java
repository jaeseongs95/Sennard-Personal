// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentContents;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.personal.skills.CsFrozenExample;
import io.github.jaeseongs95.sennard.personal.skills.SkillCheckContext;
import io.github.jaeseongs95.sennard.personal.skills.TestJson;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import io.github.jaeseongs95.sennard.service.store.RunState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The stage rules that were still marked as not ported, each on both sides and with the skills' real checks behind {@link SkillChecks} (the same bean
 * {@code run_skill_check} reaches) and the repository's real example files: a stage's bundle, a decision record and the two provider reports. The frozen request of an acceptance
 * report is an attachment of the server; its answer is made here, the way the entry answers {@link ReadRequest.Document} with the JSON object the attachment holds, because the
 * attachment tables are the server tests' ({@code serverit}). The files of a review bundle are attachments too (AGS3-D-0264): the entry here is the real {@link WorkflowFileReader}
 * (the real {@link BundleFolder}, the real skill check run in it) over {@link UploadedFiles}, which serves the bytes and file names of the case's uploads where the attachment
 * store would, so these cases cover the check itself, the arguments the entry gives it and the binding of the report to the file it read; the attachment rows are the server tests'.
 */
class SkillBackedStageGatesTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path REPO = Path.of(System.getProperty("sennard.repo.root"));
    private static final Path DECISIONS = REPO.resolve("skills/independent-deliberation-panel/evals/fixtures");
    /** The attachment that holds the frozen acceptance request in the cases below. */
    private static final String REQUEST_ATTACHMENT = "55555555-5555-4555-8555-555555555555";

    private static AnnotationConfigApplicationContext context;
    private static SkillChecks checks;
    private static SkillRegistry registry;
    private static ContractValidator validator;
    private static StageBundleChecks bundles;
    private static ProviderSemantics provider;
    private static StageGates gates;
    private static WorkflowPlan.SchemaReference decisionSchema;
    /** The entry of an install that has no skills module. */
    private static WorkflowFileReader unpreparedEntry;
    /** The decision record schema as the entry reads it, for the gate and the schema rules that judge a decision record. */
    private static FileReads decisionReads;

    private static ObjectProvider<SkillChecks> provide(SkillChecks real) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (real != null) {
            factory.addBean("checks", real);
        }
        return factory.getBeanProvider(SkillChecks.class);
    }

    @BeforeAll
    static void wire() throws IOException {
        context = SkillCheckContext.open();
        checks = context.getBean(SkillChecks.class);
        ContentRoot content = new ContentRoot(REPO);
        validator = new ContractValidator(MAPPER, content);
        registry = new SkillRegistry(content, validator, MAPPER);
        bundles = new StageBundleChecks(validator, MAPPER);
        provider = new ProviderSemantics(provide(checks), MAPPER);
        gates = new StageGates(validator, MAPPER, bundles, provider);
        // The schema of the decision record is read by the entry before the use case's transaction; no host identity is asked for here, so its state folder is never touched, and no
        // attachment is read, so the entry holds none. The entry of an install without the skills module answers a bundle's check before it reads any attachment, so it holds none either.
        WorkflowFileReader entry = new WorkflowFileReader(validator, registry, provide(null), new HostIdentity(Path.of("unused-state"), new LocalPrivateStorage()), MAPPER, null);
        unpreparedEntry = entry;
        String schema = "skills/independent-deliberation-panel/contracts/decision-record.v1.schema.json";
        decisionSchema = new WorkflowPlan.SchemaReference(schema, "sha256:" + Digests.sha256Hex(Files.readAllBytes(REPO.resolve(schema))));
        decisionReads = entry.read(new ReadPlan(-1, List.of(new ReadRequest.DeclaredSchema(decisionSchema, ContractValidator.DECISION_RECORD))));
    }

    /**
     * What the entry answers for the provider's own rules over this result: the frozen acceptance request, where the stage is held to those rules. The request the rules ask for is
     * a {@link ReadRequest.Document} (the attachment's id and the digest declared for it); it is answered with the JSON object the attachment holds, which is {@code frozen}.
     */
    private static FileReads providerReads(WorkflowPlan.Stage stage, StageResult result, String taskDigest, JsonNode frozen) {
        Map<ReadRequest, FileRead<?>> answers = new LinkedHashMap<>();
        for (ReadRequest request : provider.request(stage, result, taskDigest).stream().map(ReadRequest.class::cast).toList()) {
            assertTrue(request instanceof ReadRequest.Document, "the frozen request is read as a document of an attachment: " + request);
            answers.put(request, new FileRead<>(frozen, null));
        }
        return new FileReads(-1, answers);
    }

    /** The decision record, judged against the schema the plan froze, as the gate judges it. */
    private static JsonNode declared(JsonNode record) {
        return validator.declaredSchema(decisionReads.schema(decisionSchema, ContractValidator.DECISION_RECORD), record, ContractValidator.DECISION_RECORD);
    }

    @AfterAll
    static void close() {
        context.close();
    }

    private static WorkflowCode code(Runnable work) {
        return assertThrows(WorkflowFailure.class, work::run).code();
    }

    private static JsonNode skill(String skill, String command, Object... pairs) {
        return checks.run(skill, command, TestJson.object(pairs)).body().get("value");
    }

    private static JsonNode read(Path file) throws IOException {
        return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static ObjectNode copy(JsonNode node) {
        return (ObjectNode) node.deepCopy();
    }

    private static ProviderArtifact artifact(String id, AttachmentFileRef file, String digest, String target) {
        return new ProviderArtifact(id, "artifact.v1", file.attachmentId(), file.seq(), digest, target, true);
    }

    /** What a stage result holds for these checks: the provider's output, as the skill made it (a tree), and the artifacts it names. */
    private static StageResult result(JsonNode providerOutput, ProviderArtifact... artifacts) {
        ProviderResult output = new ProviderResult("1.0.0", OutputKind.OUTPUT, providerOutput, List.of(artifacts), null);
        return new StageResult("1.0.0", "run-1", "stage-01", 0, RunState.PASSED, output, List.of(), List.of(), List.of(), null, null, null, null, null);
    }

    /** The same result with another list of artifacts. */
    private static StageResult withArtifacts(StageResult result, ProviderArtifact... artifacts) {
        return result.withOutput(new ProviderResult(result.output().schemaVersion(), result.output().kind(), result.output().output(), List.of(artifacts), result.output().error()));
    }

    /** The digest of the bytes an attachment holding this document has: the document as the caller uploads it. */
    private static String digestOfUpload(JsonNode value) {
        return TestJson.hashBytes((MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------------------- the entry over a case's uploads

    /**
     * The attachments of a case, as the attachment store serves them to the entry: the file name each was uploaded under and its bytes, by attachment id. This stands in only for the
     * rows and the encrypted files of the attachment tables, which the server tests cover; everything the entry does with what it reads is the real code.
     */
    private static final class UploadedFiles extends AttachmentContents {
        private final Map<AttachmentFileRef, EngineeringBundles.Upload> uploads;

        private UploadedFiles(Map<AttachmentFileRef, EngineeringBundles.Upload> uploads) {
            super(null, null);
            this.uploads = Map.copyOf(uploads);
        }

        @Override
        public Named readNamed(String attachmentId, int seq, int limitBytes) {
            EngineeringBundles.Upload upload = uploads.get(new AttachmentFileRef(attachmentId, seq));
            if (upload == null) {
                throw new IllegalStateException("The case uploaded no file " + seq + " of an attachment " + attachmentId);
            }
            return new Named(upload.fileName(), upload.bytes());
        }
    }

    /** The entry of an install with the skills' real checks, over what a case uploaded. */
    private static WorkflowFileReader entryOver(Map<AttachmentFileRef, EngineeringBundles.Upload> uploads) {
        return new WorkflowFileReader(validator, registry, provide(checks), new HostIdentity(Path.of("unused-state"), new LocalPrivateStorage()), MAPPER, new UploadedFiles(uploads));
    }

    /** What the entry reads for the check of this stage's review bundle: the check is run by the skill the entry reaches, before the rules judge its answer. */
    private static FileReads bundleReads(WorkflowFileReader from, WorkflowPlan.Stage stage, StageResult result, String taskDigest) {
        List<ReadRequest> requests = bundles.request(stage, result, taskDigest).stream().map(ReadRequest.class::cast).toList();
        return from.read(new ReadPlan(-1, requests));
    }

    /** The same uploads with the bytes of one attachment replaced. */
    private static Map<AttachmentFileRef, EngineeringBundles.Upload> withBytes(Map<AttachmentFileRef, EngineeringBundles.Upload> uploads, AttachmentFileRef file, String fileName, String text) {
        Map<AttachmentFileRef, EngineeringBundles.Upload> changed = new LinkedHashMap<>(uploads);
        changed.put(file,new EngineeringBundles.Upload(fileName, text.getBytes(StandardCharsets.UTF_8)));
        return changed;
    }

    // ---------------------------------------------------------------------------------------------------------------------------------------- the CS bundle

    private static WorkflowPlan.Stage csStage() {
        String key = registry.read().stream().filter(entry -> entry.stageValidator() != null).findFirst().orElseThrow().providerKey();
        return PlanSamples.stage("stage-01", "cs-implementation-review", key, List.of(), PlanSamples.openGate(), null);
    }

    private record CsCase(StageResult result, String taskDigest, Map<AttachmentFileRef, EngineeringBundles.Upload> uploads) {
    }

    /**
     * The example's own documents on a copy whose candidate is a stand-in file and whose digests hold (the example's candidate source is not in the checkout), uploaded file by file:
     * the manifest and the review are the artifacts of the result and every other file of the copy is its evidence.
     */
    private static CsCase csCase(Path temp) throws IOException {
        Path cs = CsFrozenExample.frozen(temp.resolve("cs"));
        String taskDigest = TestJson.hashJson(read(cs.resolve("task.json")));
        String bundleDigest = TestJson.hashBytes(Files.readAllBytes(cs.resolve("stage-bundle.json")));
        JsonNode judged = skill("cs-engineering", "check-stage-bundle", "root", cs.toString(), "input", "stage-bundle.json", "bundle-digest", bundleDigest, "task-digest", taskDigest);
        String candidate = judged.get("candidateDigest").asString();
        JsonNode reviewRef = judged.get("reviewRef");
        String reviewPath = reviewRef.get("path").asString();
        Map<AttachmentFileRef, EngineeringBundles.Upload> uploads = EngineeringBundles.uploadsOf(cs);
        AttachmentFileRef bundleAttachment = EngineeringBundles.fileOf("stage-bundle.json");
        AttachmentFileRef reviewAttachment = EngineeringBundles.fileOf(reviewPath);
        StageResult result = result(read(cs.resolve(reviewPath)), artifact("cs-review-bundle", bundleAttachment, bundleDigest, candidate),
                artifact("cs-review-report", reviewAttachment, reviewRef.get("digest").asString(), candidate));
        return new CsCase(result.withEvidence(EngineeringBundles.evidenceBesides(uploads, bundleAttachment, reviewAttachment)), taskDigest, uploads);
    }

    @Test
    void aCsReviewBundleTheRealCheckAcceptsPassesTheStage(@TempDir Path temp) throws IOException {
        CsCase given = csCase(temp);
        FileReads reads = bundleReads(entryOver(given.uploads()), csStage(), given.result(), given.taskDigest());
        assertDoesNotThrow(() -> bundles.check(csStage(), given.result(), given.taskDigest(), reads));
        assertDoesNotThrow(() -> gates.assertStageBundles(csStage(), given.result(), given.taskDigest(), reads), "the stage gate is the same check");
    }

    @Test
    void aCsReviewReportDifferentFromWhatTheCheckReadIsAnIntegrityFailure(@TempDir Path temp) throws IOException {
        CsCase given = csCase(temp);
        ((ObjectNode) given.result().output().output()).put("verdict", "SOMETHING_ELSE");
        FileReads reads = bundleReads(entryOver(given.uploads()), csStage(), given.result(), given.taskDigest());
        assertEquals(WorkflowCode.INTEGRITY_FAILED, code(() -> bundles.check(csStage(), given.result(), given.taskDigest(), reads)));
    }

    @Test
    void aCsBundleJudgedForAnotherTaskIsNotAccepted(@TempDir Path temp) throws IOException {
        CsCase given = csCase(temp);
        String other = "sha256:" + "0".repeat(64);
        FileReads otherReads = bundleReads(entryOver(given.uploads()), csStage(), given.result(), other);
        assertNotNull(code(() -> bundles.check(csStage(), given.result(), other, otherReads)));
        FileReads unsignedReads = bundleReads(entryOver(given.uploads()), csStage(), given.result(), null);
        assertEquals(WorkflowCode.BINDING_REQUIRED, code(() -> bundles.check(csStage(), given.result(), null, unsignedReads)), "a review stage needs a signed task");
    }

    @Test
    void aCsBundleWhoseDigestDoesNotMatchIsRefusedBeforeItIsRead(@TempDir Path temp) throws IOException {
        CsCase given = csCase(temp);
        ProviderArtifact bundle = given.result().output().artifacts().get(0);
        StageResult changed = withArtifacts(given.result(), new ProviderArtifact(bundle.artifactId(), bundle.schemaId(), bundle.attachmentId(), bundle.seq(), "sha256:" + "0".repeat(64), bundle.targetDigest(), true),
                given.result().output().artifacts().get(1));
        FileReads reads = bundleReads(entryOver(given.uploads()), csStage(), changed, given.taskDigest());
        assertNotNull(code(() -> bundles.check(csStage(), changed, given.taskDigest(), reads)));
    }

    @Test
    void aCsReviewFileChangedAfterTheBundleWasSealedIsNotAccepted(@TempDir Path temp) throws IOException {
        CsCase given = csCase(temp);
        ProviderArtifact reviewArtifact = given.result().output().artifacts().get(1);
        AttachmentFileRef review = new AttachmentFileRef(reviewArtifact.attachmentId(), reviewArtifact.seq());
        Map<AttachmentFileRef, EngineeringBundles.Upload> uploads = withBytes(given.uploads(), review, given.uploads().get(review).fileName(), "changed");
        FileReads reads = bundleReads(entryOver(uploads), csStage(), given.result(), given.taskDigest());
        assertNotNull(code(() -> bundles.check(csStage(), given.result(), given.taskDigest(), reads)));
    }

    @Test
    void withoutTheCheckTheStageIsRefusedAndNeverPassed(@TempDir Path temp) throws IOException {
        CsCase given = csCase(temp);
        // The entry of an install without the skills module answers the check with nothing.
        FileReads unprepared = bundleReads(unpreparedEntry, csStage(), given.result(), given.taskDigest());
        assertEquals(WorkflowCode.MCP_UNAVAILABLE, code(() -> bundles.check(csStage(), given.result(), given.taskDigest(), unprepared)));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------- the engineering bundles

    @ParameterizedTest
    @ValueSource(strings = {EngineeringBundles.TEST_STAGE, EngineeringBundles.CODE_STAGE})
    void anEngineeringBundleTheRealCheckAcceptsPassesTheStage(String capability, @TempDir Path root) throws IOException {
        EngineeringBundles.Bundle given = EngineeringBundles.of(SkillBackedStageGatesTest::skill, root, capability);
        FileReads reads = bundleReads(entryOver(given.uploads()), given.stage(), given.result(), given.taskDigest());
        assertDoesNotThrow(() -> bundles.check(given.stage(), given.result(), given.taskDigest(), reads));
        assertDoesNotThrow(() -> gates.assertStageBundles(given.stage(), given.result(), given.taskDigest(), reads), "the stage gate is the same check");
    }

    /**
     * Case b5 over the real check: the same bundle with every file a file of ONE attachment (files 0, 1, 2 ... of one {@code artifact_begin}) is judged by the same skill with the same
     * answer: the entry puts the files in the folder by (attachment id, file number), and the report is bound by the file at the path the check read.
     */
    @ParameterizedTest
    @ValueSource(strings = {EngineeringBundles.TEST_STAGE, EngineeringBundles.CODE_STAGE})
    void anEngineeringBundleWhoseFilesAreFilesOfOneAttachmentPassesTheStageLikeOneAttachmentPerFile(String capability, @TempDir Path root) throws IOException {
        EngineeringBundles.Bundle separate = EngineeringBundles.of(SkillBackedStageGatesTest::skill, root, capability);
        String attachment = "88888888-8888-4888-8888-888888888888";
        EngineeringBundles.Bundle given = EngineeringBundles.inOneAttachment(separate, attachment);
        assertTrue(given.uploads().size() > 2, "the bundle has several files: " + given.uploads().keySet());
        assertTrue(given.uploads().keySet().stream().allMatch(file -> file.attachmentId().equals(attachment)), "all of them are files of one attachment");
        assertTrue(given.result().evidence().stream().anyMatch(item -> item.seq() > 0) && given.result().output().artifacts().stream().allMatch(artifact -> attachment.equals(artifact.attachmentId())),
                "the result points at the files by number");
        FileReads reads = bundleReads(entryOver(given.uploads()), given.stage(), given.result(), given.taskDigest());
        assertDoesNotThrow(() -> bundles.check(given.stage(), given.result(), given.taskDigest(), reads));
        assertDoesNotThrow(() -> gates.assertStageBundles(given.stage(), given.result(), given.taskDigest(), reads), "the stage gate is the same check");
    }

    /** A file of the one attachment whose bytes are not the sealed ones is not accepted, whichever file of the attachment it is. */
    @ParameterizedTest
    @ValueSource(strings = {EngineeringBundles.TEST_STAGE, EngineeringBundles.CODE_STAGE})
    void aFileOfTheOneAttachmentChangedAfterTheBundleWasSealedIsNotAccepted(String capability, @TempDir Path root) throws IOException {
        EngineeringBundles.Bundle given = EngineeringBundles.inOneAttachment(EngineeringBundles.of(SkillBackedStageGatesTest::skill, root, capability), "99999999-9999-4999-8999-999999999999");
        ProviderArtifact report = given.result().output().artifacts().get(1);
        AttachmentFileRef reportFile = new AttachmentFileRef(report.attachmentId(), report.seq());
        Map<AttachmentFileRef, EngineeringBundles.Upload> uploads = withBytes(given.uploads(), reportFile, EngineeringBundles.REPORT_FILE, "changed");
        FileReads reads = bundleReads(entryOver(uploads), given.stage(), given.result(), given.taskDigest());
        assertNotNull(code(() -> bundles.check(given.stage(), given.result(), given.taskDigest(), reads)));
    }

    @ParameterizedTest
    @ValueSource(strings = {EngineeringBundles.TEST_STAGE, EngineeringBundles.CODE_STAGE})
    void anEngineeringOutputThatIsNotWhatTheCheckComputedIsAnIntegrityFailure(String capability, @TempDir Path root) throws IOException {
        EngineeringBundles.Bundle given = EngineeringBundles.of(SkillBackedStageGatesTest::skill, root, capability);
        ((ObjectNode) given.result().output().output()).put("verdict", "CLAIMED");
        FileReads reads = bundleReads(entryOver(given.uploads()), given.stage(), given.result(), given.taskDigest());
        assertEquals(WorkflowCode.INTEGRITY_FAILED, code(() -> bundles.check(given.stage(), given.result(), given.taskDigest(), reads)));
    }

    @ParameterizedTest
    @ValueSource(strings = {EngineeringBundles.TEST_STAGE, EngineeringBundles.CODE_STAGE})
    void aRawFileChangedAfterTheBundleWasSealedIsNotAccepted(String capability, @TempDir Path root) throws IOException {
        EngineeringBundles.Bundle given = EngineeringBundles.of(SkillBackedStageGatesTest::skill, root, capability);
        Map<AttachmentFileRef, EngineeringBundles.Upload> uploads = withBytes(given.uploads(), EngineeringBundles.fileOf(EngineeringBundles.REPORT_FILE), EngineeringBundles.REPORT_FILE, "changed");
        FileReads reads = bundleReads(entryOver(uploads), given.stage(), given.result(), given.taskDigest());
        assertNotNull(code(() -> bundles.check(given.stage(), given.result(), given.taskDigest(), reads)));
    }

    @ParameterizedTest
    @ValueSource(strings = {EngineeringBundles.TEST_STAGE, EngineeringBundles.CODE_STAGE})
    void anEngineeringBundleJudgedForAnotherTaskIsNotAccepted(String capability, @TempDir Path root) throws IOException {
        EngineeringBundles.Bundle given = EngineeringBundles.of(SkillBackedStageGatesTest::skill, root, capability);
        String other = "sha256:" + "0".repeat(64);
        FileReads otherReads = bundleReads(entryOver(given.uploads()), given.stage(), given.result(), other);
        assertNotNull(code(() -> bundles.check(given.stage(), given.result(), other, otherReads)));
        FileReads unsignedReads = bundleReads(entryOver(given.uploads()), given.stage(), given.result(), null);
        assertEquals(WorkflowCode.BINDING_REQUIRED, code(() -> bundles.check(given.stage(), given.result(), null, unsignedReads)), "an engineering review needs a signed task");
    }

    @ParameterizedTest
    @ValueSource(strings = {EngineeringBundles.TEST_STAGE, EngineeringBundles.CODE_STAGE})
    void aBundleFileWhoseNameIsNotAPortablePathRefusesTheStageWhereTheCheckIsTaken(String capability, @TempDir Path root) throws IOException {
        EngineeringBundles.Bundle given = EngineeringBundles.of(SkillBackedStageGatesTest::skill, root, capability);
        AttachmentFileRef taskFile = EngineeringBundles.fileOf("task.json");
        String task = taskFile.attachmentId();
        Map<AttachmentFileRef, EngineeringBundles.Upload> uploads = withBytes(given.uploads(), taskFile, "../task.json", "{}");
        // Reading the plan raises nothing: the refusal of the entry is kept and raised where the use case takes the check's answer.
        FileReads reads = bundleReads(entryOver(uploads), given.stage(), given.result(), given.taskDigest());
        WorkflowFailure refused = assertThrows(WorkflowFailure.class, () -> bundles.check(given.stage(), given.result(), given.taskDigest(), reads));
        assertEquals(WorkflowCode.INVALID_INPUT, refused.code());
        assertEquals("workflow.bundle.fileNotUsable", refused.ref().key());
        assertEquals("PATH_NOT_PORTABLE", refused.details().get("reason"));
        assertEquals(task, refused.details().get("attachmentId"));
    }

    // ------------------------------------------------------------------------------------------------ which stages finalize judges again

    @Test
    void finalizeJudgesAgainTheReviewStagesAndTheMandatoryAuditsAndNoOtherStage() {
        for (String capability : List.of("cs-implementation-review", "test-sensitivity-review", "change-code-review")) {
            assertTrue(StageGates.rechecksBundles(PlanSamples.stage("stage-01", capability, List.of(), PlanSamples.openGate(), null)), capability);
        }
        assertTrue(StageGates.rechecksBundles(PlanSamples.audit("stage-01", AuditPhase.POST_EXECUTION)));
        assertFalse(StageGates.rechecksBundles(PlanSamples.stage("stage-01", "minimal-implementation", List.of(), PlanSamples.openGate(), null)));
        assertFalse(StageGates.rechecksBundles(PlanSamples.stage("stage-01", "cs-constraint-derivation", List.of(), PlanSamples.openGate(), null)), "only the review of the implementation has a bundle");
    }

    // ---------------------------------------------------------------------------------------------------------- the decision record

    private static List<Path> fixtures(String kind) throws IOException {
        try (Stream<Path> files = Files.list(DECISIONS.resolve(kind))) {
            return files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        }
    }

    static List<Path> validDecisions() throws IOException {
        return fixtures("valid");
    }

    static List<Path> invalidDecisions() throws IOException {
        return fixtures("invalid");
    }

    private static boolean rejected(JsonNode record) {
        try {
            declared(record);
        } catch (WorkflowFailure schemaFailure) {
            return true;
        }
        return !DecisionRecordSemantics.validate(record, MAPPER).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("validDecisions")
    void everyValidDecisionRecordOfTheSkillIsValidHere(Path file) throws IOException {
        JsonNode record = read(file);
        assertDoesNotThrow(() -> declared(record));
        assertEquals(List.of(), DecisionRecordSemantics.validate(record, MAPPER));
    }

    @ParameterizedTest
    @MethodSource("invalidDecisions")
    void everyInvalidDecisionRecordOfTheSkillIsRefusedHere(Path file) throws IOException {
        assertTrue(rejected(read(file)), file.getFileName().toString());
    }

    private static WorkflowPlan.Stage deliberation(List<String> requiredArtifacts, WorkflowPlan.SchemaReference recordSchema) {
        return PlanSamples.stage("stage-01", "independent-deliberation", requiredArtifacts, new WorkflowPlan.Gate(WorkflowPlan.GateKind.COMPLETION, RiskGate.NONE, null, recordSchema), null);
    }

    private static WorkflowPlan.Stage deliberation() {
        return deliberation(List.of("decision-record"), decisionSchema);
    }

    private static StageResult decisionResult(JsonNode record) {
        ObjectNode providerOutput = MAPPER.createObjectNode();
        providerOutput.set("decisionRecord", record);
        return result(providerOutput);
    }

    private static ObjectNode high() throws IOException {
        ObjectNode record = copy(read(DECISIONS.resolve("valid/rc03_high_fresh_judge.json")));
        ((ObjectNode) record.get("run")).put("strict", true);
        return record;
    }

    private static ObjectNode withoutWorker(JsonNode source, String id) {
        ObjectNode record = copy(source);
        ObjectNode run = (ObjectNode) record.get("run");
        run.set("workers", without(run.get("workers"), "id", id));
        record.set("panel_manifest", without(record.get("panel_manifest"), "id", id));
        run.set("completed_worker_ids", withoutText(run.get("completed_worker_ids"), id));
        return record;
    }

    private static tools.jackson.databind.node.ArrayNode without(JsonNode array, String field, String value) {
        tools.jackson.databind.node.ArrayNode kept = MAPPER.createArrayNode();
        array.forEach(item -> {
            if (!value.equals(item.path(field).asString())) {
                kept.add(item);
            }
        });
        return kept;
    }

    private static tools.jackson.databind.node.ArrayNode withoutText(JsonNode array, String value) {
        tools.jackson.databind.node.ArrayNode kept = MAPPER.createArrayNode();
        array.forEach(item -> {
            if (!value.equals(item.asString())) {
                kept.add(item);
            }
        });
        return kept;
    }

    /** Adds completed reviewers with roles of their own and a cap that fits: how many reviewers a run uses is the Coordinator's judgment. */
    private static ObjectNode withExtraReviewers(JsonNode source, int count) {
        ObjectNode record = copy(source);
        ObjectNode run = (ObjectNode) record.get("run");
        JsonNode template = null;
        for (JsonNode worker : run.get("workers")) {
            if ("reviewer".equals(worker.path("classification").asString())) {
                template = worker;
                break;
            }
        }
        for (int n = 0; n < count; n++) {
            ObjectNode extra = copy(template);
            extra.put("id", "extra" + n).put("role", "Extra Perspective " + n);
            ((tools.jackson.databind.node.ArrayNode) run.get("workers")).add(extra);
            ((tools.jackson.databind.node.ArrayNode) record.get("panel_manifest")).add(copy(extra));
            ((tools.jackson.databind.node.ArrayNode) run.get("completed_worker_ids")).add("extra" + n);
        }
        run.put("worker_cap", run.get("workers").size() + 3);
        return record;
    }

    @Test
    void theNumberOfReviewersAndTheWorkerCapAreJudgmentsAndOnlyTheStructureIsChecked() throws IOException {
        JsonNode high = read(DECISIONS.resolve("valid/rc03_high_fresh_judge.json"));
        JsonNode medium = read(DECISIONS.resolve("valid/rc02_medium_reviewers.json"));
        JsonNode critical = read(DECISIONS.resolve("valid/rc04_critical_red_team.json"));
        assertEquals(List.of(), DecisionRecordSemantics.validate(withoutWorker(high, "r4"), MAPPER), "HIGH with three reviewers");
        assertEquals(List.of(), DecisionRecordSemantics.validate(withExtraReviewers(medium, 2), MAPPER), "MEDIUM with four reviewers");
        ObjectNode seven = withExtraReviewers(critical, 2);
        assertEquals(List.of(), DecisionRecordSemantics.validate(seven, MAPPER), "CRITICAL with seven reviewers");
        ObjectNode aboveEight = withExtraReviewers(high, 5);
        assertTrue(aboveEight.get("run").get("worker_cap").asInt() > 8);
        assertEquals(List.of(), DecisionRecordSemantics.validate(aboveEight, MAPPER), "a worker cap above eight");
        assertDoesNotThrow(() -> declared(aboveEight));
    }

    @Test
    void theCapMustBeANonNegativeIntegerAndAMediumRunNeedsAReviewer() throws IOException {
        ObjectNode negative = copy(read(DECISIONS.resolve("valid/rc03_high_fresh_judge.json")));
        ((ObjectNode) negative.get("run")).put("worker_cap", -1);
        assertTrue(rejected(negative));
        ObjectNode none = copy(read(DECISIONS.resolve("invalid/rc02_medium_without_reviewer.json")));
        assertEquals(List.of("MEDIUM requires an eligible completed reviewer"), DecisionRecordSemantics.validate(none, MAPPER));
    }

    /** A medium run of one reviewer, whose cross-examination has nobody else to ask and so is skipped. */
    private static ObjectNode mediumWithOneReviewer() throws IOException {
        ObjectNode record = withoutWorker(read(DECISIONS.resolve("valid/rc02_medium_reviewers.json")), "r2");
        ObjectNode cross = MAPPER.createObjectNode().put("decision", "skip").put("reason", "One reviewer has nobody else to challenge a claim.");
        for (String field : List.of("trigger_items", "selected_item_ids", "coverage", "followups")) {
            cross.set(field, MAPPER.createArrayNode());
        }
        record.set("cross_examination", cross);
        return record;
    }

    /** The same record with one more object cloned from the first of an array of the run (or of the record) under another id. */
    private static void cloneFirst(ObjectNode owner, String field, String idField, String newId) {
        tools.jackson.databind.node.ArrayNode items = (tools.jackson.databind.node.ArrayNode) owner.get(field);
        ObjectNode extra = copy(items.get(0));
        if (idField != null) {
            extra.put(idField, newId);
        }
        items.add(extra);
    }

    @Test
    void reviewerAndSpecialistCountsAreJudgmentsButTheRedeliberationAndFollowupCapsAreEnforcedWithTheReExamineGuidance() throws IOException {
        assertEquals(List.of(), DecisionRecordSemantics.validate(mediumWithOneReviewer(), MAPPER), "one reviewer is the Coordinator's judgment");

        ObjectNode specialists = copy(read(DECISIONS.resolve("valid/ad01_specialist_admission.json")));
        ObjectNode run = (ObjectNode) specialists.get("run");
        JsonNode specialistWorker = null;
        for (JsonNode worker : run.get("workers")) {
            if ("adaptive_specialist".equals(worker.path("classification").asString())) {
                specialistWorker = worker;
            }
        }
        ObjectNode second = copy(specialistWorker);
        second.put("id", "specialist2").put("role", "Second Specialist");
        ((tools.jackson.databind.node.ArrayNode) run.get("workers")).add(second);
        ((tools.jackson.databind.node.ArrayNode) specialists.get("panel_manifest")).add(copy(second));
        ((tools.jackson.databind.node.ArrayNode) run.get("completed_worker_ids")).add("specialist2");
        cloneFirst(run, "specialist_additions", "worker_id", "specialist2");
        run.put("worker_cap", run.get("workers").size() + 3);
        assertEquals(2, run.get("specialist_additions").size());
        assertEquals(List.of(), DecisionRecordSemantics.validate(specialists, MAPPER), "two specialist additions");

        assertEquals(List.of(), DecisionRecordSemantics.validate(read(DECISIONS.resolve("valid/ad02_redeliberation_cap.json")), MAPPER), "one re-deliberation");
        assertEquals(List.of(), DecisionRecordSemantics.validate(read(DECISIONS.resolve("valid/cx04_followup_cap.json")), MAPPER), "one follow-up per reviewer");
        assertCapReached(DecisionRecordSemantics.validate(read(DECISIONS.resolve("invalid/ad02_redeliberation_cap.json")), MAPPER), "at most one re-deliberation is allowed");
        assertCapReached(DecisionRecordSemantics.validate(read(DECISIONS.resolve("invalid/cx04_followup_cap.json")), MAPPER), "a reviewer may receive at most one cross follow-up");

        ObjectNode redeliberations = redeliberationsTwice();
        assertEquals(2, redeliberations.get("run").get("redeliberations").size());
        assertEquals(List.of(), DecisionRecordSemantics.validate(redeliberations, MAPPER).stream().filter(error -> !DecisionRecordSemantics.isTerminationCap(error)).toList(), "the second re-deliberation breaks only the cap");
        assertCapReached(DecisionRecordSemantics.validate(redeliberations, MAPPER), "at most one re-deliberation is allowed");

        ObjectNode followups = copy(read(DECISIONS.resolve("valid/cx04_followup_cap.json")));
        cloneFirst((ObjectNode) followups.get("cross_examination"), "followups", null, null);
        assertCapReached(DecisionRecordSemantics.validate(followups, MAPPER), "a reviewer may receive at most one cross follow-up");
        assertDoesNotThrow(() -> declared(followups));
    }

    /** The valid one-re-deliberation record with a second one, which only the cap refuses. */
    private static ObjectNode redeliberationsTwice() throws IOException {
        ObjectNode record = copy(read(DECISIONS.resolve("valid/ad02_redeliberation_cap.json")));
        cloneFirst((ObjectNode) record.get("run"), "redeliberations", "id", "rd2");
        return record;
    }

    /** A reached limit is exactly one problem, a termination cap that tells the model to re-examine its direction and to finish, not to try again. */
    private static void assertCapReached(List<String> errors, String limit) {
        List<String> caps = errors.stream().filter(DecisionRecordSemantics::isTerminationCap).toList();
        assertEquals(1, caps.size(), errors.toString());
        String cap = caps.get(0);
        assertTrue(cap.contains(limit), cap);
        assertTrue(cap.contains("the current direction may be wrong"), cap);
        assertTrue(cap.contains("re-examine the question definition and the evidence"), cap);
        assertTrue(cap.contains("record the remaining issues and the evidence still needed"), cap);
        assertTrue(cap.contains("finish without another round"), cap);
    }

    @Test
    void theSchemaLimitsTheItemsOfAFollowupAndTheParticipantsOfARedeliberationToTwo() throws IOException {
        ObjectNode followups = copy(read(DECISIONS.resolve("valid/cx04_followup_cap.json")));
        ObjectNode first = (ObjectNode) followups.get("cross_examination").get("followups").get(0);
        first.set("item_ids", MAPPER.createArrayNode().add("T1").add("T2").add("T3"));
        assertThrows(WorkflowFailure.class, () -> declared(followups));

        ObjectNode redeliberations = copy(read(DECISIONS.resolve("valid/ad02_redeliberation_cap.json")));
        ObjectNode redeliberation = (ObjectNode) redeliberations.get("run").get("redeliberations").get(0);
        redeliberation.set("participant_worker_ids", MAPPER.createArrayNode().add("r1").add("r2").add("r3"));
        assertThrows(WorkflowFailure.class, () -> declared(redeliberations));
    }

    @Test
    void theGateAnswersAReachedCapWithTheReExamineMessageAndAnOrdinarySemanticErrorWithTheCanonicalOne() throws IOException {
        ObjectNode record = redeliberationsTwice();
        WorkflowFailure cap = assertThrows(WorkflowFailure.class, () -> gates.assertDeliberationGate(deliberation(), decisionResult(record), decisionReads));
        assertEquals(WorkflowCode.GATE_FAILED, cap.code());
        assertEquals("workflow.service.decisionRecordTerminationCapReached", cap.ref().key());
        assertCapReached(semanticErrorsOf(cap), "at most one re-deliberation is allowed");

        ObjectNode ordinary = copy(read(DECISIONS.resolve("valid/ad02_redeliberation_cap.json")));
        ((ObjectNode) ordinary.get("run").get("redeliberations").get(0)).set("impacted_scope", MAPPER.createArrayNode().add("NOT-A-CLAIM"));
        WorkflowFailure semantic = assertThrows(WorkflowFailure.class, () -> gates.assertDeliberationGate(deliberation(), decisionResult(ordinary), decisionReads));
        assertEquals("workflow.service.decisionRecordV1FailedCanonicalSemantic", semantic.ref().key());
    }

    private static List<String> semanticErrorsOf(WorkflowFailure failure) {
        List<String> errors = new ArrayList<>();
        for (Object error : (List<?>) failure.details().get("semanticErrors")) {
            errors.add((String) error);
        }
        return errors;
    }

    @Test
    void theSkillsTextsStateNoFixedCountOfReviewersOrSpecialistsButTheTerminationCaps() throws IOException {
        Path skill = REPO.resolve("skills/independent-deliberation-panel");
        List<Path> texts = new ArrayList<>(List.of(skill.resolve("SKILL.md"), skill.resolve("README.md"), skill.resolve("contracts/requirements.json")));
        try (Stream<Path> references = Files.list(skill.resolve("references"))) {
            references.filter(file -> file.toString().endsWith(".md")).forEach(texts::add);
        }
        java.util.regex.Pattern fixed = java.util.regex.Pattern.compile(
                "최소 두 명|최대 한 명|한 명을 admission|specialist 한 명|specialist 1명|at least two|at most one specialist|at most one reviewer", java.util.regex.Pattern.CASE_INSENSITIVE);
        List<String> found = new ArrayList<>();
        for (Path text : texts) {
            java.util.regex.Matcher matcher = fixed.matcher(Files.readString(text, StandardCharsets.UTF_8));
            while (matcher.find()) {
                found.add(skill.relativize(text) + ": " + matcher.group());
            }
        }
        assertEquals(List.of(), found, "the number of reviewers and of specialists is the Coordinator's judgment");

        // The re-deliberation and follow-up limits end repetition, so the contract, the policy and the entry text state them and what to do on reaching them.
        String requirements = Files.readString(skill.resolve("contracts/requirements.json"), StandardCharsets.UTF_8);
        assertTrue(requirements.contains("At most one selective re-deliberation may be recorded"));
        assertTrue(requirements.contains("Each reviewer receives at most one cross-examination follow-up"));
        for (String reference : List.of("references/entry-details.md", "references/orchestration-policy.md", "references/evidence-and-verdict-schema.md")) {
            String text = Files.readString(skill.resolve(reference), StandardCharsets.UTF_8);
            assertTrue(text.contains("질문 정의와 증거를 다시 짚"), reference);
            assertTrue(text.contains("남은 쟁점과 필요한 증거를 기록"), reference);
        }
        assertTrue(Files.readString(skill.resolve("references/entry-details.md"), StandardCharsets.UTF_8).contains("상한에 닿았다는 것은 지금 방향이 틀렸을 수 있다는 신호다"));
    }

    /**
     * A head count the model is told to staff ("reviewer 2명", "one specialist", "한 명 이상"). How many workers a task needs is the
     * Coordinator's judgment from importance, uncertainty and independent failure modes, so no skill text may fix it. Korean: a count
     * (digit 1 or more, or 한·두·세·네·다섯·여섯) directly before 명 and an optional particle; 0 (no worker was created) and 한 명도 are not
     * a count to staff, and a count word that is the tail of another word (고정 명칭, 한 명령) never matches. English: a count or
     * "single" before reviewer, worker, specialist, judge, auditor, subagent or agent (not "specialist skill", not "Stage 6 specialist"),
     * and "at least/most N" (not a size: N followed by a unit such as KiB, MiB, GiB, KB, MB, GB or bytes is a limit, not a head count).
     */
    private static final java.util.regex.Pattern FIXED_HEADCOUNT = java.util.regex.Pattern.compile(
            "(?<![가-힣0-9])(?:[1-9][0-9]*|한|두|세|네|다섯|여섯)\\s?명(?:을|이|은|의|만|씩|으로|과|와|에|보다|인)?(?![가-힣])"
                    + "|(?<!Stage )\\b(?:[1-9][0-9]*|one|two|three|four|five|six|a single|single)\\s+(?:independent\\s+|fresh\\s+|blind\\s+|separate\\s+|additional\\s+|extra\\s+)*"
                    + "(?:reviewer|worker|specialist|judge|auditor|subagent|agent)s?\\b(?!\\s+skill)"
                    + "|\\bat (?:least|most) (?:[1-9][0-9]*|one|two|three|four|five)\\b(?!\\s*(?:[KMGTP]i?B|bytes?)\\b)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** A sentence that records a count the user gave is a faithful record, not an instruction: the user's number is the cap. */
    private static final java.util.regex.Pattern USER_GIVEN = java.util.regex.Pattern.compile(
            "사용자가 (?:준|주는|준 값|요구한|지정한|명시한)|user[- ](?:given|specified|requested|supplied)|(?:given|requested|specified) by the user",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Sentences that name a count only as a prohibition on conflicting writes, not as staffing. Path suffix to the exact phrase. */
    private static final java.util.Map<String, String> HEADCOUNT_NOT_STAFFING = java.util.Map.of(
            "skills/coordinate-subagents/references/delegation-playbook.md", "two workers");

    private static List<String> fixedHeadcounts(String text) {
        List<String> found = new ArrayList<>();
        for (String sentence : text.split("(?<=[.。])\\s+|\\R")) {
            if (USER_GIVEN.matcher(sentence).find()) {
                continue;
            }
            java.util.regex.Matcher matcher = FIXED_HEADCOUNT.matcher(sentence);
            while (matcher.find()) {
                found.add(matcher.group());
            }
        }
        return found;
    }

    @Test
    void theHeadcountPatternCatchesFixedStaffingAndNotTheCountsThatAreNotStaffing() {
        for (String fixed : List.of(
                "reviewer(DecisionRecord 계약이 확인하는 하한은 두 명)와 원자료",
                "필요하면 specialist reserve 1명으로 정해",
                "origin이 아닌 reviewer를 한 명 이상 지정하고",
                "별도 fresh Judge 1명을 추가한다",
                "Assign one reviewer per failure mode",
                "Use two independent reviewers",
                "at least two auditors",
                "worker 3명을 둔다")) {
            assertEquals(1, fixedHeadcounts(fixed).size(), fixed);
        }
        for (String notStaffing : List.of(
                "worker를 한 명도 instantiate하지 않는다",
                "worker 0명, `consensus_proposal: null`을 기록한다",
                "고정 명칭이나 실제 대상을 가리킬 때",
                "한 명령으로 묶는다",
                "If one specialist skill suffices, use it directly",
                "Stage 6 specialist admission",
                "사용자가 준 3명을 worker cap으로 기록한다",
                "the cap is the number given by the user, such as 3 reviewers requested by the user",
                "reviewer 수는 Coordinator가 판단한다",
                "unique case-insensitively, at most 64 MiB in total.",
                "at least 512 KiB must stay free",
                "at most 2 GiB per file",
                "at most 1024 bytes",
                "at most 8 KB")) {
            assertEquals(List.of(), fixedHeadcounts(notStaffing), notStaffing);
        }
    }

    @Test
    void noSkillTextFixesAHeadcountForTheModel() throws IOException {
        Path skills = REPO.resolve("skills");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(skills)) {
            for (Path file : files.filter(Files::isRegularFile).filter(candidate -> candidate.toString().endsWith(".md")).toList()) {
                String relative = REPO.relativize(file).toString().replace('\\', '/');
                if (relative.contains("/evals/")) {
                    continue;
                }
                List<String> counts = new ArrayList<>(fixedHeadcounts(Files.readString(file, StandardCharsets.UTF_8)));
                String allowed = HEADCOUNT_NOT_STAFFING.get(relative);
                if (allowed != null) {
                    counts.remove(allowed);
                }
                counts.forEach(count -> found.add(relative + ": " + count));
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    void aValidAndEligibleDecisionRecordPassesTheGate() throws IOException {
        assertDoesNotThrow(() -> {
            gates.assertDeliberationGate(deliberation(), decisionResult(high()), decisionReads);
        });
    }

    @Test
    void aStageThatRequiresNoDecisionRecordIsNotJudgedByThisGate() {
        WorkflowPlan.Stage stage = deliberation(List.of(), decisionSchema);
        assertFalse(gates.assertDeliberationGate(stage, decisionResult(MAPPER.createObjectNode()), FileReads.NONE), "a stage with no decision record has no conditions to list");
    }

    @Test
    void aProviderThatDeclaresNoRecordSchemaFailsTheGate() throws IOException {
        WorkflowPlan.Stage stage = deliberation(List.of("decision-record"), null);
        ObjectNode record = high();
        assertEquals(WorkflowCode.GATE_FAILED, code(() -> gates.assertDeliberationGate(stage, decisionResult(record), FileReads.NONE)));
    }

    @Test
    void aRecordThatIsNotSchemaValidOrNotSemanticallyValidFailsTheGate() throws IOException {
        assertEquals(WorkflowCode.GATE_FAILED, code(() -> gates.assertDeliberationGate(deliberation(), decisionResult(MAPPER.createObjectNode().put("schema_version", "1.0")), decisionReads)));
        List<WorkflowCode> refused = new ArrayList<>();
        for (Path file : invalidDecisions()) {
            JsonNode record = read(file);
            refused.add(code(() -> gates.assertDeliberationGate(deliberation(), decisionResult(record), decisionReads)));
        }
        assertEquals(List.of(WorkflowCode.GATE_FAILED), refused.stream().distinct().toList());
    }

    @Test
    void aRecordThatCannotAdvanceTheWorkflowFailsTheGate() throws IOException {
        ObjectNode shortfall = high();
        ((ObjectNode) shortfall.get("run")).put("capability_shortfall", true);
        assertEquals(WorkflowCode.GATE_FAILED, code(() -> gates.assertDeliberationGate(deliberation(), decisionResult(shortfall), decisionReads)));

        ObjectNode provisional = high();
        ((ObjectNode) provisional.get("run")).put("assurance", "provisional");
        assertEquals(WorkflowCode.GATE_FAILED, code(() -> gates.assertDeliberationGate(deliberation(), decisionResult(provisional), decisionReads)));

        ObjectNode notStrict = high();
        ((ObjectNode) notStrict.get("run")).put("strict", false);
        assertEquals(WorkflowCode.GATE_FAILED, code(() -> gates.assertDeliberationGate(deliberation(), decisionResult(notStrict), decisionReads)));
    }

    // -------------------------------------------------------------------------------------------------------- the provider reports

    private static WorkflowPlan.Stage securityStage() {
        return PlanSamples.stage("stage-01", "software-security-audit", List.of(), PlanSamples.openGate(), null);
    }

    private static ObjectNode securityReport() {
        String source = "export const value = 'synthetic';\n";
        ObjectNode target = TestJson.object("identifier", "fixture", "baseRef", null, "files", TestJson.array(List.of(TestJson.object("path", "target.txt", "digest", TestJson.hashText(source)))));
        ObjectNode request = TestJson.object("schemaVersion", "1.0.0", "target", target, "mode", "repository", "profiles", TestJson.array(List.of("web-api", "cli-mcp")), "excluded", TestJson.array(List.of()),
                "environment", "isolated synthetic fixture", "authorization", TestJson.object("localReproduction", false, "constraints", TestJson.array(List.of("static only"))), "checks",
                TestJson.array(List.of(TestJson.object("id", "C1", "description", "input reachability"))), "providedEvidence", TestJson.array(List.of()));
        String targetDigest = TestJson.hashJson(target);
        return TestJson.object("schemaVersion", "1.0.0", "request", request, "requestDigest", TestJson.hashJson(request), "targetDigest", targetDigest, "status", "complete", "threatModel",
                TestJson.object("assets", TestJson.array(List.of("synthetic data")), "actors", TestJson.array(List.of("user")), "entrypoints", TestJson.array(List.of("function")), "trustBoundaries",
                        TestJson.array(List.of("input to effect"))),
                "checks", TestJson.array(List.of(TestJson.object("id", "C1", "status", "checked", "evidenceRefs", TestJson.array(List.of("E1")), "observation", "inspected fixture", "method", "static-analysis"))),
                "findings", TestJson.array(List.of()), "evidence", TestJson.array(List.of(TestJson.object("id", "E1", "path", "target.txt", "digest", TestJson.hashText(source), "targetDigest", targetDigest,
                        "kind", "source", "description", "synthetic source"))),
                "limitations", TestJson.array(List.of()));
    }

    @Test
    void aConsistentSecurityReportPassesTheProviderCheck() {
        assertDoesNotThrow(() -> provider.check(securityStage(), result(securityReport()), "task", "sha256:task", FileReads.NONE));
    }

    @Test
    void aSecurityReportThatDoesNotHoldTogetherIsRefused() {
        ObjectNode unlinked = securityReport();
        ((ObjectNode) unlinked.get("checks").get(0)).putArray("evidenceRefs").add("NO-SUCH-EVIDENCE");
        WorkflowFailure refusal = assertThrows(WorkflowFailure.class, () -> provider.check(securityStage(), result(unlinked), "task", "sha256:task", FileReads.NONE));
        assertEquals(WorkflowCode.INVALID_INPUT, refusal.code());
        assertEquals("workflow.provider.securityAuditReportInconsistent", refusal.ref().key());
        assertEquals("software-security-audit", refusal.details().get("validator"));
        ObjectNode stale = securityReport();
        stale.put("requestDigest", "sha256:" + "0".repeat(64));
        assertEquals(WorkflowCode.INVALID_INPUT, code(() -> provider.check(securityStage(), result(stale), "task", "sha256:task", FileReads.NONE)));
    }

    @Test
    void noSecurityReportAtAllIsRefusedWithItsOwnSentence() {
        WorkflowFailure refusal = assertThrows(WorkflowFailure.class, () -> provider.check(securityStage(), result(MAPPER.nullNode()), "task", "sha256:task", FileReads.NONE));
        assertEquals(WorkflowCode.INVALID_INPUT, refusal.code());
        assertEquals("workflow.provider.securityAuditReportOutputMissing", refusal.ref().key());
    }

    @Test
    void aSecurityReportIsNeverAcceptedWithoutTheCheck() {
        ProviderSemantics unprepared = new ProviderSemantics(provide(null), MAPPER);
        assertEquals(WorkflowCode.MCP_UNAVAILABLE, code(() -> unprepared.check(securityStage(), result(securityReport()), "task", "sha256:task", FileReads.NONE)));
    }

    @Test
    void aSecurityAuditStageIsHeldToTheSecurityRulesWhetherAGateOrTheCapabilityNamesThem() {
        ObjectNode unlinked = securityReport();
        ((ObjectNode) unlinked.get("checks").get(0)).putArray("evidenceRefs").add("NO-SUCH-EVIDENCE");
        WorkflowPlan.Stage named = PlanSamples.stage("stage-01", "software-security-audit", List.of(), PlanSamples.openGate(), null);
        WorkflowPlan.Stage other = PlanSamples.stage("stage-01", "something-else", List.of(), new WorkflowPlan.Gate(WorkflowPlan.GateKind.COMPLETION, RiskGate.NONE, "software-security-audit", null), null);
        assertEquals(WorkflowCode.INVALID_INPUT, code(() -> provider.check(named, result(unlinked), "task", "sha256:task", FileReads.NONE)));
        assertEquals(WorkflowCode.INVALID_INPUT, code(() -> provider.check(other, result(unlinked), "task", "sha256:task", FileReads.NONE)));
        assertDoesNotThrow(() -> provider.check(PlanSamples.stage("stage-01", "minimal-implementation", List.of(), PlanSamples.openGate(), null), result(unlinked), "task", "sha256:task", FileReads.NONE),
                "a stage with no validator is not a security stage");
    }

    /** An acceptance stage's result with the frozen request ({@code request}) the attachment {@link #REQUEST_ATTACHMENT} holds. */
    private record Acceptance(WorkflowPlan.Stage stage, StageResult result, String taskId, String taskDigest, JsonNode request) {
    }

    private static Acceptance acceptance(String targetOverride) throws IOException {
        ObjectNode request = copy(read(REPO.resolve("tests/acceptance-evidence-validator/fixtures/passing.json")));
        var bindings = request.putArray("criterionBindings");
        int number = 1;
        for (JsonNode statement : request.get("taskEnvelope").get("acceptanceCriteria")) {
            bindings.add(TestJson.object("criterionId", String.format("AC-%03d", number), "statement", statement.asString()));
            number += 1;
        }
        JsonNode report = skill("acceptance-evidence-validator", "analyze", "input", request);
        assertEquals("PASS", report.get("verdict").asString(), report.toString());
        String uploadedDigest = digestOfUpload(request);
        String target = request.get("target").get("digest").asString();
        TaskEnvelope.ArtifactContract contract = new TaskEnvelope.ArtifactContract("acceptance-evidence-validation",
                List.of(new TaskEnvelope.ProducedArtifact("acceptance-evidence-request", "acceptance-evidence-request.v1", targetOverride == null ? target : targetOverride)));
        WorkflowPlan.Stage stage = PlanSamples.stage("stage-01", "acceptance-evidence-validation", List.of(), PlanSamples.openGate(), contract);
        StageResult result = result(report, artifact("acceptance-evidence-request", new AttachmentFileRef(REQUEST_ATTACHMENT, 0), uploadedDigest, target));
        return new Acceptance(stage, result, request.get("taskEnvelope").get("taskId").asString(), CanonicalJson.digest(request.get("taskEnvelope")), request);
    }

    @Test
    void anAcceptanceReportBoundToItsFrozenRequestPasses() throws IOException {
        Acceptance given = acceptance(null);
        FileReads reads = providerReads(given.stage(), given.result(), given.taskDigest(), given.request());
        assertDoesNotThrow(() -> provider.check(given.stage(), given.result(), given.taskId(), given.taskDigest(), reads));
    }

    @Test
    void theFrozenRequestIsAskedForAsTheDocumentOfTheAttachmentTheResultNamesWithTheDigestItDeclares() throws IOException {
        Acceptance given = acceptance(null);
        ProviderArtifact declared = given.result().output().artifacts().get(0);
        assertEquals(java.util.Optional.of(new ReadRequest.Document(new AttachmentFileRef(REQUEST_ATTACHMENT, 0), declared.digest())), provider.request(given.stage(), given.result(), given.taskDigest()));
    }

    /** The frozen request may be any file of its attachment: the request names the file the artifact names, and another file of the attachment is another request. */
    @Test
    void theFrozenRequestIsAskedForByTheFileNumberTheArtifactNames() throws IOException {
        Acceptance given = acceptance(null);
        ProviderArtifact declared = given.result().output().artifacts().get(0);
        StageResult second = withArtifacts(given.result(), new ProviderArtifact(declared.artifactId(), declared.schemaId(), declared.attachmentId(), 1, declared.digest(), declared.targetDigest(), declared.verified()));
        ReadRequest.Document request = provider.request(given.stage(), second, given.taskDigest()).orElseThrow();
        assertEquals(new AttachmentFileRef(REQUEST_ATTACHMENT, 1), request.file());
        assertEquals(declared.digest(), request.expectedDigest());
        FileReads reads = providerReads(given.stage(), second, given.taskDigest(), given.request());
        assertDoesNotThrow(() -> provider.check(given.stage(), second, given.taskId(), given.taskDigest(), reads));
    }

    @Test
    void anAcceptanceReportThatDiffersFromItsRequestIsRefused() throws IOException {
        Acceptance given = acceptance(null);
        ((ObjectNode) given.result().output().output()).put("verdict", "BLOCKED");
        FileReads reads = providerReads(given.stage(), given.result(), given.taskDigest(), given.request());
        assertEquals(WorkflowCode.INVALID_INPUT, code(() -> provider.check(given.stage(), given.result(), given.taskId(), given.taskDigest(), reads)));
    }

    @Test
    void aSelfConsistentAcceptanceReportAboutAnotherTaskOrTargetIsRefused() throws IOException {
        Acceptance given = acceptance(null);
        FileReads reads = providerReads(given.stage(), given.result(), given.taskDigest(), given.request());
        assertEquals(WorkflowCode.BINDING_INVALID, code(() -> provider.check(given.stage(), given.result(), "another-task", given.taskDigest(), reads)));
        String otherDigest = "sha256:" + "0".repeat(64);
        FileReads otherDigestReads = providerReads(given.stage(), given.result(), otherDigest, given.request());
        assertEquals(WorkflowCode.BINDING_INVALID, code(() -> provider.check(given.stage(), given.result(), given.taskId(), otherDigest, otherDigestReads)));
        Acceptance otherTarget = acceptance("sha256:" + "0".repeat(64));
        FileReads otherTargetReads = providerReads(otherTarget.stage(), otherTarget.result(), otherTarget.taskDigest(), otherTarget.request());
        assertEquals(WorkflowCode.BINDING_INVALID, code(() -> provider.check(otherTarget.stage(), otherTarget.result(), otherTarget.taskId(), otherTarget.taskDigest(), otherTargetReads)));
    }

    @Test
    void anAcceptanceReportNeedsASignedTaskAndAVerifiedRequest() throws IOException {
        Acceptance given = acceptance(null);
        FileReads unsignedReads = providerReads(given.stage(), given.result(), null, given.request());
        assertEquals(WorkflowCode.BINDING_REQUIRED, code(() -> provider.check(given.stage(), given.result(), given.taskId(), null, unsignedReads)));
        ProviderArtifact declared = given.result().output().artifacts().get(0);
        StageResult unverified = withArtifacts(given.result(), new ProviderArtifact(declared.artifactId(), declared.schemaId(), declared.attachmentId(), declared.seq(), declared.digest(), declared.targetDigest(), false));
        FileReads unverifiedReads = providerReads(given.stage(), unverified, given.taskDigest(), given.request());
        assertEquals(WorkflowCode.MISSING_EVIDENCE, code(() -> provider.check(given.stage(), unverified, given.taskId(), given.taskDigest(), unverifiedReads)));
    }
}
