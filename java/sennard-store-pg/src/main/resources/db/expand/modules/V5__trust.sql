-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V5: the source receipts. receipt_json is the signed value and stays text byte for byte.
CREATE TABLE sennard.trust_metadata (
    meta_key   VARCHAR(255) NOT NULL, -- not named key: KEY is a reserved word of MariaDB and MySQL
    value      ${long_text} NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (meta_key)
);

CREATE TABLE sennard.input_source_receipts (
    receipt_id VARCHAR(255) NOT NULL,
    host       VARCHAR(64)  NOT NULL,
    session_id VARCHAR(512) NOT NULL,
    event_id   VARCHAR(192) NOT NULL, -- host + session_id + event_id (768 characters) is the longest unique key MariaDB's InnoDB takes in utf8mb4 (3072 bytes)
    origin_kind VARCHAR(255) NOT NULL,
    authority_effect VARCHAR(255) NOT NULL,
    observed_at TIMESTAMP NOT NULL,
    expires_at  TIMESTAMP NOT NULL,
    receipt_json ${long_text} NOT NULL,
    PRIMARY KEY (receipt_id),
    UNIQUE (host, session_id, event_id)
);
CREATE INDEX input_source_latest ON sennard.input_source_receipts (host, session_id, observed_at DESC);

INSERT INTO sennard.module_schema (module, version) VALUES ('trust', 1);
