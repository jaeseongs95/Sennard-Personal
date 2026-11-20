// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Where a POSIX shell command's writes land. Git Bash and MSYS2 write a drive as {@code /d/...}; a drive-letter file system does not take that for an
 * absolute path, so it must be spelled out before the edit gate looks for the file. Whether the shell's tree is the file system's is a parameter of
 * {@link ShellEditTargets#of(String, ShellEditTargets.Dialect, boolean, io.github.jaeseongs95.sennard.server.hook.ShellPaths)}, so both readings are tested here on every operating system and nothing here asks
 * which one the test runs on. Where an expectation is a path this file system builds (a joined path), it is built with {@code Path} too.
 */
class ShellEditTargetsTest {
    /** The shell's path spelling the product has: the one implementation the scan registers. */
    private static final MsysShellPaths SHELL = new MsysShellPaths();

    private static ShellEditTargets.Writes of(String command, ShellEditTargets.Dialect dialect, boolean shellTree) {
        return ShellEditTargets.of(command, dialect, shellTree, SHELL);
    }

    private static ShellEditTargets.Writes of(String command, ShellEditTargets.Dialect dialect) {
        return ShellEditTargets.of(command, dialect, SHELL);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '^', value = {
            "/d/x|D:\\x", "/c/Users/a b/c.txt|C:\\Users\\a b\\c.txt", "/D/x|D:\\x", "/d|D:\\", "/d/|D:\\", "/d/a/../b|D:\\a\\..\\b",
            "//server/share/x|//server/share/x", "/tmp/x|/tmp/x", "/dx/y|/dx/y", "/|/", "x/y|x/y", "D:/x|D:/x", "d:\\x|d:\\x", "~/x|~/x"})
    void anMsysDriveIsSpelledAsAWindowsDriveAndNothingElseChanges(String given, String expected) {
        assertEquals(expected, SHELL.windowsSpelling(given));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '^', value = {"/tmp/x|true", "/usr/bin/x|true", "/etc|true", "/dev/stderr|true", "/d/x|false", "/d|false", "//server/share|false", "x/y|false", "D:/x|false", "/dx|true"})
    void aPathRelativeToTheShellsOwnRootIsOneThisServerCannotPlace(String path, boolean rootRelative) {
        assertEquals(rootRelative, SHELL.rootRelative(path));
    }

    /** The shell's tree is not the file system's: Git Bash on a drive-letter file system. */
    private static ShellEditTargets.Writes msys(String command) {
        return of(command, ShellEditTargets.Dialect.POSIX, true);
    }

    /** The shell's tree is the file system's: a POSIX shell on a POSIX file system. */
    private static ShellEditTargets.Writes plain(String command) {
        return of(command, ShellEditTargets.Dialect.POSIX, false);
    }

    /** A path as this file system writes it, for an expectation the code builds with {@code Path}. */
    private static String fs(String path) {
        return Path.of(path).toString();
    }

    private static void assertRooted(ShellEditTargets.Writes writes, String... rooted) {
        assertWrites(writes, false);
        assertEquals(List.of(rooted), writes.shellRooted());
    }

    private static void assertWrites(ShellEditTargets.Writes writes, boolean unknown, String... paths) {
        assertEquals(List.of(paths), writes.paths());
        assertEquals(unknown, writes.unknown());
    }

    @Test
    void aRedirectToAnMsysDrivePathNamesTheWindowsFileWhereTheShellsTreeIsNotTheFileSystems() {
        assertWrites(msys("echo x > /d/ws/a.txt"), false, "D:\\ws\\a.txt");
        assertWrites(msys("echo x >> /c/Users/me/b.txt"), false, "C:\\Users\\me\\b.txt");
        assertWrites(plain("echo x > /d/ws/a.txt"), false, "/d/ws/a.txt");
        assertWrites(plain("echo x >> /c/Users/me/b.txt"), false, "/c/Users/me/b.txt");
    }

    @Test
    void everyCommandThatNamesAFileReadsAnMsysDriveTheSameWay() {
        for (String command : List.of("tee /d/ws/f", "rm /d/ws/f", "touch /d/ws/f", "sed -i s/a/b/ /d/ws/f", "dd if=/dev/zero of=/d/ws/f", "cp a.txt /d/ws/f", "curl -o /d/ws/f http://example.invalid/")) {
            assertWrites(msys(command), false, "D:\\ws\\f");
            assertWrites(plain(command), false, "/d/ws/f");
        }
    }

    /** AGS3-D-0170: the files {@code git -C <dir> checkout -- <file>} puts back are under {@code <dir>}, where a gate that read them under the shell's directory found another file. */
    @Test
    void aGitCommandWithDirectoryOptionNamesItsFilesUnderThatDirectory() {
        assertWrites(msys("git -C /d/ws checkout -- src/A.java"), false, "D:\\ws\\src\\A.java");
        assertWrites(plain("git -C /d/ws checkout HEAD -- a b"), false, fs("/d/ws/a"), fs("/d/ws/b"));
        assertWrites(plain("git -C /d/ws restore f"), false, fs("/d/ws/f"));
        assertWrites(plain("git -C /d/ws rm f"), false, fs("/d/ws/f"));
        assertWrites(plain("git -C sub checkout -- f"), false, fs("sub/f"));
        assertWrites(plain("cd /d/ws && git -C sub checkout -- f"), false, fs("/d/ws/sub/f"));
        assertWrites(plain("git -C /d/ws checkout -- f; echo x > g.txt"), false, fs("/d/ws/f"), "g.txt");
        assertWrites(plain("git checkout -- f"), false, "f");
    }

    @Test
    void aGitDirectoryThatIsNotOneLiteralLeavesItsFilesUnread() {
        assertWrites(plain("git -C $WS checkout -- f"), true);
        assertWrites(plain("git -C /d/a -C b checkout -- f"), true);
    }

    @Test
    void moveWritesBothItsSourceAndItsDestination() {
        assertWrites(msys("mv /d/a /d/b"), false, "D:\\a", "D:\\b");
        assertWrites(plain("mv /d/a /d/b"), false, "/d/a", "/d/b");
    }

    @Test
    void aTargetDirectoryOfAnMsysDriveTakesTheFilesNames() {
        assertWrites(msys("cp a.txt b.txt /d/dir/"), false, "D:\\dir\\a.txt", "D:\\dir\\b.txt");
        assertWrites(msys("cp -t /d/dir a.txt"), false, "D:\\dir\\a.txt");
        assertWrites(plain("cp a.txt b.txt /d/dir/"), false, fs("/d/dir/a.txt"), fs("/d/dir/b.txt"));
        assertWrites(plain("cp -t /d/dir a.txt"), false, fs("/d/dir/a.txt"));
    }

    @Test
    void aLocationChangeToAnMsysDriveIsFollowed() {
        assertWrites(msys("cd /d/ws && echo x > f.txt"), false, "D:\\ws\\f.txt");
        assertWrites(msys("cd /d/ws/sub; cd .. ; echo x > f.txt"), false, "D:\\ws\\f.txt");
        assertWrites(msys("(cd /d/ws && echo x > f.txt); echo y > g.txt"), false, "D:\\ws\\f.txt", "g.txt");
        assertWrites(plain("cd /d/ws && echo x > f.txt"), false, fs("/d/ws/f.txt"));
        assertWrites(plain("cd /d/ws/sub; cd .. ; echo x > f.txt"), false, fs("/d/ws/f.txt"));
        assertWrites(plain("(cd /d/ws && echo x > f.txt); echo y > g.txt"), false, fs("/d/ws/f.txt"), "g.txt");
    }

    /** Git Bash resolves {@code ..} in its own tree, where {@code /d} and {@code /c} are two directories of one root: climbing out of {@code /d} lands on another drive. */
    @Test
    void aDotDotThatClimbsOutOfADriveLandsOnTheDriveTheShellWouldReach() {
        assertWrites(msys("echo x > /d/../c/repo/f"), false, "C:\\repo\\f");
        assertWrites(msys("echo x > /d/ws/../../c/repo/f"), false, "C:\\repo\\f");
        assertWrites(msys("echo x > /d/../../c/f"), false, "C:\\f");
        assertWrites(msys("cd /d/ws; cd ../..; echo x > c/repo/f"), false, "C:\\repo\\f");
        assertWrites(msys("cd /d/ws && cd .. && cd .. && cp a /c/dir/"), false, "C:\\dir\\a");
        assertWrites(msys("cp a /d/../c/dir/"), false, "C:\\dir\\a");
        assertWrites(msys("echo x > /d/ws/./sub/../f"), false, "D:\\ws\\f");
        assertRooted(msys("echo x > /d/../etc/f"), "/etc/f");
        assertWrites(msys("echo x > /dev/../c/repo/f"), false, "C:\\repo\\f");
        assertEquals(List.of(), msys("echo x > /dev/../c/repo/f").shellRooted());
        // The same text in a shell whose tree is the file system's names no drive: a path is kept as it is written, or joined and normalized by the file system.
        assertWrites(plain("echo x > /d/../c/repo/f"), false, "/d/../c/repo/f");
        assertWrites(plain("echo x > /d/ws/../../c/repo/f"), false, "/d/ws/../../c/repo/f");
        assertWrites(plain("echo x > /d/../../c/f"), false, "/d/../../c/f");
        assertWrites(plain("cd /d/ws; cd ../..; echo x > c/repo/f"), false, fs("/c/repo/f"));
        assertWrites(plain("cd /d/ws && cd .. && cd .. && cp a /c/dir/"), false, fs("/c/dir/a"));
        assertWrites(plain("cp a /d/../c/dir/"), false, fs("/c/dir/a"));
        assertWrites(plain("echo x > /d/ws/./sub/../f"), false, "/d/ws/./sub/../f");
        assertEquals(List.of(), plain("echo x > /d/../etc/f").shellRooted());
    }

    @Test
    void aCommandRunByABashStartedFromTheShellReadsTheSameWay() {
        assertWrites(msys("bash -c 'echo x > /d/ws/a.txt'"), false, "D:\\ws\\a.txt");
        assertWrites(plain("bash -c 'echo x > /d/ws/a.txt'"), false, "/d/ws/a.txt");
    }

    @Test
    void aPathOfTheShellsOwnRootIsKeptAsTextWhereItIsNotTheFileSystemsAndAnAbsolutePathWhereItIs() {
        assertRooted(msys("echo x > /tmp/x"), "/tmp/x");
        assertRooted(msys("echo x > /usr/local/x"), "/usr/local/x");
        assertRooted(msys("echo x > /tmp/../etc/x"), "/etc/x");
        assertRooted(msys("echo x > /tmp//a/./b"), "/tmp/a/b");
        assertRooted(msys("cp a.txt b.txt /tmp/dir/"), "/tmp/dir/a.txt", "/tmp/dir/b.txt");
        ShellEditTargets.Writes mixed = msys("echo x > /tmp/x; echo y > /d/ws/a");
        assertWrites(mixed, false, "D:\\ws\\a");
        assertEquals(List.of("/tmp/x"), mixed.shellRooted());
        // The shell's directory is followed in its own tree too, so what it writes there next is that place.
        assertRooted(msys("cd /tmp && echo x > f"), "/tmp/f");
        assertRooted(msys("cd /tmp/a && cd ../b && echo x > f"), "/tmp/b/f");
        // A directory it cannot follow leaves the next write without a place.
        assertWrites(msys("cd \"$DIR\" && echo x > f"), true);
        assertWrites(plain("echo x > /tmp/x"), false, "/tmp/x");
        assertWrites(plain("echo x > /usr/local/x"), false, "/usr/local/x");
        assertEquals(List.of(), plain("echo x > /tmp/x").shellRooted());
    }

    @Test
    void aDeviceNameIsNoFileInTheShellsTreeAndAPathInTheFileSystemsOwn() {
        assertNull(msys("echo x > /dev/stderr"));
        assertNull(msys("echo x > /dev/null"));
        assertWrites(plain("echo x > /dev/stderr"), false, "/dev/stderr");
        assertNull(plain("echo x > /dev/null"));
    }

    @Test
    void theNullDeviceIsNamedByTheShellsDialectAndNotByTheOperatingSystem() {
        for (String command : List.of("echo x > nul", "echo x > NUL", "echo x > nul:", "echo x > $null", "echo x > $NULL")) {
            assertNull(of(command, ShellEditTargets.Dialect.POWERSHELL, true), command + " in PowerShell");
            assertNull(of(command, ShellEditTargets.Dialect.POWERSHELL, false), command + " in PowerShell");
        }
        // In a POSIX shell nul is a file name like any other, and $null is a variable it cannot read.
        assertWrites(msys("echo x > nul"), false, "nul");
        assertWrites(plain("echo x > nul"), false, "nul");
        assertWrites(plain("echo x > $null"), true);
    }

    @Test
    void anEventThatNamesNoShellIsReadInBothDialects() {
        ShellEditTargets.Writes powershell = of("Set-Content a.txt x", ShellEditTargets.Dialect.EITHER, false);
        assertWrites(powershell, false, "a.txt");
        assertWrites(of("echo x > b.txt", ShellEditTargets.Dialect.EITHER, false), false, "b.txt");
        assertWrites(of("echo x > nul", ShellEditTargets.Dialect.EITHER, false), false, "nul");
        // A heredoc is POSIX only: PowerShell has no such syntax, so its reading is not added.
        assertNull(of("cat <<EOF\nSet-Content a.txt x\nEOF", ShellEditTargets.Dialect.EITHER, false));
    }

    @Test
    void anAbsolutePathIsWhatTheFileSystemSaysAndIsNotJoinedOntoTheShellsDirectory() {
        String absolute = Path.of("").toAbsolutePath().resolve("x.txt").toString();
        // A slash spelling of the same absolute path, so the POSIX tokenizer keeps it whole.
        String slashes = absolute.replace('\\', '/');
        assertWrites(plain("cd sub && echo x > " + slashes), false, slashes);
        assertWrites(of("cd sub; Set-Content '" + absolute + "' v", ShellEditTargets.Dialect.POWERSHELL, false), false, absolute);
        assertWrites(plain("cd sub && echo x > y.txt"), false, fs("sub/y.txt"));
    }

    @Test
    void theDefaultTakesTheShellsTreeFromTheFileSystemsOwnRule() {
        boolean slashIsNotAnAbsolutePath = !Path.of("/").isAbsolute();
        for (String command : List.of("echo x > /d/ws/a.txt", "cd /tmp && echo x > f", "cp a /d/dir/", "echo x > rel.txt")) {
            assertEquals(of(command, ShellEditTargets.Dialect.POSIX, slashIsNotAnAbsolutePath), of(command, ShellEditTargets.Dialect.POSIX), command);
        }
    }

    @Test
    void aUncPathAndAWindowsDriveAreAbsoluteAsTheyAre() {
        assertWrites(msys("echo x > //server/share/x"), false, "//server/share/x");
        assertWrites(msys("echo x > D:/ws/x"), false, "D:/ws/x");
        assertWrites(plain("echo x > //server/share/x"), false, "//server/share/x");
        assertWrites(plain("echo x > D:/ws/x"), false, "D:/ws/x");
    }

    @Test
    void aRelativePathStaysRelative() {
        assertWrites(msys("echo x > a/b.txt"), false, "a/b.txt");
        assertWrites(plain("echo x > a/b.txt"), false, "a/b.txt");
        assertWrites(msys("cd sub && echo x > b.txt"), false, fs("sub/b.txt"));
        assertWrites(plain("cd sub && echo x > b.txt"), false, fs("sub/b.txt"));
    }

    @Test
    void powerShellReadsASlashPathAsItsOwnWithNoDriveInIt() {
        assertWrites(of("Set-Content /d/x v", ShellEditTargets.Dialect.POWERSHELL, true), false, "/d/x");
        assertWrites(of("Set-Content /d/x v", ShellEditTargets.Dialect.POWERSHELL, false), false, "/d/x");
    }

    /** AGS3-D-0196: cmd.exe keeps a backslash as a backslash, quotes only with {@code "}, and expands {@code %VAR%}. */
    @Test
    void cmdReadsABackslashPathAsWindowsDoes() {
        for (boolean shellTree : List.of(true, false)) {
            assertWrites(of("echo review>D:\\repo\\cmd-0.txt", ShellEditTargets.Dialect.CMD, shellTree), false, "D:\\repo\\cmd-0.txt");
            assertWrites(of("echo a >> \"C:\\Users\\me\\my dir\\f.txt\" & echo b > D:\\repo\\g.txt", ShellEditTargets.Dialect.CMD, shellTree), false, "C:\\Users\\me\\my dir\\f.txt", "D:\\repo\\g.txt");
            assertWrites(of("echo x > %TEMP%\\f.txt", ShellEditTargets.Dialect.CMD, shellTree), true);
            assertWrites(of("echo x > D:\\repo\\*.txt", ShellEditTargets.Dialect.CMD, shellTree), true);
            assertNull(of("echo x > nul 2>nul", ShellEditTargets.Dialect.CMD, shellTree));
            assertNull(of("dir D:\\repo # not a comment", ShellEditTargets.Dialect.CMD, shellTree));
        }
    }

    @Test
    void cmdFollowsADirectoryChangeOfItsOwnSyntax() {
        String inWorkspace = Path.of("D:\\ws").resolve("a.txt").toString();
        assertWrites(of("cd /d D:\\ws && echo x > a.txt", ShellEditTargets.Dialect.CMD, false), false, inWorkspace);
        // A change of a drive's directory without /d does not move the current directory: where a relative write lands is not known.
        assertWrites(of("cd D:\\ws && echo x > a.txt", ShellEditTargets.Dialect.CMD, false), true);
    }

    /** AGS3-D-0005: an interpreter given a script file runs code the observation does not read, so it is a write whose target is unknown. */
    @Test
    void anInterpreterThatRunsAScriptFileIsAnUnreadWrite() {
        for (String command : List.of("node ./write-three.mjs", "nodejs write.js --flag", "node --max-old-space-size=512 write.mjs", "python write.py", "python3 -u ./write.py arg", "py write.py",
                "ruby write.rb", "bun write.ts", "bun run ./write.mjs", "cd sub && node write.mjs", "FOO=1 python write.py")) {
            assertWrites(msys(command), true);
            assertWrites(plain(command), true);
        }
    }

    @Test
    void anInterpreterWithNoScriptFileIsNoUnreadWrite() {
        for (String command : List.of("node --version", "node -v", "node -e \"console.log(1)\"", "python --version", "python -m pytest tests", "python - <<EOF\nprint(1)\nEOF", "python -c \"print(1)\"",
                "ruby -v", "bun test", "bun install", "bun run build")) {
            assertNull(msys(command), command);
            assertNull(plain(command), command);
        }
    }

    /**
     * The stream number of a redirection ({@code 2} of {@code 2>&1}) is no operand: left as a word, it was the script operand of {@code node --check -}, and the read-only check of a
     * script from a pipe was refused as a write to an unknown path.
     */
    @Test
    void aDescriptorDuplicationIsNoWriteAndNoOperandOfTheCommand() {
        for (String command : List.of("node --check - 2>&1", "curl -s https://example.test/a.js | awk '{print}' | node --check - 2>&1 | head", "node --check - 2>&1 >&2", "node --check - 1>&2", "node --check - 2>/dev/null",
                "node --check - &>/dev/null", "node --check - >/dev/null 2>&1", "echo x >&2", "echo x 1>&2", "echo x 2>&1 | head", "node --check - 10>&2", "node --check - 0</dev/null 2>&1")) {
            assertNull(msys(command), command);
            assertNull(plain(command), command);
        }
    }

    @Test
    void aStreamNumberIsNotTakenFromAnArgumentThatIsANumber() {
        // With a space the 2 is the program's argument, as before: a script operand is still a script file run.
        assertWrites(msys("node 2 > /d/ws/out.txt"), true, "D:\\ws\\out.txt");
        assertWrites(msys("node write.mjs 2>&1"), true);
        assertWrites(plain("node write.mjs 2>&1"), true);
    }

    /** A redirection to a file is a write whether or not it carries a stream number: this rule is the same as before. */
    @Test
    void aRedirectionToAFileIsStillAWriteWithOrWithoutAStreamNumber() {
        assertWrites(msys("echo x 2> /d/ws/err.log"), false, "D:\\ws\\err.log");
        assertWrites(msys("echo x 2>/d/ws/err.log"), false, "D:\\ws\\err.log");
        assertWrites(msys("echo x > /d/ws/out.log 2>&1"), false, "D:\\ws\\out.log");
        assertWrites(msys("echo x &> /d/ws/both.log"), false, "D:\\ws\\both.log");
        assertWrites(msys("echo x 2>> /d/ws/err.log"), false, "D:\\ws\\err.log");
        assertWrites(plain("echo x 2>/d/ws/err.log"), false, "/d/ws/err.log");
        assertWrites(plain("echo 2 > /d/ws/out.log"), false, "/d/ws/out.log");
    }

    @Test
    void aStreamNumberIsDroppedInPowerShellAndCmdToo() {
        assertNull(of("node --check - 2>&1", ShellEditTargets.Dialect.POWERSHELL, false));
        assertNull(of("node --check - 2>&1", ShellEditTargets.Dialect.CMD, false));
    }

    @Test
    void aScriptFileRunIsStillNamedWithTheFilesTheSameCommandNames() {
        assertWrites(msys("node write.mjs > /d/ws/out.txt"), true, "D:\\ws\\out.txt");
    }

    @Test
    void aReadOnlyCommandIsNoWrite() {
        assertNull(msys("cat /d/ws/a.txt"));
        assertNull(msys("ls /d/ws && git status"));
        assertNull(plain("cat /d/ws/a.txt"));
        assertNull(plain("ls /d/ws && git status"));
    }

    /** AGS3-D-0005: a shell given a script file, a program started by its path, and perl, php and deno given a script run code the observation does not read. */
    @Test
    void aScriptFileGivenToAShellOrStartedByPathOrGivenToAnInterpreterIsAnUnreadWrite() {
        for (String command : List.of("bash x.sh", "sh ./x.sh", "zsh -x x.sh arg", "bash -e ./scripts/run.sh", "./x.sh", "scripts/run.sh --flag", "/opt/tools/run", "cd sub && ./x.sh", "FOO=1 ./x.sh",
                "cat x.sh | bash", "source ./env.sh", ". ./env.sh", "perl x.pl", "perl -w ./x.pl arg", "php x.php", "deno run x.ts", "deno run -A https://example.invalid/x.ts", "deno task build", "deno x.ts")) {
            assertWrites(msys(command), true);
            assertWrites(plain(command), true);
        }
        assertWrites(of("./x.sh", ShellEditTargets.Dialect.EITHER, false), true);
    }

    @Test
    void aShellOrAnInterpreterThatRunsNoScriptFileIsNoUnreadWrite() {
        for (String command : List.of("bash --version", "bash -n x.sh", "bash -c 'echo hi'", "perl -e 'print 1'", "perl -v", "php -r 'echo 1;'", "php --version", "deno --version", "deno lint",
                "deno eval 'console.log(1)'", "echo hi", "ls ./scripts", "cat ./x.sh")) {
            assertNull(msys(command), command);
            assertNull(plain(command), command);
        }
    }

    @Test
    void aShellGivenCommandsOnItsInputReadsThemAndOnePipedInIsUnread() {
        assertWrites(plain("bash <<EOF\necho x > a.txt\nEOF"), false, "a.txt");
        assertWrites(plain("bash <<< 'echo x > a.txt'"), false, "a.txt");
        assertWrites(plain("echo 'echo x > a.txt' | bash"), true);
    }

    @Test
    void powerShellGivenAScriptFileOrStartedByPathIsAnUnreadWrite() {
        for (boolean shellTree : List.of(true, false)) {
            for (String command : List.of("pwsh -File x.ps1", "pwsh -NoProfile -ExecutionPolicy Bypass -File ./x.ps1 -Arg 1", "powershell -File x.ps1", "pwsh x.ps1", "pwsh -NoProfile x.ps1",
                    ".\\x.ps1", "& .\\x.ps1", "scripts\\run.ps1 arg", ". .\\x.ps1", "./x.ps1", "pwsh -Command .\\x.ps1")) {
                assertWrites(of(command, ShellEditTargets.Dialect.POWERSHELL, shellTree), true);
            }
            for (String command : List.of("pwsh -Command \"Get-Date\"", "pwsh -NoProfile -Command Get-ChildItem", "pwsh -Version", "Get-ChildItem .\\src", "Get-Content x.ps1")) {
                assertNull(of(command, ShellEditTargets.Dialect.POWERSHELL, shellTree), command);
            }
        }
    }

    /** A Windows binary started by its path is no script. */
    @Test
    void aWindowsBinaryStartedByItsPathIsNoScript() {
        assertNull(of("C:\\tools\\ctest.exe --version", ShellEditTargets.Dialect.POWERSHELL, false));
        assertNull(of("C:\\tools\\ctest.exe --version", ShellEditTargets.Dialect.CMD, false));
    }

    /** AGS3-D-0005: cmd.exe started from any shell runs its command line, which is read as cmd.exe reads it. */
    @Test
    void aCmdCommandLineGivenToCmdExeIsReadAsNestedShellInEveryDialect() {
        String redirect = "cmd /c \"echo x > D:\\repo\\a.txt\"";
        for (boolean shellTree : List.of(true, false)) {
            assertWrites(of(redirect, ShellEditTargets.Dialect.POSIX, shellTree), false, "D:\\repo\\a.txt");
            assertWrites(of("cmd //c \"echo x > D:\\repo\\a.txt\"", ShellEditTargets.Dialect.POSIX, shellTree), false, "D:\\repo\\a.txt");
            assertWrites(of(redirect, ShellEditTargets.Dialect.POWERSHELL, shellTree), false, "D:\\repo\\a.txt");
            assertWrites(of(redirect, ShellEditTargets.Dialect.CMD, shellTree), false, "D:\\repo\\a.txt");
            assertWrites(of("cmd /c \"echo x > a.txt & echo y > b.txt\"", ShellEditTargets.Dialect.CMD, shellTree), false, "a.txt", "b.txt");
            assertWrites(of("cmd /c build.bat", ShellEditTargets.Dialect.POSIX, shellTree), true);
            assertWrites(of("cmd /c call x.cmd", ShellEditTargets.Dialect.CMD, shellTree), true);
            assertWrites(of("x.bat arg", ShellEditTargets.Dialect.CMD, shellTree), true);
            assertWrites(of(".\\tools\\run.bat", ShellEditTargets.Dialect.CMD, shellTree), true);
            assertNull(of("cmd /c dir D:\\repo", ShellEditTargets.Dialect.POSIX, shellTree));
            assertNull(of("cmd /c \"type D:\\repo\\a.txt\"", ShellEditTargets.Dialect.POSIX, shellTree));
        }
    }

    private static ShellEditTargets.Writes cmd(String command) {
        return of(command, ShellEditTargets.Dialect.CMD, false);
    }

    /** AGS3-D-0196: the file commands built into cmd.exe write the file their operand names. */
    @Test
    void cmdDeleteAndRemoveCommandsCountEveryFileOrDirectoryTheyName() {
        assertWrites(cmd("del D:\\repo\\a.txt"), false, "D:\\repo\\a.txt");
        assertWrites(cmd("del /q /f D:\\repo\\a.txt D:\\repo\\b.txt"), false, "D:\\repo\\a.txt", "D:\\repo\\b.txt");
        assertWrites(cmd("erase D:\\repo\\a.txt"), false, "D:\\repo\\a.txt");
        assertWrites(cmd("md D:\\repo\\new"), false, "D:\\repo\\new");
        assertWrites(cmd("mkdir D:\\repo\\a D:\\repo\\b"), false, "D:\\repo\\a", "D:\\repo\\b");
        assertWrites(cmd("rd /s /q D:\\repo\\old"), false, "D:\\repo\\old");
        assertWrites(cmd("rmdir D:\\repo\\old"), false, "D:\\repo\\old");
        assertWrites(cmd("del D:\\repo\\a.txt & type D:\\repo\\b.txt"), false, "D:\\repo\\a.txt");
    }

    @Test
    void cmdDeleteOfATargetItCannotReadIsAnUnreadWrite() {
        assertWrites(cmd("del D:\\repo\\*.txt"), true);
        assertWrites(cmd("del %TEMP%\\a.txt"), true);
        assertWrites(cmd("rd /s /q %BUILD%"), true);
    }

    @Test
    void cmdCopyMoveRenameAndLinkCountTheFilesThatChange() {
        assertWrites(cmd("copy a.txt D:\\repo\\b.txt"), false, "D:\\repo\\b.txt");
        assertWrites(cmd("copy /y D:\\src\\a.txt D:\\repo\\b.txt"), false, "D:\\repo\\b.txt");
        assertWrites(cmd("copy a.txt D:\\repo\\out\\"), false, Path.of("D:\\repo\\out\\").resolve("a.txt").normalize().toString());
        assertWrites(cmd("copy a.txt"), true);
        assertWrites(cmd("move D:\\repo\\a.txt D:\\repo\\b.txt"), false, "D:\\repo\\a.txt", "D:\\repo\\b.txt");
        assertWrites(cmd("ren D:\\repo\\a.txt b.txt"), false, "D:\\repo\\a.txt", Path.of("D:/repo").resolve("b.txt").normalize().toString());
        assertWrites(cmd("rename a.txt b.txt"), false, "a.txt", "b.txt");
        assertWrites(cmd("ren a.txt"), true);
        assertWrites(cmd("ren *.txt *.bak"), true);
        assertWrites(cmd("mklink /D D:\\repo\\link D:\\repo\\target"), false, "D:\\repo\\link");
        assertWrites(cmd("mklink D:\\repo\\link"), true);
    }

    @Test
    void cmdXcopyAndRobocopyCountTheirDestinationAndWhatTheyAlsoWrite() {
        assertWrites(cmd("xcopy D:\\src D:\\repo\\out /E /I /Y"), false, "D:\\repo\\out");
        assertWrites(cmd("xcopy D:\\src"), true);
        assertWrites(cmd("robocopy D:\\src D:\\repo\\out /MIR"), false, "D:\\repo\\out");
        assertWrites(cmd("robocopy D:\\src D:\\repo\\out /E /MOVE"), false, "D:\\repo\\out", "D:\\src");
        assertWrites(cmd("robocopy D:\\src D:\\repo\\out /LOG:D:\\logs\\r.txt"), false, "D:\\repo\\out", "D:\\logs\\r.txt");
        assertWrites(cmd("robocopy D:\\src"), true);
    }

    @Test
    void aCmdBuiltinIsReadFromABatchLineAFollowedDirectoryAndANestedCmd() {
        assertWrites(cmd("cd /d D:\\ws && del a.txt"), false, Path.of("D:\\ws").resolve("a.txt").toString());
        assertWrites(of("cmd.exe /d /c \"del D:\\repo\\a.txt\"", ShellEditTargets.Dialect.POSIX, false), false, "D:\\repo\\a.txt");
        assertWrites(of("cmd /c \"copy a.txt D:\\repo\\b.txt\"", ShellEditTargets.Dialect.POWERSHELL, false), false, "D:\\repo\\b.txt");
        assertWrites(cmd("cmd /c \"ren D:\\repo\\a.txt b.txt\""), false, "D:\\repo\\a.txt", Path.of("D:/repo").resolve("b.txt").normalize().toString());
    }

    @Test
    void cmdCommandsThatOnlyReadAreNoWrite() {
        for (String command : List.of("dir D:\\repo", "dir /s /b D:\\repo", "type D:\\repo\\a.txt", "echo hi", "findstr x D:\\repo\\a.txt", "where git", "tree D:\\repo", "cd /d D:\\repo")) {
            assertNull(cmd(command), command);
        }
        // These are no built-ins of a POSIX shell: it has no such command to read.
        assertNull(plain("copy a b"));
        assertNull(plain("del a"));
    }

    /** A backslash in a POSIX double-quoted string stays one unless it escapes {@code $}, a backquote, a quote or a backslash, so a Windows path in it is whole. */
    @Test
    void aBackslashInAPosixDoubleQuotedStringEscapesOnlyWhatPosixSays() {
        assertWrites(plain("echo x > \"D:\\ws\\a.txt\""), false, "D:\\ws\\a.txt");
        assertWrites(plain("echo x > \"a\\\\b\""), false, "a\\b");
        assertWrites(plain("echo x > \"a\\$b\""), false, "a$b");
        assertWrites(plain("echo x > a\\ b"), false, "a b");
    }

    /** AGS3-D-0005: what these git commands change is the difference of two states of the repository, which the command text does not name. */
    @Test
    void aGitCommandThatRewritesTheWorkingTreeFromTheRepositoryStateIsAnUnreadWrite() {
        for (String command : List.of("git pull", "git pull origin main", "git merge topic", "git merge --abort", "git cherry-pick abc123", "git rebase main", "git rebase --continue", "git stash pop",
                "git stash apply stash@{1}", "git reset --hard", "git reset --hard HEAD~1", "git checkout main", "git checkout -b topic", "git switch main", "git switch -c topic",
                "git -C sub merge topic", "git checkout main && git pull")) {
            assertWrites(msys(command), true);
            assertWrites(plain(command), true);
        }
        assertWrites(plain("git -C /d/ws pull"), true);
        assertWrites(plain("git pull && echo x > a.txt"), true, "a.txt");
    }

    @Test
    void aGitCommandThatOnlyReadsOrOnlyChangesTheIndexOrNamesItsFilesIsNoUnreadWrite() {
        for (String command : List.of("git status", "git log --oneline -5", "git diff HEAD~1", "git show HEAD", "git branch -a", "git fetch origin", "git stash list", "git stash show", "git reset --soft HEAD~1",
                "git reset HEAD a.txt", "git restore --staged a.txt", "git merge-base main topic", "git rev-parse HEAD", "git remote -v", "git checkout")) {
            assertNull(msys(command), command);
            assertNull(plain(command), command);
        }
        assertWrites(plain("git checkout -- a.txt"), false, "a.txt");
        assertWrites(plain("git checkout HEAD -- a.txt b.txt"), false, "a.txt", "b.txt");
        assertWrites(plain("git restore a.txt"), false, "a.txt");
    }

    private static void assertUnreadIn(ShellEditTargets.Writes writes, boolean unplaced, String... folders) {
        assertEquals(true, writes.unknown());
        assertEquals(List.of(folders), writes.unreadFolders());
        assertEquals(unplaced, writes.unreadUnplaced());
    }

    /** A write the hook cannot read is at the folder the shell followed to: a build run by path after {@code cd}, in either spelling of a drive. */
    @Test
    void anUnreadWriteIsInTheFolderTheShellFollowedTo() {
        assertUnreadIn(msys("./gradlew build"), false, "");
        assertUnreadIn(msys("cd /d/ws && ./gradlew build"), false, "D:\\ws");
        assertUnreadIn(msys("cd /d/ws/../ws/java && ./gradlew build"), false, "D:\\ws\\java");
        assertUnreadIn(msys("cd D:/ws && ./gradlew build"), false, "D:/ws");
        assertUnreadIn(plain("cd /d/ws && ./gradlew build"), false, "/d/ws");
        assertUnreadIn(msys("cd sub && ./gradlew build"), false, "sub");
        assertUnreadIn(msys("cd /d/ws && cd java && ./gradlew build"), false, "D:\\ws\\java");
        assertUnreadIn(msys("cd /d/ws && cd /c/other && ./gradlew build"), false, "C:\\other");
    }

    /** The two spellings of one drive name one place, so a relative file after {@code cd} is the same file in both. */
    @Test
    void aNamedPathAfterALocationChangeIsTheSameFileInEveryDriveSpelling() {
        assertWrites(msys("cd /d/ws && sed -i s/a/b/ java/a.html"), false, "D:\\ws\\java\\a.html");
        assertWrites(msys("cd /D/ws && sed -i s/a/b/ java/a.html"), false, "D:\\ws\\java\\a.html");
        assertWrites(msys("cd D:/ws && sed -i s/a/b/ java/a.html"), false, fs("D:/ws/java/a.html"));
        assertWrites(msys("sed -i s/a/b/ /d/ws/java/a.html"), false, "D:\\ws\\java\\a.html");
        assertWrites(msys("sed -i s/a/b/ D:/ws/java/a.html"), false, "D:/ws/java/a.html");
    }

    @Test
    void thePlacesOfSeveralUnreadWritesAreAllKeptAndASubshellKeepsItsFolderToItself() {
        assertUnreadIn(msys("(cd /d/ws && ./a.sh); ./b.sh"), false, "D:\\ws", "");
        assertUnreadIn(msys("cd /d/ws && ./a.sh; cd /d/other && ./b.sh; ./c.sh"), false, "D:\\ws", "D:\\other");
        assertUnreadIn(msys("bash -c \"cd /d/ws && ./gradlew build\""), false, "D:\\ws");
        assertUnreadIn(msys("git -C /d/ws pull; ./b.sh"), false, "D:\\ws", "");
    }

    /** A folder the observation cannot follow to is no folder: the write is unplaced, which no workflow covers. */
    @Test
    void aLocationChangeThatIsNotOneLiteralLeavesTheUnreadWriteUnplaced() {
        for (String command : List.of("cd \"$X\" && ./gradlew build", "cd $X && ./gradlew build", "cd ~ && ./gradlew build", "cd - && ./gradlew build", "cd /d/w* && ./gradlew build", "cd && ./gradlew build",
                "pushd /d/ws && popd && ./gradlew build", "git -C $WS pull", "git -C /d/a -C b pull", "cd \"$X\"; cd /d/ws && ./gradlew build; cd \"$X\" && ./gradlew build")) {
            ShellEditTargets.Writes writes = msys(command);
            assertEquals(true, writes.unreadUnplaced(), command);
        }
        assertUnreadIn(msys("cd /d/ws && ./a.sh; cd \"$X\" && ./b.sh"), true, "D:\\ws");
        // A relative write after a folder that is not known has no place either.
        assertUnreadIn(msys("cd \"$X\" && echo x > a.txt"), true);
        // The shell's own root is a place this file system cannot find; it is no folder here, and is one of the file system's where the tree is the file system's.
        assertUnreadIn(msys("cd /tmp && ./run.sh"), true);
        assertUnreadIn(plain("cd /tmp && ./run.sh"), false, "/tmp");
    }

    @Test
    void powerShellAndCmdFollowTheirOwnLocationChangeTheSameWay() {
        ShellEditTargets.Dialect ps = ShellEditTargets.Dialect.POWERSHELL;
        ShellEditTargets.Dialect cmd = ShellEditTargets.Dialect.CMD;
        assertUnreadIn(of("Set-Location D:\\ws; ./gradlew build", ps, false), false, "D:\\ws");
        assertUnreadIn(of("Set-Location -Path D:/ws ; .\\gradlew build", ps, false), false, "D:/ws");
        assertUnreadIn(of("cd D:\\ws; ./gradlew build", ps, false), false, "D:\\ws");
        assertUnreadIn(of("sl sub; ./gradlew build", ps, false), false, "sub");
        assertUnreadIn(of("Set-Location $env:TARGET; ./gradlew build", ps, false), true);
        assertUnreadIn(of("Pop-Location; ./gradlew build", ps, false), true);
        assertUnreadIn(of("cd /d D:\\ws && gradlew.bat build", cmd, false), false, "D:\\ws");
        assertUnreadIn(of("cd /d sub && gradlew.bat build", cmd, false), false, "sub");
        assertUnreadIn(of("cd /d %TARGET% && gradlew.bat build", cmd, false), true);
        // A drive path without /d changes that drive's own directory, not the current one: where the run is, is not known.
        assertUnreadIn(of("cd D:\\ws && gradlew.bat build", cmd, false), true);
        // The same location change reads the same in a shell the event does not name.
        assertUnreadIn(of("cd D:/ws && ./gradlew build", ShellEditTargets.Dialect.EITHER, false), false, "D:/ws");
    }
}
