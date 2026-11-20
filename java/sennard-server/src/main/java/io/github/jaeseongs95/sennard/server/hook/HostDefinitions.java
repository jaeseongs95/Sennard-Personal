// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The hosts the code outside the Spring context knows: every {@link HostDefinition} that {@link ServiceLoader} finds on the class path. No list of hosts is
 * written here or in a caller; a host is known when its definition is named in {@code META-INF/services/}.
 */
public final class HostDefinitions {
    private final List<HostDefinition> all;
    private final Map<String, HostDefinition> byLabel = new HashMap<>();

    private HostDefinitions(List<HostDefinition> found) {
        this.all = List.copyOf(found);
        for (HostDefinition definition : found) {
            HostDefinition earlier = byLabel.put(definition.host().label(), definition);
            if (earlier != null) {
                throw new IllegalStateException("Two host definitions have the label " + definition.host().label() + ": " + earlier.getClass().getName() + " and " + definition.getClass().getName());
            }
        }
    }

    /**
     * Finds the definitions through the class loader of this type, so that the answer is the same whatever the calling thread's context class loader is.
     *
     * @throws IllegalStateException when two definitions have the same label
     * @throws java.util.ServiceConfigurationError when a definition named in a services file cannot be made
     */
    public static HostDefinitions load() {
        return of(ServiceLoader.load(HostDefinition.class, HostDefinition.class.getClassLoader()).stream().map(ServiceLoader.Provider::get).toList());
    }

    /**
     * The definitions given, not the ones the class path names: the way a test gives the code a host that is not in the product.
     *
     * @throws IllegalStateException when two definitions have the same label
     */
    public static HostDefinitions of(List<HostDefinition> definitions) {
        return new HostDefinitions(definitions);
    }

    public List<HostDefinition> all() {
        return all;
    }

    /** @return the definition of the host whose hooks are posted under the label, or empty for a label no definition names */
    public Optional<HostDefinition> ofLabel(String label) {
        return Optional.ofNullable(byLabel.get(label));
    }
}
