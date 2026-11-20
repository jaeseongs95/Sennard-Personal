// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A test class that runs on the database (PostgreSQL) the person running it names ({@link BusinessDb}): tagged {@code business-db}, which the task {@code test} leaves out
 * (CI has no such database) and {@code businessDbTest} runs, and started from the business schemas dropped and migrated anew. Every test starts from empty business tables
 * ({@link BusinessDb#clearData()}), so the counts and capacity limits of the service as a whole are never another test's. The class does not skip when the
 * database is missing; it fails.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Tag("business-db")
@ExtendWith(BusinessDbTest.Fresh.class)
@interface BusinessDbTest {
    /** Resets the business schemas before the first test of the class and empties the business tables before each test. */
    final class Fresh implements BeforeAllCallback, BeforeEachCallback {
        @Override
        public void beforeAll(ExtensionContext context) throws Exception {
            BusinessDb.reset();
        }

        @Override
        public void beforeEach(ExtensionContext context) throws Exception {
            BusinessDb.clearData();
        }
    }
}
