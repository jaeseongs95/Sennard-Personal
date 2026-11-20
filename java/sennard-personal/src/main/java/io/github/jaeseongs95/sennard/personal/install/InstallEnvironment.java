// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal.install;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.core.env.StandardEnvironment;

import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;
import io.github.jaeseongs95.sennard.service.secret.PrivateStorage;

/**
 * Where this user's installation lives and what runs its programs. The home folder is the JVM's {@code user.home}, which is the same question on every OS; there is no
 * per-OS data folder to choose between, so the same input gives the same place everywhere. There is no registration with the OS: the server is started
 * by {@link ServerStarter}.
 *
 * <p>{@code SENNARD_INSTALL_ROOT} replaces the folder an installation claims on a PC; it exists so that a test can never touch the real one, and so that a user whose home folder cannot be
 * written in the encoding the OS passes file names in can name another ({@link #installRoot()}).
 *
 * @param environment the environment of the process the command runs in
 * @param home the user's home folder ({@code user.home})
 * @param java the {@code java} the installation runs its programs with
 * @param commands runs the installation's own {@code java} commands
 * @param starter starts the server
 * @param memory the machine's memory as the JVM reports it
 * @param fileNameEncoding the encoding the JVM passes a program's arguments to the OS in: a path it cannot encode cannot be given to a {@code java} command
 */
public record InstallEnvironment(Map<String, String> environment, Path home, JavaExecutable java, Commands commands, ServerStarter starter, HostMemory memory, Charset fileNameEncoding) {
    /** The folder under the home folder that holds everything of Sennard's own: the state folder, and the installation in its {@code install} folder. */
    public static final String HOME_FOLDER = ".sennard";

    /** An environment whose server start is a real process and whose memory is the JVM's reading. */
    public InstallEnvironment(Map<String, String> environment, Path home, JavaExecutable java, Commands commands, Charset fileNameEncoding) {
        this(environment, home, java, commands, ServerStarter.ofProcess(), HostMemory.ofJvm(), fileNameEncoding);
    }

    /** An environment whose server start is a real process. */
    public InstallEnvironment(Map<String, String> environment, Path home, JavaExecutable java, Commands commands, HostMemory memory, Charset fileNameEncoding) {
        this(environment, home, java, commands, ServerStarter.ofProcess(), memory, fileNameEncoding);
    }

    /**
     * The real environment of this process. The CLI is a process of its own with no application context, so this is the one place its environment is taken, and it is taken
     * through Spring's {@link StandardEnvironment} (the abstraction over the process environment and the JVM's properties) and not by asking the JVM directly. The map
     * is the JVM's own, not a copy: on Windows it matches a variable's name ignoring case ({@code Path}, {@code PATH}). {@code user.home} and {@code native.encoding} (the JDK's own name for the
     * encoding of the OS, JDK 17) are properties of the JVM.
     */
    public static InstallEnvironment current() {
        StandardEnvironment process = new StandardEnvironment();
        return new InstallEnvironment(variables(process), Path.of(process.getRequiredProperty("user.home")), JavaExecutable.ofCurrentProcess(), new JavaCommands(), ServerStarter.ofProcess(),
                HostMemory.ofJvm(), Charset.forName(process.getRequiredProperty("native.encoding")));
    }

    @SuppressWarnings("unchecked") // the system environment's names and values are all Strings; StandardEnvironment types it as Map<String, Object>
    private static Map<String, String> variables(StandardEnvironment process) {
        return (Map<String, String>) (Map<?, ?>) process.getSystemEnvironment();
    }

    /**
     * The folder the installation is made in: {@code SENNARD_INSTALL_ROOT} when it is set, otherwise {@code <home>/.sennard/install}.
     *
     * @throws InvalidInputException INSTALL_ROOT_NOT_REPRESENTABLE when the folder cannot be written in {@link #fileNameEncoding}. The Windows {@code java} launcher reads its command line in that
     *     encoding, so a program started with such a path in its arguments would get another folder; the folder is refused with the way out (the variable), not replaced by another one.
     */
    public Path installRoot() {
        String configured = environment.get("SENNARD_INSTALL_ROOT");
        return checked(configured != null && !configured.isBlank() ? Path.of(configured) : home.resolve(HOME_FOLDER).resolve("install"));
    }

    /** {@code root} itself when it can be written in the encoding of the OS. @throws InvalidInputException INSTALL_ROOT_NOT_REPRESENTABLE */
    public Path checked(Path root) {
        if (!representable(root)) {
            throw new InvalidInputException(InputCode.INSTALL_ROOT_NOT_REPRESENTABLE, SennardException.args("root", root));
        }
        return root;
    }

    /**
     * The storage of secrets of this edition: every folder and file that holds a secret, the state folder included, is made through it. It keeps no state, so the one made here and the
     * one the server's context makes are interchangeable.
     */
    public PrivateStorage privateStorage() {
        return new LocalPrivateStorage();
    }

    /** The folder for temporary files, which the JVM names ({@code java.io.tmpdir}, taken through Spring's {@link StandardEnvironment}); the AOT training's state folder is made in it. */
    public Path temporaryFolder() {
        return Path.of(new StandardEnvironment().getRequiredProperty("java.io.tmpdir"));
    }

    /**
     * Whether the path survives the encoding a program's arguments are passed to the OS in. The encoding is the JDK's standard {@code native.encoding}, and the answer is the encoder's
     * (not a guess from the OS's name). A text that is no path at all never gets here: {@code Path.of} refuses it (InvalidPathException) at its caller.
     */
    public boolean representable(Path path) {
        return fileNameEncoding.newEncoder().canEncode(path.toString());
    }
}
