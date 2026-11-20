// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The connection of the tests of the business schema has no default: a setting that is not given is an error that names it, so that a run without a database neither
 * guesses one nor skips a test. This class needs no database and runs in {@code test}.
 *
 * <pre>
 * Case                                   Input                                                   Expected
 * no setting                             none of the four properties, no environment variable    IllegalStateException naming sennard.test.businessDb.url and businessDbTest
 * url and user, no password file         url and user                                            IllegalStateException naming sennard.test.businessDb.passwordFile
 * url, user and password file, no driver url, user and password file                            IllegalStateException naming sennard.test.businessDb.driverPath
 * </pre>
 */
class BusinessDbSettingsTest {
    private static final List<String> PROPERTIES = List.of(BusinessDb.URL_PROPERTY, BusinessDb.USER_PROPERTY, BusinessDb.PASSWORD_FILE_PROPERTY, BusinessDb.DRIVER_PATH_PROPERTY);

    /** Runs {@code body} with the four properties as given ({@code null} removes one) and restores them. */
    private static void withSettings(String url, String user, String passwordFile, String driverPath, Runnable body) {
        List<String> before = PROPERTIES.stream().map(System::getProperty).toList();
        try {
            set(BusinessDb.URL_PROPERTY, url);
            set(BusinessDb.USER_PROPERTY, user);
            set(BusinessDb.PASSWORD_FILE_PROPERTY, passwordFile);
            set(BusinessDb.DRIVER_PATH_PROPERTY, driverPath);
            body.run();
        } finally {
            for (int index = 0; index < PROPERTIES.size(); index++) {
                set(PROPERTIES.get(index), before.get(index));
            }
        }
    }

    private static void set(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    @Test
    void aRunWithoutTheConnectionIsAnErrorThatNamesTheSettingAndTheTask() {
        withSettings(null, null, null, null, () -> {
            IllegalStateException failure = assertThrows(IllegalStateException.class, BusinessDb::config);
            assertTrue(failure.getMessage().contains(BusinessDb.URL_PROPERTY), failure.getMessage());
            assertTrue(failure.getMessage().contains("businessDbTest"), failure.getMessage());
        });
    }

    @Test
    void aMissingPasswordFileIsAnErrorEvenWhenTheUrlAndTheUserAreGiven() {
        withSettings("jdbc:postgresql://localhost/example", "example", null, null, () -> {
            IllegalStateException failure = assertThrows(IllegalStateException.class, BusinessDb::config);
            assertTrue(failure.getMessage().contains(BusinessDb.PASSWORD_FILE_PROPERTY), failure.getMessage());
        });
    }

    @Test
    void aMissingDriverPathIsAnErrorEvenWhenTheUrlTheUserAndThePasswordFileAreGiven() {
        withSettings("jdbc:mariadb://localhost/example", "example", "example.pw", null, () -> {
            IllegalStateException failure = assertThrows(IllegalStateException.class, BusinessDb::config);
            assertTrue(failure.getMessage().contains(BusinessDb.DRIVER_PATH_PROPERTY), failure.getMessage());
        });
    }
}
