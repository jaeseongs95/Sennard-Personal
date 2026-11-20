// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonRawValue;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundCode;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.server.contract.ApiResult;
import io.github.jaeseongs95.sennard.server.mcp.OperationKeys;
import io.github.jaeseongs95.sennard.server.mcp.ToolSupport;
import io.github.jaeseongs95.sennard.service.store.JobState;
import io.github.jaeseongs95.sennard.service.store.jobs.JobService;
import io.github.jaeseongs95.sennard.service.store.jobs.JobStore;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One tool for every skill's checks: the skill's instructions stay files, its judgments are the server's beans. A check that walks a tree or starts a process answers from a job. */
@Component
public class SkillCheckTool {
    /** A job reference is a UUID in its canonical text form (what {@link UUID#toString()} writes); testing it first keeps a malformed value from being parsed by exception. */
    private static final Pattern JOB_REFERENCE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** What the tool answers for a check that runs as a job. */
    public record JobQueued(String jobRef, JobState state) {
    }

    /**
     * What {@code get_background_job} answers.
     *
     * @param result what the job's tool would have answered (its envelope), as the JSON text the job stored; null while there is none
     */
    public record JobView(String jobRef, String kind, JobState state, Instant createdAt, Instant startedAt, Instant finishedAt, String errorCode, @JsonRawValue String result) {
    }

    private final SkillCheckService service;
    private final SkillCheckQueue queue;
    private final JobService jobs;
    private final OperationKeys keys;
    private final JsonMapper mapper;
    private final ToolSupport tools;

    public SkillCheckTool(SkillCheckService service, SkillCheckQueue queue, JobService jobs, OperationKeys keys, JsonMapper mapper, ToolSupport tools) {
        this.service = service;
        this.queue = queue;
        this.jobs = jobs;
        this.keys = keys;
        this.mapper = mapper;
        this.tools = tools;
    }

    @McpTool(name = "run_skill_check", description = "tools.run_skill_check.description",
            // destructive: the test-engineering command starts a program the caller names, and what that program does to the computer is not known here; it never leaves the computer.
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = false, destructiveHint = true, openWorldHint = false))
    public McpSchema.CallToolResult run(@McpToolParam(description = "Your key for this operation: make a new random one for each call (a UUID, for example), 16 to 128 characters of A-Z a-z 0-9 _ . : -") String operationKey,
            @McpToolParam(description = "The skill's directory name") String skill,
            @McpToolParam(description = "The command of the skill's check") String command,
            @McpToolParam(description = "The command's inputs; a document may be given inline or as {\"file\": \"<absolute path>\"}", required = false) Map<String, Object> arguments) {
        return tools.answer(() -> {
            if (!OperationKey.valid(operationKey)) {
                // As every write tool refuses a key that is not the contract's (OperationKeys); the server makes none.
                throw new InvalidInputException(InputCode.CONTRACT_MISMATCH, SennardException.args("name", "run_skill_check"), SennardException.args("field", OperationKey.FIELD));
            }
            JsonNode input = arguments == null ? null : mapper.valueToTree(arguments);
            if (service.runsInBackground(skill, command)) {
                Map<String, Object> content = new java.util.HashMap<>();
                content.put(OperationKey.FIELD, operationKey);
                content.put("skill", skill);
                content.put("command", command);
                content.put("arguments", arguments);
                // The tool settles no caller (it takes no sessionRef and binds no session), so its key has the global scope; to be decided by the lead (AGS3-D-0267, scope of the receipt).
                // A conflict of the key is answered as every write tool answers it (code REQUEST_CONFLICT, details.reason and details.field).
                UUID job = queue.enqueue(skill, command, input, keys.global("run_skill_check", content));
                return ApiResult.ok(new JobQueued(job.toString(), JobState.QUEUED));
            }
            return service.run(skill, command, input).result();
        });
    }

    @McpTool(name = "get_background_job", description = "Reads a job a tool answered with a jobRef. data holds the job: state is QUEUED, RUNNING, SUCCEEDED or FAILED; result is what the tool would have answered, its whole envelope. "
            + "A job interrupted by a server restart is failed with errorCode SERVICE_RESTARTED and result.effect unknown: what it did is not known.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, destructiveHint = false, openWorldHint = false))
    public McpSchema.CallToolResult job(@McpToolParam(description = "The jobRef from the tool's answer") String jobRef) {
        return tools.run(() -> {
            if (jobRef == null || !JOB_REFERENCE.matcher(jobRef).matches()) {
                throw new InvalidInputException(InputCode.JOB_REFERENCE_INVALID);
            }
            JobStore.BackgroundJob job = jobs.find(UUID.fromString(jobRef)).orElseThrow(() -> new NotFoundException(NotFoundCode.JOB_NOT_FOUND));
            return new JobView(job.jobId().toString(), job.kind(), job.state(), job.createdAt(), job.startedAt(), job.finishedAt(), job.errorCode(), job.resultJson());
        });
    }
}
