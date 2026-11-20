// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts the installed server by running the installed jar ({@code java -jar <jar> start --within=<seconds>}, {@link HookClientCall#START_COMMAND}), which waits for the server to
 * be ready as a host's session start does (one start at a time, nothing printed). It is the one program this client starts, and a process because the start is the installer's, which
 * is Spring and the settings database: the client, which the host starts for every hook, may not load them, and only the installed jar knows where the installation is. A session start
 * finds no server once per session at most, and the start itself takes seconds (the JVM's start is a fraction of it), so the extra process is no cost on the hook path.
 *
 * <p>The Java that runs it is the Java that runs this client ({@link ProcessHandle#info()}): the release is the one jar and the user's own JDK, so there is no launcher program
 * to start instead. The JVM options are the ones that suit a short-lived command that only has to start the server and wait: one collector thread, a small heap and the
 * first compiler tier; the server itself is started by the command with the options of the installation's JVM preset, not these.
 *
 * <p>The process has a deadline ({@link #GRACE} above what the start may take) and is killed with its children when it is over; its output is discarded (a hook's output is
 * its answer to the host, and this process's is not).
 */
final class JarServerStart implements HookClient.ServerStart {
    /** What the command gets above the wait it was asked for, for its own JVM to start and end. */
    static final Duration GRACE = Duration.ofSeconds(15);
    /** The JVM options of the command (see above). */
    static final List<String> JVM_OPTIONS = List.of("-XX:+UseSerialGC", "-Xmx256m", "-XX:TieredStopAtLevel=1");

    private final Path jar;

    /** @param jar the installed Sennard jar ({@link HookClientCall#JAR}) */
    JarServerStart(Path jar) {
        this.jar = jar;
    }

    /** The command's words: this client's own Java, {@link #JVM_OPTIONS}, the jar, {@code start --within=<seconds>}. */
    static List<String> command(String java, Path jar, Duration within) {
        List<String> words = new ArrayList<>(List.of(java));
        words.addAll(JVM_OPTIONS);
        words.addAll(List.of("-jar", jar.toString(), HookClientCall.START_COMMAND, HookClientCall.START_WITHIN + "=" + within.toSeconds()));
        return words;
    }

    @Override
    public void startWithin(Duration within) {
        String java = ProcessHandle.current().info().command().orElseThrow(() -> new IllegalStateException("The Java that runs this client is not known: JAVA_EXECUTABLE_UNKNOWN"));
        Process process;
        try {
            process = new ProcessBuilder(command(java, jar, within)).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException cannotStart) {
            throw new IllegalStateException("the Sennard jar cannot be run: " + cannotStart.getClass().getSimpleName(), cannotStart);
        }
        try {
            process.getOutputStream().close(); // the command reads no input
            if (!process.waitFor(within.plus(GRACE).toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new IllegalStateException("the start did not end in time");
            }
        } catch (IOException cannotClose) {
            kill(process);
            throw new IllegalStateException("the start's input cannot be closed: " + cannotClose.getClass().getSimpleName(), cannotClose);
        } catch (InterruptedException interrupted) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the wait for the start was interrupted", interrupted);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("the start ended with exit code " + process.exitValue());
        }
    }

    /** The command's process and every process it started, so that a start that never ends leaves nothing behind. */
    private static void kill(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
