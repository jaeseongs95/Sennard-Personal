// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.admincli;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.server.context.AdminLog;
import io.github.jaeseongs95.sennard.server.context.AdminLogSource;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;
import io.github.jaeseongs95.sennard.service.settings.SettingsTransactions;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code sennard admin-reset} while the section of the signed admin log before the link is open, that is, while the business database was never ready: the log is only in the settings
 * database, there is no business log to archive, and nothing of the business database is read or written. What authorizes it is that the settings database is the file of the owner
 * of the installation's state folder: whoever can open it (the OS user of the installation, which on a personal computer is the whole trust boundary) is the one who can lose a
 * passkey and the one who resets it. This is the way out for a passkey lost between its first registration and the first connection, including a link that failed (the link settings
 * are not a signed record and stay, so the user can link again with the new first key).
 *
 * <p>Once the business database was ready the section is closed and the log is the business database's too: {@link AdminReset} resets that case, with the database's own credential
 * as the authority. Which one runs is the one state of the section ({@link PreLinkAdminRecords#closed()}), read by the entrance ({@link AdminResetConfiguration}) and not a
 * question of an edition.
 *
 * <p>The use case is the one public method, one transaction of the settings database: the registry is emptied first and the section's records are moved to the archive after it,
 * so a run that stops half way leaves no key to sign with, and running it again gives the same result (the two writes are service methods of the settings database that join this
 * transaction).
 */
@Service
public class AdminResetBeforeLink {
    private final JsonMapper json;
    private final PasskeyRegistry passkeys;
    private final PreLinkAdminRecords preLink;

    public AdminResetBeforeLink(JsonMapper json, PasskeyRegistry passkeys, PreLinkAdminRecords preLink) {
        this.json = json;
        this.passkeys = passkeys;
        this.preLink = preLink;
    }

    /**
     * Reads the records of the section and, when confirmed, empties the passkey registry and moves the records to the settings database's archive. The result has no business log
     * reset ({@code reset} is null): there was none.
     */
    @Transactional(transactionManager = SettingsTransactions.MANAGER)
    public AdminReset.Result run(boolean confirmed) {
        // The signatures are checked with the registry's keys, so the section is read before the registry is emptied.
        AdminLog.State state = AdminLog.fold(AdminLogSource.logRecords(preLink.list()), json, passkeys);
        AdminReset.Summary summary = new AdminReset.Summary(state.keys.size(), state.roles.size(), state.bindings.size(), state.lastSeq);
        if (confirmed) {
            passkeys.clear();
            preLink.archive();
        }
        return new AdminReset.Result(summary, null);
    }
}
