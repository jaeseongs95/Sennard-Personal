-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- The first root generation stays attached to the host-returned child identity even after that root is gone.
CREATE TABLE sennard.managed_child_identity (
    child_ref        VARCHAR(64) NOT NULL,
    root_ref         VARCHAR(64) NOT NULL,
    root_generation  BIGINT      NOT NULL,
    PRIMARY KEY (child_ref),
    CONSTRAINT managed_child_session FOREIGN KEY (child_ref) REFERENCES sennard.session_ref (session_ref) ON DELETE CASCADE,
    CONSTRAINT managed_child_distinct CHECK (child_ref <> root_ref),
    CONSTRAINT managed_child_generation CHECK (root_generation > 0)
);
