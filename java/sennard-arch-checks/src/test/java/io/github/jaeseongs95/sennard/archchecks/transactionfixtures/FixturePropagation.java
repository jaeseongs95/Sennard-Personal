// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.transactionfixtures;

/** Stands for Spring's {@code Propagation}: the names the rule looks for are {@code REQUIRES_NEW} and {@code NESTED}. */
enum FixturePropagation {
    REQUIRED, REQUIRES_NEW, NESTED
}
