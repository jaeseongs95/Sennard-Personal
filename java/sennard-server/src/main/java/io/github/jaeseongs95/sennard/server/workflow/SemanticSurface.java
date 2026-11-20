// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

/**
 * What a user approval binds of a root or of a replacement: its task, without the generated task ID, and its frame. Every substantive task field stays bound,
 * so changing the objective or the scope asks the user again, while a new task ID does not.
 */
public record SemanticSurface(TaskEnvelope task, ConvergenceFrame frame) {

    static SemanticSurface of(TaskEnvelope task, ConvergenceFrame frame) {
        return new SemanticSurface(task.withoutTaskId(), frame);
    }
}
