// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.util.List;
import java.util.Map;

/** The rows of a test statement by column label, or its update count. */
public record QueryResult(List<Map<String, Object>> rows, long rowCount) {
}
