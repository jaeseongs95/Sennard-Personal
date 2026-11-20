-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V1: the tables every other script depends on. They hold no business rows. The business database is used by one account, so the scripts declare only
-- tables, keys, CHECK constraints and indexes: ANSI SQL, with no function, trigger, procedure or vendor type, and nothing the vendors differ in.
-- The migration runner made the schema sennard (and its history tables) before this script, so names are qualified and nothing relies on a search path.
-- Every time column of the schema is a TIMESTAMP that holds a UTC value written by the service's one Clock (Timestamps in sennard-domain); the database has no clock of its own here.
-- ${long_text} is the long text type of the database; the runner replaces it with the declaration SqlBuilder.generateLongText gives for the product's dialect.

-- The version each module's schema is at. Rows are written by the script that creates a module's tables (V3 and later).
CREATE TABLE sennard.module_schema (
    module  VARCHAR(32) NOT NULL PRIMARY KEY,
    version BIGINT      NOT NULL
);
