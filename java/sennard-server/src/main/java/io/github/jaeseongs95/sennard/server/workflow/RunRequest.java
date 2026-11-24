// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The inputs of the tools that name a run or a root and nothing else, bound by {@link ToolInputs}: a key the tool does not take is refused, and so is a field of
 * another JSON type than the published tool schema names (a number where a text is, a text or a number with a fraction where the revision is an integer), which
 * Jackson would otherwise convert. A call that names no run or root asks for the one that is not there and gets {@code RUN_NOT_FOUND}, as before.
 */
final class RunRequest {
    private RunRequest() {
    }

    /** An omitted cursor returns retained history. An empty cursor starts the bounded open-root listing. */
    record SessionList(@JsonDeserialize(using = StrictInput.Text.class) @Size(max = 255) String openRootAfter) { }

    /** {@code get_workflow_status}: the run to read. */
    record Lookup(@JsonDeserialize(using = StrictInput.Text.class) @JsonInclude(JsonInclude.Include.NON_NULL) String runId) {

        /** The run asked for; the empty ID names none. */
        String id() {
            return runId == null ? "" : runId;
        }
    }

    /** A paused run resumes only with the revision and operation key supplied together. */
    record Resume(@JsonDeserialize(using = StrictInput.Text.class) String runId,
            @JsonDeserialize(using = StrictInput.Integral.class) @PositiveOrZero Long expectedRevision) {
        String id() { return runId == null ? "" : runId; }
    }

    /** A deliberate pause, with the reason and what permits an explicit resume. */
    record Pause(@JsonDeserialize(using = StrictInput.Text.class) String runId,
            @JsonDeserialize(using = StrictInput.Integral.class) @NotNull @PositiveOrZero Long expectedRevision,
            @JsonDeserialize(using = StrictInput.Text.class) @NotBlank @Size(max = 2000) String reason,
            @JsonDeserialize(using = StrictInput.Text.class) @NotBlank @Size(max = 2000) String resumeCondition) {
        String id() { return runId == null ? "" : runId; }
    }

    /** {@code get_convergence_status}: the root to read. */
    record RootLookup(@JsonDeserialize(using = StrictInput.Text.class) @JsonInclude(JsonInclude.Include.NON_NULL) String rootId) {

        /** The root asked for; the empty ID names none. */
        String id() {
            return rootId == null ? "" : rootId;
        }
    }

    /** {@code finalize_workflow} and {@code abort_workflow}: the run, and the revision the caller last saw. */
    record Change(@JsonDeserialize(using = StrictInput.Text.class) String runId, @JsonDeserialize(using = StrictInput.Integral.class) @NotNull @PositiveOrZero Long expectedRevision) {

        String id() {
            return runId == null ? "" : runId;
        }
    }

    /** {@code submit_change_plan}: the run, the revision the caller last saw, and the files the work will change (relative to the workspace), 1 to 256 distinct paths. */
    record Plan(@JsonDeserialize(using = StrictInput.Text.class) String runId, @JsonDeserialize(using = StrictInput.Integral.class) @NotNull @PositiveOrZero Long expectedRevision,
            @JsonDeserialize(contentUsing = StrictInput.Item.class) @NotNull @Size(min = 1, max = 256) @Distinct List<@NotBlank @Size(max = 2000) String> files) {

        String id() {
            return runId == null ? "" : runId;
        }
    }
}
