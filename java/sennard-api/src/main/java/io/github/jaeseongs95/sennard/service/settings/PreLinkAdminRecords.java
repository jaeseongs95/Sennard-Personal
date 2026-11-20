// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

import java.util.List;
import java.util.Optional;

/**
 * The signed admin records of the time before the business database is linked. The store keeps them as they were signed; it does not check the chain or the signature,
 * which is the admin log's own work. The section is open until the business database is ready for the first time ({@link #close()}); the records stay where they are, and
 * the admin log reads them before the business database's records.
 */
public interface PreLinkAdminRecords {
    /** Appends a record; {@link SettingsCode#REQUEST_ALREADY_RECORDED} when its request id has a record already, {@link SettingsCode#PRE_LINK_CLOSED} when the section is closed. */
    PreLinkAdminRecord append(PreLinkAdminDraft draft);

    /** Every record in the order they were appended. */
    List<PreLinkAdminRecord> list();

    /** The record a request id signed; empty when none. */
    Optional<PreLinkAdminRecord> findByRequest(String requestId);

    /** Whether the section is closed: the business database was ready once. */
    boolean closed();

    /** Closes the section (once; closing it again changes nothing): no record is appended after this. */
    void close();

    /** The admin reset: moves every record to the archive of the settings database, so the log starts again; the section stays closed or open as it was. */
    void archive();
}
