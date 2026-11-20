// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.config.SennardProperties;

/**
 * The folder holding {@code skills/registry.json} and the contract files the skill providers name: the install folder of the server (or a
 * source checkout in a test). Skills are files the install owner places there; the workflow reads their descriptors from it. A component, made from
 * {@code SennardProperties#content()}.
 */
@Component
public class ContentRoot {
    private final Path root;

    /** The component: the folder the server's settings name. */
    @Autowired
    public ContentRoot(SennardProperties properties) {
        this(properties.content());
    }

    public ContentRoot(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path path() {
        return root;
    }

    public Path registryFile() {
        return root.resolve("skills").resolve("registry.json");
    }
}
