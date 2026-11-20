// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.domain.error;

/**
 * A reason of one {@link Category}. Every constant names the catalog message that tells it ({@link #messageKey()}, a key of {@code locales/<lang>/*.json});
 * a test fails when a constant's key is missing from the English or the Korean catalog. The set of implementations is closed: the seven code enums.
 *
 * <p>The public form of a reason on the wire is the constant's own to state, because the published contract ({@code contracts/api-result.v1.schema.json}) names codes that
 * are finer than a category ({@code STALE_REVISION}, {@code REQUEST_CONFLICT}, {@code SNAPSHOT_NOT_FOUND}, {@code BINDING_REQUIRED}, the admin page's {@code ADMIN_*}) and shows
 * a {@code details.reasonCode} for some reasons only. A constant that says nothing about it is published under its category's code and with its name as the reason.
 */
public sealed interface ErrorCode extends java.io.Serializable permits InputCode, NotFoundCode, ConflictCode, GateCode, BindingCode, IntegrityCode, UnavailableCode {
    /** The constant's name: what the wire carries as {@code details.reasonCode} (when {@link #wireReason()}). */
    String name();

    /** The message catalog key. */
    String messageKey();

    Category category();

    /** The public {@code error.code} of the result envelope for this reason: the category's unless the reason is published under a code of its own. */
    String wireCode();

    /** Whether the envelope's {@code details.reasonCode} names this constant; false for a reason that the contract has always shown without one. */
    boolean wireReason();
}
