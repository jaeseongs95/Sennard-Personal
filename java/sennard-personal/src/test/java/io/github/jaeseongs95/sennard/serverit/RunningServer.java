// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.jaeseongs95.sennard.personal.SennardPersonalApplication;
import io.github.jaeseongs95.sennard.server.web.AdminPageAddress;

/** The assembled application on a real Tomcat with a free port and its own state directory. Extra configuration classes are the test's. */
final class RunningServer implements AutoCloseable {
    ConfigurableApplicationContext context; // null once closed
    final int port;
    private final Path ownedState; // the state directory this server made for itself and deletes when it is closed; null for one the caller gave

    private RunningServer(ConfigurableApplicationContext context, Path ownedState) {
        this.context = context;
        this.port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
        this.ownedState = ownedState;
    }

    static RunningServer start(List<Class<?>> extraSources, String... extraArguments) throws IOException {
        return startOwned(Files.createTempDirectory("sennard-server-state-"), extraSources, extraArguments);
    }

    /**
     * On a state directory the server owns: it is deleted when the server is closed, and at once when the server does not start (D-0194: a test run leaves nothing in the
     * temporary folder). The directory is the caller's to make so that its name tells the test it belongs to.
     */
    static RunningServer startOwned(Path stateDirectory, List<Class<?>> extraSources, String... extraArguments) throws IOException {
        try {
            return start(stateDirectory, stateDirectory, extraSources, extraArguments);
        } catch (IOException | RuntimeException failure) {
            try {
                deleteTree(stateDirectory);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** On a given state directory, for a test that restarts the server over the same state; the caller deletes the directory. */
    static RunningServer startAt(Path stateDirectory, List<Class<?>> extraSources, String... extraArguments) throws IOException {
        return start(stateDirectory, null, extraSources, extraArguments);
    }

    /**
     * Deletes a folder and what is in it, for a test that made a folder of its own. The attachment files of the state folder are closed read-only by the product (the DOS
     * attribute where the file system has one), and Windows refuses to delete such a file; so the attribute is cleared first wherever the file system has the DOS view. Where it has
     * none (POSIX), the write permission of the folder is what deletes. A folder that is not there is already deleted.
     */
    static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (dos != null) {
                    dos.setReadOnly(false);
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static RunningServer start(Path stateDirectory, Path ownedState, List<Class<?>> extraSources, String... extraArguments) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--sennard.state-dir=" + stateDirectory));
        if (Stream.of(extraArguments).noneMatch(argument -> argument.startsWith("--sennard.store.prepare="))) {
            arguments.add("--sennard.store.prepare=false"); // a web-layer test has no database to prepare
        }
        arguments.add("--sennard.scheduled-probes=false"); // the host CLIs and the tag API; a test that needs them asks directly
        arguments.addAll(List.of(extraArguments));
        List<Class<?>> sources = new ArrayList<>(List.of(SennardPersonalApplication.class));
        sources.addAll(extraSources);
        return new RunningServer(new SpringApplicationBuilder(sources.toArray(Class<?>[]::new)).run(arguments.toArray(String[]::new)), ownedState);
    }

    /** The port the admin page listens on: the operating system gave it when this server started, so it is asked of the server and never chosen by a test. */
    int adminPort() {
        return bean(AdminPageAddress.class).port();
    }

    <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    Path stateDirectory() {
        return Path.of(context.getEnvironment().getRequiredProperty("sennard.state-dir"));
    }

    @Override
    public void close() {
        if (context == null) {
            return;
        }
        context.close();
        if (ownedState != null) {
            try {
                deleteTree(ownedState);
            } catch (IOException failure) {
                throw new UncheckedIOException("The state directory " + ownedState + " of the closed server could not be deleted", failure);
            }
        }
        // A test class's static field keeps this object after the class is done, and a closed context keeps its environment, its stopped Tomcat and its bean factory.
        // The ~30 servers of one test JVM would hold a large part of its 512 MB heap: let go of the closed context.
        context = null;
    }
}
