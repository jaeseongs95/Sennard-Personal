// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static io.github.jaeseongs95.sennard.server.message.MessageRef.msg;
import static io.github.jaeseongs95.sennard.server.workflow.WorkflowFailure.details;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.attachment.AttachmentContents;
import io.github.jaeseongs95.sennard.app.attachment.AttachmentFileRef;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reads of the workflow tools: what a use case judges from files and attachments is read here, by the entry, before the use case's transaction opens, and handed to the use case as
 * {@link FileReads}. A transaction is held for rows; a read inside it holds the connection (and the row locks) for as long as the disk takes, and a rollback cannot undo it. This class
 * decides nothing: it answers the requests of a {@link ReadPlan}, which the use case's read-only planning method made from the rows, and keeps each outcome (a value or the failure of
 * the read) for the use case to judge where it takes the value ({@link FileRead}). What it reads of a stage result is an attachment of the server's own store, by its id; it never
 * opens a path of the caller's computer (the design's principle 5).
 */
@Component
class WorkflowFileReader {
    /** The largest JSON document the server takes from an attachment (a stage output, a frozen request): provider outputs larger than this are not accepted by reference either. */
    static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;

    private final ContractValidator validator;
    private final SkillRegistry registry;
    private final ObjectProvider<SkillChecks> checks;
    private final HostIdentity hostIdentity;
    private final JsonMapper mapper;
    private final AttachmentContents contents;

    WorkflowFileReader(ContractValidator validator, SkillRegistry registry, ObjectProvider<SkillChecks> checks, HostIdentity hostIdentity, JsonMapper mapper, AttachmentContents contents) {
        this.hostIdentity = hostIdentity;
        this.validator = validator;
        this.registry = registry;
        this.checks = checks;
        this.mapper = mapper;
        this.contents = contents;
    }

    /** Answers every request of the plan; a request asked twice is read once. */
    FileReads read(ReadPlan plan) {
        Map<ReadRequest, FileRead<?>> answers = new LinkedHashMap<>();
        for (ReadRequest request : plan.requests()) {
            if (!answers.containsKey(request)) {
                answers.put(request, answer(request));
            }
        }
        return new FileReads(plan.revision(), answers);
    }

    private FileRead<?> answer(ReadRequest request) {
        return switch (request) {
            case ReadRequest.Document document -> FileRead.of(() -> load(document.file(), document.expectedDigest()));
            case ReadRequest.Stored stored -> FileRead.of(() -> contents.standing(stored.file().attachmentId(), stored.file().seq(), stored.sha256()));
            case ReadRequest.DeclaredSchema schema -> FileRead.of(() -> validator.bound(schema.reference(), schema.label()));
            case ReadRequest.BundleCheck check -> FileRead.of(() -> bundleCheck(check));
            case ReadRequest.HostId ignored -> FileRead.of(hostIdentity::id);
            case ReadRequest.RootIdentity identity -> FileRead.of(() -> WorkspaceIdentity.resolveRootIdentity(identity.workspaceLocator(), identity.entries(), identity.physicalOnly()));
        };
    }

    private ReadRequest.CheckAnswer bundleCheck(ReadRequest.BundleCheck request) {
        Optional<String> skill = request.providerKey() == null ? Optional.ofNullable(request.skill()) : declaredValidator(request.providerKey());
        return skill.map(name -> judged(name, request)).orElseGet(ReadRequest.NoValidator::new);
    }

    /**
     * Runs the skill's check over the bundle: the files are put in a folder of the server's own, each at the path its file name says, and the folder is removed when the check
     * has answered. The answer carries the path of each file, which the use case needs to bind the report artifact to the file the check read.
     */
    private ReadRequest.CheckAnswer judged(String skill, ReadRequest.BundleCheck request) {
        Optional<SkillChecks> skillChecks = checks.stream().findFirst();
        if (skillChecks.isEmpty()) {
            return new ReadRequest.SkillsModuleAbsent(skill);
        }
        try (BundleFolder folder = BundleFolder.open()) {
            Map<AttachmentFileRef, String> paths = new LinkedHashMap<>();
            for (AttachmentFileRef file : request.files()) {
                paths.put(file, put(folder, file));
            }
            ObjectNode arguments = mapper.createObjectNode();
            arguments.put("root", folder.root().toString());
            arguments.put("input", paths.get(request.bundle()));
            arguments.put("bundle-digest", request.bundleDigest());
            arguments.put("task-digest", request.taskDigest());
            if (request.capability() != null) {
                arguments.put("capability", request.capability());
            }
            return new ReadRequest.Checked(skill, skillChecks.get().run(skill, request.command(), arguments), Map.copyOf(paths));
        }
    }

