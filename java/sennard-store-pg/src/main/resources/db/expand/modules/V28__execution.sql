-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V28: the programs that workflows had run (AGS3-D-0348). One row per execution: who asked (the workflow run and the session), which server installation ran it, the program and where it
-- ran, how it is or ended, and where its output is. The output itself is never in the database: it is in the two log files (stdout.log, stderr.log) of the folder log_location, in the
-- state folder of the server that ran it.
-- There is no foreign key and no BLOB: run_id and requested_by are the text of the workflow run's id and of the session's server-issued reference. Every time column is a TIMESTAMP that
-- holds a UTC value written by the service's one Clock.
--   host_id           the identity of the server installation that ran the program (the UUID of its state folder's host-id file). Several servers on several computers may share this
--                     database; a restarted server accounts only for the rows with its own host_id, because the process and the log files of another's are on another computer.
--   kind              OPERATION (a workflow in which what the program changes in the working folder is recorded and reported).
--   status            RUNNING until the program ends; then EXITED (exit_code is the program's), TIMED_OUT, KILLED or NOT_STARTED. LOST is what a restarted server writes for a row that was
--                     still RUNNING and whose process it cannot find or recognise: the result is unknown, it is neither a success nor a failure, the process may still be alive, and
--                     when it ended is not known (ended_at stays empty).
--   pid, process_started_at   the operating system process, so that a restarted server can tell the same process from another one that has the id later.
--   log_completeness  what the log files held when the execution ended: COMPLETE, or TRUNCATED when a log file stopped at its limit or could not be written to. It says nothing of what
--                     became of the files after that; the two columns below do.
--   log_bytes         how many bytes the log files took when the execution ended (the retention of the logs adds these up and does not measure the folders again); empty while running, and
--                     when the folder could not be measured.
--   log_removed_at    when this server deleted the log files after their retention (UTC); empty until then. Only an ended execution has it.
--   log_unreachable_at  when this server found that log_location is not a folder of its own log folder (the state folder was moved, or the setting changed): it cannot delete those files,
--                     it does not touch them, and it does not look at the record again. Not both this and log_removed_at.
--   aged_at           the time the age of the log files counts from: when the execution ended, and until then (and for a LOST row, whose end is not known) when it started. Never empty, so the
--                     retention of the logs can order and page by it with an index; the database checks that it is COALESCE(ended_at, started_at).
--   log_pending       1 while the log files of the row are for the retention to judge (the execution has ended, has a log folder, and the files are neither deleted nor out of reach), else 0.
--                     The index leads with it, so the rows whose files are gone or out of reach are not read again; the database checks that it is exactly that.
CREATE TABLE sennard.execution (
    id                 CHAR(36)     NOT NULL,
    run_id             VARCHAR(255) NOT NULL,
    kind               VARCHAR(16)  NOT NULL,
    requested_by       VARCHAR(255) NOT NULL,
    host_id            CHAR(36)     NOT NULL,
    argv               ${long_text} NOT NULL,
    cwd                ${long_text} NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    exit_code          INTEGER,
    started_at         TIMESTAMP    NOT NULL,
    ended_at           TIMESTAMP,
    aged_at            TIMESTAMP    NOT NULL,
    log_location       ${long_text},
    log_completeness   VARCHAR(16)  NOT NULL,
    log_bytes          BIGINT,
    log_removed_at     TIMESTAMP,
    log_unreachable_at TIMESTAMP,
    log_pending        SMALLINT     NOT NULL,
    pid                BIGINT,
    process_started_at TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT execution_kind CHECK (kind IN ('OPERATION')),
    CONSTRAINT execution_status CHECK (status IN ('RUNNING', 'EXITED', 'TIMED_OUT', 'KILLED', 'NOT_STARTED', 'LOST')),
    CONSTRAINT execution_log_completeness CHECK (log_completeness IN ('COMPLETE', 'TRUNCATED')),
    CONSTRAINT execution_log_bytes CHECK (log_bytes IS NULL OR log_bytes >= 0),
    CONSTRAINT execution_log_removed CHECK (log_removed_at IS NULL OR (status <> 'RUNNING' AND log_unreachable_at IS NULL)),
    CONSTRAINT execution_ended CHECK ((status IN ('RUNNING', 'LOST') AND ended_at IS NULL) OR (status NOT IN ('RUNNING', 'LOST') AND ended_at IS NOT NULL)),
    CONSTRAINT execution_exit_code CHECK (exit_code IS NULL OR status = 'EXITED'),
    CONSTRAINT execution_aged CHECK (aged_at = COALESCE(ended_at, started_at)),
    CONSTRAINT execution_log_pending CHECK (log_pending = CASE WHEN status <> 'RUNNING' AND log_location IS NOT NULL AND log_removed_at IS NULL AND log_unreachable_at IS NULL THEN 1 ELSE 0 END)
);
-- A restarted server looks for the rows of its own installation that are still RUNNING; a workflow run asks for its executions.
CREATE INDEX execution_host_status_started ON sennard.execution (host_id, status, started_at);
-- The retention of the logs reads the rows whose files are its to judge (log_pending = 1) of one installation, oldest first, a page at a time after a position (aged_at, id), and adds up their
-- sizes: one range of this index, with no row read that was deleted, is out of reach or has not ended.
CREATE INDEX execution_host_pending_aged ON sennard.execution (host_id, log_pending, aged_at, id);
CREATE INDEX execution_run_started ON sennard.execution (run_id, started_at);

INSERT INTO sennard.module_schema (module, version) VALUES ('execution', 1);
