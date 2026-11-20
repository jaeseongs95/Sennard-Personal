// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Stands for the {@code @Transactional} annotation, on a method or on a class, with the propagation it may name. */
@Retention(RetentionPolicy.RUNTIME)
@interface FixtureTransactional {
    FixturePropagation propagation() default FixturePropagation.REQUIRED;

    Class<? extends Throwable>[] noRollbackFor() default {};

    String[] noRollbackForClassName() default {};
}
