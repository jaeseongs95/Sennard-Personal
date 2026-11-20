// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.jaeseongs95.sennard.service.database.JdbcUrlPassword;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder;
import io.github.jaeseongs95.sennard.service.database.SqlBuilder.Database;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * Everything the service needs to reach the database, whoever prepared it. The service connects as one account, the one that owns the schemas ({@code ownerUser}); it runs the
 * migrations and the business statements alike. The URL is the user's and so is the driver ({@code driverPath}, a jar or a folder of jars, see {@link JdbcDrivers}): Sennard names
 * no DBMS and no driver class. The URL never carries a password: the account has a user and a file that holds its password.
 *
 * <p>H2 is refused as the business database ({@code BUSINESS_DATABASE_NOT_H2}): it holds Sennard's own first-time settings only. Any other database the URL names must be one Sennard
 * has SQL for ({@link SqlBuilder.Database}); a URL of another form is refused here, at the settings, and not by a failing statement later.
 *
 * <p>The TLS rule is checked here so no provider can skip it: only an IP literal in 127.0.0.0/8 or ::1 may be plain text; a name (localhost included) or any other address needs
 * the server verified, by the option the database's own driver has for it ({@link ServerVerification}: PostgreSQL, MariaDB, MySQL). For a database whose option Sennard does not know,
 * a remote address cannot be shown to be verified and is refused.
 *
 * @param jdbcUrl the user's JDBC URL, {@code jdbc:<subprotocol>://host[:port]/database[?options]}
 * @param ownerUser the one account of the service
 * @param ownerPasswordRef the file whose first line is that account's password
 * @param driverPath the user's JDBC driver: a jar, or a folder of jars
 */
public record SennardDatabaseConfig(String jdbcUrl, String ownerUser, Path ownerPasswordRef, Path driverPath, TlsPolicy tlsPolicy, int poolSize) {

    public enum TlsPolicy { LOOPBACK_PLAINTEXT, VERIFY_FULL }

    /**
     * The connection option with which a driver verifies the server's certificate and its name, per database product: its name, the value that means "verify the certificate and the
     * host name", and the option that names the certificate authority when the driver needs one (PostgreSQL: pgjdbc "Connection Parameters"; MariaDB Connector/J {@code sslMode};
     * MySQL Connector/J {@code sslMode}). A product that is not here has none that Sennard knows.
     */
    private enum ServerVerification {
        POSTGRESQL(Database.POSTGRESQL, "sslmode", "verify-full", "sslrootcert"),
        MARIADB(Database.MARIADB, "sslMode", "verify-full", null),
        MYSQL(Database.MYSQL, "sslMode", "VERIFY_IDENTITY", null);

        private final Database product;
        private final String option;
        private final String verifyingValue;
        private final String certificateOption;

        ServerVerification(Database product, String option, String verifyingValue, String certificateOption) {
            this.product = product;
            this.option = option;
            this.verifyingValue = verifyingValue;
            this.certificateOption = certificateOption;
        }

        static Optional<ServerVerification> of(Database product) {
            for (ServerVerification verification : values()) {
                if (verification.product == product) {
                    return Optional.of(verification);
                }
            }
            return Optional.empty();
        }

        /** The options (names compared without regard to case, as the drivers read them) ask for verification, and name the authority where the driver needs it. */
        boolean holdsIn(Map<String, String> options) {
            String value = options.get(option);
            if (value == null || !value.equals(verifyingValue)) {
                return false;
            }
            if (certificateOption == null) {
                return true;
            }
            String certificate = options.get(certificateOption);
            return certificate != null && !certificate.isBlank();
        }
    }

    /** The option every driver of the verified products reads the TLS request from: its presence says the user asked for TLS. */
    private static final String TLS_OPTION = "sslmode";

    private static final Logger LOG = LoggerFactory.getLogger(SennardDatabaseConfig.class);
    private static final String JDBC_PREFIX = "jdbc:";

    public SennardDatabaseConfig {
        if (ownerUser == null || ownerPasswordRef == null || tlsPolicy == null) {
            throw new StoreException(StoreCode.INVALID_DATABASE_CONFIG, "The account, its password file and the TLS policy are required.");
        }
        if (driverPath == null) {
            throw new StoreException(StoreCode.INVALID_DATABASE_CONFIG, "The JDBC driver (a jar or a folder of jars) is required: Sennard ships no database driver.");
        }
        if (poolSize < 1) {
            throw new StoreException(StoreCode.INVALID_DATABASE_CONFIG, "The pool size is at least 1.");
        }
        checkUrl(jdbcUrl, tlsPolicy);
    }

