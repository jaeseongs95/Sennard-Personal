// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.settings;

/**
 * Reads the text of a column that holds an enum constant's name. The settings schema lists no allowed values (an {@code IN} list in a {@code CHECK} refused correct rows
 * of an open H2 database), so the closed set is the Java enum, and a text outside it is refused here, before a value is written and when one is read.
 */
public final class StoredEnum {
    private StoredEnum() {
    }

    /** The constant named by {@code text}; {@link SettingsCode#VALUE_OUTSIDE_SET} when the enum has none (no value in the message). */
    public static <E extends Enum<E>> E of(Class<E> type, String field, String text) {
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException | NullPointerException outside) {
            throw new SettingsException(SettingsCode.VALUE_OUTSIDE_SET, "The value of " + field + " is not one of the " + type.getSimpleName() + " constants.", outside);
        }
    }

    /** The same for a text that comes out of a stored row. */
    public static <E extends Enum<E>> E ofStored(Class<E> type, String field, String text) {
        try {
            return of(type, field, text);
        } catch (SettingsException outside) {
            throw new SettingsException(SettingsCode.STORED_VALUE_UNKNOWN, "The stored value of " + field + " is not one of the " + type.getSimpleName() + " constants.", outside);
        }
    }
}
