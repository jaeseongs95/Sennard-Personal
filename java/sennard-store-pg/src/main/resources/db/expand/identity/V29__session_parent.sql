-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

ALTER TABLE sennard.session_ref ADD COLUMN parent_ref VARCHAR(64);

-- Earlier managed launches recorded the directly creating main conversation.
-- Observations that only recorded an ultimate root cannot establish a direct edge.
UPDATE sennard.session_ref SET parent_ref = (
    SELECT m.root_ref FROM sennard.managed_child_identity m
    JOIN sennard.session_generation g ON g.session_ref = m.root_ref AND g.generation = m.root_generation
    WHERE m.child_ref = sennard.session_ref.session_ref
) WHERE kind = 'CHILD';

ALTER TABLE sennard.session_ref ADD CONSTRAINT session_ref_parent
    FOREIGN KEY (parent_ref) REFERENCES sennard.session_ref (session_ref);
ALTER TABLE sennard.session_ref ADD CONSTRAINT session_ref_distinct_parent
    CHECK (parent_ref IS NULL OR (kind = 'CHILD' AND parent_ref <> session_ref));
