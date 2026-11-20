// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * The product jar the build made (the one the packaged image carries) has no way to start a PostgreSQL of its own: no runtime installer or server starter, no
 * runtime manifest, and none of the libraries that only read the runtime archive. Read from the jar itself ({@code sennard.personal.jar}, which the test task builds first),
 * so a later change that brings one back fails here and not on a user's PC.
 */
class ProductJarContentTest {
    private static List<String> entries() throws IOException {
        String jar = System.getProperty("sennard.personal.jar");
        assertNotNull(jar, "the build passes the product jar (sennard-personal/build.gradle.kts)");
        try (ZipFile zip = new ZipFile(jar)) {
            return zip.stream().map(entry -> entry.getName()).toList();
        }
    }

    @Test
    void theProductJarHasNoClassOfTheEmbeddedDatabase() throws Exception {
        List<String> entries = entries();
        assertTrue(entries.stream().anyMatch(name -> name.startsWith("BOOT-INF/lib/sennard-store-pg")), "the store module is in the jar: " + entries.size() + " entries read");
        assertEquals(List.of(), entries.stream().filter(name -> name.contains("/service/store/embedded/")).toList());
        assertEquals(List.of(), entries.stream().filter(name -> name.endsWith("postgres-runtime.properties")).toList());
    }

    /** The business database's driver is the user's: no vendor JDBC driver is in the product jar (the H2 of the first-time settings is not a vendor's driver of a business database). */
    @Test
    void theProductJarCarriesNoVendorJdbcDriver() throws Exception {
        List<String> drivers = entries().stream().filter(name -> name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar"))
                .filter(name -> VENDOR_DRIVER_JARS.stream().anyMatch(name::contains)).toList();
        assertEquals(List.of(), drivers, "a database driver is the user's to give (the driver path of the link on the admin page)");
    }

    /** Parts of the jar names of the drivers of the business databases Sennard is used with or could be: PostgreSQL, MariaDB, MySQL, Oracle, SQL Server, Db2. */
    private static final List<String> VENDOR_DRIVER_JARS = List.of("postgresql", "mariadb-java-client", "mysql-connector", "ojdbc", "mssql-jdbc", "jcc-");

    @Test
    void theProductJarCarriesNoArchiveReadingLibrary() throws Exception {
        List<String> libraries = entries().stream().filter(name -> name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")).toList();
        assertTrue(libraries.size() > 10, "the libraries were read: " + libraries.size());
        assertEquals(List.of(), libraries.stream().filter(name -> name.contains("commons-compress") || name.contains("/xz-")).toList());
    }

    /** The store module's own jar inside the product jar carries no embedded-database class either (they live in a nested jar, not in BOOT-INF/classes). */
    @Test
    void theStoreModuleJarInsideTheProductJarHasNoClassOfTheEmbeddedDatabase() throws Exception {
        String jar = System.getProperty("sennard.personal.jar");
        try (ZipFile zip = new ZipFile(jar)) {
            var storeJar = zip.stream().filter(entry -> entry.getName().startsWith("BOOT-INF/lib/sennard-store-pg")).findFirst().orElseThrow();
            try (java.util.zip.ZipInputStream nested = new java.util.zip.ZipInputStream(zip.getInputStream(storeJar))) {
                List<String> names = new java.util.ArrayList<>();
                for (java.util.zip.ZipEntry entry = nested.getNextEntry(); entry != null; entry = nested.getNextEntry()) {
                    names.add(entry.getName());
                }
                assertTrue(names.stream().anyMatch(name -> name.endsWith("/store/pg/PreparedStore.class")), "the store jar was read: " + names.size() + " entries");
                assertEquals(List.of(), names.stream().filter(name -> name.contains("/service/store/embedded/") || name.endsWith("postgres-runtime.properties")).toList());
            }
        }
    }
}
