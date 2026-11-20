// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

/**
 * The environment a test-engineering run's child gets: nothing of the server's except the names listed here, then what the caller named in the request. The server's own
 * environment comes from the process that started it (the session-start hook's client), so it can hold any other session's variables; the child of a run is the caller's
 * command and must not see them.
 *
 * <p>A name that the server's environment does not have is skipped, and the list holds both spellings of a name that differs by operating system ({@code PATH} and
 * {@code Path}); the code does not branch on the operating system. The caller's variables are added last and win over a listed name of the same spelling.
 */
@Component
public class RunEnvironment {
    /**
     * The server's variables a child is given when the server has them, each for what a program fails without:
     * <ul>
     * <li>{@code PATH}, {@code Path}, {@code PATHEXT}: finding the programs and scripts the command starts;</li>
     * <li>{@code JAVA_HOME}: the JDK a Java-based tool (Gradle, Maven) uses;</li>
     * <li>{@code TEMP}, {@code TMP}, {@code TMPDIR}: the folder for temporary files;</li>
     * <li>{@code HOME}, {@code USERPROFILE}: where tools keep their caches and settings for the same user;</li>
     * <li>{@code SystemRoot}, {@code SystemDrive}, {@code windir}, {@code ComSpec}: what a Windows program needs to load its libraries, reach the network and start the
     * command interpreter;</li>
     * <li>{@code LANG}, {@code LC_ALL}, {@code LC_CTYPE}: the text encoding a program writes its output in.</li>
     * </ul>
     */
    public static final List<String> INHERITED_NAMES = List.of("PATH", "Path", "PATHEXT", "JAVA_HOME", "TEMP", "TMP", "TMPDIR", "HOME", "USERPROFILE", "SystemRoot", "SystemDrive",
            "windir", "ComSpec", "LANG", "LC_ALL", "LC_CTYPE");

    /**
     * The child's environment. {@code server} is only read, through {@code get}, so a map that matches names the operating system's way (the one of
     * {@link ProcessBuilder#environment()}) keeps doing so.
     */
    public Map<String, String> build(Map<String, String> server, Map<String, String> caller) {
        Map<String, String> child = new LinkedHashMap<>();
        for (String name : INHERITED_NAMES) {
            String value = server.get(name);
            if (value != null) {
                child.put(name, value);
            }
        }
        child.putAll(caller);
        return child;
    }

    /** What the receipt says of the environment when the caller gave no note of its own: the server's variables that may pass and the names (never the values) the caller set. */
    public String describe(Set<String> callerNames) {
        String named = callerNames.isEmpty() ? "none" : String.join(", ", new TreeSet<>(callerNames));
        return "Child environment: emptied, then the server's variables " + String.join(", ", INHERITED_NAMES) + " when the server has them, then the variables set by the caller (names only; values are not recorded): "
                + named + ". Undeclared environmental inputs are not captured.";
    }
}
