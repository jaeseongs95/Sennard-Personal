// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.operation;

/**
 * The result of {@link OperationReceipts#once}, with whether this call did the work (AGS3-D-0267). What a use case does besides its own writes, after it committed (an event that
 * rings a waiting hook, the note of a saved record for the turn check), is the effect of the work and not of the answer: a request that comes again with its key is answered with the
 * stored result and must do none of it again. A caller that has such an effect does it only for {@link Fresh}.
 *
 * @param <R> the result of the use case
 */
public sealed interface Receipted<R> {
    /** The result: the one the work made, or the one the first request stored. */
    R value();

    /** This call did the work, and its receipt was written in the same transaction. */
    record Fresh<R>(R value) implements Receipted<R> {
    }

    /** The key had succeeded before for this content: the stored result of the first request, with no work done. */
    record Replayed<R>(R value) implements Receipted<R> {
    }
}
