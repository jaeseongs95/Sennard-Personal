// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

/**
 * Published inside the commit of a signed {@link AdminOp#DATABASE_LINK}, after the link settings were written in the same transaction. The edition that owns the store listens
 * for it after the commit and starts to prepare the store with the settings (connect, migrate, ready); a record that rolls back starts nothing.
 */
public record DatabaseLinkSaved() {
}
