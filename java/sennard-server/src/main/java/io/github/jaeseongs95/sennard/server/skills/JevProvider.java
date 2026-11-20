// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonValue;

import io.github.jaeseongs95.sennard.domain.secret.SecretValue;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Diagnostics;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.DispatchState;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Profile;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.ReasonCode;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Request;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Response;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Usage;

/**
 * What the classification service asks of a JEV provider: the advice layer's one outward contract. A provider owns everything of its own wire (address, paths,
 * request and answer shapes, the models it names, what a call costs); the service and the admin page know it only through this interface and by its
 * {@link #providerId()}. The user picks a provider's model, allows sending, sets the budget and registers the provider's key on the admin page. JEV is an advice
 * layer: no qualification gate comes before a call, and when a provider cannot advise the agent chooses by itself.
 *
 * <p>A provider makes one call per {@link #classify} and never retries it: whether a failed call is tried again, and where else it goes, is the service's decision
 * ({@link JevRetry}, {@link JevFailureKind}), because every try has to be reserved against, and settled with, the budget.
 */
public interface JevProvider {
    /** How long one provider call may take; every provider's HTTP client is built with it. */
    long CALL_TIMEOUT_MS = 30_000;

    /** The provider's identifier as the signed setting names it ({@code [a-z0-9-]{1,60}}). */
    String providerId();

    /** The provider's name as the admin page shows it. */
    String label();

    /** The address a classification call is sent to, for the admin page's list of what is transmitted. */
    String endpoint();

    /** The name of the key the user registers on the admin page for this provider. */
    String credentialName();

    /** The most one call may cost, in USD: the ceiling the budget reserves before the call is sent. */
    double maximumCostUsd();

    /** What the model's input tokens cost, in USD, as an estimate (a provider's answer carries no billed amount). */
    double estimateUsd(long inputTokens);

    /** The profile of a chosen model; empty when this provider does not offer it (a model its own list no longer names is not offered). */
    Optional<Profile> profileFor(String modelId);

    /** The size, in bytes, of the semantic part of the request a model receives. */
    int projectionBytes(Request request);

    /** One synchronous call; runs outside any transaction, on the tool's own virtual thread. The reason it did not answer is a returned {@link Failure}. */
    Evaluated classify(Request request, Profile profile, SecretValue key);

    /** The models the provider offers, for the admin page's choice. It never changes a model the user signed. */
    CatalogSnapshot catalog(SecretValue key, long nowMs);

    /** The members of what one call sends, as the admin page lists them. */
    TransmittedFields transmittedFields();

    /**
     * What one provider call came to: an answer that was read, or the reason it was not. The call is a sequence of checks that each may end it (credential, request, size,
     * transport, status, answer), so the reason is a returned value and not an exception thrown from the middle of the sequence.
     */
    sealed interface Evaluated permits Evaluation, Failure {
    }

    record Evaluation(Response response, Usage usage, DispatchState dispatchState, Diagnostics diagnostics) implements Evaluated {
    }

    /**
     * A call that ended without an answer; {@code dispatchState} says how far the call got. {@code retryAfter} is the provider's {@code Retry-After} header as it was sent
     * (null when it sent none). The cause of a failure is logged where it is met.
     */
    record Failure(ReasonCode code, DispatchState dispatchState, boolean invalid, int httpStatus, String retryAfter) implements Evaluated {
        public Failure(ReasonCode code, DispatchState dispatchState, boolean invalid) {
            this(code, dispatchState, invalid, 0, null);
        }
    }

    /** Whether the offered models are the bundled ones, were just listed, could not be listed, or are not asked for without a key. */
    enum CatalogStatus {
        BUNDLED("bundled"), NO_KEY("no-key"), CURRENT("current"), UNAVAILABLE("unavailable");

        private final String wire;

        CatalogStatus(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    record CatalogSnapshot(String providerId, CatalogStatus status, String updatedAt, int refreshHours, List<String> models) {
    }

    record TransmittedFields(List<String> request, List<String> task, List<String> perSkill) {
    }
}
