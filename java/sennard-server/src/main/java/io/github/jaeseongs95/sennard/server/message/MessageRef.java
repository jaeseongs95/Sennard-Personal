// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.message;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A catalog message by key with its parameters: the key and params are the contract, the rendered text is display. A parameter may itself be a
 * {@code MessageRef} (a nested sentence), which {@link Messages} renders first, in the same locale.
 */
public record MessageRef(String key, Map<String, Object> params) {
    public MessageRef {
        params = new LinkedHashMap<>(params == null ? Map.of() : params);
    }

    public static MessageRef of(String key) {
        return new MessageRef(key, Map.of());
    }

    public static MessageRef of(String key, Map<String, Object> params) {
        return new MessageRef(key, params);
    }

    /** The parameters as name, value, name, value, ... */
    public static MessageRef of(String key, Object... pairs) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            params.put((String) pairs[index], pairs[index + 1]);
        }
        return new MessageRef(key, params);
    }

    /** The short form of {@link #of(String, Object...)} for a static import. */
    public static MessageRef msg(String key, Object... pairs) {
        return of(key, pairs);
    }
}
