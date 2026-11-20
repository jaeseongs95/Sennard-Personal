// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The digest and the canonical text of a typed document. The canonical form is defined over a tree (sorted keys, no spaces, fixed number text), so a record
 * is written once with the application's mapper and that tree is canonicalized; the key order and the mapper's formatting therefore do not reach the digest.
 */
@Component
public class DocumentDigests {
    private final JsonMapper mapper;

    public DocumentDigests(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** {@code sha256:} and the hex digest of the canonical form of the document. */
    public String of(Object document) {
        return CanonicalJson.digest(mapper.valueToTree(document));
    }

    /** The canonical text of the document. */
    public String canonical(Object document) {
        return CanonicalJson.of(mapper.valueToTree(document));
    }

    /** A document's digest and its JSON text, made from one writing of the document. */
    public record Sealed(String digest, String json) {
    }

    /** The digest and the JSON text of a document, for a stored document whose digest is kept with it: the document is written once for both. */
    public Sealed seal(Object document) {
        JsonNode tree = mapper.valueToTree(document);
        return new Sealed(CanonicalJson.digest(tree), mapper.writeValueAsString(tree));
    }

    /** The {@code integrityToken} of a signed document: the HMAC of its canonical form without the token. */
    public String integrityToken(Object document, byte[] key) {
        return CanonicalJson.integrityToken(mapper.valueToTree(document), key);
    }

    /** The digests of a root's inputs: the task, the frame, and the four parts of the frame. */
    ConvergenceDigests convergence(TaskEnvelope task, ConvergenceFrame frame) {
        return convergence(of(task), frame);
    }

    /** The digests of a root's inputs for a task whose digest is already known. */
    ConvergenceDigests convergence(String taskDigest, ConvergenceFrame frame) {
        return new ConvergenceDigests(of(frame), of(frame.workspace()), of(frame.controlArtifacts()), of(frame.targetArtifacts()), of(frame.operationalSettings()), taskDigest);
    }
}
