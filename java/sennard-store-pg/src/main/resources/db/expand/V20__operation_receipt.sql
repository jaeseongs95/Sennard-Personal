-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- The receipt of a write tool's operation (AGS3-D-0267): the caller's operationKey names one operation of one tool of one caller, and the first answer that succeeded is kept under it.
-- A request that comes again with the same key and the same content is answered with the stored answer; the same key with other content is a conflict (OPERATION_CONFLICT).
-- Only a success is stored. A refusal has no effect and is rolled back with its transaction, so the same key sent again is judged again and is refused again for the same reason.
-- The row is written in the transaction of the use case that did the work, so the effect and its receipt commit or roll back together. There is no foreign key: the receipt
-- refers to no row of another table.
-- The key is the caller's own: scope is the server-issued reference of the session that called (two sessions that send one key for one tool are two operations), and the fixed
-- text 'global' for open_session, which is called before the caller has a session.
CREATE TABLE sennard.operation_receipt (
    tool VARCHAR(64) NOT NULL,
    scope VARCHAR(128) NOT NULL,
    operation_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    response ${long_text} NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tool, scope, operation_key)
);
-- A receipt older than the retention (sennard.operations.receipt-retention) is removed when a new one is written; the index finds them.
CREATE INDEX operation_receipt_created ON sennard.operation_receipt (created_at);

INSERT INTO sennard.module_schema (module, version) VALUES ('operation', 1);
