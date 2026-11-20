-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V11: the row a PreToolUse hook leaves for the tool call it precedes, on hosts whose tool call does not say by itself who called
-- (single-mcp-server-design.md 2, classes B and C). One claim per row: the claim deletes it. Rows that are
-- never claimed are removed by age when a new row is written.

CREATE TABLE sennard.call_admission (
    host            VARCHAR(64)  NOT NULL,
    call_id         VARCHAR(512) NOT NULL,           -- the host's id of the call, or a key the host's adapter built when the host has none
    tool            VARCHAR(255) NOT NULL,
    host_session_id VARCHAR(512) NOT NULL,
    agent_id        VARCHAR(512),
    model           VARCHAR(255),
    effort          VARCHAR(64),
    turn_id         VARCHAR(512),
    transcript_path ${long_text},
    created_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (host, call_id)
);
CREATE INDEX call_admission_age ON sennard.call_admission (created_at);
