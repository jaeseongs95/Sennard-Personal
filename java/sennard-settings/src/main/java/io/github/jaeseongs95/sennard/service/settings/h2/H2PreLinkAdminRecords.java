// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings.h2;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jaeseongs95.sennard.domain.Timestamps;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminDraft;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecord;
import io.github.jaeseongs95.sennard.service.settings.PreLinkAdminRecords;
import io.github.jaeseongs95.sennard.service.settings.SettingsCode;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;
import io.github.jaeseongs95.sennard.service.settings.SettingsTransactions;

/**
 * The signed admin records of the time before the link, in the settings database. The section has one state row ({@code pre_link_section}: the last sequence number and whether it is
 * closed), and appending and closing both are conditional UPDATEs of that row, so the database decides which of an append and a close comes first (a record is before the close or is
 * refused with PRE_LINK_CLOSED); the UPDATE of the second waits for the first one's transaction and tests its condition again on what the first wrote. The writes are service methods
 * of the settings database, each with its one {@code @Transactional}: the use case that appends a record together with a key and the link settings (the pre-link use cases)
 * joins its transaction, and {@link #close} and {@link #archive} are the use cases of their own that an entrance calls.
 */
@Service
public class H2PreLinkAdminRecords implements PreLinkAdminRecords {
    private static final String SELECT = "SELECT seq, target, intent, credential_id, authenticator_data, client_data_json, signature, record_hash, request_id, created_at "
            + "FROM sennard.pre_link_admin_records";

    private static final int SECTION_ID = 1;

    private final JdbcClient jdbc;
    private final Clock clock;

    H2PreLinkAdminRecords(@Qualifier("settingsJdbc") JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * The conditional UPDATE of the state row takes the next sequence number if the section is open, and holds the row until this transaction ends: a close that comes meanwhile waits
     * for the record, and a record that comes after a close finds no open row (0 rows) and is refused. A repeated request id violates its unique key and the transaction ends
     * without the record (and without the sequence number it took).
     */
    @Override
    @Transactional(transactionManager = SettingsTransactions.MANAGER)
    public PreLinkAdminRecord append(PreLinkAdminDraft draft) {
        int taken = jdbc.sql("UPDATE sennard.pre_link_section SET last_seq = last_seq + 1 WHERE section_id = ? AND closed = FALSE").param(SECTION_ID).update();
        if (taken == 0) {
            throw new SettingsException(SettingsCode.PRE_LINK_CLOSED, "The admin records before the link take no more records.");
        }
        long seq = jdbc.sql("SELECT last_seq FROM sennard.pre_link_section WHERE section_id = ?").param(SECTION_ID).query(Long.class).single();
        try {
            jdbc.sql("INSERT INTO sennard.pre_link_admin_records (seq, target, intent, credential_id, authenticator_data, client_data_json, signature, record_hash, "
                    + "request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(seq, draft.target(), draft.intent(), draft.credentialId(), draft.authenticatorData(), draft.clientDataJson(), draft.signature(), draft.recordHash(),
                            draft.requestId(), Timestamps.toTimestamp(clock.instant()))
                    .update();
        } catch (DuplicateKeyException signed) {
            throw new SettingsException(SettingsCode.REQUEST_ALREADY_RECORDED, "The request id has a record already.", signed);
        }
        return jdbc.sql(SELECT + " WHERE seq = ?").param(seq).query(H2PreLinkAdminRecords::map).single();
    }

    @Override
    public List<PreLinkAdminRecord> list() {
        return jdbc.sql(SELECT + " ORDER BY seq").query(H2PreLinkAdminRecords::map).list();
    }

    @Override
    public Optional<PreLinkAdminRecord> findByRequest(String requestId) {
        return jdbc.sql(SELECT + " WHERE request_id = ?").param(requestId).query(H2PreLinkAdminRecords::map).optional();
    }

    @Override
    public boolean closed() {
        return jdbc.sql("SELECT closed FROM sennard.pre_link_section WHERE section_id = ?").param(SECTION_ID).query(Boolean.class).single();
    }

    /** One conditional UPDATE of the state row: closing an open section closes it (1 row), closing a closed one changes nothing (0 rows). */
    @Override
    @Transactional(transactionManager = SettingsTransactions.MANAGER)
    public void close() {
        jdbc.sql("UPDATE sennard.pre_link_section SET closed = TRUE, closed_time = ? WHERE section_id = ? AND closed = FALSE")
                .params(Timestamps.toTimestamp(clock.instant()), SECTION_ID).update();
    }

    /**
     * The copy and then the delete, in one transaction. Each statement can be run again as well (the copy skips a record the archive has, the delete empties the section), so a run
     * that stops before the commit leaves the records where they were and the next one does the same work. The state row is left as it is: the section stays closed or open, and the
     * sequence numbers go on counting.
     */
    @Override
    @Transactional(transactionManager = SettingsTransactions.MANAGER)
    public void archive() {
        jdbc.sql("INSERT INTO sennard.pre_link_admin_records_archive (seq, target, intent, credential_id, authenticator_data, client_data_json, signature, record_hash, "
                + "request_id, created_at, archived_at) SELECT r.seq, r.target, r.intent, r.credential_id, r.authenticator_data, r.client_data_json, r.signature, r.record_hash, "
                + "r.request_id, r.created_at, CAST(? AS TIMESTAMP) FROM sennard.pre_link_admin_records r WHERE NOT EXISTS "
                + "(SELECT 1 FROM sennard.pre_link_admin_records_archive a WHERE a.seq = r.seq)").param(Timestamps.toTimestamp(clock.instant())).update();
        jdbc.sql("DELETE FROM sennard.pre_link_admin_records").update();
    }

    private static PreLinkAdminRecord map(ResultSet row, int rowNumber) throws SQLException {
        PreLinkAdminDraft draft = new PreLinkAdminDraft(row.getString("target"), row.getString("intent"), row.getString("credential_id"),
                row.getString("authenticator_data"), row.getString("client_data_json"), row.getString("signature"), row.getString("record_hash"), row.getString("request_id"));
        return new PreLinkAdminRecord(row.getLong("seq"), draft, Timestamps.toInstant(row.getObject("created_at", LocalDateTime.class)));
    }
}
