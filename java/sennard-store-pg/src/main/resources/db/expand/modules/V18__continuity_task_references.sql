-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V18: what refers to a continuity task has a foreign key to it, and a turn's one question has its column.
-- A request of a session only reads and updates the session's task (the task is made with the session's registration), so a state cleanup that deletes a task
-- while a hook writes a row of that task must not leave the row behind: the database refuses the later of the two statements, and that request fails.
ALTER TABLE sennard.continuity_current_memory ADD CONSTRAINT continuity_current_memory_task FOREIGN KEY (task_correlation) REFERENCES sennard.continuity_tasks (task_correlation);
ALTER TABLE sennard.continuity_memory_history ADD CONSTRAINT continuity_memory_history_task FOREIGN KEY (task_correlation) REFERENCES sennard.continuity_tasks (task_correlation);
ALTER TABLE sennard.continuity_requests ADD CONSTRAINT continuity_requests_task FOREIGN KEY (task_correlation) REFERENCES sennard.continuity_tasks (task_correlation);
ALTER TABLE sennard.continuity_observations ADD CONSTRAINT continuity_observations_task FOREIGN KEY (task_correlation) REFERENCES sennard.continuity_tasks (task_correlation);
ALTER TABLE sennard.continuity_tombstones ADD CONSTRAINT continuity_tombstones_task FOREIGN KEY (task_correlation) REFERENCES sennard.continuity_tasks (task_correlation);

-- The turn's one question has its column turn_asked_start in continuity_tasks (V7): the statement that moves it forward is the one that asks.

UPDATE sennard.module_schema SET version = 5 WHERE module = 'continuity';
