// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.transcript;

/** Why a transcript cannot be read. The domain names the reason; the words for it are the server's catalog, so the domain carries no sentence. */
public enum UnreadableReason {
    /** The file is there but is not in the format of the host. */
    NOT_A_TRANSCRIPT,
    /** The entry that was located earlier is no longer in the file. */
    ENTRY_GONE,
    /** The file does not exist. */
    FILE_MISSING,
    /** The file may not be read. */
    FILE_DENIED,
    /** Any other failure of reading the file. */
    FILE_IO
}
