// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminDraft;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecord;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore;
import io.github.jaeseongs95.sennard.service.store.modules.ContextLayerStore.LogRecord;
import io.github.jaeseongs95.sennard.service.store.modules.StoreValues;

/**
 * Where the signed admin log is kept: one log, in two places in time. The records signed before the business database was linked are in the settings database
 * ({@link PreLinkAdminRecords}); once it is ready the records go to its {@code admin_log}. A reader folds the two as one log, the section before the link first and the business
 * records after it, so the first business record of a chain names the last pre-link record of that chain as {@code prevTargetHash}; nothing is copied from one to the other
 * (the same record is never in two databases).
 *
 * <p>The sequence numbers of the two places each start at 1, and the log is ordered by one: the business records are shifted by the last sequence number of the pre-link
 * section, which no longer grows once the business database is ready (the section is closed then). A log with no pre-link record is not shifted.
 *
 * <p>This class opens no transaction: the pre-link records are read from the settings database and the business records inside the caller's business transaction. Whether the
 * business database can be read is the caller's to know (a business transaction cannot begin before it is ready).
 */
@Component
public class AdminLogSource {
    private final ContextLayerStore store;
    private final PreLinkAdminRecords preLink;

    public AdminLogSource(ContextLayerStore store, PreLinkAdminRecords preLink) {
        this.store = store;
        this.preLink = preLink;
    }

    /** Whether the section before the link takes records: the business database was never ready. */
    public boolean preLinkOpen() {
        return !preLink.closed();
    }

    /** The pre-link section as log records, in order. */
    public List<LogRecord> preLinkRecords() {
        return logRecords(preLink.list());
    }

    /** Records of the pre-link section as log records, for a caller that has the section's records and no business store (the admin reset before the link). */
    public static List<LogRecord> logRecords(List<PreLinkAdminRecord> records) {
        return records.stream().map(AdminLogSource::logRecord).toList();
    }

    /** The whole log: the pre-link section, then the business records after it (read in the caller's business transaction). */
    public List<LogRecord> records() {
        List<PreLinkAdminRecord> before = preLink.list();
        long shift = shift(before);
        List<LogRecord> all = new ArrayList<>(before.stream().map(AdminLogSource::logRecord).toList());
        for (LogRecord record : store.logRecords()) {
            all.add(shifted(record, shift));
        }
        return all;
    }

    /** The record a request id signed, wherever it was kept; empty when none. */
    public Optional<LogRecord> byRequest(String requestId) {
        Optional<LogRecord> before = preLinkByRequest(requestId);
        if (before.isPresent()) {
            return before;
        }
        return Optional.ofNullable(store.recordByRequest(requestId)).map(record -> shifted(record, shift(preLink.list())));
    }

    /** The record a request id signed in the pre-link section; empty when none. */
    public Optional<LogRecord> preLinkByRequest(String requestId) {
        return preLink.findByRequest(requestId).map(AdminLogSource::logRecord);
    }

    /** Appends a record to the business log; the record as the log now has it (its sequence number in the one order). */
    public LogRecord append(LogRecord record, String requestId) {
        LogRecord appended = store.appendRecord(record, requestId);
        return shifted(appended, shift(preLink.list()));
    }

    /** Appends a record to the pre-link section. */
    public LogRecord appendPreLink(LogRecord record, String requestId) {
        PreLinkAdminRecord appended = preLink.append(new PreLinkAdminDraft(record.target(), record.intent(), record.credentialId(), record.authenticatorData(),
                record.clientDataJson(), record.signature(), record.recordHash(), requestId));
        return logRecord(appended);
    }

    private static long shift(List<PreLinkAdminRecord> before) {
        return before.isEmpty() ? 0 : before.getLast().seq();
    }

    private static LogRecord shifted(LogRecord record, long shift) {
        return shift == 0 ? record : new LogRecord(record.seq() + shift, record.target(), record.intent(), record.credentialId(), record.authenticatorData(), record.clientDataJson(),
                record.signature(), record.recordHash(), record.createdAt());
    }

    private static LogRecord logRecord(PreLinkAdminRecord record) {
        PreLinkAdminDraft draft = record.draft();
        return new LogRecord(record.seq(), draft.target(), draft.intent(), draft.credentialId(), draft.authenticatorData(), draft.clientDataJson(), draft.signature(), draft.recordHash(),
                StoreValues.iso(record.createdAt().toEpochMilli()));
    }
}
