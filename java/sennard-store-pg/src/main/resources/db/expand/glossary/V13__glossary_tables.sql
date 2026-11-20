-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V13: the tables of the reviewed Korean terms (the glossary of the korean-prose-editor skill).
-- Reference data. The service only reads it and only a migration writes it
-- (R__korean_prose_glossary.sql). ANSI SQL only, so the same script creates the tables on H2 and on PostgreSQL; it refers to no table of another module.
-- policy, status and form_kind hold the name of the Java enum constant (GlossaryPolicy, GlossaryEntryStatus, GlossaryFormKind) and are listed in a CHECK:
-- a truth-value type is an optional feature of the standard that some DBMS lack, so no column has one.
-- The glossary's identity and version; one row.
CREATE TABLE sennard.glossary_metadata (
    glossary_id VARCHAR(100) NOT NULL PRIMARY KEY,
    version     VARCHAR(20)  NOT NULL
);

CREATE TABLE sennard.glossary_entries (
    entry_id       VARCHAR(100)  NOT NULL PRIMARY KEY,
    canonical_form VARCHAR(200)  NOT NULL,
    policy         VARCHAR(10)   NOT NULL,
    source_ref     VARCHAR(1000) NOT NULL,
    priority       INTEGER       NOT NULL,
    status         VARCHAR(10)   NOT NULL,
    CONSTRAINT glossary_entries_policy CHECK (policy IN ('PROTECT', 'PREFER', 'ALLOW', 'AVOID')),
    CONSTRAINT glossary_entries_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT glossary_entries_priority CHECK (priority BETWEEN 0 AND 1000)
);

-- The spellings of an entry in the order they were reviewed.
CREATE TABLE sennard.glossary_forms (
    owner_entry_id VARCHAR(100) NOT NULL REFERENCES sennard.glossary_entries (entry_id),
    ordinal        INTEGER      NOT NULL,
    form_text      VARCHAR(200) NOT NULL,
    form_kind      VARCHAR(12)  NOT NULL,
    PRIMARY KEY (owner_entry_id, ordinal),
    CONSTRAINT glossary_forms_kind CHECK (form_kind IN ('CANONICAL', 'ALIAS', 'DISCOURAGED')),
    CONSTRAINT glossary_forms_form UNIQUE (owner_entry_id, form_text)
);
