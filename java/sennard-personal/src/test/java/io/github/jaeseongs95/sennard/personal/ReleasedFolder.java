// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.personal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/**
 * Waits until the operating system has let go of a folder after the processes that used it were killed. Measured on Windows 11 with JDK 25.0.5: after {@code destroyForcibly}
 * and {@link ProcessHandle#onExit()} the process is gone, but a folder that was its working directory (or held a program it ran) can still not be deleted or renamed, for
 * tens of milliseconds up to about a second when the process was killed through a {@link ProcessHandle}, and until the parent's {@link Process} object is garbage collected
 * when it was killed through that object. A test that kills a process and ends would have JUnit's clean-up of its temporary folder fail in that time, so it waits here
 * first. The wait ends when the folder can be renamed (and is renamed back); a folder that stays held for the whole wait is a failure, not something to skip.
 */
public final class ReleasedFolder {
    private static final RetryTemplate WAIT = new RetryTemplate(RetryPolicy.builder().includes(IOException.class).delay(Duration.ofMillis(50)).maxRetries(120).build());

    private ReleasedFolder() {
    }

    /** Returns when {@code folder} can be renamed; throws {@link AssertionError} when it still cannot after about six seconds. */
    public static void await(Path folder) {
        Path aside = folder.resolveSibling(folder.getFileName() + ".released");
        try {
            WAIT.execute(() -> {
                Files.move(folder, aside);
                Files.move(aside, folder);
                return folder;
            });
        } catch (RetryException stillHeld) {
            throw new AssertionError("The folder is still held by a process that was killed: " + folder, stillHeld);
        }
    }
}
