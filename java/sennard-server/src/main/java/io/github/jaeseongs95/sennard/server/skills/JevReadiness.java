// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.context.AdminLog;
import io.github.jaeseongs95.sennard.server.context.JevAdviceGate;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Profile;

/**
 * Whether JEV can be asked for advice now, and if not what is still missing: the one judgment the turn-start hook, the admin page and the classification service all
 * use, so that they cannot say three different things. The signed setting must be on, the skill-classification preset on, a model chosen that its provider still offers,
 * sending allowed, the chosen provider's key registered (the key of the provider the model belongs to, under that provider's own credential name), and a budget that
 * covers one more call. The context layers ask it through {@link JevAdviceGate}, which the context package owns.
 */
@Component
public class JevReadiness implements JevAdviceGate {
    /** What is still missing, in the order the page lists it. */
    public enum Missing {
        SWITCH_OFF, PRESET_OFF, MODEL_NOT_SELECTED, MODEL_UNSUPPORTED, TRANSMISSION_NOT_ALLOWED, KEY_MISSING, BUDGET_MISSING, BUDGET_BELOW_ONE_CALL, BUDGET_EXHAUSTED
    }

    /** The preset of the tool that asks JEV for skill advice. */
    public static final String SKILL_CLASSIFICATION_PRESET = "skill-classification";

    private final JevProviders providers;
    private final JevBudget budget;

    public JevReadiness(JevProviders providers, JevBudget budget) {
        this.providers = providers;
        this.budget = budget;
    }

    /**
     * @param signed the signed JEV setting, null when none is signed
     * @param keyUsable whether a key of this credential name is registered and its stored value is the one that was signed
     * @return what is missing; empty when JEV can be asked now
     */
    public List<Missing> missing(AdminLog.JevState signed, Predicate<String> keyUsable) {
        List<Missing> missing = new ArrayList<>();
        if (signed == null || !signed.enabled()) {
            missing.add(Missing.SWITCH_OFF);
        }
        if (signed != null && !signed.presets().getOrDefault(SKILL_CLASSIFICATION_PRESET, true)) {
            missing.add(Missing.PRESET_OFF);
        }
        // The profile of the chosen model; empty when no model is chosen, or its provider is not installed or no longer offers it.
        Optional<Profile> profile = signed == null ? Optional.empty() : providers.profile(signed.model());
        if (signed == null || signed.model() == null) {
            missing.add(Missing.MODEL_NOT_SELECTED);
        } else if (profile.isEmpty()) {
            missing.add(Missing.MODEL_UNSUPPORTED);
        }
        if (signed == null || !signed.transmissionAllowed()) {
            missing.add(Missing.TRANSMISSION_NOT_ALLOWED);
        }
        // The chosen model's provider decides which key counts; with no usable model, a key of any provider.
        boolean keyed = profile.map(chosen -> providers.of(chosen).map(provider -> keyUsable.test(provider.credentialName())).orElse(false))
                .orElseGet(() -> providers.all().stream().anyMatch(provider -> keyUsable.test(provider.credentialName())));
        if (!keyed) {
            missing.add(Missing.KEY_MISSING);
        }
        Double limit = signed == null ? null : signed.limitUsd();
        if (limit == null) {
            missing.add(Missing.BUDGET_MISSING);
        } else {
            profile.flatMap(chosen -> budgetShortfall(limit, chosen)).ifPresent(missing::add);
        }
        return List.copyOf(missing);
    }

    /** Whether the signed limit covers one more call of the chosen model: what the budget lacks, empty when it lacks nothing. */
    private Optional<Missing> budgetShortfall(double limit, Profile profile) {
        if (limit < profile.maximumCostUsd()) {
            return Optional.of(Missing.BUDGET_BELOW_ONE_CALL);
        }
        // Totals that cannot be read do not by themselves make JEV unavailable: the call's own reserve (JevBudget#reserve) refuses when they cannot be shown to cover it.
        return budget.view().filter(usage -> usage.estimatedUsd() + usage.reservedUsd() + profile.maximumCostUsd() > limit).map(usage -> Missing.BUDGET_EXHAUSTED);
    }

    @Override
    public boolean ready(AdminLog.JevState signed, Predicate<String> keyUsable) {
        return missing(signed, keyUsable).isEmpty();
    }
}
