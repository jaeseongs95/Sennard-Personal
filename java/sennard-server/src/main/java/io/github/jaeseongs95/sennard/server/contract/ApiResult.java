// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.contract;

import java.util.Map;

/** The envelope every tool answers with: the published {@code ApiResultV1}. */
public record ApiResult(String schemaVersion, boolean ok, Object data, ErrorBody error) {
    public static final String VERSION = "1.0.0";

    /**
     * @param messageKey the catalog key of {@code message}; {@code params} are its arguments
     * @param details what the code needs to be understood: a map, or the JSON of a workflow failure; null when the failure has none
     */
    public record ErrorBody(String code, String message, String messageKey, Map<String, Object> params, Object details) {
    }

    public static ApiResult ok(Object data) {
        return new ApiResult(VERSION, true, data, null);
    }

    public static ApiResult failed(ErrorBody error) {
        return new ApiResult(VERSION, false, null, error);
    }
}
