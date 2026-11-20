// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

import java.util.Optional;

/**
 * The one set of link settings of this installation (one user, one business database): what the admin page saves, with a passkey's signature, to link the business database.
 * It is the one source of the business database: the server, {@code sennard admin-reset} and {@code sennard attachments} read it, the server when it starts and (before the business
 * database is ready) when the page has just saved it. It is kept in the settings database of the state folder, so it outlives the installed program.
 */
public interface DatabaseLinkSettings {
    /** Replaces the saved settings (or saves the first); the saved settings. */
    DatabaseLink save(DatabaseLinkRequest request);

    /** The saved settings; empty before the first save. */
    Optional<DatabaseLink> find();
}
