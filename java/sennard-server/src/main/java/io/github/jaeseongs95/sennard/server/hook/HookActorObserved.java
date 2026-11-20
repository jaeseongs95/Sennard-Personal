// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/** A host whose hooks need correlation supplied new actor evidence for this raw conversation. */
public record HookActorObserved(HostId host, String conversation) {
}
