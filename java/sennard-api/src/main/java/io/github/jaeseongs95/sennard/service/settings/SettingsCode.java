// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

/**
 * The reason of a {@link SettingsException}: a constant of this enum, not a text, so a caller's {@code switch} names a constant the compiler checks.
 */
public enum SettingsCode {
    /** A value of a request is missing, too long or not of the form the settings store keeps. */
    INVALID_SETTING,
    /** A closed set of values (a constant of a Java enum) was given a value outside it; the schema has no list of its own. */
    VALUE_OUTSIDE_SET,
    /** A stored row holds a value that is not a constant of its enum (the file was changed from outside). */
    STORED_VALUE_UNKNOWN,
    PASSKEY_EXISTS,
    PASSKEY_NOT_FOUND,
    REQUEST_ALREADY_RECORDED,
    /** The admin records of the time before the link take no more records: the business database was ready once, and the signed log is there. */
    PRE_LINK_CLOSED,
    /** The settings store could not be made ready or answered with a failure of the database. */
    SETTINGS_UNAVAILABLE,
    MIGRATION_FAILED,
    MIGRATION_CHANGED,
    MIGRATION_MISSING,
    MIGRATION_OUT_OF_ORDER,
    /** The numbers of the scripts are not 1, 2, 3 ... one after the other. */
    MIGRATION_GAP
}
