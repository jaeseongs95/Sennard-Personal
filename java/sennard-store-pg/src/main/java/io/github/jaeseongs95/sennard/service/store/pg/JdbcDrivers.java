// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The JDBC driver of the business database, which the user provides: Sennard ships no vendor driver. The user names a jar, or a folder of jars; the jars are read by a class
 * loader of their own (its parent is the platform class loader, so no driver of the application's own class path can stand in for them), and the driver is found the way JDBC says
 * a driver announces itself: the {@code java.sql.Driver} service of the jar ({@link ServiceLoader}, {@code META-INF/services/java.sql.Driver}). Of the drivers found, the one
 * whose {@link Driver#acceptsURL} takes the URL is the driver of the connection. {@link java.sql.DriverManager}, the JVM's global list of drivers, is not asked.
 *
 * <p>This is a bean: the class loader of a location lives as long as the application and is closed with it, so a driver is read from its jar once however many connections and pools use it.
 */
@Component
public class JdbcDrivers implements AutoCloseable {
    private final ConcurrentMap<Path, URLClassLoader> loaders = new ConcurrentHashMap<>();

    /**
     * The driver at {@code location} that accepts {@code url}.
     *
     * @throws StoreException DB_DRIVER_NOT_FOUND when the location holds no driver, DB_DRIVER_REJECTS_URL when the drivers it holds do not take this URL
     */
    public Driver find(Path location, String url) {
        List<Driver> drivers = drivers(location);
        List<String> names = new ArrayList<>();
        for (Driver driver : drivers) {
            names.add(driver.getClass().getName());
            try {
                if (driver.acceptsURL(url)) {
                    return driver;
                }
            } catch (SQLException failure) {
                throw new StoreException(StoreCode.DB_DRIVER_REJECTS_URL, "The driver " + driver.getClass().getName() + " could not tell whether it accepts the JDBC URL.", failure);
            }
        }
        throw new StoreException(StoreCode.DB_DRIVER_REJECTS_URL,
                "None of the JDBC drivers at " + location + " accepts the JDBC URL (drivers found: " + String.join(", ", names) + "). The URL's form is that of another database or driver.");
    }

    private List<Driver> drivers(Path location) {
        URLClassLoader loader = loader(location);
        List<Driver> found = new ArrayList<>();
        try {
            for (Driver driver : ServiceLoader.load(Driver.class, loader)) {
                found.add(driver);
            }
        } catch (ServiceConfigurationError broken) {
            throw new StoreException(StoreCode.DB_DRIVER_NOT_FOUND, "A JDBC driver at " + location + " could not be loaded.", broken);
        }
        if (found.isEmpty()) {
            throw new StoreException(StoreCode.DB_DRIVER_NOT_FOUND,
                    "No JDBC driver is at " + location + ": a jar or a folder of jars that holds a java.sql.Driver service (META-INF/services/java.sql.Driver) is needed.");
        }
        return found;
    }

    private URLClassLoader loader(Path location) {
        Path key = location.toAbsolutePath().normalize();
        URLClassLoader existing = loaders.get(key);
        if (existing != null) {
            return existing;
        }
        URLClassLoader made = open(key);
        URLClassLoader raced = loaders.putIfAbsent(key, made);
        if (raced == null) {
            return made;
        }
        close(made, key);
        return raced;
    }

    private static URLClassLoader open(Path location) {
        List<URL> urls = new ArrayList<>();
        try {
            if (Files.isDirectory(location)) {
                try (Stream<Path> files = Files.list(location)) {
                    for (Path file : files.filter(Files::isRegularFile).filter(JdbcDrivers::isJar).sorted().toList()) {
                        urls.add(file.toUri().toURL());
                    }
                }
            } else if (Files.isRegularFile(location)) {
                urls.add(location.toUri().toURL());
            }
        } catch (IOException unreadable) {
            throw new StoreException(StoreCode.DB_DRIVER_NOT_FOUND, "The JDBC driver location " + location + " cannot be read.", unreadable);
        }
        if (urls.isEmpty()) {
            throw new StoreException(StoreCode.DB_DRIVER_NOT_FOUND, "The JDBC driver location " + location + " is not a jar file or a folder that holds jar files.");
        }
        return new URLClassLoader("sennard-jdbc-driver", urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static boolean isJar(Path file) {
        return file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }

    private static void close(URLClassLoader loader, Path location) {
        try {
            loader.close();
        } catch (IOException failure) {
            throw new StoreException(StoreCode.DB_DRIVER_NOT_FOUND, "The JDBC driver location " + location + " could not be closed.", failure);
        }
    }

    /** Closes the class loaders: the application's context does it (Spring ends an {@code AutoCloseable} bean by {@code close()}), a command that made its own does it by try-with-resources. */
    @Override
    public void close() {
        StoreException failed = null;
        for (var entry : loaders.entrySet()) {
            try {
                close(entry.getValue(), entry.getKey());
            } catch (StoreException failure) {
                if (failed == null) {
                    failed = failure;
                } else {
                    failed.addSuppressed(failure);
                }
            }
        }
        loaders.clear();
        if (failed != null) {
            throw failed;
        }
    }
}
