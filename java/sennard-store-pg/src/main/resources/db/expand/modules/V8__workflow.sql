-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V8: workflow and convergence. The call admissions are in sennard.call_admission (V11).
-- The column types are ANSI SQL types; ${long_text} stands for the long text type of the database (it has no one name in SQL), filled in when the script is applied.
CREATE TABLE sennard.workflow_metadata (
    meta_key VARCHAR(255) NOT NULL, -- not named key: KEY is a reserved word of MariaDB and MySQL
    value ${long_text} NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (meta_key)
);

CREATE TABLE sennard.workflow_runs (
    run_id VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL, -- RunState: the enum constant's name
    receipt_json ${long_text} NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (run_id),
    CONSTRAINT workflow_runs_revision CHECK (revision >= 0),
    CONSTRAINT workflow_runs_state CHECK (state IN ('READY', 'RUNNING', 'NEEDS_INPUT', 'NEEDS_APPROVAL', 'NEEDS_REDESIGN', 'FAILED', 'PASSED', 'BLOCKED'))
);
CREATE INDEX workflow_runs_cleanup ON sennard.workflow_runs (state, updated_at);

CREATE TABLE sennard.plugin_update_state (
    target_id VARCHAR(255) NOT NULL,
    current_version VARCHAR(255) NOT NULL,
    latest_version VARCHAR(255),
    latest_tag VARCHAR(255),
    latest_commit VARCHAR(255),
    etag VARCHAR(512),
    comparison VARCHAR(32) NOT NULL, -- UpdateComparison: the enum constant's name
    last_attempt_at TIMESTAMP,
    last_successful_check_at TIMESTAMP,
    next_check_at TIMESTAMP NOT NULL,
    last_notified_version VARCHAR(255),
    last_notified_at TIMESTAMP,
    last_error_code VARCHAR(32),
    PRIMARY KEY (target_id),
    CONSTRAINT plugin_update_state_comparison CHECK (comparison IN ('UNKNOWN', 'UP_TO_DATE', 'UPDATE_AVAILABLE', 'AHEAD_OF_STABLE')),
    CONSTRAINT plugin_update_state_error CHECK (last_error_code IS NULL OR last_error_code IN ('TIMEOUT', 'NETWORK', 'HTTP', 'INVALID_RESPONSE', 'NO_STABLE_TAG'))
);

CREATE TABLE sennard.convergence_roots (
    root_id VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL, -- RootState
    workspace_id VARCHAR(255) NOT NULL,
    workspace_locator ${long_text} NOT NULL,
    root_json ${long_text} NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (root_id),
    CONSTRAINT convergence_roots_revision CHECK (revision >= 0),
    CONSTRAINT convergence_roots_state CHECK (state IN ('OPEN', 'NEEDS_REVIEW', 'NEEDS_USER', 'COMPLETED', 'ABANDONED'))
);
CREATE INDEX convergence_active_roots_by_workspace ON sennard.convergence_roots (workspace_id, state);

CREATE TABLE sennard.convergence_root_identities (
    root_id VARCHAR(255) NOT NULL,
    identity_json ${long_text} NOT NULL,
    surface_digest VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (root_id)
);

CREATE TABLE sennard.convergence_epochs (
    root_id VARCHAR(255) NOT NULL,
    epoch BIGINT NOT NULL,
    frame_digest VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (root_id, epoch),
    CONSTRAINT convergence_epochs_epoch CHECK (epoch >= 1),
    FOREIGN KEY (root_id) REFERENCES sennard.convergence_roots (root_id)
);

CREATE TABLE sennard.convergence_leases (
    lease_id VARCHAR(255) NOT NULL,
    root_id VARCHAR(255) NOT NULL,
    root_revision BIGINT NOT NULL,
    epoch BIGINT NOT NULL,
    ordinal BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL, -- LeaseState
    lease_json ${long_text} NOT NULL,
    proposal_json ${long_text} NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    PRIMARY KEY (lease_id),
    UNIQUE (root_id, epoch, ordinal, lease_id),
    CONSTRAINT convergence_leases_revision CHECK (root_revision >= 0),
    CONSTRAINT convergence_leases_epoch CHECK (epoch >= 1),
    CONSTRAINT convergence_leases_ordinal CHECK (ordinal >= 1),
    CONSTRAINT convergence_leases_state CHECK (state IN ('ISSUED', 'CONSUMED', 'EXPIRED')),
    FOREIGN KEY (root_id) REFERENCES sennard.convergence_roots (root_id)
);

