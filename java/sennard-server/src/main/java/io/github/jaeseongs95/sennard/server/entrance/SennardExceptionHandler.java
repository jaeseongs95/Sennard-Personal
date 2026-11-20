// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.entrance;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.jaeseongs95.sennard.app.message.SennardMessageResolvable;
import io.github.jaeseongs95.sennard.app.support.PersistenceErrors;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.GateRefusedException;
import io.github.jaeseongs95.sennard.domain.error.IntegrityException;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.NotFoundException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.server.hook.HookRejected;
import io.github.jaeseongs95.sennard.server.hook.HookRejectionBody;
import io.github.jaeseongs95.sennard.server.web.RefusedRequest;
import io.github.jaeseongs95.sennard.server.web.WebRefusal;
import io.github.jaeseongs95.sennard.service.settings.SettingsException;

/**
 * The HTTP boundary's one answer to a failure, the counterpart of {@code SennardToolResults} for MCP: the one
 * {@code @RestControllerAdvice}, a {@link ResponseEntityExceptionHandler} so that what Spring's own MVC throws (a body that is not readable, a constraint that fails, a
 * type that does not match, a missing parameter, an unsupported media type or method) is answered as an RFC 9457 {@code ProblemDetail}, rendered as
 * {@code application/problem+json}. A controller has no handler of its own, and a filter, which answers before the dispatcher, throws a {@link RefusedRequest} through
 * {@code FilterRefusals}, which this advice answers like any other.
 *
 * <p>An {@code SennardException} is a problem whose status is chosen by a complete {@code switch} over the sealed categories and whose sentence is made by the context's
 * {@code MessageSource} in the request's language ({@code Accept-Language}: English or Korean); its public {@code code}, {@code reasonCode} and catalog key are properties
 * of the problem, as the MCP envelope carries them. A hook call that the server cannot answer is the one answer that is not a problem: the host's launcher reads
 * {@code {"reason": ...}}. The last handler is the catch-all: it logs the failure and answers 500 with a fixed sentence, saying nothing of what failed.
 */
