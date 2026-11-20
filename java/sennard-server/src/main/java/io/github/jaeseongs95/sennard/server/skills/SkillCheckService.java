// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.ApiResult;
import io.github.jaeseongs95.sennard.server.message.Messages;
import io.github.jaeseongs95.sennard.service.files.FileFacts;
import io.github.jaeseongs95.sennard.service.files.FileFactsException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one command of one skill. The MCP tool and the stage validator both call this, so they cannot disagree about a skill's rules.
 * The caller's command name becomes the check's enum constant here, once, so the checks never compare command names.
 */
@Service
public class SkillCheckService {
    private static final Logger LOG = LoggerFactory.getLogger(SkillCheckService.class);

    /** One check with its commands by the name a caller sends. */
    private record Registered<C extends Enum<C> & SkillCommand>(SkillCheck<C> check, Map<String, C> commands) {
        static <C extends Enum<C> & SkillCommand> Registered<C> of(SkillCheck<C> check) {
            Map<String, C> commands = new LinkedHashMap<>();
            Arrays.stream(check.commandType().getEnumConstants()).forEach(command -> commands.put(command.wire(), command));
            return new Registered<>(check, Map.copyOf(commands));
        }

        boolean runsInBackground(String command) {
            C constant = commands.get(command);
            return constant != null && check.backgroundCommands().contains(constant);
        }
    }

    private final Map<String, Registered<?>> registered;
    private final FileFacts files;
    private final Messages messages;
    private final JsonMapper mapper;

    public SkillCheckService(List<SkillCheck<?>> checks, FileFacts files, Messages messages, JsonMapper mapper) {
        this.registered = checks.stream().map(SkillCheckService::register).collect(Collectors.toUnmodifiableMap(entry -> entry.check().skill(), Function.identity()));
        this.files = files;
        this.messages = messages;
        this.mapper = mapper;
    }

    private static <C extends Enum<C> & SkillCommand> Registered<C> register(SkillCheck<C> check) {
        return Registered.of(check);
    }

    /**
     * A value and an exit code, or the error body of a refused input. {@code body} is what the workflow's stages read ({@code {exitCode, value}}, or the refusal with its code and sentence);
     * {@code refusal} is the same refusal as the envelope's error, null for a check that ran. The tool answers with {@link #result}, and so does the job that ran the check in the background,
     * so a job's result is what the call would have answered.
     */
    public record Outcome(JsonNode body, int exitCode, ApiResult.ErrorBody refusal) {
        public ApiResult result() {
            return refusal == null ? ApiResult.ok(body) : ApiResult.failed(refusal);
        }
    }

    /** The answer of a command that ran: {@code {exitCode, value}}. */
    private record Answer(int exitCode, Object value) {
    }

    /** The answer of a refused input; {@code details} stays out when the failure has none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Refusal(String status, String code, String message, String messageKey, Map<String, Object> params, Object details) {
    }

    /** Whether this command belongs off the request's thread; an unknown skill or command is refused by {@link #run}, on it. */
    public boolean runsInBackground(String skill, String command) {
        Registered<?> entry = registered.get(skill);
        return entry != null && entry.runsInBackground(command);
    }

    public Outcome run(String skill, String command, JsonNode arguments) {
        try {
            Registered<?> entry = registered.get(skill);
            if (entry == null) {
                throw new InvalidInputException(InputCode.SKILL_CHECK_UNKNOWN_SKILL, SennardException.args("skill", skill));
            }
            return execute(entry, command, arguments);
        } catch (InvalidInputException | IntegrityException failure) {
            // A refused input is this call's answer (code, catalog key, parameters, details); the log ends the handling. The answer's code is the constant's public one.
            LOG.debug("skill check refused: {} {}", failure.code(), failure.messageKey(), failure);
            return refused(failure.code().wireCode(), failure.messageKey(), failure.arguments(), failure.details().isEmpty() ? null : failure.details());
        } catch (JacksonException malformed) {
            // A document whose shape a check bound to a record and could not: the caller's input, not a fault of this server.
            LOG.debug("skill check refused: a document does not bind to the shape the check reads", malformed);
            return refused(InputCode.SKILL_CHECK_MALFORMED_ARGUMENT.wireCode(), InputCode.SKILL_CHECK_MALFORMED_ARGUMENT.messageKey(), Map.of(), null);
        } catch (FileFactsException failure) {
            // The file layer refused the path (outside the roots, too large, not found): the caller's input again.
            LOG.debug("skill check refused: {}", failure.messageKey(), failure);
            return refused(failure.code(), failure.messageKey(), failure.params(), null);
        }
    }

    private <C extends Enum<C> & SkillCommand> Outcome execute(Registered<C> entry, String command, JsonNode arguments) {
        C constant = entry.commands().get(command);
        if (constant == null) {
            throw new InvalidInputException(InputCode.SKILL_CHECK_UNKNOWN_COMMAND, SennardException.args("skill", entry.check().skill(), "command", command));
        }
        SkillCheckResult result = entry.check().run(constant, new SkillArguments(arguments, files, mapper));
        return new Outcome(mapper.valueToTree(new Answer(result.exitCode(), result.value())), result.exitCode(), null);
    }

    private Outcome refused(String code, String messageKey, Map<String, Object> params, Object details) {
        String message = messages.forRequest(messageKey, params);
        return new Outcome(mapper.valueToTree(new Refusal("ERROR", code, message, messageKey, params, details)), 2, new ApiResult.ErrorBody(code, message, messageKey, params, details));
    }

    /** Every check by its skill's name. */
    public Map<String, SkillCheck<?>> checks() {
        return registered.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().check()));
    }
}
