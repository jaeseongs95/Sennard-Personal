// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentContents;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.server.workflow.WorkspaceIdentity.Identity;
import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a use case judges is read by the entry before its transaction and handed over as answers. This class covers the answers that need no attachment: the schema a plan froze, the
 * identity of this machine and the locations of a root, from the real file system and the real contract files; a read that failed is kept and raised where the use case takes the
 * value, not before; a read nobody asked for is the use case's mistake, not an answer; the locations of a root nobody resolved make the call stale. The reads of an attachment (the
 * output file, a frozen request, the standing of an audited attachment, the files of a review bundle) need the attachment tables, so they are covered with real PostgreSQL by the server
 * tests ({@code serverit}); no stand-in of the attachments is made here. The two answers of a bundle's check that are given before any attachment is read (no stage validator, no
 * skills module) need none, so they are covered here with no attachment store behind the entry; {@code SkillBackedStageGatesTest} runs the real check over uploaded files.
 *
 * <pre>
 * w1  schema         the schema a plan froze is answered compiled; with another digest the failure is kept and raised at the take (STALE_REVISION), not when the plan is read
 * w2  not asked      a request the plan did not make is an IllegalStateException; the locations of a root nobody resolved are STALE_REVISION
 * w3  locations      a root's workspace and entries are resolved to physical paths; a request asked twice has one answer
 * w4  host           the identity of this machine is read from the state folder, once made it is the same
 * w5  bundle check   with no skills module the answer names the skill and ran nothing; a provider that declares no validator, or that no registry entry has, gives an answer with no skill
 * w6  by file        a read is asked and answered by the file of an attachment (attachment id and number): the answer for file 0 is no answer for file 1, and two files of one attachment
 *                    are two requests, two bundle files, two keys of the paths of a check
 * </pre>
 */
class WorkflowFileReaderTest {
    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final String SCHEMA = "contracts/provider-result.v1.schema.json";
    private static final String ATTACHMENT = "44444444-4444-4444-8444-444444444444";
    private static PlanSamples.Wiring wiring;

    @TempDir
    Path tmp;

    @BeforeAll
    static void wire() throws IOException {
        wiring = PlanSamples.wire();
    }

    private static ObjectProvider<SkillChecks> noSkillChecks() {
        return new StaticListableBeanFactory().getBeanProvider(SkillChecks.class);
    }

    /**
     * The entry over the real validator and registry, no skills module, and a host identity in the test's state folder. The requests of these tests ask for no attachment to be read
     * (a bundle's check is answered before its attachments are read where there is no skills module or no validator), so the entry is given no attachment store.
     */
    private WorkflowFileReader reader() {
        return new WorkflowFileReader(wiring.validator(), wiring.registry(), noSkillChecks(), new HostIdentity(tmp.resolve("state"), new LocalPrivateStorage()), MAPPER, null);
    }

    @Test
    void theSchemaAPlanFrozeIsAnsweredAndAFailedReadIsRaisedWhereTheValueIsTaken() throws IOException {
        WorkflowPlan.SchemaReference frozen = new WorkflowPlan.SchemaReference(SCHEMA, "sha256:" + Digests.sha256Hex(Files.readAllBytes(PlanSamples.REPO.resolve(SCHEMA))));
        WorkflowPlan.SchemaReference changed = new WorkflowPlan.SchemaReference(SCHEMA, "sha256:" + "0".repeat(64));
        // Reading the plan raises nothing: the failure is kept in the answer.
        FileReads reads = reader().read(new ReadPlan(-1, List.of(new ReadRequest.DeclaredSchema(frozen, ContractValidator.PROVIDER_RESULT),
                new ReadRequest.DeclaredSchema(changed, ContractValidator.PROVIDER_RESULT))));
        assertNotNull(reads.schema(frozen, ContractValidator.PROVIDER_RESULT));
        assertEquals(WorkflowCode.STALE_REVISION, assertThrows(WorkflowFailure.class, () -> reads.schema(changed, ContractValidator.PROVIDER_RESULT)).code());
    }

    @Test
    void aReadNobodyAskedForIsTheUseCasesMistakeAndRootsNobodyResolvedAreStale() {
        FileReads none = FileReads.NONE;
        assertThrows(IllegalStateException.class, () -> none.document(new AttachmentFileRef(ATTACHMENT, 0), "sha256:" + "0".repeat(64)));
        assertThrows(IllegalStateException.class, () -> none.stored(new AttachmentFileRef(ATTACHMENT, 0), "sha256:" + "0".repeat(64)));
        assertThrows(IllegalStateException.class, () -> none.hostId());
        WorkflowFailure stale = assertThrows(WorkflowFailure.class, () -> none.identity(tmp.toString(), List.of("src"), false));
        assertEquals(WorkflowCode.STALE_REVISION, stale.code());
    }

