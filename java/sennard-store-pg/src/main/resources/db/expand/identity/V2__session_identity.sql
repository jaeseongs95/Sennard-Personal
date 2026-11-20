-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V2: the session identity tables.
-- A session_ref row is one host session: the address other sessions use, kept across restarts; a
-- session_generation row is one live run of it, and its lease.
--
-- Standard types only. A key column is a VARCHAR of a size; the text of no fixed size is ${long_text}. Nothing here depends on a collation: session_ref is sorted by Java to take
-- row locks in one order (I13), and a column is compared for equality only. A digest and a UUID are the text Java made of them (hex, and the 36 characters of UUID.toString()).

CREATE TABLE sennard.session_ref (
    session_ref          VARCHAR(64)  NOT NULL,
    host                 VARCHAR(64)  NOT NULL,
    host_session_digest  CHAR(64)     NOT NULL,                  -- the HMAC-SHA-256 of the host session, in hex
    pepper_version       SMALLINT     NOT NULL,
    local_session_id     VARCHAR(512),                           -- the host's own id of the session
    last_generation      BIGINT       NOT NULL DEFAULT 0,
    next_message_seq     BIGINT       NOT NULL DEFAULT 1,
    pending_count        INTEGER      NOT NULL DEFAULT 0,
    sender_drafts        INTEGER      NOT NULL DEFAULT 0,
    sender_receipts      INTEGER      NOT NULL DEFAULT 0,
    kind                 VARCHAR(16)  NOT NULL DEFAULT 'MAIN',   -- SessionKind: the enum constant's name; its CHECK is added by V12
    created_at           TIMESTAMP    NOT NULL,
    last_seen_at         TIMESTAMP    NOT NULL,
    PRIMARY KEY (session_ref)
);

-- I1: one sessionRef per host session. The natural key has no coalesce: every caller of 6.0.0 has an installation.
CREATE UNIQUE INDEX session_ref_natural ON sennard.session_ref (host, host_session_digest);

CREATE TABLE sennard.session_generation (
    session_ref           VARCHAR(64)  NOT NULL,
    generation            BIGINT       NOT NULL,
    origin                VARCHAR(16)  NOT NULL,                  -- GenerationOrigin: the enum constant's name
    started_at            TIMESTAMP    NOT NULL,                  -- the service's clock
    client_started_at     TIMESTAMP,                              -- the PC's clock; compared only with the PC's clock (5.4)
    client_ended_at       TIMESTAMP,                              -- the PC's clock
    process_key           ${long_text},
    client_instance_id    CHAR(36),                               -- a UUID in its text form
    relay_id              CHAR(36),                               -- a UUID in its text form; NULL: a generation without an owning client
    code_digest           ${long_text},
    lease_until           TIMESTAMP    NOT NULL,
    heartbeat_at          TIMESTAMP    NOT NULL,
    ended_at              TIMESTAMP,
    end_reason            ${long_text},
    superseded_at         TIMESTAMP,
    transport             ${long_text},
    delivery              ${long_text} NOT NULL,                  -- JSON text
    PRIMARY KEY (session_ref, generation),
    CONSTRAINT session_generation_origin CHECK (origin IN ('REGISTER', 'RECOVERY')),
    CONSTRAINT session_generation_session FOREIGN KEY (session_ref) REFERENCES sennard.session_ref (session_ref) ON DELETE CASCADE
);

-- I2: at most one live generation of a session. The live one has a row here, written when it is made and removed when it ends or is superseded; the primary key is the session, so a
-- new-generation path that does not close the previous row fails here. (A UNIQUE index over the live rows only would be a partial index, which the standard has not.)
CREATE TABLE sennard.active_generation (
    session_ref VARCHAR(64) NOT NULL,
    generation  BIGINT      NOT NULL,
    PRIMARY KEY (session_ref),
    CONSTRAINT active_generation_generation FOREIGN KEY (session_ref, generation) REFERENCES sennard.session_generation (session_ref, generation) ON DELETE CASCADE
);

CREATE INDEX session_generation_retention ON sennard.session_generation (lease_until);
