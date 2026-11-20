-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V9: the session messaging queue.
-- Rewritten in 6.0.0 (plan D9), as the board's V3 was: the concurrency is the primary and UNIQUE keys, the version column (optimistic locking) and the serializable
-- isolation of the two methods that judge a limit, not a lock the code asks for; there is no trigger or procedure. The database keeps facts:
-- foreign keys, CHECKs and UNIQUE keys.
-- Values are made in Java and handed to the database (plan D9): a time is the injected clock's UTC instant stored as TIMESTAMP (no time zone: the conversion is Java's,
-- see MessagingService), an identifier is made by the application's IdGenerator, a state is the name of a Java enum constant held in a VARCHAR and listed in a CHECK.
-- Sessions are sennard.session_ref values (identity area): every reference to one is a foreign key to (session_ref).
-- The counters this queue keeps (pending_count, sender_drafts, sender_receipts of session_ref) stay in the identity
-- area's table and are changed by conditional UPDATEs of sennard-app (SessionRefRepository); next_message_seq of session_ref is no longer
-- used: the order of a queue is the seq column below, made in Java.
-- Standard types only: an id or a session reference is a VARCHAR of a size, a body is ${long_text}; the new columns (state, version, times) use standard types too. Nothing depends on a collation.
-- A message of a sender: first a draft (state PREPARED, the body is kept), after the send its receipt (SUBMITTED, no body, the send time and the message's expiry), or
-- RECALLED when the sender took it back. The state is stored by the enum constant's name (MessageState); who is queued, delivered or acknowledged is not a state here:
-- it is read from the times of the queue row in messages (claimed_at, acknowledged_at), which the claim and the acknowledgement write anyway.
CREATE TABLE sennard.prepared_messages (
    message_id  VARCHAR(255) NOT NULL,
    sender_ref  VARCHAR(64) NOT NULL,
    target_ref  VARCHAR(64) NOT NULL,
    body        ${long_text},
    ttl_seconds BIGINT           NOT NULL,
    prepared_at TIMESTAMP        NOT NULL,
    expires_at  TIMESTAMP        NOT NULL,
    state       VARCHAR(16)      NOT NULL,
    sent_at     TIMESTAMP,
    message_expires_at TIMESTAMP,
    recalled_at TIMESTAMP,
    record_bytes BIGINT          NOT NULL,
    version     BIGINT           NOT NULL,
    PRIMARY KEY (message_id),
    CONSTRAINT prepared_messages_sender FOREIGN KEY (sender_ref) REFERENCES sennard.session_ref (session_ref),
    CONSTRAINT prepared_messages_target FOREIGN KEY (target_ref) REFERENCES sennard.session_ref (session_ref),
    CONSTRAINT prepared_messages_state CHECK (state IN ('PREPARED', 'SUBMITTED', 'RECALLED')),
    -- what each state keeps: a draft has its body and nothing of a send; a receipt has no body and has when it was sent and when the message expires; a recalled one also when.
    CONSTRAINT prepared_messages_facts CHECK (
           (state = 'PREPARED'  AND body IS NOT NULL AND sent_at IS NULL     AND message_expires_at IS NULL     AND recalled_at IS NULL)
        OR (state = 'SUBMITTED' AND body IS NULL     AND sent_at IS NOT NULL AND message_expires_at IS NOT NULL AND recalled_at IS NULL)
        OR (state = 'RECALLED'  AND body IS NULL     AND sent_at IS NOT NULL AND message_expires_at IS NOT NULL AND recalled_at IS NOT NULL))
);
CREATE INDEX prepared_messages_expiry ON sennard.prepared_messages (expires_at);
CREATE INDEX prepared_messages_owner ON sennard.prepared_messages (sender_ref);

-- A message in its recipient's queue. Every message has its prepared_messages row (the ID is the server's), so the row below is removed before its draft or receipt.
-- seq is the recipient's order, kept by the UNIQUE key and made in Java (the recipient's highest plus one; a second writer that makes the same number meets the key).
-- Not stored: a state (see above) and any lock; a claim and an acknowledgement change the row with its version.
CREATE TABLE sennard.messages (
    message_id  VARCHAR(255) NOT NULL,
    sender_ref  VARCHAR(64) NOT NULL,
    sender_host VARCHAR(64) NOT NULL,
    target_ref  VARCHAR(64) NOT NULL,
    seq         BIGINT           NOT NULL,
    body        ${long_text} NOT NULL,
    body_bytes  BIGINT           NOT NULL,
    created_at  TIMESTAMP        NOT NULL,
    expires_at  TIMESTAMP        NOT NULL,
    claimed_at  TIMESTAMP,
    claim_until TIMESTAMP,
    delivery_attempts BIGINT     NOT NULL DEFAULT 0,
    first_delivered_at TIMESTAMP,
    acknowledged_at TIMESTAMP,
    claim_receipt VARCHAR(255),
    acknowledgement_receipt VARCHAR(255),
    version     BIGINT           NOT NULL,
    PRIMARY KEY (message_id),
    CONSTRAINT messages_prepared FOREIGN KEY (message_id) REFERENCES sennard.prepared_messages (message_id),
    CONSTRAINT messages_sender FOREIGN KEY (sender_ref) REFERENCES sennard.session_ref (session_ref),
    CONSTRAINT messages_target FOREIGN KEY (target_ref) REFERENCES sennard.session_ref (session_ref),
    CONSTRAINT messages_target_seq UNIQUE (target_ref, seq),
    -- A claim has its receipt, "<delivery attempt>:<uuid>", and the number before the colon is the attempt count (standard POSITION, SUBSTRING and CAST: the former
    -- strpos/substr/::text were PostgreSQL's). The receipt of an acknowledgement is the one of the claim.
    CONSTRAINT messages_claim_receipt CHECK ((claim_receipt IS NULL AND delivery_attempts = 0)
        OR (claim_receipt IS NOT NULL AND delivery_attempts > 0
            AND SUBSTRING(claim_receipt FROM 1 FOR POSITION(':' IN claim_receipt) - 1) = CAST(delivery_attempts AS CHAR(20)))),
    CONSTRAINT messages_acknowledgement_receipt CHECK (acknowledged_at IS NULL OR claim_receipt IS NULL OR (acknowledgement_receipt IS NOT NULL AND acknowledgement_receipt = claim_receipt))
);
CREATE INDEX messages_target_pending ON sennard.messages (target_ref, acknowledged_at, expires_at, claim_until);
CREATE INDEX messages_expiry ON sennard.messages (expires_at);

-- Keyed by session_ref; instance_id and birth_generation are the server-minted generation. The last time a message was claimed at a hook boundary of that generation.
CREATE TABLE sennard.wake_paths (
    session_ref VARCHAR(64) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    birth_generation VARCHAR(255) NOT NULL,
    claim_boundary_at TIMESTAMP,
    updated_at  TIMESTAMP        NOT NULL,
    version     BIGINT           NOT NULL,
    PRIMARY KEY (session_ref, instance_id, birth_generation),
    CONSTRAINT wake_paths_session FOREIGN KEY (session_ref) REFERENCES sennard.session_ref (session_ref)
);

-- What the last input seen on a session means for the next tool-boundary claim: DEFERRED (the claim is skipped once) or SETTLED (nothing pending). It was a 0/1 flag
-- that is only ever one of these two states, so it is the name of ObservationState now.
CREATE TABLE sennard.input_observations (
    session_ref VARCHAR(64) NOT NULL,
    state       VARCHAR(16)      NOT NULL,
    observed_at TIMESTAMP        NOT NULL,
    version     BIGINT           NOT NULL,
    PRIMARY KEY (session_ref),
    CONSTRAINT input_observations_session FOREIGN KEY (session_ref) REFERENCES sennard.session_ref (session_ref),
    CONSTRAINT input_observations_state CHECK (state IN ('DEFERRED', 'SETTLED'))
);

CREATE TABLE sennard.peer_wait_snapshots (
    query_key   VARCHAR(255) NOT NULL,
    owner_key   VARCHAR(255) NOT NULL,
    fingerprint VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMP        NOT NULL,
    version     BIGINT           NOT NULL,
    PRIMARY KEY (query_key)
);
CREATE INDEX peer_wait_snapshots_expiry ON sennard.peer_wait_snapshots (expires_at);

INSERT INTO sennard.module_schema (module, version) VALUES ('messaging', 4);