    @Test
    void aReadIsAnsweredForTheFileOfTheAttachmentItAskedAboutAndNotForAnotherFileOfTheSameAttachment() {
        String digest = "sha256:" + "0".repeat(64);
        AttachmentFileRef first = new AttachmentFileRef(ATTACHMENT, 0);
        AttachmentFileRef second = new AttachmentFileRef(ATTACHMENT, 1);
        FileReads reads = new FileReads(-1, Map.of(new ReadRequest.Stored(first, digest), new FileRead<>(AttachmentContents.Standing.INTACT, null)));
        assertEquals(AttachmentContents.Standing.INTACT, reads.stored(first, digest));
        assertThrows(IllegalStateException.class, () -> reads.stored(second, digest), "file 1 was not asked for, so file 0's answer is no answer for it");
        assertThrows(IllegalStateException.class, () -> reads.document(first, digest), "a document is another question than the standing of the stored copy");
        assertEquals(new ReadRequest.Stored(first, digest), new ReadRequest.Stored(new AttachmentFileRef(ATTACHMENT, 0), digest));
        assertTrue(!new ReadRequest.Stored(first, digest).equals(new ReadRequest.Stored(second, digest)), "two files of one attachment are two requests");
        assertEquals(2, new ReadRequest.BundleCheck(null, "code-review", "check-stage-bundle", first, List.of(first, second), digest, digest, "change-code-review").files().size());
    }

    @Test
    void theLocationsOfARootAreResolvedOnceForARequestAskedTwice() throws IOException {
        Files.createDirectories(tmp.resolve("ws").resolve("src"));
        String workspace = tmp.resolve("ws").toString();
        ReadRequest.RootIdentity request = new ReadRequest.RootIdentity(workspace, List.of("src"), false);
        FileReads reads = reader().read(new ReadPlan(-1, List.of(request, request, new ReadRequest.RootIdentity(workspace, List.of("src"), true))));
        Identity strict = reads.identity(workspace, List.of("src"), false);
        assertEquals(tmp.resolve("ws").toRealPath(), strict.workspacePhysical());
        assertEquals(tmp.resolve("ws").resolve("src").toRealPath(), strict.surfaces().getFirst().physical());
        assertNotNull(reads.identity(workspace, List.of("src"), true));
    }

    @Test
    void theIdentityOfThisMachineIsReadFromTheStateFolderAndStaysTheSame() throws IOException {
        WorkflowFileReader reader = reader();
        String first = reader.read(new ReadPlan(-1, List.of(new ReadRequest.HostId()))).hostId();
        String second = reader.read(new ReadPlan(-1, List.of(new ReadRequest.HostId()))).hostId();
        assertTrue(first.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), first);
        assertEquals(first, second);
        assertEquals(first, Files.readString(tmp.resolve("state").resolve(HostIdentity.FILE), StandardCharsets.UTF_8).strip());
    }

    private static ReadRequest.BundleCheck bundleCheck(String providerKey, String skill, String capability) {
        String digest = "sha256:" + "0".repeat(64);
        return new ReadRequest.BundleCheck(providerKey, skill, "check-stage-bundle", new AttachmentFileRef(ATTACHMENT, 0), List.of(new AttachmentFileRef(ATTACHMENT, 0)), digest, digest, capability);
    }

    @Test
    void aBundleCheckWithNoSkillsModuleNamesTheSkillAndRanNothing() {
        List<SkillProvider> providers = wiring.registry().read();
        String csProvider = providers.stream().filter(provider -> provider.stageValidator() != null).findFirst().orElseThrow().providerKey();
        ReadRequest.BundleCheck engineering = bundleCheck(null, "code-review", "change-code-review");
        ReadRequest.BundleCheck cs = bundleCheck(csProvider, null, null);
        // Nothing is read of the attachment the request names: the entry holds no attachment store, and the answer is given all the same.
        FileReads reads = reader().read(new ReadPlan(-1, List.of(engineering, cs)));
        assertEquals(new ReadRequest.SkillsModuleAbsent("code-review"), reads.check(engineering), "the skills module is not there, so nothing was run");
        assertEquals(new ReadRequest.SkillsModuleAbsent("cs-engineering"), reads.check(cs), "the stage validator the provider declares is the skill that would judge");
    }

    @Test
    void aProviderWithNoValidatorOrNoProviderAtAllHasNoSkillToJudge() {
        String undeclared = wiring.registry().read().stream().filter(provider -> provider.stageValidator() == null).findFirst().orElseThrow().providerKey();
        ReadRequest.BundleCheck declaresNone = bundleCheck(undeclared, null, null);
        ReadRequest.BundleCheck unknownProvider = bundleCheck("no-such-skill:no-phase:1", null, null);
        FileReads reads = reader().read(new ReadPlan(-1, List.of(declaresNone, unknownProvider)));
        assertEquals(new ReadRequest.NoValidator(), reads.check(declaresNone), "the provider exists and declares no stage validator");
        assertEquals(new ReadRequest.NoValidator(), reads.check(unknownProvider), "no provider of that key declares a stage validator");
    }
}
