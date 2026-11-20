-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V10: long work that does not fit a tool call's answer (single-mcp-server-design.md B.3). Input and result are JSON text, the same as every
-- other *_json column; they are never matched or indexed in SQL.
CREATE TABLE sennard.background_job (
    job_id      CHAR(36)         NOT NULL,           -- a UUID in its text form
    kind        VARCHAR(255)     NOT NULL,
    state       VARCHAR(16)      NOT NULL,           -- JobState: the enum constant's name
    input_json  ${long_text}     NOT NULL,
    created_at  TIMESTAMP        NOT NULL,
    started_at  TIMESTAMP,
    finished_at TIMESTAMP,
    error_code  VARCHAR(255),
    result_json ${long_text},
    PRIMARY KEY (job_id),
    CONSTRAINT background_job_state CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);
-- The start-up recovery looks at the unfinished rows by their state.
CREATE INDEX background_job_unfinished ON sennard.background_job (state, created_at);
