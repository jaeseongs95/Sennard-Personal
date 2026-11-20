// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import java.util.Locale;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * The language of the request being answered. Spring's {@code DispatcherServlet} puts the locale its {@code LocaleResolver} chose (Accept-Language, else
 * {@code spring.web.locale}) into {@link LocaleContextHolder}; this reads it. The server's own default locale is never used: Sennard does not assume the language of the
 * machine it runs on. Where no request is being answered (no locale context), the answer is English, the catalogs' source language.
 */
public final class RequestLocale {
    private RequestLocale() {
    }

    public static Locale current() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        Locale locale = context == null ? null : context.getLocale();
        return locale == null ? Locale.ENGLISH : locale;
    }
}
