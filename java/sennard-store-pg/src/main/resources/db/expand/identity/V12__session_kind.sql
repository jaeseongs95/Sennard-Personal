-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V12: what kind of session a session_ref is. The session list and the admin page's session binding show main sessions only;
-- a conversation a tool call's metadata named as a child, and a session its host started with an agent chosen at launch, are marked here so the
-- list can leave them out. The column holds the constant's name (SessionKind) and is made with the table (V2), where it is a main session until a host says otherwise;
-- the standard's ALTER TABLE has no ADD COLUMN spelling that Oracle and SQL Server take, so no script adds a column to a table that exists.

ALTER TABLE sennard.session_ref ADD CONSTRAINT session_ref_kind CHECK (kind IN ('MAIN', 'CHILD', 'PRESET_AGENT'));