    /** True when the URL asks for TLS ({@code sslmode} among its options): the settings then are held to the full verification rule, on a loopback address too. */
    static boolean asksForTls(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.toLowerCase(java.util.Locale.ROOT).contains(TLS_OPTION + "=");
    }

    private static void checkUrl(String url, TlsPolicy policy) {
        if (url == null) {
            throw new StoreException(StoreCode.INVALID_DATABASE_CONFIG, "The JDBC URL is required.");
        }
        if (!url.startsWith(JDBC_PREFIX)) {
            throw new StoreException(StoreCode.INVALID_DATABASE_CONFIG, "The JDBC URL does not start with jdbc:.");
        }
        // The product the URL names, as Spring Boot reads it: H2 is the settings store's database and is no business database; another product Sennard has no SQL for is refused.
        Database product = SqlBuilder.ofJdbcUrl(url);
        if (product == Database.H2) {
            throw new StoreException(StoreCode.BUSINESS_DATABASE_NOT_H2, "The JDBC URL names H2, which is for Sennard's first-time settings only.");
        }
        // Before the URL is split: the forms that carry a password differ per product (Db2 ':password=x;', Oracle 'user/secret@'), so the one judgment of JdbcUrlPassword reads the whole text.
        if (JdbcUrlPassword.carriedBy(url)) {
            throw new StoreException(StoreCode.INVALID_DATABASE_CONFIG, "The JDBC URL carries no password.");
        }
        URI uri = hostUrl(url);
        Map<String, String> options = options(uri.getRawQuery());
        if (uri.getUserInfo() != null) {
            throw new StoreException(StoreCode.INVALID_DATABASE_CONFIG, "The JDBC URL carries no user information: the account is ownerUser.");
        }
        boolean strict = policy == TlsPolicy.VERIFY_FULL || !isLoopbackHost(uri.getHost());
        if (!strict) {
            return;
        }
        boolean verified = policy == TlsPolicy.VERIFY_FULL && ServerVerification.of(product).map(verification -> verification.holdsIn(options)).orElse(false);
        if (!verified) {
            throw new StoreException(StoreCode.REMOTE_DB_TLS_NOT_VERIFIED,
                    "A database that is not an IP-literal loopback address needs its server verified by the driver's own option (PostgreSQL: sslmode=verify-full and sslrootcert; "
                            + "MariaDB: sslMode=verify-full; MySQL: sslMode=VERIFY_IDENTITY); Sennard knows no such option for " + product + ".");
        }
    }

    /**
     * The URL as {@link URI} reads it: {@code jdbc:} then a {@code <subprotocol>://host[:port]/database[?options]} URI with exactly one host and one database. {@code URI} does the
     * splitting (scheme, host, port, path, query), so no pattern of ours has to know the URL grammar.
     */
    private static URI hostUrl(String url) {
        String shape = "The JDBC URL is not jdbc:<subprotocol>://host[:port]/database[?options] with one host and one database.";
        URI uri;
        try {
            uri = new URI(url.substring(JDBC_PREFIX.length()));
        } catch (URISyntaxException notAUri) {
            throw new StoreException(StoreCode.INVALID_DATABASE_CONFIG, shape, notAUri);
        }
        String database = uri.getPath();
        if (uri.getHost() == null || database == null || database.length() < 2 || database.substring(1).contains("/")) {
            throw new StoreException(StoreCode.INVALID_DATABASE_CONFIG, shape);
        }
        return uri;
    }

    /** The query's {@code key=value} pairs (keys compared without regard to case). The JDK's {@code URI} gives the query as one text ({@code getRawQuery}) and has no parser for its pairs. */
    private static Map<String, String> options(String query) {
        Map<String, String> options = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (query == null || query.isEmpty()) {
            return options;
        }
        for (String pair : query.split("&")) {
            int at = pair.indexOf('=');
            String key = at < 0 ? pair : pair.substring(0, at);
            String value = at < 0 ? "" : pair.substring(at + 1);
            options.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return options;
    }

    /**
     * True only for an IP literal: 127.0.0.0/8 or ::1. A name, localhost included, is not loopback: what it resolves to is
     * not the host string's to decide.
     */
    public static boolean isLoopbackHost(String host) {
        try {
            // JDK 22+: parses an IP address literal and never asks a name service (a host name throws instead).
            return InetAddress.ofLiteral(host).isLoopbackAddress();
        } catch (IllegalArgumentException notALiteral) {
            LOG.debug("The host is not an IP address literal, so it is not loopback: {}", host, notALiteral);
            return false;
        }
    }
}
