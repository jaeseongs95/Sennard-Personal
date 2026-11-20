-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- A peer address is the existing session reference plus an optional host-observed actor.
-- Existing rows and unqualified addresses use the empty component. Counters and sequence allocation stay per session.
ALTER TABLE sennard.prepared_messages ADD COLUMN sender_actor_id VARCHAR(200) DEFAULT '' NOT NULL;
ALTER TABLE sennard.prepared_messages ADD COLUMN target_actor_id VARCHAR(200) DEFAULT '' NOT NULL;
ALTER TABLE sennard.messages ADD COLUMN sender_actor_id VARCHAR(200) DEFAULT '' NOT NULL;
ALTER TABLE sennard.messages ADD COLUMN target_actor_id VARCHAR(200) DEFAULT '' NOT NULL;

CREATE INDEX messages_actor_pending ON sennard.messages (target_ref, target_actor_id, acknowledged_at, expires_at, claim_until);