    /** One file of the bundle, put in the folder at the path of its file name; the bytes still to be taken are bounded by what the folder may hold. */
    private String put(BundleFolder folder, AttachmentFileRef ref) {
        AttachmentContents.Named file;
        try {
            file = contents.readNamed(ref.attachmentId(), ref.seq(), (int) BundleFolder.MAX_BYTES);
        } catch (NotFoundException missing) {
            throw bundleFileUnusable(ref, "ATTACHMENT_NOT_FOUND", missing);
        } catch (UnavailableException refused) {
            if (refused.code() != UnavailableCode.ARTIFACT_LIMIT) {
                throw refused;
            }
            throw bundleFileUnusable(ref, "TOO_LARGE", refused);
        }
        return folder.put(ref.attachmentId(), file.fileName(), file.bytes());
    }

    private static WorkflowFailure bundleFileUnusable(AttachmentFileRef ref, String reason, Throwable cause) {
        return new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.bundle.fileNotUsable", "reason", reason), details("attachmentId", ref.attachmentId(), "seq", ref.seq(), "reason", reason), cause);
    }

    /** The stage validator the provider of this key declares in the registry as it is now; empty where it declares none or there is no such provider. */
    private Optional<String> declaredValidator(String providerKey) {
        return registry.read().stream().filter(provider -> Objects.equals(provider.providerKey(), providerKey)).findFirst().map(SkillProvider::stageValidator);
    }

    /** The JSON object a file of an attachment holds after its digest was checked; the caller validates it exactly like inline output. */
    private JsonNode load(AttachmentFileRef ref, String expectedDigest) {
        byte[] bytes;
        try {
            bytes = contents.readWhole(ref.attachmentId(), ref.seq(), MAX_DOCUMENT_BYTES);
        } catch (NotFoundException missing) {
            throw unreadable(ref, missing);
        } catch (UnavailableException refused) {
            // Only the size limit is the attachment's own fault here; a storage or key failure is the server's and ends the call as it is.
            if (refused.code() != UnavailableCode.ARTIFACT_LIMIT) {
                throw refused;
            }
            throw unreadable(ref, refused);
        }
        String digest = "sha256:" + Digests.sha256Hex(bytes);
        if (!Objects.equals(digest, expectedDigest)) {
            // The attachment's actual digest is not echoed back.
            throw new WorkflowFailure(WorkflowCode.INTEGRITY_FAILED, msg("workflow.outputFile.outputFileContentMatchDigest"), details("attachmentId", ref.attachmentId(), "seq", ref.seq(), "expected", expectedDigest));
        }
        JsonNode parsed;
        try {
            // Reading the bytes lets Jackson see a byte order mark, which Windows tools may write, as the encoding mark it is.
            parsed = mapper.readTree(bytes);
        } catch (JacksonException cause) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.outputFile.outputFileValidJSON"), details("attachmentId", ref.attachmentId(), "seq", ref.seq()), cause);
        }
        if (!parsed.isObject()) {
            throw new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.outputFile.outputFileContainJSONObject"), details("attachmentId", ref.attachmentId(), "seq", ref.seq()));
        }
        return parsed;
    }

    private static WorkflowFailure unreadable(AttachmentFileRef ref, Throwable cause) {
        return new WorkflowFailure(WorkflowCode.INVALID_INPUT, msg("workflow.outputFile.outputFileAttachmentReadable"), details("attachmentId", ref.attachmentId(), "seq", ref.seq()), cause);
    }
}
