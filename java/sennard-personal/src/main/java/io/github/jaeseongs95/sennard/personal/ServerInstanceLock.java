// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import io.github.jaeseongs95.sennard.domain.error.ConflictCode;
import io.github.jaeseongs95.sennard.domain.error.ConflictException;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.service.personal.LocalPrivateStorage;

/**
 * One server for one state folder. The server has no fixed port that a second one would fail to bind, so what keeps two servers from running on the same state folder (the same
 * settings database, attachments, store and address file) is this lock: the file {@value #FILE_NAME} of the state folder, locked through the JDK's {@link FileChannel#tryLock()} when
 * the server starts and held until the process ends (or the context is closed, which a test does). The operating system releases a lock when its process ends however it ended, so a
 * killed server leaves no lock behind, which a file that records "a server is running" could not promise.
 *
 * <p>A second server finds the lock held and does not start: its start fails with a {@link ConflictException} of {@link ConflictCode#SERVER_ALREADY_RUNNING} (the folder is its {@code folder} argument) and the process ends. The lock is also what lets
 * {@link ServerAddressPublisher} write the address file without asking who else writes it: only the holder does. The JDK keeps a lock of the JVM's own as well
 * ({@link OverlappingFileLockException} is how a second channel of the same JVM learns that the first holds it), which is the case of a test that starts two servers in one process and
 * is refused the same way.
 *
 * <p>Why it is taken here, and why this is a {@link BeanFactoryPostProcessor}: the container makes these before every other bean, so the lock is taken before anything opens the settings
 * database or a port, and a second server is refused with its own reason, not with the one of the first resource of the first server that it would have met (the settings database's file
 * lock). The state folder is read from the environment, which is complete by then ({@code sennard.state-dir}, from {@code application.yaml}, {@code SENNARD_STATE_DIR} or
 * {@code sennard.yaml}), and made through the port of the storage of secrets if it is not there.
 *
 * <p>How it becomes a bean: by {@link ServerInstanceLockConfiguration}'s {@code static @Bean} method, which is the container's way to make a {@link BeanFactoryPostProcessor} early. It is not a
 * scanned {@code @Component}: the container makes the post-processors before it has the bean post-processor that picks a constructor with arguments, so a scanned one could only be made by a
 * default constructor, which this class does not have. A factory method takes the {@link Environment} as its argument at any time.
 *
 * <p>ceiling: a server process makes one lock. A second one in the same JVM (a test) opens and closes a second channel on the file, and the {@link FileChannel} documentation says that on
 * some systems closing a channel releases every lock the JVM holds on the file, the first's too; the refusal is right, and what a test must not rely on after it is that the first lock is still held.
 */
final class ServerInstanceLock implements BeanFactoryPostProcessor, AutoCloseable {
    /** The lock file's name in the state folder. */
    static final String FILE_NAME = "server.lock";
    /** The environment's key of the state folder (see {@link PersonalProperties}). */
    private static final String STATE_DIRECTORY = "sennard.state-dir";

    private final Path folder;
    private final FileChannel channel;

    /**
     * @param environment where {@code sennard.state-dir} is read from
     * @throws ConflictException {@link ConflictCode#SERVER_ALREADY_RUNNING} (argument {@code folder}) when another server holds the folder's lock
     * @throws IllegalStateException when the environment names no state folder
     */
    ServerInstanceLock(Environment environment) throws IOException {
        this.folder = Binder.get(environment).bind(STATE_DIRECTORY, Path.class).orElseThrow(() -> new IllegalStateException(STATE_DIRECTORY + " is not set")).toAbsolutePath().normalize();
        new LocalPrivateStorage().createFolder(folder);
        this.channel = FileChannel.open(folder.resolve(FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldByThisJvm) {
            channel.close();
            throw new ConflictException(ConflictCode.SERVER_ALREADY_RUNNING, SennardException.args("folder", folder.toString()), heldByThisJvm);
        } catch (IOException cannotLock) {
            channel.close();
            throw cannotLock;
        }
        if (lock == null) { // another process holds it
            channel.close();
            throw new ConflictException(ConflictCode.SERVER_ALREADY_RUNNING, SennardException.args("folder", folder.toString()));
        }
    }

    /** The lock is taken when this is made, which the container does before any other bean: there is nothing to do to the bean factory. */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Nothing: being a BeanFactoryPostProcessor is what makes the container build this first (see the class comment).
    }

    /** The state folder this server holds: where its address file is. */
    Path folder() {
        return folder;
    }

    /** Releases the lock (closing the channel does): at the end of the context, which is the end of the process for a server. */
    @Override
    public void close() throws IOException {
        channel.close();
    }
}
