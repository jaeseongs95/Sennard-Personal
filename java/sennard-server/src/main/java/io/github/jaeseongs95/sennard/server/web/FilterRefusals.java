// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * How a filter refuses a request: it does not write a body. A filter answers before {@code DispatcherServlet}, so no controller advice sees what it throws; this hands the
 * refusal to the application's {@code HandlerExceptionResolver} (the one {@code DispatcherServlet} uses, which asks the {@code @RestControllerAdvice}), so a filter's
 * refusal is rendered by the same advice as a controller's. Spring's documented way for a filter to use {@code @ExceptionHandler} methods.
 */
@Component
public class FilterRefusals {
    private final ObjectProvider<HandlerExceptionResolver> resolver;

    /**
     * The qualifier: Boot's {@code DefaultErrorAttributes} is also a {@code HandlerExceptionResolver}. The provider: the filters are created while the servlet context starts,
     * before the MVC beans they would otherwise pull in early; the resolver is looked up when a request is refused.
     */
    public FilterRefusals(@Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> resolver) {
        this.resolver = resolver;
    }

    /** Answers the request with the refusal; the caller adds any header of its own first and does not continue the chain. */
    public void refuse(HttpServletRequest request, HttpServletResponse response, RuntimeException refusal) {
        if (resolver.getObject().resolveException(request, response, null, refusal) == null) {
            throw refusal; // no resolver took it: the container's own error answer, never a silent empty one
        }
    }
}