@RestControllerAdvice
public class SennardExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(SennardExceptionHandler.class);
    /** The {@code type} of a problem names the refusal by its public code, as {@link RefusedRequest} does. */
    private static final String TYPE_PREFIX = "urn:sennard:problem:";

    private final MessageSource messages;

    public SennardExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(SennardException.class)
    public ProblemDetail handle(SennardException failure, Locale locale) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status(failure), messages.getMessage(SennardMessageResolvable.of(failure), locale));
        String code = failure.code().wireCode();
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setTitle(code);
        problem.setProperty("code", code);
        if (failure.code().wireReason()) {
            problem.setProperty("reasonCode", failure.code().name());
        }
        problem.setProperty("messageKey", failure.messageKey());
        Map<String, Object> details = new LinkedHashMap<>(failure.details());
        if (!details.isEmpty()) {
            problem.setProperty("details", details);
        }
        return problem;
    }

    /**
     * What the settings store refuses (the passkey registry, the records before the link) is not an {@code SennardException} (the store is below the application layer), so it is stated
     * here, at the entrance, by the one translation ({@link PersistenceErrors#ofSettings}) and answered like every other refusal: no code goes out as the unexpected 500.
     */
    @ExceptionHandler(SettingsException.class)
    public ProblemDetail settingsRefused(SettingsException failure, Locale locale) {
        return handle(PersistenceErrors.ofSettings(failure), locale);
    }

    /**
     * The status of each category; no {@code default}, so a category added to the sealed hierarchy fails to compile here until it has one. A gate is told apart by its reason
     * (again with no {@code default}): the admin page's refusals of who may sign a change, and from where, are 403.
     */
    static HttpStatus status(SennardException failure) {
        return switch (failure) {
            case InvalidInputException input -> HttpStatus.BAD_REQUEST;
            case NotFoundException missing -> HttpStatus.NOT_FOUND;
            case ConflictException conflict -> HttpStatus.CONFLICT;
            case GateRefusedException gate -> switch (gate.code()) {
                case GATE_FAILED, ADMIN_RESET_NOT_PERMITTED, DOWNGRADE_REFUSED, NOT_A_RELEASE_JAR, UNINSTALL_FROM_INSTALLED_COPY -> HttpStatus.UNPROCESSABLE_CONTENT;
                case ADMIN_ORIGIN_REJECTED, ADMIN_KEY_NOT_REGISTERED, ADMIN_KEY_UNKNOWN, ADMIN_KEY_REVOKED -> HttpStatus.FORBIDDEN;
            };
            case BindingException binding -> HttpStatus.UNAUTHORIZED;
            case IntegrityException integrity -> HttpStatus.UNPROCESSABLE_CONTENT;
            case UnavailableException unavailable -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    /**
     * A hook call the server will not answer: its status and its reason. It is answered with JSON, not as a problem, because the host's launcher reads {@code reason}.
     * Every hook entry point shares this handler, so none needs its own.
     */
    @ExceptionHandler(HookRejected.class)
    public ResponseEntity<HookRejectionBody> hookRejected(HookRejected rejection) {
        if (rejection.status() >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            // A hook the server could not answer is the host's "hook failed"; the log says which refusal it was (a 4xx is the caller's and stays silent).
            LOG.warn("HOOK_REJECTED status={} rejection={} reason={}", rejection.status(), rejection.rejection().name(), rejection.reason(), rejection);
        }
        return ResponseEntity.status(rejection.status()).contentType(MediaType.APPLICATION_JSON).body(HookRejectionBody.of(rejection));
    }

    /**
     * What the store raised outside a service method (a request that reaches a repository or a filter that reads the store) is stated by the same translation the application layer
     * runs around every service method ({@link PersistenceErrors#ofDataAccess}) and answered like every other refusal: a race with another writer is the retryable 409 {@code CONFLICT}
     * ({@code CONCURRENT_UPDATE}), a constraint of the data is 422, and only a store that cannot answer is 503 ({@code MCP_UNAVAILABLE}, reason {@code STORE_UNAVAILABLE}). The answer says
     * nothing of the driver: the failure is the cause for the log and is never shown.
     */
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail dataAccessFailed(DataAccessException failure, Locale locale) {
        SennardException translated = PersistenceErrors.ofDataAccess(failure);
        if (translated instanceof UnavailableException) {
            LOG.warn("The store did not answer a request", failure);
        }
        return handle(translated, locale);
    }

    /**
     * The catch-all of the HTTP boundary: whatever no other handler took. It is logged with its cause, and the answer names nothing of it. Spring's own exceptions are
     * {@code ResponseEntityExceptionHandler}'s and an {@code SennardException} is its own handler's; this one is only for what nobody expected.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception failure) {
        LOG.error("A request failed unexpectedly", failure);
        return WebRefusal.INTERNAL_FAILURE.because(Map.of()).getBody();
    }

    /** A constraint of the request body failed: the page learns which field, never its value. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException failed, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        FieldError field = failed.getBindingResult().getFieldError();
        return requestInvalid(failed, Map.of("field", field == null ? failed.getBindingResult().getObjectName() : field.getField()), headers, status, request);
    }

    /** A constraint of a path variable or a parameter failed. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException failed, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = failed.getParameterValidationResults().stream().findFirst().map(result -> result.getMethodParameter().getParameterName()).orElse("request");
        return requestInvalid(failed, Map.of("field", String.valueOf(field)), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException failed, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = failed instanceof MethodArgumentTypeMismatchException argument ? argument.getName() : Objects.requireNonNullElse(failed.getPropertyName(), "request");
        return requestInvalid(failed, Map.of("field", field), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException failed, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return requestInvalid(failed, Map.of("field", failed.getParameterName()), headers, status, request);
    }

    /** The body is absent or is not JSON of the record's shape. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException failed, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        LOG.debug("The request body could not be read", failed);
        return requestInvalid(failed, Map.of("reason", "JSON"), headers, status, request);
    }

    private ResponseEntity<Object> requestInvalid(Exception failed, Map<String, Object> details, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(failed, WebRefusal.REQUEST_INVALID.because(details).getBody(), headers, status, request);
    }
}
