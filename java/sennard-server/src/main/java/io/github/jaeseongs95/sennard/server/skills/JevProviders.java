// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.context.AdminLog;
import io.github.jaeseongs95.sennard.server.skills.SkillTypes.Profile;

/**
 * The JEV providers of this installation, by the provider id a signed setting names. Every {@link JevProvider} bean is one entry; two with the same id are a wiring error
 * and the server does not start. The service and the admin page choose a provider here and nowhere else.
 */
@Component
public class JevProviders {
    private final Map<String, JevProvider> byId = new LinkedHashMap<>();

    public JevProviders(List<JevProvider> providers) {
        for (JevProvider provider : providers) {
            JevProvider prior = byId.putIfAbsent(provider.providerId(), provider);
            if (prior != null) {
                throw new IllegalStateException("Two JEV providers have the id " + provider.providerId() + ": " + prior.getClass().getName() + " and " + provider.getClass().getName());
            }
        }
    }

    /** Every provider, in the order the beans were registered. */
    public List<JevProvider> all() {
        return List.copyOf(byId.values());
    }

    public Optional<JevProvider> find(String providerId) {
        return Optional.ofNullable(byId.get(providerId));
    }

    /** The provider a profile was made by. */
    public Optional<JevProvider> of(Profile profile) {
        return find(profile.vendorId());
    }

    /** The profile of a model a signed setting names; empty when the provider is not installed or does not offer the model. */
    public Optional<Profile> profile(AdminLog.JevModel model) {
        if (model == null) {
            return Optional.empty();
        }
        return find(model.providerId()).flatMap(provider -> provider.profileFor(model.modelId()));
    }
}
