// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.sennard.service.secret.TestPrivateStorage;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * How the product finds the driver of the user's database: the cases that need no database. The driver is a real {@link java.sql.Driver} ({@link FakeJdbcDriver}) packed into real
 * jars made here, and the product's own {@link JdbcDrivers} reads them. Cases:
 *
 * <pre>
 * d1  a jar with the java.sql.Driver service        the driver is found by ServiceLoader in a class loader of its own (not the application's), and takes its URL
 * d2  a folder of jars                              the same, with the jar among files that are no jar
 * d3  a URL no driver there takes                   DB_DRIVER_REJECTS_URL, which names the drivers that were found
 * d4  no driver at the path                         DB_DRIVER_NOT_FOUND for a path that is not there, a file that is no jar, a folder with no jar and a jar with no driver service
 * d5  the same path asked twice                     one class loader, the same driver instance
 * d6  the connection of the product                 DataSourceFactory.direct names the driver by the path and refuses a URL the driver does not take, before any connection
 * </pre>
 */
class JdbcDriversTest {
    private static final String CLASS_ENTRY = "io/github/jaeseongs95/sennard/service/store/pg/FakeJdbcDriver.class";
    private static final String FAKE_URL = FakeJdbcDriver.URL_PREFIX + "//127.0.0.1/sennard";

    /** A jar that holds the fake driver and announces it as a {@code java.sql.Driver} service. */
    private static Path driverJar(Path folder, String name, boolean withService) throws IOException {
        Path jar = folder.resolve(name);
        try (InputStream compiled = FakeJdbcDriver.class.getResourceAsStream("FakeJdbcDriver.class"); OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry(CLASS_ENTRY));
            out.write(compiled.readAllBytes());
            out.closeEntry();
            if (withService) {
                out.putNextEntry(new JarEntry("META-INF/services/java.sql.Driver"));
                out.write(FakeJdbcDriver.class.getName().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void d1ADriverJarIsReadByAClassLoaderOfItsOwnAndTheDriverTakesItsUrl(@TempDir Path folder) throws Exception {
        Path jar = driverJar(folder, "fake-driver.jar", true);
        try (JdbcDrivers drivers = new JdbcDrivers()) {
            Driver driver = drivers.find(jar, FAKE_URL);
            assertEquals(FakeJdbcDriver.class.getName(), driver.getClass().getName());
            assertNotSame(FakeJdbcDriver.class, driver.getClass(), "the driver is the jar's class, not the application's");
            URLClassLoader loader = assertInstanceOf(URLClassLoader.class, driver.getClass().getClassLoader());
            assertEquals("sennard-jdbc-driver", loader.getName());
            assertTrue(driver.acceptsURL(FAKE_URL));
        }
    }

    @Test
    void d2AFolderOfJarsIsReadAndFilesThatAreNoJarAreLeftAlone(@TempDir Path folder) throws IOException {
        driverJar(folder, "fake-driver.jar", true);
        Files.writeString(folder.resolve("README.txt"), "not a jar");
        try (JdbcDrivers drivers = new JdbcDrivers()) {
            assertEquals(FakeJdbcDriver.class.getName(), drivers.find(folder, FAKE_URL).getClass().getName());
        }
    }

    @Test
    void d3AUrlNoDriverThereTakesIsRefusedAndTheDriversThatWereFoundAreNamed(@TempDir Path folder) throws IOException {
        Path jar = driverJar(folder, "fake-driver.jar", true);
        try (JdbcDrivers drivers = new JdbcDrivers()) {
            StoreException refused = assertThrows(StoreException.class, () -> drivers.find(jar, "jdbc:postgresql://127.0.0.1/sennard"));
            assertEquals(StoreCode.DB_DRIVER_REJECTS_URL, refused.code());
            assertTrue(refused.getMessage().contains(FakeJdbcDriver.class.getName()), refused.getMessage());
        }
    }

    @Test
    void d4NoDriverAtThePathIsRefusedWithTheReason(@TempDir Path folder) throws IOException {
        Path plainFile = folder.resolve("notes.txt");
        Files.writeString(plainFile, "not a jar");
        Path emptyFolder = Files.createDirectory(folder.resolve("empty"));
        Path noService = driverJar(folder, "no-service.jar", false);
        try (JdbcDrivers drivers = new JdbcDrivers()) {
            for (Path path : new Path[] {folder.resolve("missing.jar"), emptyFolder}) {
                assertEquals(StoreCode.DB_DRIVER_NOT_FOUND, assertThrows(StoreException.class, () -> drivers.find(path, FAKE_URL), path.toString()).code(), path.toString());
            }
            // A file is a jar whatever its name: the class loader reads what is in it, and a file that holds no driver service is a path with no driver.
            assertEquals(StoreCode.DB_DRIVER_NOT_FOUND, assertThrows(StoreException.class, () -> drivers.find(plainFile, FAKE_URL)).code());
            assertEquals(StoreCode.DB_DRIVER_NOT_FOUND, assertThrows(StoreException.class, () -> drivers.find(noService, FAKE_URL)).code());
        }
    }

    @Test
    void d5TheSamePathAskedTwiceIsReadOnce(@TempDir Path folder) throws IOException {
        Path jar = driverJar(folder, "fake-driver.jar", true);
        try (JdbcDrivers drivers = new JdbcDrivers()) {
            Driver first = drivers.find(jar, FAKE_URL);
            assertSame(first.getClass(), drivers.find(jar, FAKE_URL).getClass(), "the jar's driver class is loaded once");
            assertSame(first.getClass().getClassLoader(), drivers.find(jar.toAbsolutePath().normalize(), FAKE_URL).getClass().getClassLoader(), "the same location, spelled otherwise");
        }
    }

    @Test
    void d6TheConnectionOfTheProductRefusesAUrlTheDriverAtThePathDoesNotTake(@TempDir Path folder) throws IOException {
        Path jar = driverJar(folder, "fake-driver.jar", true);
        Path password = Files.writeString(folder.resolve("account.pw"), "secret\n");
        try (JdbcDrivers drivers = new JdbcDrivers()) {
            DataSourceFactory factory = new DataSourceFactory(drivers, new TestPrivateStorage());
            SennardDatabaseConfig config = new SennardDatabaseConfig("jdbc:postgresql://127.0.0.1:5432/sennard", "sennard_owner", password, jar, SennardDatabaseConfig.TlsPolicy.LOOPBACK_PLAINTEXT, 2);
            StoreException refused = assertThrows(StoreException.class, () -> factory.direct(config));
            assertEquals(StoreCode.DB_DRIVER_REJECTS_URL, refused.code());
            assertThrows(StoreException.class, () -> factory.create(config), "the pool is not made when the driver does not take the URL");
        }
    }
}
