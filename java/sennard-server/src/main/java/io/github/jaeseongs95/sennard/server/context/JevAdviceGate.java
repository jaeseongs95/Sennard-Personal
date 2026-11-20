// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.util.function.Predicate;

/**
 * What the context layers need to know about JEV: whether a turn should be told to ask it. The context package owns this interface and the skills package implements it
 * ({@code JevReadiness}), so the dependency runs from the skills (which already use the signed settings of this package) to the context and never back: the layers do not
 * know how JEV judges whether it can be asked.
 */
public interface JevAdviceGate {
    /**
     * @param signed the signed JEV setting, null when none is signed
     * @param keyUsable whether a key of this credential name is registered and its stored value is the one that was signed
     * @return true when JEV can be asked now
     */
    boolean ready(AdminLog.JevState signed, Predicate<String> keyUsable);
}
