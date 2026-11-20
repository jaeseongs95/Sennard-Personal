-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- Native host doorbells are distinct from wake_paths' claim-boundary observations.
-- No connection environment or message body is persisted here. STARTED/UNKNOWN remain outstanding after a restart.
CREATE TABLE sennard.native_wake_reservations (
    session_ref VARCHAR(64) NOT NULL PRIMARY KEY,
    generation BIGINT NOT NULL,
    nonce CHAR(36) NOT NULL,
    server_id CHAR(36) NOT NULL,
    state VARCHAR(16) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    retry_after TIMESTAMP NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT native_wake_session FOREIGN KEY (session_ref) REFERENCES sennard.session_ref (session_ref) ON DELETE CASCADE,
    CONSTRAINT native_wake_state CHECK (state IN ('READY','RESERVED','STARTED','SUBMITTED','UNKNOWN','OBSERVED','NOT_SENT','CANCELLED'))
);

UPDATE sennard.module_schema SET version = 5 WHERE module = 'messaging';
