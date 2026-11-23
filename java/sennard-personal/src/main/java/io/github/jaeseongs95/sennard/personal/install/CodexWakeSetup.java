// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.awt.AWTError;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.io.Console;
import java.io.IOError;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import javax.swing.JOptionPane;

import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.UnavailableCode;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.personal.wake.CodexQueueWakeSettings;
import io.github.jaeseongs95.sennard.personal.wake.CodexQueueWakeSettings.Configuration;
import io.github.jaeseongs95.sennard.server.updates.StableVersion;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Confirms the effective wake transition before locking, then checks and applies it under the install lock. */
record CodexWakeSetup(InstallEnvironment environment, CodexQueueWakeSettings settings, List<Copy> copies, boolean promoteFiles) {
    static final CodexWakeSetup NONE = new CodexWakeSetup(null, null, List.of(), false);
    private static final String LEGACY_OVERRIDE = "AGENT_GOVERNANCE_CODEX_QUEUE_WAKE";
    private static final String PROMPT = """
            Codex queue wake defaults to on in Sennard 1.4. This upgrade will turn on wake where it was off.
            Turn on Codex queue wake and continue setup?
            Sennard 1.4에서는 Codex 큐 깨우기가 기본으로 켜집니다. 이번 업그레이드로 꺼져 있던 깨우기가 켜집니다.
            Codex 큐 깨우기를 켜고 설치를 계속하시겠습니까?""";
    private static final String CANCELLED = "Codex queue wake confirmation declined or unavailable. Setup cancelled."
            + " / Codex 큐 깨우기를 확인할 수 없어 설치를 중단했습니다.";

    static boolean isTransition(String previous, String next) {
        return isReleaseLine(previous, 3) && isReleaseLine(next, 4);
    }

    private static boolean isReleaseLine(String version, long minor) {
        String core = version.split("[-+]", 2)[0];
        return StableVersion.tryParse(core)
                .filter(parsed -> BigInteger.ONE.equals(parsed.major()) && BigInteger.valueOf(minor).equals(parsed.minor()))
                .isPresent();
    }

    static CodexWakeSetup prepare(InstallEnvironment environment, boolean migration, boolean commandLine) {
        return prepare(environment, migration, commandLine, commandLine ? CodexWakeSetup::confirmInTerminal : CodexWakeSetup::confirmInWindow);
    }

    static CodexWakeSetup prepare(InstallEnvironment environment, boolean migration, boolean commandLine, Predicate<String> confirm) {
        if (!migration) {
            return NONE;
        }
        String current = environment.environment().get(CodexQueueWakeSettings.OVERRIDE);
        if (current != null && !"1".equals(current)) {
            return NONE;
        }
        String legacy = environment.environment().get(LEGACY_OVERRIDE);
        try {
            if (current != null && legacy != null) {
                confirmTransition(!"1".equals(legacy), confirm);
                return NONE;
            }
            CodexQueueWakeSettings settings = new CodexQueueWakeSettings(JsonMapper.builder().build());
            List<Copy> copies = readCopies(environment, settings);
            boolean previouslyOff = legacy == null ? copies.stream().anyMatch(copy -> !copy.wasEnabled()) : !"1".equals(legacy);
            confirmTransition(previouslyOff, confirm);
            return new CodexWakeSetup(environment, settings, copies, current == null);
        } catch (IOException | JacksonException | InvalidPathException failed) {
            throw refused("Cannot read Codex queue wake settings. / Codex 큐 깨우기 설정을 읽을 수 없습니다.", failed);
        }
    }

    private static List<Copy> readCopies(InstallEnvironment environment, CodexQueueWakeSettings settings) throws IOException {
        List<Copy> copies = new ArrayList<>();
        for (Path folder : new CodexPackageLocation().dataFolders(environment)) {
            copies.add(new Copy(folder, settings.read(folder)));
        }
        return List.copyOf(copies);
    }

    private static void confirmTransition(boolean previouslyOff, Predicate<String> confirm) {
        if (previouslyOff && !confirm.test(PROMPT)) {
            throw refused(CANCELLED, null);
        }
    }

    void apply() {
        if (settings == null) {
            return;
        }
        try {
            if (!copies.equals(readCopies(environment, settings))) {
                throw refused("Codex queue wake settings changed during setup. Run the upgrade again."
                        + " / Codex 큐 깨우기 설정이 바뀌었습니다. 업그레이드를 다시 실행해 주세요.", null);
            }
            if (promoteFiles) {
                for (Copy copy : copies) {
                    if (copy.configuration().isPresent() && !copy.wasEnabled()) {
                        settings.enable(copy.folder(), copy.configuration().orElseThrow());
                    }
                }
            }
        } catch (IOException | JacksonException | InvalidPathException failed) {
            throw refused("Cannot write Codex queue wake settings. / Codex 큐 깨우기 설정을 저장할 수 없습니다.", failed);
        }
    }

    private record Copy(Path folder, Optional<Configuration> configuration) {
        boolean wasEnabled() {
            return configuration.map(Configuration::enabled).orElse(false);
        }
    }

    private static boolean confirmInTerminal(String prompt) {
        Console terminal = System.console();
        if (terminal == null || !terminal.isTerminal()) {
            return false;
        }
        try {
            return accepts(terminal.readLine("%s%n[yes/no] ", prompt));
        } catch (IOError failed) {
            throw refused(CANCELLED, failed);
        }
    }

    static boolean accepts(String input) {
        return input != null && "yes".equalsIgnoreCase(input.strip());
    }

    private static boolean confirmInWindow(String prompt) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        try {
            return JOptionPane.showConfirmDialog(null, prompt, "Sennard · Codex", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
        } catch (HeadlessException | AWTError failed) {
            throw refused(CANCELLED, failed);
        }
    }

    private static UnavailableException refused(String reason, Throwable cause) {
        return new UnavailableException(UnavailableCode.HOST_FILES_FAILED, SennardException.args("cause", reason), cause);
    }
}
