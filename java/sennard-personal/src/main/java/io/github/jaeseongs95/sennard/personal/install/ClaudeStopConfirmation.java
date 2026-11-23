// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.awt.AWTError;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.io.Console;
import java.util.Locale;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import javax.swing.JOptionPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The installer asks about an existing Claude Code value through this boundary. */
@FunctionalInterface
interface ClaudeStopConfirmation {
    enum Answer { YES, NO, UNAVAILABLE }

    Answer confirm(String message);

    static ClaudeStopConfirmation interactive() {
        return message -> ask(message, GraphicsEnvironment.isHeadless(),
                prompt -> JOptionPane.showConfirmDialog(null, prompt, "Sennard · Claude Code", JOptionPane.YES_NO_OPTION),
                prompt -> {
                    Console console = System.console();
                    return console == null ? null : console.readLine("%s%n[y/n] ", prompt);
                });
    }

    static Answer ask(String message, boolean headless, ToIntFunction<String> window, UnaryOperator<String> terminal) {
        if (!headless) {
            try {
                return window.applyAsInt(message) == JOptionPane.YES_OPTION ? Answer.YES : Answer.NO;
            } catch (HeadlessException | AWTError unavailable) {
                Logger log = LoggerFactory.getLogger(ClaudeStopConfirmation.class);
                log.debug("The Claude Code confirmation dialog was unavailable. Trying the terminal.", unavailable);
            }
        }
        String input = terminal.apply(message);
        if (input == null) {
            return Answer.UNAVAILABLE;
        }
        return switch (input.strip().toLowerCase(Locale.ROOT)) {
            case "y", "yes" -> Answer.YES;
            default -> Answer.NO;
        };
    }
}
