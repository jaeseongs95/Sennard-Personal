// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.server.execution.Refused;

/**
 * Whether a command's argument list can be handed to a Windows batch file ({@code .bat}, {@code .cmd}) as given. The JDK starts such a file through the command interpreter
 * (CreateProcess runs {@code cmd.exe} for it), and the interpreter reads an argument again as command text: the JDK quotes an argument that has an ampersand, a bar or a
 * space, but a quotation mark inside an argument ends that quoting, and a percent sign is expanded, a greater-than or less-than sign redirects. Checked on JDK 25.0.5 on
 * Windows 11 with a batch file that prints its first two arguments: {@code a&echo X} and {@code a|echo X} arrived as one argument, {@code a"&echo X} ran {@code echo X},
 * {@code %PATHEXT%} was expanded and {@code a>out.txt} created {@code out.txt}. The run command promises the argument list reaches the program as written, so an argument
 * the interpreter would read as more than a word is refused instead of started.
 *
 * <p>The judgment looks only at the text, so it is the same on every operating system; on one where the file is not run by the interpreter it only refuses an argument no
 * batch file is given. {@code gradlew.bat} with its usual arguments ({@code build}, {@code --no-daemon}, {@code -Pname=value}, {@code :module:task}) has none of the characters.
 */
@Component
public class BatchFileArguments {
    /** The characters the command interpreter reads as syntax inside an argument: quotation mark, ampersand, bar, redirects, caret, percent, exclamation mark and line breaks. */
    private static final String SYNTAX = "\"&|<>^%!\r\n";

    /** Whether {@code program} names a batch file: its name ends in {@code .bat} or {@code .cmd}, in any case, and Windows ignores dots and spaces after it. */
    public boolean isBatchFile(String program) {
        int end = program.length();
        while (end > 0 && (program.charAt(end - 1) == '.' || program.charAt(end - 1) == ' ')) {
            end--;
        }
        String name = program.substring(0, end).toLowerCase(Locale.ROOT);
        return name.endsWith(".bat") || name.endsWith(".cmd");
    }

    /**
     * Passes an argument list that can be handed on as written.
     *
     * @throws Refused with the index in {@code argv} of the first item the interpreter would read as syntax, when {@code argv} starts a batch file
     */
    public void requireSafe(List<String> argv) {
        if (argv.isEmpty() || !isBatchFile(argv.getFirst())) {
            return;
        }
        for (int index = 0; index < argv.size(); index++) {
            if (argv.get(index).chars().anyMatch(c -> SYNTAX.indexOf(c) >= 0)) {
                throw Refused.unsafeArgument(index);
            }
        }
    }
}
