// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.web;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.SimpleTimeZoneAwareLocaleContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import org.springframework.web.servlet.LocaleContextResolver;

import io.github.jaeseongs95.sennard.hookclient.HookHeaders;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The language and the time zone of a request, as the client states them: the language the user chose on the admin page (the cookie {@value #LANGUAGE_COOKIE}) when there
 * is one, else the one from Accept-Language (English and Korean are supported, English is the default), the time zone from the {@value HookHeaders#TIME_ZONE} header, an IANA zone name (a browser sends no time zone of its own; the admin page sends
 * {@code Intl.DateTimeFormat().resolvedOptions().timeZone}). Without the header the zone is UTC. {@code DispatcherServlet} puts both into
 * {@code LocaleContextHolder}, so no filter is needed. A header that is not a zone name never gets here: {@link GuardFilter} refuses it with 400.
 * Server calculations stay on the UTC clock; the zone is read only where a day boundary of the user is needed.
 */
@Component(DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME) // the bean name DispatcherServlet looks its resolver up by; Boot's default resolver backs off for it
public final class SennardLocaleResolver extends AcceptHeaderLocaleResolver implements LocaleContextResolver {
    /** The cookie that holds the language chosen on the admin page: {@code en} or {@code ko}, nothing else. */
    public static final String LANGUAGE_COOKIE = "sennard-admin-language";
    private static final Duration LANGUAGE_COOKIE_AGE = Duration.ofDays(365);
    /** The names of the time-zone database, taken once: {@code ZoneId.getAvailableZoneIds()} builds a new set on every call, and this is asked on every request that carries the header. */
    private static final Set<String> ZONE_NAMES = Set.copyOf(ZoneId.getAvailableZoneIds());

    /** @param web the application's web settings: {@code spring.web.locale} is the language of a request that states none; English when it sets none */
    public SennardLocaleResolver(WebProperties web) {
        setSupportedLocales(List.of(Locale.ENGLISH, Locale.KOREAN));
        setDefaultLocale(web.getLocale() == null ? Locale.ENGLISH : web.getLocale());
    }

    /** True when the text is the name of a zone of the time-zone database (an IANA name such as {@code Asia/Seoul} or {@code UTC}), which is what the header must hold. */
    public static boolean isZoneName(String text) {
        return ZONE_NAMES.contains(text);
    }

    /**
     * The language the user chose on the admin page, when the request carries it (the cookie {@value #LANGUAGE_COOKIE}), else the one Accept-Language gives. Only a supported
     * language is taken from the cookie; any other value is ignored, as if there were no cookie.
     */
    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        Cookie chosen = WebUtils.getCookie(request, LANGUAGE_COOKIE);
        if (chosen != null) {
            for (Locale supported : getSupportedLocales()) {
                if (supported.getLanguage().equals(chosen.getValue())) {
                    return supported;
                }
            }
        }
        return super.resolveLocale(request);
    }

    @Override
    public LocaleContext resolveLocaleContext(HttpServletRequest request) {
        Locale locale = resolveLocale(request);
        String header = request.getHeader(HookHeaders.TIME_ZONE);
        TimeZone zone = TimeZone.getTimeZone(header == null || header.isBlank() || !isZoneName(header.strip()) ? ZoneOffset.UTC : ZoneId.of(header.strip()));
        return new SimpleTimeZoneAwareLocaleContext(locale, zone);
    }

    /**
     * Remembers the language the user chose, the way Spring's {@code CookieLocaleResolver} does: in a cookie of the admin page. A null context forgets it, so the browser's
     * language is followed again. The time zone is not kept: it is the client's, sent with every request.
     *
     * @throws IllegalArgumentException when the language is not one of the supported ones
     */
    @Override
    public void setLocaleContext(HttpServletRequest request, HttpServletResponse response, LocaleContext localeContext) {
        Locale locale = localeContext == null ? null : localeContext.getLocale();
        if (locale != null && !getSupportedLocales().contains(Locale.forLanguageTag(locale.getLanguage()))) {
            throw new IllegalArgumentException("Not a supported language: " + locale);
        }
        // Not readable by a script (the page never reads it), sent only to this site (Strict), and it holds the language and nothing else: no identity, no secret.
        ResponseCookie cookie = ResponseCookie.from(LANGUAGE_COOKIE, locale == null ? "" : locale.getLanguage())
                .path("/").httpOnly(true).sameSite("Strict").maxAge(locale == null ? Duration.ZERO : LANGUAGE_COOKIE_AGE).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
