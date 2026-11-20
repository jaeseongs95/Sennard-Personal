// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The cs-engineering example ({@code skills/cs-engineering/assets/examples/claim-queue}) as a chain of documents whose digests hold. A case that changes a file or a
 * document of the chain runs on a copy whose digests are frozen again ({@link #refreeze}); the documents and the candidate are the example's own.
 */
public final class CsFrozenExample {
    private static final Path EXAMPLE = Path.of(System.getProperty("sennard.repo.root")).resolve("skills/cs-engineering/assets/examples/claim-queue");

    private CsFrozenExample() {
    }

    private static ObjectNode object(Path dir, String name) throws IOException {
        return (ObjectNode) TestJson.MAPPER.readTree(Files.readString(dir.resolve(name), StandardCharsets.UTF_8));
    }

    private static void write(Path dir, String name, JsonNode document) throws IOException {
        Files.writeString(dir.resolve(name), TestJson.MAPPER.writeValueAsString(document) + "\n", StandardCharsets.UTF_8);
    }

    private static String rawDigest(Path dir, String relative) throws IOException {
        return TestJson.hashBytes(Files.readAllBytes(dir.resolve(relative)));
    }

    public static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : files.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }

    /** A copy of the example, its digests written again from the files of the copy (a case changes a file or a document of the copy and the digests follow). */
    public static Path frozen(Path where) throws IOException {
        copyTree(EXAMPLE, where);
        refreeze(where);
        return where;
    }

    /**
     * Writes every digest a directory's documents hold again, from the files and documents as they are now, in the order the chain is built: sources and the candidate's files,
     * the request, the constraint report, the candidate, the review, the binding and the stage bundle. What a case changed in a document's meaning stays; only the digests follow.
     */
    public static void refreeze(Path dir) throws IOException {
        ObjectNode requestDocument = object(dir, "request.json");
        for (JsonNode source : requestDocument.get("sourceRefs")) {
            ((ObjectNode) source).put("digest", rawDigest(dir, source.get("path").stringValue()));
        }
        requestDocument.put("sourceSnapshotDigest", TestJson.hashJson(requestDocument.get("sourceRefs")));
        write(dir, "request.json", requestDocument);

        ObjectNode constraintDocument = object(dir, "constraints.json");
        constraintDocument.put("requestDigest", TestJson.hashJson(requestDocument));
        constraintDocument.put("sourceSnapshotDigest", requestDocument.get("sourceSnapshotDigest").stringValue());
        write(dir, "constraints.json", constraintDocument);

        ObjectNode candidateDocument = object(dir, "candidate.json");
        for (JsonNode file : candidateDocument.get("files")) {
            ((ObjectNode) file).put("digest", rawDigest(dir, file.get("path").stringValue()));
        }
        write(dir, "candidate.json", candidateDocument);
        String candidateDigest = TestJson.hashJson(candidateDocument);

        ObjectNode reviewDocument = object(dir, "review.json");
        reviewDocument.put("constraintReportDigest", TestJson.hashJson(constraintDocument));
        reviewDocument.put("candidateDigest", candidateDigest);
        for (JsonNode result : reviewDocument.get("verificationResults")) {
            ((ObjectNode) result).put("targetDigest", candidateDigest);
        }
        for (JsonNode item : reviewDocument.get("evidence")) {
            ((ObjectNode) item).put("targetDigest", candidateDigest);
            ((ObjectNode) item).put("digest", rawDigest(dir, item.get("path").stringValue()));
        }
        write(dir, "review.json", reviewDocument);

        ObjectNode bindingDocument = object(dir, "binding.json");
        ((ObjectNode) bindingDocument.get("requestRef")).put("digest", rawDigest(dir, "request.json"));
        bindingDocument.put("requestDigest", TestJson.hashJson(requestDocument));
        ((ObjectNode) bindingDocument.get("constraintReportRef")).put("digest", rawDigest(dir, "constraints.json"));
        bindingDocument.put("constraintReportDigest", TestJson.hashJson(constraintDocument));
        bindingDocument.put("taskDigest", TestJson.hashJson(object(dir, "task.json")));
        bindingDocument.put("policyDigest", TestJson.hashJson(object(dir, "policy.json")));
        write(dir, "binding.json", bindingDocument);

        ObjectNode bundle = object(dir, "stage-bundle.json");
        for (String name : List.of("binding", "task", "policy", "review", "candidate")) {
            ((ObjectNode) bundle.get(name)).put("digest", rawDigest(dir, bundle.get(name).get("path").stringValue()));
        }
        write(dir, "stage-bundle.json", bundle);
    }
}
