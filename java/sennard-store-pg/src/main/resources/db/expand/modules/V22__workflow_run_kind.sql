-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V22: the kind of a workflow run and the skills its start declared, so that the admin page's workflow statistics are counted by the database (GROUP BY) and not by reading every receipt
-- of the period. Before this the kind was cut out of a text the continuity observations hold and the skills out of the receipt's JSON.
--
-- The kind is a column of workflow_runs, added to the table that exists. ALTER TABLE t ADD c <type> is the spelling every database Sennard runs on takes (PostgreSQL, Oracle, SQL Server,
-- MySQL, MariaDB, Db2 and H2); the keyword COLUMN is the part that differs (SQL Server and Oracle refuse it), so the script leaves it out. The column is NOT NULL from its first instant:
-- DEFAULT comes before NOT NULL, the one order Oracle takes and the others accept, and the default is the value a run without a recorded kind has always been counted under (CHANGE: a run
-- opened by the longer path is a change). So the rows of the databases that ran V8 before this script are given CHANGE by the database itself, in the ALTER, and no UPDATE and no second
-- statement that turns NOT NULL on is needed (that one is spelled differently by each product: SET NOT NULL, MODIFY, ALTER COLUMN ... NOT NULL). The default stays on the column; the
-- service writes the kind of every run it starts, so it is never the value of a new run by accident of a missing column in a statement.
-- kind holds the name of the constant of the server's enum WorkflowKind; this module does not know the kinds, so there is no CHECK listing them (a kind added to the enum needs no script).
ALTER TABLE sennard.workflow_runs ADD kind VARCHAR(32) DEFAULT 'CHANGE' NOT NULL;

-- The statistics ask for the runs of a period: WHERE created_at >= ? GROUP BY kind, and the newest list: ORDER BY created_at DESC LIMIT 50. created_at leads for the range and the order;
-- kind follows so the count by kind is read from the index alone.
CREATE INDEX workflow_runs_period ON sennard.workflow_runs (created_at, kind);

-- One row per skill a run's start declared (the skill's own name, without a plugin prefix). The primary key (run_id, skill_id) holds a skill once per run, so a count of rows is a
-- count of runs, and it is the index the statistics reach the skills of a run by (the runs of the period come from workflow_runs_period, then this key by run_id). The skills of a run
-- started before this script are not written back: they are in its receipt only, and a script of this runner is SQL, which cannot read a JSON document portably; the statistics count
-- such a run under its kind with no skills.
CREATE TABLE sennard.workflow_run_skills (
    run_id VARCHAR(255) NOT NULL,
    skill_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (run_id, skill_id),
    FOREIGN KEY (run_id) REFERENCES sennard.workflow_runs (run_id)
);

UPDATE sennard.module_schema SET version = 9 WHERE module = 'workflow';
