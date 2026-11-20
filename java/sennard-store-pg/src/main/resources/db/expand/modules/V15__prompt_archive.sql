-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V15: the prompt archive: what the user said, word for word, and a short account of what the agent did after hearing it.
-- It follows the board's V3 and the messaging's V9: there is no trigger or procedure; the database keeps facts (foreign keys, CHECKs, primary keys).
-- Values are made in Java and handed to the database: a time is the injected clock's UTC instant stored as TIMESTAMP (no time zone), an identifier is made by the application's
-- IdGenerator, the digest and the byte count are computed from the text before it is written.
--
-- The words of the user are never changed after they are written: the code has one write path for user_prompt and no UPDATE or DELETE statement for it (an architecture
-- rule checks that). The agent's recap of a turn is a later note about one prompt, and a recap can be replaced (UPDATE with the
-- version column, optimistic locking). The answer of the agent is not stored at all, and neither is the host's transcript: the archive keeps only where in it a prompt is.
-- One row per user prompt of a main session. The key column is named for its table (user_prompt_id), as the messaging's message_id is, so that the machine check for a reference
-- column without a foreign key (ForeignKeyCatalogTest) reads it as the table's own key.
-- prompt_text is the whole prompt, or its first part when it was over the size limit (completeness = 'TRUNCATED', else 'COMPLETE'); original_bytes and prompt_digest (SHA-256 of the
-- UTF-8 bytes, lower-case hex) are those of the whole prompt. A yes-or-no fact is a name that says what it means, listed in a CHECK (completeness). transcript_path is where the
-- host keeps the session's transcript, as the host named it.
CREATE TABLE sennard.user_prompt (
    user_prompt_id  CHAR(36)         NOT NULL,
    host            VARCHAR(64)      NOT NULL,
    session_id      VARCHAR(512)     NOT NULL,
    received_at     TIMESTAMP        NOT NULL,
    prompt_text     ${long_text}     NOT NULL,
    original_bytes  BIGINT           NOT NULL,
    prompt_digest   CHAR(64)         NOT NULL,
    completeness    VARCHAR(16)      NOT NULL,
    transcript_path ${long_text},
    PRIMARY KEY (user_prompt_id),
    CONSTRAINT user_prompt_completeness CHECK (completeness IN ('COMPLETE', 'TRUNCATED')),
    CONSTRAINT user_prompt_original_bytes CHECK (original_bytes >= 0)
);
CREATE INDEX user_prompt_session ON sennard.user_prompt (host, session_id, received_at DESC, user_prompt_id DESC);
CREATE INDEX user_prompt_received_at ON sennard.user_prompt (received_at DESC);

-- At most one recap per prompt (the primary key). The agent writes it at the end of the turn; a second recording for the same prompt replaces the first.
CREATE TABLE sennard.turn_recap (
    user_prompt_id CHAR(36)         NOT NULL,
    recap_text     ${long_text}     NOT NULL,
    recorded_at    TIMESTAMP        NOT NULL,
    version        BIGINT           NOT NULL,
    PRIMARY KEY (user_prompt_id),
    CONSTRAINT turn_recap_prompt FOREIGN KEY (user_prompt_id) REFERENCES sennard.user_prompt (user_prompt_id)
);

-- The one reminder a turn's end gives for a prompt that has no recap: the key lets a second turn end of the same prompt (even at the same moment) find the first one's row,
-- so the agent is asked once per prompt. Written once and never changed.
CREATE TABLE sennard.recap_nudge (
    user_prompt_id CHAR(36)  NOT NULL,
    nudged_at      TIMESTAMP NOT NULL,
    PRIMARY KEY (user_prompt_id),
    CONSTRAINT recap_nudge_prompt FOREIGN KEY (user_prompt_id) REFERENCES sennard.user_prompt (user_prompt_id)
);

-- Where in the host's transcript the entry of a prompt is (read_prompt_transcript): the host's identifier of the entry (Claude Code: the line's uuid), found the first time the
-- transcript is read and kept so that the next read starts there. It is a position and nothing of the transcript itself: no text, no line, no copy. At most one per prompt (the
-- primary key), so two first readings of one prompt at once meet it and the loser reads the winner's row. Written once and never changed.
CREATE TABLE sennard.transcript_anchor (
    user_prompt_id CHAR(36)     NOT NULL,
    entry_key      VARCHAR(512) NOT NULL,
    located_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (user_prompt_id),
    CONSTRAINT transcript_anchor_prompt FOREIGN KEY (user_prompt_id) REFERENCES sennard.user_prompt (user_prompt_id)
);

INSERT INTO sennard.module_schema (module, version) VALUES ('prompt', 1);
