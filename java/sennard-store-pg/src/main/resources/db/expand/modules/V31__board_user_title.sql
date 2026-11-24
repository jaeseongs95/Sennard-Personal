-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

ALTER TABLE sennard.board_sessions
    ADD COLUMN user_title ${long_text},
    ADD COLUMN user_title_observed_at TIMESTAMP;
