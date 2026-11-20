-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V3: the session board (one table).
-- The concurrency is the primary key and the version column (optimistic locking), not a database lock.
-- Values are made in Java and handed to the database (plan D9): a time is the injected clock's UTC instant, stored as TIMESTAMP (no time zone: the conversion is Java's, see
-- BoardService), never a value the database computes.
CREATE TABLE sennard.board_sessions (
    host       VARCHAR(64)  NOT NULL,
    session_id VARCHAR(512) NOT NULL,
    cwd        ${long_text},
    summary    ${long_text},
    summary_at TIMESTAMP,
    last_prompt_at TIMESTAMP,
    seen_at    TIMESTAMP    NOT NULL,
    role       VARCHAR(255),
    version    BIGINT       NOT NULL,
    PRIMARY KEY (host, session_id)
);

INSERT INTO sennard.module_schema (module, version) VALUES ('board', 1);
