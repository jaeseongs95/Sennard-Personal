// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.promptfixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Stands for the {@code @McpTool} annotation: a class with a method that has it defines tools. */
@Retention(RetentionPolicy.RUNTIME)
@interface FixtureTool {
}
