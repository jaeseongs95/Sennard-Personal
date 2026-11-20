-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- A pause is a run state, not a stage or attempt outcome. Existing rows and receipts stay byte-for-byte as they were.
ALTER TABLE sennard.workflow_runs DROP CONSTRAINT workflow_runs_state;
ALTER TABLE sennard.workflow_runs ADD CONSTRAINT workflow_runs_state CHECK (state IN ('READY', 'RUNNING', 'PAUSED', 'NEEDS_INPUT', 'NEEDS_APPROVAL', 'NEEDS_REDESIGN', 'FAILED', 'PASSED', 'BLOCKED'));

UPDATE sennard.module_schema SET version = 10 WHERE module = 'workflow';
