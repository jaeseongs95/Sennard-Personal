// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/** The registered host facts shared by hook and tool consumers. */
@Component
public class HostWorkFactsRegistry {
    private final Map<HostId, HostWorkFacts> hookHosts;
    private final Map<String, HostWorkFacts> sessionHosts;

    public HostWorkFactsRegistry(List<HostWorkFacts> facts) {
        hookHosts = facts.stream().collect(Collectors.toUnmodifiableMap(HostWorkFacts::host, Function.identity()));
        sessionHosts = facts.stream().collect(Collectors.toUnmodifiableMap(fact -> fact.host().sessionHost(), Function.identity(),
                (first, duplicate) -> first));
    }

    public HostWorkFacts forHost(HostId host) {
        return hookHosts.getOrDefault(host, HostWorkFacts.NONE);
    }

    public HostWorkFacts forSessionHost(String sessionHost) {
        return sessionHost == null ? HostWorkFacts.NONE : sessionHosts.getOrDefault(sessionHost, HostWorkFacts.NONE);
    }
}
