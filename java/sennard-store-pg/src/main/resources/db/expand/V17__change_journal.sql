-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- The journal stores paths and metadata, never file contents. Times are UTC TIMESTAMPs.
-- The optimistic version prevents a scan based on an older snapshot from replacing a concurrent hook event.
CREATE TABLE sennard.journal_workflow (
    workflow_id VARCHAR(255) NOT NULL,
    baseline_state VARCHAR(16) NOT NULL CHECK (baseline_state IN ('EMPTY', 'CAPTURED')),
    version BIGINT NOT NULL,
    PRIMARY KEY (workflow_id)
);

CREATE TABLE sennard.plan_revision (
    plan_revision_id CHAR(36) NOT NULL,
    workflow_id VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (plan_revision_id),
    UNIQUE (workflow_id, revision),
    FOREIGN KEY (workflow_id) REFERENCES sennard.journal_workflow (workflow_id)
);

CREATE TABLE sennard.plan_file (
    plan_file_id CHAR(36) NOT NULL,
    plan_revision_id CHAR(36) NOT NULL,
    path ${long_text} NOT NULL,
    path_hash CHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (plan_file_id),
    FOREIGN KEY (plan_revision_id) REFERENCES sennard.plan_revision (plan_revision_id),
    UNIQUE (plan_revision_id, path_hash)
);

CREATE TABLE sennard.baseline_file (
    baseline_file_id CHAR(36) NOT NULL,
    workflow_id VARCHAR(255) NOT NULL,
    path ${long_text} NOT NULL,
    path_hash CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    sha256 CHAR(64),
    version BIGINT NOT NULL,
    PRIMARY KEY (baseline_file_id),
    UNIQUE (workflow_id, path_hash),
    FOREIGN KEY (workflow_id) REFERENCES sennard.journal_workflow (workflow_id)
);

CREATE TABLE sennard.write_root (
    write_root_id CHAR(36) NOT NULL,
    workflow_id VARCHAR(255) NOT NULL,
    path ${long_text} NOT NULL,
    path_hash CHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (write_root_id),
    UNIQUE (workflow_id, path_hash),
    FOREIGN KEY (workflow_id) REFERENCES sennard.journal_workflow (workflow_id)
);

CREATE TABLE sennard.change_event (
    change_event_id CHAR(36) NOT NULL,
    workflow_id VARCHAR(255) NOT NULL,
    plan_revision BIGINT NOT NULL,
    path ${long_text} NOT NULL,
    path_hash CHAR(64) NOT NULL,
    sha256 CHAR(64),
    size_bytes BIGINT,
    modified_at TIMESTAMP,
    recorded_at TIMESTAMP NOT NULL,
    source VARCHAR(16) NOT NULL CHECK (source IN ('HOOK', 'RESCAN')),
    version BIGINT NOT NULL,
    PRIMARY KEY (change_event_id),
    FOREIGN KEY (workflow_id, plan_revision) REFERENCES sennard.plan_revision (workflow_id, revision)
);
CREATE INDEX change_event_workflow_path ON sennard.change_event (workflow_id, path_hash);

-- The latest observation is separate from the immutable baseline; a null size marks a deletion.
CREATE TABLE sennard.observed_file (
    observed_file_id CHAR(36) NOT NULL,
    workflow_id VARCHAR(255) NOT NULL,
    path ${long_text} NOT NULL,
    path_hash CHAR(64) NOT NULL,
    size_bytes BIGINT,
    modified_at TIMESTAMP,
    sha256 CHAR(64),
    version BIGINT NOT NULL,
    PRIMARY KEY (observed_file_id),
    UNIQUE (workflow_id, path_hash),
    FOREIGN KEY (workflow_id) REFERENCES sennard.journal_workflow (workflow_id)
);

-- The most recent rescan's unreadable paths, so a later result cannot call them unchanged.
CREATE TABLE sennard.scan_failure (
    scan_failure_id CHAR(36) NOT NULL,
    workflow_id VARCHAR(255) NOT NULL,
    path ${long_text} NOT NULL,
    reason VARCHAR(32) NOT NULL CHECK (reason IN ('NOT_FOUND', 'ACCESS_DENIED', 'NOT_A_REGULAR_FILE', 'LINK_OR_SPECIAL_SKIPPED', 'CHANGED_DURING_SCAN', 'IO_ERROR')),
    version BIGINT NOT NULL,
    PRIMARY KEY (scan_failure_id),
    FOREIGN KEY (workflow_id) REFERENCES sennard.journal_workflow (workflow_id)
);
CREATE INDEX scan_failure_workflow ON sennard.scan_failure (workflow_id);

INSERT INTO sennard.module_schema (module, version) VALUES ('journal', 1);