-- One issued lease per root: the lease that is ISSUED has a row here, written when it is issued and removed when it is consumed or expires; the primary key is the root, so a
-- second issued lease fails here. (A UNIQUE index over the issued leases only would be a partial index, which the standard has not.)
CREATE TABLE sennard.convergence_issued_leases (
    root_id VARCHAR(255) NOT NULL,
    lease_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (root_id),
    UNIQUE (lease_id),
    FOREIGN KEY (root_id) REFERENCES sennard.convergence_roots (root_id),
    FOREIGN KEY (lease_id) REFERENCES sennard.convergence_leases (lease_id)
);

CREATE TABLE sennard.convergence_attempts (
    root_id VARCHAR(255) NOT NULL,
    epoch BIGINT NOT NULL,
    ordinal BIGINT NOT NULL,
    lease_id VARCHAR(255) NOT NULL,
    run_id VARCHAR(255) NOT NULL,
    state VARCHAR(16) NOT NULL, -- AttemptState
    outcome_json ${long_text},
    started_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (root_id, epoch, ordinal),
    UNIQUE (lease_id),
    UNIQUE (run_id),
    CONSTRAINT convergence_attempts_epoch CHECK (epoch >= 1),
    CONSTRAINT convergence_attempts_ordinal CHECK (ordinal >= 1),
    CONSTRAINT convergence_attempts_state CHECK (state IN ('RUNNING', 'PASSED', 'FAILED', 'ABORTED')),
    FOREIGN KEY (root_id) REFERENCES sennard.convergence_roots (root_id),
    FOREIGN KEY (lease_id) REFERENCES sennard.convergence_leases (lease_id),
    FOREIGN KEY (run_id) REFERENCES sennard.workflow_runs (run_id)
);

CREATE TABLE sennard.convergence_reviews (
    review_id VARCHAR(255) NOT NULL,
    root_id VARCHAR(255) NOT NULL,
    epoch BIGINT NOT NULL,
    review_json ${long_text} NOT NULL,
    reviewed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (review_id),
    CONSTRAINT convergence_reviews_epoch CHECK (epoch >= 1),
    FOREIGN KEY (root_id) REFERENCES sennard.convergence_roots (root_id)
);

CREATE TABLE sennard.workflow_attempt_links (
    run_id VARCHAR(255) NOT NULL,
    root_id VARCHAR(255) NOT NULL,
    lease_id VARCHAR(255) NOT NULL,
    epoch BIGINT NOT NULL,
    ordinal BIGINT NOT NULL,
    PRIMARY KEY (run_id),
    UNIQUE (lease_id),
    CONSTRAINT workflow_attempt_links_epoch CHECK (epoch >= 1),
    CONSTRAINT workflow_attempt_links_ordinal CHECK (ordinal >= 1),
    FOREIGN KEY (run_id) REFERENCES sennard.workflow_runs (run_id),
    FOREIGN KEY (root_id) REFERENCES sennard.convergence_roots (root_id),
    FOREIGN KEY (lease_id) REFERENCES sennard.convergence_leases (lease_id)
);

CREATE TABLE sennard.state_cleanup_claims (
    plan_id VARCHAR(255) NOT NULL,
    plan_digest VARCHAR(255) NOT NULL,
    claimed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (plan_id)
);

-- A claim never expires.
CREATE TABLE sennard.execution_observation_claims (
    observation_id VARCHAR(255) NOT NULL,
    consumed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (observation_id)
);

CREATE TABLE sennard.root_approval_decisions (
    approval_id VARCHAR(255) NOT NULL,
    scope_digest VARCHAR(255) NOT NULL,
    connection_id VARCHAR(255) NOT NULL,
    request_id VARCHAR(255) NOT NULL,
    record_json ${long_text} NOT NULL,
    created_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    PRIMARY KEY (approval_id),
    UNIQUE (connection_id, request_id)
);
CREATE INDEX root_approval_scope ON sennard.root_approval_decisions (scope_digest, revoked_at);

CREATE TABLE sennard.workflow_cleanup_backup (
    plan_id VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    row_no BIGINT NOT NULL,
    row_json ${long_text} NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (plan_id, table_name, row_no),
    CONSTRAINT workflow_cleanup_backup_plan FOREIGN KEY (plan_id) REFERENCES sennard.state_cleanup_claims (plan_id)
);

INSERT INTO sennard.module_schema (module, version) VALUES ('workflow', 7);
