// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/**
 * Whether a checking actor is separate from the actors that did the work, judged only by what the host observed so a new host needs no change:
 * {@code VERIFIED} (observed and distinct), {@code VIOLATED} (the same actor or its descendant), {@code UNOBSERVED} (Sennard cannot tell).
 * Written in the observed reviewer by the constant's name.
 */
public enum Independence {
    VERIFIED,
    VIOLATED,
    UNOBSERVED
}
