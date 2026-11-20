// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.context.AdminLog;
import io.github.jaeseongs95.sennard.server.context.JevAdviceGate;
import io.github.jaeseongs95.sennard.server.skills.JevReadiness.Missing;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Profile;

/** What is still missing before JEV can be asked: each way the chosen model, its key and the budget can fall short, and that the context layers ask it only through the interface they own. */
class JevReadinessTest {
    private static final double PER_CALL = 0.5;
    private static final AdminLog.JevModel OFFERED = new AdminLog.JevModel("acme", "small");
    private static final AdminLog.JevModel NOT_OFFERED = new AdminLog.JevModel("acme", "gone");
    private static final AdminLog.JevModel NOT_INSTALLED = new AdminLog.JevModel("other", "small");

    @Test
    void aReadinessIsTheGateTheContextLayersAsk() {
        JevAdviceGate gate = readiness(new JevBudget.UsageView(0, 0, 0));
        assertTrue(gate.ready(signed(OFFERED, 10.0), name -> true));
        assertFalse(gate.ready(signed(OFFERED, 10.0), name -> false));
    }

    @Test
    void nothingSignedMissesEverythingAndNoModelHasNoProfileToMeasureTheBudgetBy() {
        JevReadiness readiness = readiness(new JevBudget.UsageView(0, 0, 0));
        assertEquals(List.of(Missing.SWITCH_OFF, Missing.MODEL_NOT_SELECTED, Missing.TRANSMISSION_NOT_ALLOWED, Missing.KEY_MISSING, Missing.BUDGET_MISSING),
                readiness.missing(null, name -> false));
        assertEquals(List.of(Missing.MODEL_NOT_SELECTED), readiness.missing(signed(null, 10.0), name -> true));
    }

    @Test
    void aModelItsProviderDoesNotOfferOrNobodyInstalledIsUnsupported() {
        JevReadiness readiness = readiness(new JevBudget.UsageView(0, 0, 0));
        assertEquals(List.of(Missing.MODEL_UNSUPPORTED), readiness.missing(signed(NOT_OFFERED, 10.0), name -> true));
        assertEquals(List.of(Missing.MODEL_UNSUPPORTED), readiness.missing(signed(NOT_INSTALLED, 10.0), name -> true));
    }

    @Test
    void theKeyThatCountsIsTheChosenModelsProvidersAndWithoutAModelAnyProvidersKey() {
        JevReadiness readiness = readiness(new JevBudget.UsageView(0, 0, 0));
        assertEquals(List.of(Missing.KEY_MISSING), readiness.missing(signed(OFFERED, 10.0), "other-key"::equals));
        assertTrue(readiness.missing(signed(OFFERED, 10.0), "acme-key"::equals).isEmpty());
        assertEquals(List.of(Missing.MODEL_UNSUPPORTED), readiness.missing(signed(NOT_OFFERED, 10.0), "acme-key"::equals), "with no usable model any provider's key is enough");
    }

    @Test
    void theBudgetMustCoverOneMoreCallOfTheChosenModel() {
        assertEquals(List.of(Missing.BUDGET_BELOW_ONE_CALL), readiness(new JevBudget.UsageView(0, 0, 0)).missing(signed(OFFERED, PER_CALL / 2), name -> true));
        assertEquals(List.of(Missing.BUDGET_EXHAUSTED), readiness(new JevBudget.UsageView(0.25, 9.5, 20)).missing(signed(OFFERED, 10.0), name -> true));
        assertTrue(readiness(new JevBudget.UsageView(0, 9.5, 20)).missing(signed(OFFERED, 10.0), name -> true).isEmpty(), "exactly the limit is still covered");
        assertTrue(readiness(null).missing(signed(OFFERED, 10.0), name -> true).isEmpty(), "totals that cannot be read do not by themselves make JEV unavailable");
    }

    private static AdminLog.JevState signed(AdminLog.JevModel model, double limit) {
        return new AdminLog.JevState(true, limit, Map.of(), model, null, true, 1, "2026-11-14T00:00:00Z");
    }

    private static JevReadiness readiness(JevBudget.UsageView usage) {
        JevProviders providers = new JevProviders(List.of(new Acme()));
        JevBudget budget = new JevBudget(null, providers, null) {
            @Override
            public Optional<UsageView> view() {
                return Optional.ofNullable(usage);
            }
        };
        return new JevReadiness(providers, budget);
    }

    /** A provider that offers one model; everything a call needs is out of this test's reach. */
    private static final class Acme implements JevProvider {
        @Override
        public String providerId() {
            return "acme";
        }

        @Override
        public String label() {
            return "Acme";
        }

        @Override
        public String endpoint() {
            return "http://localhost/acme";
        }

        @Override
        public String credentialName() {
            return "acme-key";
        }

        @Override
        public double maximumCostUsd() {
            return PER_CALL;
        }

        @Override
        public double estimateUsd(long inputTokens) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Profile> profileFor(String modelId) {
            return "small".equals(modelId) ? Optional.of(new Profile("acme-small", null, "acme", "small", null, null, null, null, null, null, null, 0, 0, PER_CALL, null, null)) : Optional.empty();
        }

        @Override
        public int projectionBytes(SkillTypes.Request request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Evaluated classify(SkillTypes.Request request, Profile profile, io.github.jaeseongs95.sennard.domain.secret.SecretValue key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CatalogSnapshot catalog(io.github.jaeseongs95.sennard.domain.secret.SecretValue key, long nowMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TransmittedFields transmittedFields() {
            throw new UnsupportedOperationException();
        }
    }
}
