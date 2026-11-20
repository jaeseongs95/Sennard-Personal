-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V21: the attachments (AGS3-D-0264; the design the three products share, docs/contributing/attachments.md). The tables hold metadata and where a file is; the bytes of an attachment are
-- never in the database: they are AES-256-GCM segments in a file of the server's own folder, and no column of these tables can hold them (no BLOB, no long text for a body).
-- An attachment is a bundle of files (one bundle, N files). The tables of a bundle are records that are inserted and never changed:
--   attachment          one row per bundle: its id, who made it, when. A record that points at attachments carries this key only.
--   attachment_file     one row per file of a bundle, (attachment_id, seq): the name, the media type, the size and the sha256 of the plain bytes (the facts of the file as it was sent).
--   attachment_storage  one row per stored copy of a file, (attachment_id, seq, rev): where the encrypted bytes are and with which key. The upload's closing writes rev 0 and each
--                       re-encryption writes the next rev; the current copy is the row of the highest rev. The primary key is what makes two re-encryptions of one file end in a duplicate.
-- There is no foreign key on purpose (the owner's decision): a record that points at an attachment carries the attachment's key, and the use case that writes the record checks that
-- the attachment is there and is the author's. Every time column is a TIMESTAMP that holds a UTC value written by the service's one Clock.
-- An upload in progress: the one table of the attachments that changes. An upload is one file of a bundle: attachment_id is the id of the attachment its finish makes (shared by the uploads
-- begun together) and seq its number in it. The key of an operation is (owner, operation_key, seq): one begin with several files makes one row each under the same operation key.
-- owner is the author (the calling session as the server observed it). storage_key is the random name of the
-- file; the file holds next_sequence segments of the chunks received, and stored_bytes is the size of the file the rows have taken in (the file may be longer by one segment that a
-- lost transaction left, which a later chunk or finish takes in). received_bytes counts the plain bytes. A row that is COMPLETED has made its attachment and waits for the cleaner.
CREATE TABLE sennard.upload_staging (
    id                CHAR(36)     NOT NULL,
    attachment_id     CHAR(36)     NOT NULL,
    seq               INTEGER      NOT NULL,
    owner             VARCHAR(255) NOT NULL,
    operation_key     VARCHAR(128) NOT NULL,
    file_name         VARCHAR(512) NOT NULL,
    media_type        VARCHAR(255) NOT NULL,
    declared_size     BIGINT       NOT NULL,
    declared_sha256   CHAR(64)     NOT NULL,
    received_bytes    BIGINT       NOT NULL,
    stored_bytes      BIGINT       NOT NULL,
    next_sequence     BIGINT       NOT NULL,
    last_chunk_sha256 CHAR(64),
    storage_key       CHAR(32)     NOT NULL,
    key_id            VARCHAR(64)  NOT NULL,
    state             VARCHAR(16)  NOT NULL,
    expires_at        TIMESTAMP    NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT upload_staging_operation UNIQUE (owner, operation_key, seq),
    CONSTRAINT upload_staging_bundle UNIQUE (attachment_id, seq),
    CONSTRAINT upload_staging_storage_key UNIQUE (storage_key),
    CONSTRAINT upload_staging_seq CHECK (seq >= 0),
    CONSTRAINT upload_staging_state CHECK (state IN ('STAGING', 'COMPLETED')),
    CONSTRAINT upload_staging_sizes CHECK (declared_size >= 0 AND received_bytes >= 0 AND stored_bytes >= 0 AND next_sequence >= 0)
);
-- The cleaner looks for the uploads that expired, and for the ones that are done.
CREATE INDEX upload_staging_state_expiry ON sennard.upload_staging (state, expires_at);

-- A kept bundle (a record: inserted, never changed). created_by is the author. The files are in attachment_file.
CREATE TABLE sennard.attachment (
    id         CHAR(36)     NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

-- The files of a bundle (a record: inserted, never changed). content_sha256 is the hash of the plain bytes, size_bytes their number.
CREATE TABLE sennard.attachment_file (
    attachment_id  CHAR(36)     NOT NULL,
    seq            INTEGER      NOT NULL,
    file_name      VARCHAR(512) NOT NULL,
    media_type     VARCHAR(255) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    content_sha256 CHAR(64)     NOT NULL,
    PRIMARY KEY (attachment_id, seq),
    CONSTRAINT attachment_file_numbers CHECK (seq >= 0 AND size_bytes >= 0)
);

-- Where the bytes of a file are, one row per copy it was written to (a record: inserted, never changed). Rev 0 is the file the finish closed; a later rev is the file a re-encryption
-- wrote under another key. The current copy of a file is the row of the highest rev. The primary key is the triple; there is no surrogate key.
CREATE TABLE sennard.attachment_storage (
    attachment_id CHAR(36)    NOT NULL,
    seq           INTEGER     NOT NULL,
    rev           INTEGER     NOT NULL,
    storage_key   CHAR(32)    NOT NULL,
    key_id        VARCHAR(64) NOT NULL,
    created_at    TIMESTAMP   NOT NULL,
    PRIMARY KEY (attachment_id, seq, rev),
    CONSTRAINT attachment_storage_storage_key UNIQUE (storage_key),
    CONSTRAINT attachment_storage_numbers CHECK (seq >= 0 AND rev >= 0)
);

INSERT INTO sennard.module_schema (module, version) VALUES ('attachment', 1);
