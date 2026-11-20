// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.admincli;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.server.context.AdminLog;
import io.github.jaeseongs95.sennard.server.context.AdminLogSource;
import io.github.jaeseongs95.sennard.service.settings.PasskeyRegistry;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.LogRecord;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.Reset;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code sennard admin-reset}: every passkey was lost, so the signed admin log is archived and every signed item returns to its default. There is no HTTP or MCP path to
 * it. It writes nothing unless confirmed, and it grants nothing: the first passkey has to be registered again, and a server sees the new reset id on its next read (the command stops the installation's server before it opens the settings database, so
 * the next hook call starts one that reads it; see {@code AdminResetCommand}).
 *
 * <p>There are two cases, and the state of the section of the log before the link ({@link PreLinkAdminRecords#closed()}) tells which. This class is the one after the business
 * database was ready once (the section is closed and the log is the business database's as well): what authorizes it is that only the OS user of this PC's installation can read the
 * database account's password file, and when the business database cannot be reached the command ends with ADMIN_RESET_DATABASE_UNREACHABLE (the business log has to be archived
 * too, so it needs the database). While the section is still open the log is in the settings database alone, and {@link AdminResetBeforeLink} resets it without the business database;
 * the authority there is the owner's settings file.
 *
 * <p>This is a service of the offline command's own context ({@link AdminResetConfiguration}), and it is not in a package the server scans, so the server has no such bean. The
 * use case is the one public method, which is the one transaction.
 */
@Service
public class AdminReset {
    public static final String EFFECT = "Every signed admin item returns to its default (no global context, no roles, no bindings, no passkeys, JEV off, no JEV keys, "
            + "no edit gate exception paths). Re-run with --yes.";

    /** What is about to be, or was, moved to the archive. */
    public record Summary(int keys, int roles, int bindings, long lastSeq) {
    }

    /** @param reset null for a run that was not confirmed */
    public record Result(Summary summary, Reset reset) {
    }

    private final ContextLayerStore store;
    private final Clock clock;
    private final JsonMapper json;
    private final PasskeyRegistry passkeys;
    private final PreLinkAdminRecords preLink;
    private final AdminLogSource logs;

    public AdminReset(ContextLayerStore store, Clock clock, JsonMapper json, PasskeyRegistry passkeys, PreLinkAdminRecords preLink, AdminLogSource logs) {
        this.store = store;
        this.clock = clock;
        this.json = json;
        this.passkeys = passkeys;
        this.preLink = preLink;
        this.logs = logs;
    }

    /**
     * Reads the signed log (the records before the link and the business database's) and, when confirmed, empties the passkey registry, moves the records before the link
     * to the settings database's archive and archives the business log, in this order: the registry and the pre-link records are the settings database and the log the
     * business database, which are not one transaction, so the run that stops half way is the one that leaves no key to sign with (the registry is empty) and not one that
     * leaves keys with no log, and running it again gives the same result. A running server that appends a record between the read and the move is a limit this accepts: the
     * reset is an offline command of a user who lost every passkey, and the record would be archived with the rest by the move itself.
     */
    @Transactional
    public Result run(boolean confirmed) {
        List<LogRecord> records = logs.records();
        // The signatures are checked with the registry's keys, so the log is read before the registry is emptied.
        AdminLog.State state = AdminLog.fold(records, json, passkeys);
        Summary summary = new Summary(state.keys.size(), state.roles.size(), state.bindings.size(), state.lastSeq);
        if (!confirmed) {
            return new Result(summary, null);
        }
        // Two use cases of the settings database, each a service method with its one transaction of the settings database's manager: they commit on their own (another DataSource, so
        // this business transaction does not hold them), before the business log is archived, which is the order that fails towards "no key can sign".
        passkeys.clear();
        preLink.archive();
        return new Result(summary, store.resetLog(records.isEmpty() ? null : records.getLast().recordHash(), StoreValues.iso(clock.millis())));
    }
}
