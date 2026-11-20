// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.repotools;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes every digest of the cs-engineering knowledge pack and of its frozen example again from the files as they are now, in the order the chain is built (the checks of
 * {@code CsEngineeringCheck} read it the same way):
 * <ol>
 * <li>{@code assets/rule-catalog.json}: the digest of each rule is the canonical digest of its card in the module's rule file;</li>
 * <li>{@code assets/knowledge-lock.json}: the raw digest of each file it names; the pack digest is the canonical digest of this document;</li>
 * <li>the example: the sources and the candidate's files by raw digest, then the request, the constraint report (with the pack digest and the digests of the cards it
 * selects), the candidate, the review (with the digest of the evidence file), the binding and the stage bundle.</li>
 * </ol>
 * What the author wrote stays: only digests change. Nothing is changed in a check ({@code write} false); the files that differ are named.
 */
final class CsEngineeringLock {
    private static final String SKILL = "skills/cs-engineering/";
    private static final String EXAMPLE = SKILL + "assets/examples/claim-queue/";

    private final Repo repo;
    /** The documents as this run has them now, by repository path; what a later step reads is what an earlier step wrote. */
    private final Map<String, byte[]> changed = new LinkedHashMap<>();

    CsEngineeringLock(Repo repo) {
        this.repo = repo;
    }

    /** The files whose content differs from what the digests demand; with {@code write} they are rewritten. */
    List<String> sync(boolean write) {
        changed.clear();
        ObjectNode catalogue = (ObjectNode) json(SKILL + "assets/rule-catalog.json");
        Map<String, String> ruleDigests = new HashMap<>();
        for (JsonNode module : catalogue.path("modules")) {
            for (JsonNode card : json(SKILL + module.path("ruleFile").stringValue())) {
                ruleDigests.put(card.path("id").stringValue(), CanonicalJson.digest(card));
            }
        }
        for (JsonNode rule : catalogue.path("rules")) {
            String digest = ruleDigests.get(rule.path("id").stringValue());
            if (digest == null) {
                throw new RepoTools.Failed("the catalogue names a rule no card has: " + rule.path("id").stringValue());
            }
            ((ObjectNode) rule).put("digest", digest);
        }
        put(SKILL + "assets/rule-catalog.json", catalogue);

        ObjectNode lock = (ObjectNode) json(SKILL + "assets/knowledge-lock.json");
        for (JsonNode file : lock.path("files")) {
            ((ObjectNode) file).put("digest", rawDigest(SKILL + file.path("path").stringValue()));
        }
        put(SKILL + "assets/knowledge-lock.json", lock);
        String packDigest = CanonicalJson.digest(lock);

        ObjectNode request = (ObjectNode) json(EXAMPLE + "request.json");
        for (JsonNode source : request.path("sourceRefs")) {
            ((ObjectNode) source).put("digest", rawDigest(EXAMPLE + source.path("path").stringValue()));
        }
        request.put("sourceSnapshotDigest", CanonicalJson.digest(request.path("sourceRefs")));
        put(EXAMPLE + "request.json", request);

        ObjectNode constraints = (ObjectNode) json(EXAMPLE + "constraints.json");
        constraints.put("requestDigest", CanonicalJson.digest(request));
        constraints.put("sourceSnapshotDigest", request.path("sourceSnapshotDigest").stringValue());
        constraints.put("knowledgePackDigest", packDigest);
        for (JsonNode selected : constraints.path("selectedRules")) {
            ((ObjectNode) selected).put("ruleDigest", ruleDigests.get(selected.path("ruleId").stringValue()));
        }
        put(EXAMPLE + "constraints.json", constraints);

        ObjectNode candidate = (ObjectNode) json(EXAMPLE + "candidate.json");
        for (JsonNode file : candidate.path("files")) {
            ((ObjectNode) file).put("digest", rawDigest(EXAMPLE + file.path("path").stringValue()));
        }
        put(EXAMPLE + "candidate.json", candidate);
        String candidateDigest = CanonicalJson.digest(candidate);

        ObjectNode review = (ObjectNode) json(EXAMPLE + "review.json");
        review.put("constraintReportDigest", CanonicalJson.digest(constraints));
        review.put("candidateDigest", candidateDigest);
        for (JsonNode result : review.path("verificationResults")) {
            ((ObjectNode) result).put("targetDigest", candidateDigest);
        }
        for (JsonNode item : review.path("evidence")) {
            ((ObjectNode) item).put("targetDigest", candidateDigest);
            ((ObjectNode) item).put("digest", rawDigest(EXAMPLE + item.path("path").stringValue()));
        }
        put(EXAMPLE + "review.json", review);

        ObjectNode binding = (ObjectNode) json(EXAMPLE + "binding.json");
        ((ObjectNode) binding.path("requestRef")).put("digest", rawDigest(EXAMPLE + "request.json"));
        binding.put("requestDigest", CanonicalJson.digest(request));
        ((ObjectNode) binding.path("constraintReportRef")).put("digest", rawDigest(EXAMPLE + "constraints.json"));
        binding.put("constraintReportDigest", CanonicalJson.digest(constraints));
        binding.put("taskDigest", CanonicalJson.digest(json(EXAMPLE + "task.json")));
        binding.put("knowledgePackDigest", packDigest);
        binding.put("policyDigest", CanonicalJson.digest(json(EXAMPLE + "policy.json")));
        put(EXAMPLE + "binding.json", binding);

        ObjectNode bundle = (ObjectNode) json(EXAMPLE + "stage-bundle.json");
        for (String name : List.of("binding", "task", "policy", "review", "candidate")) {
            ((ObjectNode) bundle.path(name)).put("digest", rawDigest(EXAMPLE + bundle.path(name).path("path").stringValue()));
        }
        put(EXAMPLE + "stage-bundle.json", bundle);

        Map<String, byte[]> differing = new TreeMap<>();
        changed.forEach((path, content) -> {
            if (!Arrays.equals(content, repo.bytes(path))) {
                differing.put(path, content);
            }
        });
        if (write) {
            differing.forEach(repo::write);
        }
        return List.copyOf(differing.keySet());
    }

    private JsonNode json(String path) {
        try {
            return Repo.JSON.readTree(bytes(path));
        } catch (tools.jackson.core.JacksonException failure) {
            throw new IllegalStateException("invalid JSON " + path, failure);
        }
    }

    private void put(String path, JsonNode document) {
        changed.put(path, PrettyJson.text(document).getBytes(StandardCharsets.UTF_8));
    }

    private byte[] bytes(String path) {
        byte[] written = changed.get(path);
        return written != null ? written : repo.bytes(path);
    }

    private String rawDigest(String path) {
        return DirectoryChecksum.sha256Of(bytes(path));
    }
}
