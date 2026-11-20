// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.trust;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import io.github.jaeseongs95.sennard.server.hook.HostContext;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import io.github.jaeseongs95.sennard.service.store.modules.TrustStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What this release can actually enforce about trust, and the provenance observations on a collaboration decision. It reports; it issues no
 * authority and attests no direct user input.
 */
@Service
public class TrustService {
    public record Capabilities(String schemaVersion, boolean provenanceRecording, boolean directUserInputAttestation, boolean authorityIssuance, boolean scopedDelegation,
            List<String> acceptedOrigins, List<String> acceptedAuthorityEffects) {
    }

    public record CallerObservation(String host, String sessionId, String actorKind, String observedBy, String assurance) {
    }

    public record AuthorityCapabilities(boolean directUserInputAttestation, boolean authorityIssuance, boolean scopedDelegation) {
    }

    public record Validation(String structuralValidity, List<String> structuralErrors, boolean receiptFound, String receiptIntegrity, Boolean receiptBoundToCaller, String receiptFreshness,
            Boolean sourceClaimMatch, CallerObservation callerObservation, String callerBindingAssurance, AuthorityCapabilities authorityCapabilities) {
    }

    /**
     * What a stored receipt says about itself, read after its integrity token was verified (so it is a receipt this server made). A field that is absent reads as empty text.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredReceipt(String host, String sessionId, String originKind, String expiresAt) {
        public StoredReceipt {
            host = host == null ? "" : host;
            sessionId = sessionId == null ? "" : sessionId;
            originKind = originKind == null ? "" : originKind;
            expiresAt = expiresAt == null ? "" : expiresAt;
        }
    }

    private static final Capabilities CAPABILITIES = new Capabilities("1.0.0", true, false, false, false, List.of("peer", "system", "developer", "project", "artifact"),
            List.of("none", "restrict-only"));

    private final TrustStore store;
    private final CollaborationDecisions decisions;
    private final JsonMapper mapper;
    private final Clock clock;

    public TrustService(TrustStore store, CollaborationDecisions decisions, JsonMapper mapper, Clock clock) {
        this.clock = clock;
        this.store = store;
        this.decisions = decisions;
        this.mapper = mapper;
    }

    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    /**
     * The start of the server: the per-database signing key exists afterwards. One use case, one transaction: it reads the key and, when there is none, INSERTs it, so the
     * request paths (a hook's claim) only read it. Two servers of one database that start at the very same moment may both find none; the primary key lets one INSERT win and
     * the other's start fails with the translated duplicate-key error (nothing catches it or runs the start again; a later start finds the key).
     */
    @Transactional
    public void createSigningKeyIfAbsent() {
        if (!store.hasSigningKey()) {
            store.insertSigningKey(Timestamps.format(clock.instant()));
        }
    }

    @Transactional(readOnly = true)
    public Validation validate(JsonNode decisionTree, SelfSession caller, String sessionId) {
        CollaborationDecisions.Checked checked = decisions.check(decisionTree);
        List<String> errors = checked.errors();
        boolean main = caller.actor().role() == HostContext.Role.ROOT;
        CallerObservation observation = new CallerObservation(caller.host(), sessionId, main ? "main" : "subagent", "host-observed", "observed");
        Boolean childClaim = !main && checked.claimsUserTurn() ? Boolean.FALSE : null;
        Validation base = new Validation(errors.isEmpty() ? "valid" : "invalid", errors, false, null, null, null, childClaim, observation, "observational",
                new AuthorityCapabilities(false, false, false));
        CollaborationDecisions.Decision decision = checked.decision();
        if (decision == null || decision.sourceReceiptId() == null) {
            return base;
        }
        TrustStore.KeyAndReceipt stored = store.readForVerification(decision.sourceReceiptId());
        if (stored.receiptJson() == null) {
            return base;
        }
        // A tree, because the integrity token signs the receipt's canonical form whatever fields it holds; its own fields are read from a record once it verified.
        JsonNode receipt = mapper.readTree(stored.receiptJson());
        boolean valid = verified(stored.signingKey(), receipt);
        StoredReceipt facts = valid ? mapper.treeToValue(receipt, StoredReceipt.class) : null;
        return new Validation(base.structuralValidity(), base.structuralErrors(), true, valid ? "valid" : "invalid",
                valid ? Boolean.valueOf(facts.host().equals(observation.host()) && facts.sessionId().equals(observation.sessionId())) : null,
                valid ? (StoreValues.parseIso(facts.expiresAt()) > clock.millis() ? "fresh" : "stale") : null,
                // A receipt can refine provenance, never undo an already rejected child claim.
                Boolean.FALSE.equals(childClaim) ? Boolean.FALSE : valid ? Boolean.valueOf(facts.originKind().equals(decision.sourceOriginKind().wire())) : null,
                observation, "observational", base.authorityCapabilities());
    }

    /** HMAC-SHA256 of the canonical receipt without its token, under the per-database key, compared in constant time as the token's own text (base64url, no padding). */
    private static boolean verified(SecretValue signingKey, JsonNode receipt) {
        JsonNode token = receipt.get("integrityToken");
        if (signingKey == null || token == null || !token.isString()) {
            return false;
        }
        String expected = CanonicalJson.integrityToken(receipt, signingKey);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), token.asString().getBytes(StandardCharsets.UTF_8));
    }
}
