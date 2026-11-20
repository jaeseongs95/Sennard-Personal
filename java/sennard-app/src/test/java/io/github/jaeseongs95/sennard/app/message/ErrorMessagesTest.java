// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.Category;
import io.github.jaeseongs95.sennard.domain.error.ErrorCode;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Case i of the board's case table (AbstractBoardServiceTest in sennard-store-pg): the error model and its wording, with no database.
 *
 * <pre>
 * i3  every code has a line   each constant of the seven code enums: its messageKey is in locales/en and locales/ko (the files on the classpath)
 * i4  the source resolves     CatalogMessageSource gives English and Korean for the same code (different text), the named argument is filled in,
 *                             an unknown key falls back to the default message (the code's name)
 * i5  kind matches code       every subclass of SennardException, built with every constant of its code enum, reports that constant's category; the seven
 *                             categories have one subclass each and different wire codes
 * i6  no name collision       the constant names of the seven code enums are all different (the wire's reasonCode is the bare name)
 * </pre>
 */
class ErrorMessagesTest {
    private static final ResourcePatternResolver RESOURCES = new PathMatchingResourcePatternResolver();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static List<ErrorCode> allCodes() {
        List<ErrorCode> codes = new ArrayList<>();
        for (Class<?> type : ErrorCode.class.getPermittedSubclasses()) {
            for (Object constant : type.getEnumConstants()) {
                codes.add((ErrorCode) constant);
            }
        }
        return codes;
    }

    private static Map<String, String> catalog(String language) throws IOException {
        Map<String, String> keys = new HashMap<>();
        Resource[] files = RESOURCES.getResources("classpath*:locales/" + language + "/*.json");
        assertTrue(files.length > 0, "the catalogs of " + language + " are on the classpath");
        for (Resource file : files) {
            try (InputStream in = file.getInputStream()) {
                JsonNode body = MAPPER.readTree(in);
                body.properties().forEach(entry -> keys.put(entry.getKey(), entry.getValue().asString()));
            }
        }
        return keys;
    }

    @Test
    void everyCodeHasItsLineInTheEnglishAndTheKoreanCatalog() throws IOException {
        Map<String, String> english = catalog("en");
        Map<String, String> korean = catalog("ko");
        List<ErrorCode> codes = allCodes();
        assertEquals(7, ErrorCode.class.getPermittedSubclasses().length);
        assertFalse(codes.isEmpty());
        List<String> missing = new ArrayList<>();
        for (ErrorCode code : codes) {
            if (!english.containsKey(code.messageKey())) {
                missing.add("en: " + code.getClass().getSimpleName() + "." + code.name() + " -> " + code.messageKey());
            }
            if (!korean.containsKey(code.messageKey())) {
                missing.add("ko: " + code.getClass().getSimpleName() + "." + code.name() + " -> " + code.messageKey());
            }
        }
        assertEquals(List.of(), missing, "a code without its catalog line is told to the caller as its bare name");
    }

    @Test
    void theMessageSourceResolvesTheSameCodeInEnglishAndKoreanAndFillsTheNamedArgument() {
        CatalogMessageSource source = new CatalogMessageSource(RESOURCES, MAPPER);
        SennardMessageResolvable resolvable = SennardMessageResolvable.of(new InvalidInputException(InputCode.SUMMARY_INVALID, SennardException.args("maxLength", 200)));
        String english = source.getMessage(resolvable, Locale.ENGLISH);
        String korean = source.getMessage(resolvable, Locale.KOREAN);
        assertEquals("summary must be one non-empty line of at most 200 characters.", english);
        assertEquals("summary는 비어 있지 않은 한 줄이어야 하고 200자 이하여야 한다.", korean);
        assertNotEquals(english, korean);
        assertEquals(english, source.getMessage(resolvable, Locale.forLanguageTag("fr")), "a language with no catalog is answered in English");

        SennardMessageResolvable fromAnotherCatalog = SennardMessageResolvable.of(new InvalidInputException(InputCode.CONTRACT_MISMATCH));
        assertNotEquals(InputCode.CONTRACT_MISMATCH.name(), source.getMessage(fromAnotherCatalog, Locale.ENGLISH), "the key of another domain's catalog is resolved too");
        assertEquals("NO_SUCH_KEY", source.getMessage("no.such.key", new Object[0], "NO_SUCH_KEY", Locale.ENGLISH), "an unknown key gives the default message");
    }

    @Test
    void everyExceptionKindReportsTheCategoryOfItsCodesAndTheCategoriesAreDistinct() throws ReflectiveOperationException {
        Set<Category> seen = EnumSet.noneOf(Category.class);
        Set<String> wireCodes = new HashSet<>();
        Class<?>[] kinds = SennardException.class.getPermittedSubclasses();
        assertEquals(Category.values().length, kinds.length, "one subclass per category");
        for (Class<?> kind : kinds) {
            Constructor<?> widest = null;
            for (Constructor<?> constructor : kind.getConstructors()) {
                if (widest == null || constructor.getParameterCount() > widest.getParameterCount()) {
                    widest = constructor;
                }
            }
            assertTrue(widest != null && ErrorCode.class.isAssignableFrom(widest.getParameterTypes()[0]), kind.getSimpleName() + " takes a code first");
            Class<?> codeType = widest.getParameterTypes()[0];
            Category expected = null;
            for (Object constant : codeType.getEnumConstants()) {
                Object[] arguments = new Object[widest.getParameterCount()];
                arguments[0] = constant;
                for (int index = 1; index < arguments.length; index++) {
                    arguments[index] = Map.class.isAssignableFrom(widest.getParameterTypes()[index]) ? Map.of() : null;
                }
                SennardException built = (SennardException) widest.newInstance(arguments);
                ErrorCode code = (ErrorCode) constant;
                assertEquals(code, built.code());
                assertEquals(code.category(), built.category(), kind.getSimpleName() + " with " + code.name());
                assertEquals(code.messageKey(), built.messageKey());
                expected = expected == null ? built.category() : expected;
                assertEquals(expected, built.category(), "all codes of " + codeType.getSimpleName() + " are of one category");
            }
            assertTrue(seen.add(expected), "the category " + expected + " has one subclass");
            assertTrue(wireCodes.add(expected.wireCode()), "the wire code " + expected.wireCode() + " is different for every category");
        }
        assertEquals(EnumSet.allOf(Category.class), seen);
        assertEquals(Category.values().length, wireCodes.size());
    }

    @Test
    void noTwoCodesShareOneMessageKey() {
        Map<String, String> owner = new HashMap<>();
        List<String> shared = new ArrayList<>();
        for (ErrorCode code : allCodes()) {
            String previous = owner.put(code.messageKey(), code.getClass().getSimpleName() + "." + code.name());
            if (previous != null) {
                shared.add(code.messageKey() + " is the line of " + previous + " and of " + code.getClass().getSimpleName() + "." + code.name());
            }
        }
        assertEquals(List.of(), shared, "a code that shares a generic line tells the caller a sentence that is not its own");
    }

    @Test
    void theNamesOfTheCodesDoNotCollideAcrossTheSevenEnums() {
        Map<String, String> owner = new HashMap<>();
        List<String> collisions = new ArrayList<>();
        for (ErrorCode code : allCodes()) {
            String previous = owner.put(code.name(), code.getClass().getSimpleName());
            if (previous != null) {
                collisions.add(code.name() + " in " + previous + " and " + code.getClass().getSimpleName());
            }
        }
        assertEquals(List.of(), collisions, "the wire's reasonCode is the bare constant name");
    }
}
