-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

ALTER TABLE sennard.convergence_roots ADD COLUMN stop_rechecks INTEGER NOT NULL DEFAULT 0 CHECK (stop_rechecks >= 0);
ALTER TABLE sennard.workflow_runs ADD COLUMN stop_violation BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sennard.convergence_roots ADD COLUMN plan_target ${long_text};

CREATE TABLE sennard.workflow_blocked_plans (
    record_id VARCHAR(36) NOT NULL UNIQUE,
    workspace_id VARCHAR(64) NOT NULL,
    workspace_path ${long_text} NOT NULL,
    kind VARCHAR(40) NOT NULL,
    plan_digest VARCHAR(71) NOT NULL,
    run_id VARCHAR(255) NOT NULL,
    reason VARCHAR(80) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (workspace_id, kind, plan_digest)
);

-- Plan submission and recording a denial take this row in the same transaction as their workflow changes.
INSERT INTO sennard.workflow_metadata (meta_key, value, updated_at) VALUES ('blocked-plan-write-lock', '1', CURRENT_TIMESTAMP);

UPDATE sennard.module_schema SET version = 11 WHERE module = 'workflow';
