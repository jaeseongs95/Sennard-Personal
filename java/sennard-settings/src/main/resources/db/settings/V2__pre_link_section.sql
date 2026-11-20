-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V2: the section of the admin log before the link has one state row, which is what makes closing it and appending to it a race the database decides. Appending a record
-- raises last_seq with a conditional UPDATE that only an open section takes, and closing is a conditional UPDATE of the same row, so a record is either before the close
-- (the append took the row first) or refused (the close did): no record enters a closed section. The sequence number of a record is the last_seq its append raised, and it goes on
-- counting after the admin reset (the archive is keyed by it, as before). The row of the earlier table that held only the closing (pre_link_closed) is folded into this one.
CREATE TABLE sennard.pre_link_section (
    section_id  INTEGER   NOT NULL, -- one section per installation: the code writes 1
    last_seq    BIGINT    NOT NULL, -- the sequence number of the last record, which the admin reset does not take back
    closed      BOOLEAN   NOT NULL, -- true once the business database was ready for the first time
    closed_time TIMESTAMP,
    PRIMARY KEY (section_id)
);

INSERT INTO sennard.pre_link_section (section_id, last_seq, closed, closed_time)
SELECT 1,
       CASE WHEN r.last_seq > a.last_seq THEN r.last_seq ELSE a.last_seq END,
       CASE WHEN c.closings > 0 THEN TRUE ELSE FALSE END,
       c.closed_time
FROM (SELECT COALESCE(MAX(seq), 0) AS last_seq FROM sennard.pre_link_admin_records) r
CROSS JOIN (SELECT COALESCE(MAX(seq), 0) AS last_seq FROM sennard.pre_link_admin_records_archive) a
CROSS JOIN (SELECT COUNT(*) AS closings, MAX(closed_at) AS closed_time FROM sennard.pre_link_closed) c;

DROP TABLE sennard.pre_link_closed;
