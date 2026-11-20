// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit.golden;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import io.github.jaeseongs95.sennard.serverit.GoldenHarness;

/**
 * The answer to every recorded hook body ({@code hook-fixtures/<os>/<host>/<event>.json}, 38 files) on the assembled server with real
 * PostgreSQL. Each case posts its fixture alone, under its own session, to the path the host's registration uses for that event. Cases H-*.
 */
@Tag("business-db")
class HookGoldenTest {
    private static final String GROUP = "hooks";
    private static final Path FIXTURES = Golden.REPOSITORY.resolve("java/sennard-personal/src/test/resources/hook-fixtures");
    private static Locale before;
    private static GoldenHarness server;
    private static List<Golden.Token> tokens;

    private record Fixture(String os, String host, String name) {
        String caseId() {
            return "H-" + os + "-" + host + "-" + name;
        }

        /** {@code PreInvocation-next} is a second recording of {@code PreInvocation}. */
        String event() {
            int dash = name.indexOf('-');
            return dash < 0 ? name : name.substring(0, dash);
        }

        /** The module the host registers for this event (ClaudeHookAdapter, CodexHookAdapter): an MCP tool's PreToolUse goes to continuity. */
        String path() {
            boolean module = event().equals("PreToolUse") && !host.equals("agy");
            return "/hooks/v1/" + host + "/" + event() + (module ? "/continuity" : "");
        }
    }

    @BeforeAll
    static void start() throws Exception {
        before = Locale.getDefault();
        Locale.setDefault(Locale.US); // the case table's language; restored after the class
        server = GoldenHarness.full(Golden.REPOSITORY);
        tokens = List.of(new Golden.Token("{STATE}", server.stateDirectory().toString(), true), new Golden.Token("{REPO}", Golden.REPOSITORY.toString(), true),
                new Golden.Token("{TMP}", Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString(), true),
                new Golden.Token("{ADMIN_PORT}", Integer.toString(server.adminPort()), false), new Golden.Token("{PORT}", Integer.toString(server.port()), false));
    }

    @AfterAll
    static void stop() {
        try {
            server.close();
        } finally {
            Locale.setDefault(before);
        }
    }

    private static List<Fixture> fixtures() throws IOException {
        List<Fixture> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(FIXTURES)) {
            files.filter(file -> file.toString().endsWith(".json")).map(FIXTURES::relativize).sorted().forEach(relative -> out.add(new Fixture(relative.getName(0).toString(),
                    relative.getName(1).toString(), relative.getName(2).toString().replace(".json", ""))));
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> everyRecordedHookGetsTheSameAnswer() throws IOException {
        return fixtures().stream().map(fixture -> DynamicTest.dynamicTest(fixture.caseId(), () -> {
            String session = UUID.nameUUIDFromBytes(("golden:" + fixture.caseId()).getBytes(StandardCharsets.UTF_8)).toString();
            byte[] body = GoldenHarness.hookFixture(fixture.os(), fixture.host(), fixture.name(), session, "toolu_fixture_2");
            Golden.check(GROUP, fixture.caseId(), Golden.answer(server.hook(fixture.path(), body)), tokens);
        }));
    }

    /** The order a host runs its events in; a recording that is not listed here is not part of a session's course. */
    private static final List<String> COURSE = List.of("SessionStart", "UserPromptSubmit", "PreInvocation", "PreToolUse", "PostToolUse", "PostInvocation", "PreInvocation-next", "Stop", "SessionEnd");

    /** The recordings of one OS and host in the order of {@link #COURSE}. */
    private static List<Fixture> course(String os, String host) throws IOException {
        return fixtures().stream().filter(fixture -> fixture.os().equals(os) && fixture.host().equals(host)).sorted(Comparator.comparingInt(fixture -> COURSE.indexOf(fixture.name()))).toList();
    }

    private static String sequenceId(Fixture fixture, int step) {
        return "H-seq-" + fixture.os() + "-" + fixture.host() + "-" + step + "-" + fixture.name();
    }

    private static List<String> sequenceIds() throws IOException {
        List<String> ids = new ArrayList<>();
        for (String os : List.of("linux", "windows")) {
            for (String host : List.of("agy", "claude", "codex")) {
                List<Fixture> events = course(os, host);
                for (int step = 0; step < events.size(); step++) {
                    ids.add(sequenceId(events.get(step), step + 1));
                }
            }
        }
        return ids;
    }

    /**
     * Each host's recordings one after another in one session, as the host runs them: what an event answers once the earlier events have
     * left their state (the answer to the session start alone is the single case of the same fixture).
     */
    @TestFactory
    Stream<DynamicTest> everyEventOfASessionGetsTheSameAnswer() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (String os : List.of("linux", "windows")) {
            for (String host : List.of("agy", "claude", "codex")) {
                String session = UUID.nameUUIDFromBytes(("golden:seq:" + os + "-" + host).getBytes(StandardCharsets.UTF_8)).toString();
                List<Fixture> events = course(os, host);
                for (int step = 0; step < events.size(); step++) {
                    Fixture fixture = events.get(step);
                    String id = sequenceId(fixture, step + 1);
                    tests.add(DynamicTest.dynamicTest(id, () -> {
                        byte[] body = GoldenHarness.hookFixture(fixture.os(), fixture.host(), fixture.name(), session, "toolu_fixture_2");
                        Golden.check(GROUP, id, Golden.answer(server.hook(fixture.path(), body)), tokens);
                    }));
                }
            }
        }
        return tests.stream();
    }

    @Test
    void theTableListsExactlyTheseCases() throws IOException {
        List<String> ids = new ArrayList<>(fixtures().stream().map(Fixture::caseId).toList());
        ids.addAll(sequenceIds());
        Golden.assertTableCovers("H-", ids);
    }
}
