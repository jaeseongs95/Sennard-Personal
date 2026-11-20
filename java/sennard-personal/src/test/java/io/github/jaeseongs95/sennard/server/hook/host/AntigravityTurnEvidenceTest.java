// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.jaeseongs95.sennard.server.hook.HostWorkFacts.TurnWork;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The hand-written command and call tables of the turn-evidence rule (C1 to C3). */
class AntigravityTurnEvidenceTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '^', value = {"python app.py|python","pytest -q|pytest", "./run.sh|run.sh", "FOO=1 npm test|npm", "ls && pytest|pytest", "C:\\tools\\ctest.exe|ctest", "'python' x.py|python"})
    void aCommandThatRunsSomethingNamesIt(String command, String program) {
        assertEquals(program, AntigravityTurnEvidence.runProgram(command));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '^', value = {"ls -la","cat a", "grep x a", "git status", "echo hi", "sed -i s/a/b/ f", "cat <<EOF", "get-content a", "cd x && ls"})
    void aCommandThatOnlyLooksIsNoRun(String command) {
        assertEquals("", AntigravityTurnEvidence.runProgram(command));
    }

    @Test
    void aHeredocBodyIsNotReadForAProgram() {
        assertEquals("", AntigravityTurnEvidence.runProgram("cat <<EOF\npytest\nEOF"));
    }

    private static ObjectNode args() {
        return MAPPER.createObjectNode();
    }

    @Test
    void anEditCountsUnlessItFailedOrIsAnArtifactNote() {
        ObjectNode workspace = args().put("TargetFile", "/work/a.txt").put(AntigravityTurnEvidence.ARTIFACT_DIRECTORY, "/brain/1");
        assertEquals(Optional.of(TurnWork.EDIT), AntigravityTurnEvidence.classify("Write", workspace, false));
        assertEquals(Optional.empty(), AntigravityTurnEvidence.classify("Write", workspace, true));
        ObjectNode note = args().put("TargetFile", "/brain/1/plan.md").put(AntigravityTurnEvidence.ARTIFACT_DIRECTORY, "/brain/1");
        assertEquals(Optional.empty(), AntigravityTurnEvidence.classify("Edit", note, false));
        ObjectNode sibling = args().put("TargetFile", "/brain/10/plan.md").put(AntigravityTurnEvidence.ARTIFACT_DIRECTORY, "/brain/1");
        assertEquals(Optional.of(TurnWork.EDIT), AntigravityTurnEvidence.classify("MultiEdit", sibling, false), "a folder with the same prefix is another folder");
    }

    @Test
    void theArtifactFolderIsComparedAsPathsAfterDotsAreResolved() {
        String folder = AntigravityTurnEvidence.ARTIFACT_DIRECTORY;
        assertEquals(Optional.empty(), AntigravityTurnEvidence.classify("Write", args().put("TargetFile", "/brain/1/sub/../plan.md").put(folder, "/brain/1"), false), "a .. that stays inside");
        assertEquals(Optional.empty(), AntigravityTurnEvidence.classify("Write", args().put("TargetFile", "/brain/1/plan.md").put(folder, "/brain/./1/"), false), "a . and a closing slash");
        assertEquals(Optional.of(TurnWork.EDIT), AntigravityTurnEvidence.classify("Write", args().put("TargetFile", "/brain/1/../2/plan.md").put(folder, "/brain/1"), false), "a .. that climbs out");
        assertEquals(Optional.of(TurnWork.EDIT), AntigravityTurnEvidence.classify("Write", args().put("TargetFile", "/brain/1").put(folder, "/brain/1"), false), "the folder itself is no note inside it");
        assertEquals(Optional.empty(), AntigravityTurnEvidence.classify("Write", args().put("TargetFile", "brain/1/plan.md").put(folder, "brain/1"), false), "both relative: both resolved against the same directory");
    }

    /** Letter case is the file system's rule ({@code Path.equals}: "In some cases the paths are compared without regard to case"), so the expectation asks the same Path API. */
    @Test
    void letterCaseOfAPathIsDecidedByTheFileSystemAndNotByTheServersOperatingSystem() {
        Path folder = Path.of("/Brain/1").toAbsolutePath().normalize();
        Path file = Path.of("/brain/1/plan.md").toAbsolutePath().normalize();
        Optional<TurnWork> expected = file.startsWith(folder) ? Optional.empty() : Optional.of(TurnWork.EDIT);
        assertEquals(expected, AntigravityTurnEvidence.classify("Write", args().put("TargetFile", "/brain/1/plan.md").put(AntigravityTurnEvidence.ARTIFACT_DIRECTORY, "/Brain/1"), false));
        Optional<TurnWork> swapped = Path.of("/Brain/1/x").toAbsolutePath().startsWith(Path.of("/brain/1").toAbsolutePath()) ? Optional.empty() : Optional.of(TurnWork.EDIT);
        assertEquals(swapped, AntigravityTurnEvidence.classify("Write", args().put("TargetFile", "/Brain/1/x").put(AntigravityTurnEvidence.ARTIFACT_DIRECTORY, "/brain/1"), false));
    }

    @Test
    void aTargetThatIsNoPathIsNotInsideTheArtifactFolder() {
        ObjectNode odd = args().put("TargetFile", "/brain/1/a\0b").put(AntigravityTurnEvidence.ARTIFACT_DIRECTORY, "/brain/1");
        assertEquals(Optional.of(TurnWork.EDIT), AntigravityTurnEvidence.classify("Write", odd, false));
    }

    @Test
    void aSkillReadIsANamedSkillFileAndNotAnyFile() {
        assertEquals(Optional.of(TurnWork.SKILL_READ), AntigravityTurnEvidence.classify("view_file", args().put("AbsolutePath", "/x/SKILL.md"), false));
        assertEquals(Optional.empty(), AntigravityTurnEvidence.classify("view_file", args().put("AbsolutePath", "/x/README.md"), false));
    }

    @Test
    void aCommandThatFailedStillRan() {
        assertEquals(Optional.of(TurnWork.RUN), AntigravityTurnEvidence.classify("Bash", args().put("CommandLine", "pytest"), true));
        assertEquals(Optional.empty(), AntigravityTurnEvidence.classify("Bash", args().put("CommandLine", "ls"), false));
    }
}
