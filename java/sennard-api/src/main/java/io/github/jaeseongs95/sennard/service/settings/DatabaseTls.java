// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

/**
 * How the link to the business database is protected, as the user chose it on the admin page: the database's address is a loopback IP literal and may be plain text, or the
 * driver verifies the server's certificate and its name with its own option (the URL carries that option). The column holds the constant's name.
 */
public enum DatabaseTls {
    LOOPBACK_PLAINTEXT, VERIFY_FULL
}
