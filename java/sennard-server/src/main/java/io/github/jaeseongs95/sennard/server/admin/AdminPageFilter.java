// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.springframework.http.HttpMethod;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;
import io.github.jaeseongs95.sennard.server.web.SennardLocaleResolver;
import io.github.jaeseongs95.sennard.server.web.FilterRefusals;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** The page's own route ({@code /}): a visitor of the loopback address is sent to {@code localhost}, with the reason. */
final class AdminPageFilter extends AdminPortFilter {
    private static final PropertyPlaceholderHelper PLACEHOLDERS = new PropertyPlaceholderHelper("${", "}");
    /** Stands for the address in the reason's sentence while it is rendered and escaped; a private-use character that no sentence of the catalog has. */
    private static final String ADDRESS = "\uE000";

    private final AdminPageAddress address;
    private final CspNonce nonces;
    private final String moveTemplate;
    private final AdminTexts texts;
    private final SennardLocaleResolver locales;

    /**
     * @param moveTemplate the page that sends a visitor of the loopback address to localhost, with {@code ${target}}, {@code ${nonce}}, and the sentences
     *        {@code ${language}}, {@code ${title}}, {@code ${heading}}, {@code ${reason}} and {@code ${moveNow}} in it
     * @param locales this filter runs before the dispatcher has chosen the request's language, so it asks the same resolver
     */
    AdminPageFilter(AdminPageAddress address, FilterRefusals refusals, CspNonce nonces, String moveTemplate, AdminTexts texts, SennardLocaleResolver locales) {
        super(address, refusals);
        this.address = address;
        this.nonces = nonces;
        this.moveTemplate = moveTemplate;
        this.texts = texts;
        this.locales = locales;
    }

    /** The page sent to http://127.0.0.1:port: why it moves, then the page on localhost. Any method but GET is refused. */
    @Override
    void loopbackAddressed(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            refuse(request, response, AdminRefusal.HOST_REJECTED);
            return;
        }
        String nonce = nonces.next();
        Locale locale = locales.resolveLocale(request);
        // The page on localhost: where a visitor of the loopback address is sent.
        String target = HtmlUtils.htmlEscape(UriComponentsBuilder.fromUriString(address.origin()).path("/").build().toUriString());
        Properties values = new Properties();
        values.setProperty("nonce", nonce);
        values.setProperty("target", target);
        values.setProperty("language", HtmlUtils.htmlEscape(texts.language(locale)));
        values.setProperty("title", HtmlUtils.htmlEscape(texts.text("move.title", locale)));
        values.setProperty("heading", HtmlUtils.htmlEscape(texts.text("move.heading", locale)));
        values.setProperty("reason", HtmlUtils.htmlEscape(texts.text("move.reason", locale, Map.of("target", ADDRESS))).replace(ADDRESS, "<code>" + target + "</code>"));
        values.setProperty("moveNow", HtmlUtils.htmlEscape(texts.text("move.now", locale)));
        String html = PLACEHOLDERS.replacePlaceholders(moveTemplate, values);
        common(response);
        response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'nonce-" + nonce + "'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        response.setHeader("Vary", "Accept-Language, Cookie");
        response.setContentType("text/html;charset=utf-8");
        response.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
    }
}
