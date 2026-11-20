// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * A name declared twice in one scope of the page script ({@code const stateLabel} on the bindings screen and again on the workflow statistics screen) is a
 * {@code SyntaxError} at parse time: the browser runs none of the script and the page stays on "loading". The JDK has no JavaScript engine, so this reads the page text instead.
 *
 * <p>How it decides: the page script is one function, {@code (function () { ... })();}, whose body is indented by two spaces. Every line of the script that starts with exactly
 * two spaces and then {@code const}, {@code let}, {@code var}, {@code function}, {@code async function} or {@code class} declares one name in that top-level scope, and the
 * first identifier after the keyword is counted. A name counted twice fails the test (a {@code function} and a {@code const} of one name too).
 *
 * <p>Limits: only the declarations at that indentation are seen. Names inside a nested function or block (four spaces or more) may repeat, as the language allows, and
 * are not checked; a declaration of several names ({@code const a = 1, b = 2}) is counted by its first name only, and a destructuring one is refused outright, so that
 * nobody hides a duplicate in one; a declaration not at the start of its line, or a page whose indentation differs, is not seen. The test is a guard
 * for the indentation rule this page keeps, not a parser.
 */
class AdminPageScriptScopeTest {
    private static final Pattern DECLARATION = Pattern.compile("^  (?:async )?(const|let|var|function\\*?|class) +([A-Za-z_$][\\w$]*)(.*)$");
    private static final Pattern DESTRUCTURED = Pattern.compile("^  (?:const|let|var) *[\\[{].*");

    @Test
    void noNameIsDeclaredTwiceInTheTopLevelScopeOfThePageScript() {
        Map<String, List<Integer>> lines = new HashMap<>();
        List<String> scope = scriptBody();
        for (int i = 0; i < scope.size(); i++) {
            Matcher declaration = DECLARATION.matcher(scope.get(i));
            if (declaration.matches()) {
                lines.computeIfAbsent(declaration.group(2), name -> new ArrayList<>()).add(i + 1);
            }
        }
        assertFalse(lines.isEmpty(), "the page script declares names at its top level");
        lines.forEach((name, at) -> assertEquals(1, at.size(), name + " is declared " + at.size() + " times in the top-level scope of the page script (script lines " + at + ")"));
    }

    @Test
    void noTopLevelDeclarationIsADestructuringTheTestCouldNotCount() {
        for (String line : scriptBody()) {
            assertFalse(DESTRUCTURED.matcher(line).matches(), "a destructuring declaration at the top level is not counted by this test: " + line.strip());
        }
    }

    @Test
    void theScopeTheTestReadsIsTheWholeScript() {
        List<String> scope = scriptBody();
        assertTrue(scope.size() > 1000, "the script body is read whole, not a part of it");
        assertTrue(scope.stream().anyMatch(line -> line.startsWith("  const stateLabel = ")), "the bindings screen's label is among the names read");
        assertTrue(scope.stream().anyMatch(line -> line.startsWith("  const workflowStateLabel = ")), "the workflow screen's label has its own name");
    }

    /** The lines between {@code (function () \{} and its closing {@code \})();} of the last script of the page. */
    private static List<String> scriptBody() {
        String page = page();
        int open = page.indexOf("(function () {\n", page.lastIndexOf("<script nonce="));
        int close = page.lastIndexOf("})();");
        assertTrue(open >= 0 && close > open, "the page script is one function, called at once");
        return List.of(page.substring(open + "(function () {\n".length(), close).split("\r?\n", -1));
    }

    private static String page() {
        try (InputStream in = new ClassPathResource("admin/admin-page.html").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("the admin page is not in the classpath", unreadable);
        }
    }
}
