// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Stands for {@code @RetryOnLostRace}: the second retry annotation of the product, itself annotated with the retry annotation. */
@Retention(RetentionPolicy.RUNTIME)
@FixtureRetryable
@interface FixtureRetryOnLostRace {
}
