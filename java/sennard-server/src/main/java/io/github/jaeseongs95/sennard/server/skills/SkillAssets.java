// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * The skills' packaged data files: contracts, catalogs, registries. They ship in the server's jar (the server has its own copy
 * of everything a check reads), so a caller's path never reaches them.
 */
@Component
public class SkillAssets {
    private static final String ROOT = "sennard-skills/";

    private final JsonMapper mapper;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public SkillAssets(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** A packaged JSON file bound to the record that describes it. */
    public <T> T read(String relative, Class<T> type) {
        return mapper.readValue(bytes(relative), type);
    }

    public byte[] bytes(String relative) {
        try (InputStream in = new ClassPathResource(ROOT + relative).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(relative, e);
        }
    }

    public boolean exists(String relative) {
        return new ClassPathResource(ROOT + relative).exists();
    }

    /** Every packaged file matching an Ant pattern, keyed by its path relative to the asset root, with its content as UTF-8 text. */
    public Map<String, String> matching(String pattern) {
        Map<String, String> found = new LinkedHashMap<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:" + ROOT + pattern)) {
                String location = resource.getURL().toString();
                int at = location.lastIndexOf(ROOT);
                found.put(location.substring(at + ROOT.length()), resource.getContentAsString(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(pattern, e);
        }
        return found;
    }
}
