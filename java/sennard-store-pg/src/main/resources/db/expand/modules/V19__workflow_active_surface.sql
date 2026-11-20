-- SPDX-License-Identifier: AGPL-3.0-or-later
-- Copyright (C) 2026 Jaeseong Song
-- See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

-- V19: a root that is active holds its write surface in a table of its own, so two roots of the same surface that are opened together cannot both commit.
-- Only the roots that are active have a row here: the primary key is the surface, so a second active root of it fails, and the UNIQUE key on the root lets a root hold one surface.
-- (A UNIQUE column on convergence_roots that is NULL for the roots that are not active would repeat NULL only on some databases, and a UNIQUE column must be NOT NULL on others; a
-- UNIQUE index over the active roots only would be a partial index, which the standard has not.) A root gives its surface up in the transaction that moves it out of the active
-- states. The check of overlapping surfaces stays what finds the conflict that is answered ROOT_CONFLICT; this key is the one the database holds when two requests pass that check at
-- the same moment.
CREATE TABLE sennard.convergence_active_surfaces (
    active_surface VARCHAR(255) NOT NULL,
    root_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (active_surface),
    UNIQUE (root_id),
    FOREIGN KEY (root_id) REFERENCES sennard.convergence_roots (root_id)
);

UPDATE sennard.module_schema SET version = 8 WHERE module = 'workflow';
