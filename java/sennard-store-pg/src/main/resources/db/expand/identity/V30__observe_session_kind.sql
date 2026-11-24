-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

ALTER TABLE sennard.session_ref DROP CONSTRAINT session_ref_kind;
ALTER TABLE sennard.session_ref ADD CONSTRAINT session_ref_kind
    CHECK (kind IN ('UNKNOWN', 'MAIN', 'CHILD', 'PRESET_AGENT'));

-- Earlier Antigravity startup hooks recorded the same kind for both roots and uncorrelated conversations.
-- The next matching tool call confirms which role the conversation has.
UPDATE sennard.session_ref SET kind = 'UNKNOWN' WHERE host = 'antigravity' AND kind = 'MAIN';
