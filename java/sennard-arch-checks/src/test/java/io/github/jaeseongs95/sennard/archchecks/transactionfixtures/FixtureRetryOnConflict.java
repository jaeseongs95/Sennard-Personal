// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Stands for {@code @RetryOnConflict}: an annotation of the product that is itself annotated with the retry annotation, so the rule has to see through it. */
@Retention(RetentionPolicy.RUNTIME)
@FixtureRetryable
@interface FixtureRetryOnConflict {
}
