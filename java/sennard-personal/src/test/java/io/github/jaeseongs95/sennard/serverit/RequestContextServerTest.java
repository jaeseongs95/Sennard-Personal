// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The language and the time zone of a request are the client's (SennardLocaleResolver, put into the context by DispatcherServlet):
 *
 * <pre>
 * z1  no X-Sennard-Time-Zone                     UTC, English
 * z2  X-Sennard-Time-Zone: Asia/Seoul            that zone
 * z3  a value that is not a zone name        400 TIME_ZONE_NOT_VALID, nothing reached; never turned into UTC
 * z4  JVM default zone Asia/Seoul, no header UTC
 * z5  Accept-Language ko / fr / none         ko / en (no catalog) / en
 * z6  cookie sennard-admin-language en / ko  that language before Accept-Language; any other value ignored
 * </pre>
 */
class RequestContextServerTest {
    private static RunningServer server;

    @BeforeAll
    static void start() throws Exception {
        server = RunningServer.start(List.of(ServerFixtures.Beans.class));
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static String context(Map<String, String> headers) throws Exception {
        RawHttp.Response response = RawHttp.exchange(server.port, "GET", "/hooks/v1/context", headers, null);
        assertEquals(200, response.status(), response.text());
        return response.text();
    }

    @Test
    void noHeaderIsUtcAndEnglish() throws Exception {
        assertEquals("UTC;en", context(Map.of()));
    }

    @Test
    void theClientsZoneIsTheRequestsZone() throws Exception {
        assertEquals("Asia/Seoul;en", context(Map.of("X-Sennard-Time-Zone", "Asia/Seoul")));
    }

    @Test
    void aValueThatIsNotAZoneNameIsRefusedAndNotTurnedIntoUtc() throws Exception {
        int reached = server.bean(ServerFixtures.Endpoints.class).reached.get();
        for (String bad : List.of("Mars/Base", "+09:00x", "korea standard time", "")) {
            RawHttp.Response response = RawHttp.exchange(server.port, "GET", "/hooks/v1/probe", Map.of("X-Sennard-Time-Zone", bad), null);
            assertEquals(400, response.status(), bad + " " + response.text());
            assertTrue(response.text().contains("TIME_ZONE_NOT_VALID"), response.text());
        }
        assertEquals(reached, server.bean(ServerFixtures.Endpoints.class).reached.get(), "a refused request reached the handler");
    }

    @Test
    void theJvmDefaultZoneIsNotTheRequestsZone() throws Exception {
        TimeZone before = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        try {
            assertEquals("UTC;en", context(Map.of()));
        } finally {
            TimeZone.setDefault(before);
        }
    }

    /** z6  the language the user chose (cookie sennard-admin-language) is followed before Accept-Language; none or a value that is not en/ko: Accept-Language; the zone is kept. */
    @Test
    void theLanguageTheUserChoseComesBeforeAcceptLanguage() throws Exception {
        assertEquals("UTC;ko", context(Map.of("Cookie", "sennard-admin-language=ko", "Accept-Language", "en")));
        assertEquals("UTC;en", context(Map.of("Cookie", "sennard-admin-language=en", "Accept-Language", "ko")));
        assertEquals("UTC;ko", context(Map.of("Cookie", "sennard-admin-language=ko")));
        assertEquals("UTC;ko", context(Map.of("Cookie", "other=en", "Accept-Language", "ko")), "another cookie is not a choice");
        for (String bad : List.of("fr", "KO", "", "ko-KR", "xx")) {
            assertEquals("UTC;ko", context(Map.of("Cookie", "sennard-admin-language=" + bad, "Accept-Language", "ko")), "ignored: " + bad);
            assertEquals("UTC;en", context(Map.of("Cookie", "sennard-admin-language=" + bad, "Accept-Language", "fr")), "ignored: " + bad);
        }
        assertEquals("Asia/Seoul;ko", context(Map.of("Cookie", "sennard-admin-language=ko", "X-Sennard-Time-Zone", "Asia/Seoul")), "the time zone is still the client's");
    }

    @Test
    void theLanguageIsAcceptLanguageWithEnglishWhereThereIsNoCatalog() throws Exception {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.KOREAN);
        try {
            assertEquals("UTC;ko", context(Map.of("Accept-Language", "ko")));
            assertEquals("UTC;en", context(Map.of("Accept-Language", "fr")));
            assertEquals("UTC;en", context(Map.of()), "the JVM default language is not the request's");
        } finally {
            Locale.setDefault(before);
        }
    }
}
