// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.execution;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Where the log files of executions are, as far as the retention of them needs: which folders there are, how much a location takes and how recently it was written, and deleting it.
 * Choosing what to delete is {@link LogRetentionPolicy}; this is the files of one implementation of {@link ProcessRunner}, which made them. A location is a folder of the log folder of
 * this server's own state: one that is not (a record can name any text) is never measured and never deleted.
 */
public interface ExecutionLogStore {
    /**
     * A folder of log files, found by listing the log folder.
     *
     * @param id the folder's name, which is the id of the execution that made it
     * @param location what the folder is called to the other methods
     */
    record Folder(String id, String location) {
    }

    /**
     * What a folder takes and when it was last written.
     *
     * @param bytes the bytes of its files
     * @param lastModified when a file in it, or it, was last modified, as the file system says; what the age of a folder that has no record is counted from
     */
    record Measure(long bytes, Instant lastModified) {
    }

    /**
     * The folders of this server in the log folder of its state, with nothing measured, in no order: a folder is listed only when it really is directly in the log folder and its name
     * is an execution id ({@link ExecutionIds}); a link of any kind in its place, a folder of another name and a file are not listed. Nothing when the log folder is not there.
     *
     * @throws IOException when the log folder cannot be listed
     */
    List<Folder> folders() throws IOException;

    /**
     * What is known of whether a location is a folder of this server, as a type of three answers: it is, it is not, and it cannot be told now. The third is not a kind of the second: a
     * folder whose real path could not be had this time may be this server's, and what is noted about a record (that its folder is out of reach, for ever) must not be noted from it.
     */
    sealed interface Ownership {
        /** The location is a folder of this server: directly in its log folder, and named as an execution id. */
        record Ours() implements Ownership {
        }

        /** The location is not: it is somewhere else, it is a link to somewhere else, it is not a folder, or it is no path at all. A later look gives the same answer. */
        record NotOurs() implements Ownership {
        }

        /**
         * The real path of the location (or of its parent) could not be had: the answer is not known now, and a later look may give either of the others.
         *
         * @param reason what went wrong, as the file system said it
         */
        record Undecided(IOException reason) implements Ownership {
            public Undecided {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /**
     * Whether {@code logLocation} is a folder of this server, by the same judgment as {@link #folders()}: where the folder really is, not how its path is spelled. A text that is no
     * path at all is {@link Ownership.NotOurs}, and so is a link, a file or a folder that is not named as an execution id. A location whose real path cannot be had, and that is not a
     * link that points nowhere, is {@link Ownership.Undecided}.
     */
    Ownership ownershipOf(String logLocation);

    /**
     * How many bytes the log files at {@code logLocation} take.
     *
     * @return the bytes; 0 when nothing is there (already deleted)
     * @throws IOException when the files cannot be listed, or the location is not one this store made
     */
    long sizeOf(String logLocation) throws IOException;

    /**
     * The bytes and the last write of the folder at {@code logLocation}.
     *
     * @throws IOException when the files cannot be listed or read, or the location is not one this store made
     */
    Measure measure(String logLocation) throws IOException;

    /**
     * Deletes the log files at {@code logLocation}, and the location itself. Deleting what is already gone is not a failure, so a delete that failed half way can be repeated.
     *
     * @throws IOException when something could not be deleted, or the location is not one this store made (nothing is deleted then)
     */
    void delete(String logLocation) throws IOException;
}
