// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import java.net.URI;
import java.util.Map;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * An HTTP refusal that is not an {@code SennardException}: Spring's own {@link ErrorResponseException} carrying an RFC 9457 {@link ProblemDetail} whose {@code type} names the
 * refusal, with the public {@code code} and the {@code details} as properties of the problem (as {@code SennardExceptionHandler} does for the exceptions). It is thrown and the one
 * {@code ResponseEntityExceptionHandler} advice answers it; a filter, which runs before the dispatcher, hands it to {@link FilterRefusals}, which asks the same advice.
 */
public final class RefusedRequest extends ErrorResponseException {
    private static final long serialVersionUID = 1L;
    private static final String TYPE_PREFIX = "urn:sennard:problem:";

    /**
     * @param code the public code of the refusal; also the title
     * @param detail the sentence for the reader of the response, fixed per code (a request value never goes into it)
     * @param details what the code needs to be understood ({@code field}, {@code reason}); empty when nothing
     */
    public RefusedRequest(HttpStatusCode status, String code, String detail, Map<String, Object> details) {
        super(status, problem(status, code, detail, details), null);
    }

    private static ProblemDetail problem(HttpStatusCode status, String code, String detail, Map<String, Object> details) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code));
        problem.setTitle(code);
        problem.setProperty("code", code);
        problem.setProperty("details", details);
        return problem;
    }
}
