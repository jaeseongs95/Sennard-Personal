// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.database;

import java.util.regex.Pattern;

/**
 * The one judgment of whether a JDBC URL carries a password. The password of the business database is in the file the settings point at and nowhere else, so both places that take
 * the user's URL (the admin page's link request and the store's {@code SennardDatabaseConfig}) ask this class and cannot disagree.
 *
 * <p>The URL grammar differs per product, so no list of separators decides; the forms of the products {@link SqlBuilder.Database} names are what the patterns follow:
 * <ul>
 *   <li>a property named {@code password}, {@code passwd}, {@code pwd} (or {@code passfile}, the file a driver reads it from) followed by {@code =}, wherever a name can start: after
 *       {@code ?}, {@code &}, {@code ;} (SQL Server, H2, MySQL, MariaDB, PostgreSQL) or {@code :} (Db2: {@code jdbc:db2://h:50000/db:password=x;}) or any other character that
 *       cannot be part of a name. Case and blanks around the name do not matter;</li>
 *   <li>URI user information {@code //user:secret@host} (PostgreSQL, MySQL, MariaDB);</li>
 *   <li>Oracle's {@code jdbc:oracle:<driver>:user/secret@...}.</li>
 * </ul>
 * A name that only ends in one of those words ({@code trustStorePassword}) is another property and is not judged here.
 */
public final class JdbcUrlPassword {
    private static final Pattern PROPERTY = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])(?:password|passwd|pwd|passfile)\\s*=");
    private static final Pattern USER_INFORMATION = Pattern.compile("//[^/@\\s]*:[^/@\\s]*@");
    private static final Pattern ORACLE_USER_SLASH_PASSWORD = Pattern.compile("(?i)^jdbc:oracle:[a-z0-9]+:[^/@\\s:]+/[^@\\s]+@");

    private JdbcUrlPassword() {
    }

    /** True when the URL holds a password in any of the forms above. */
    public static boolean carriedBy(String jdbcUrl) {
        return jdbcUrl != null
                && (PROPERTY.matcher(jdbcUrl).find() || USER_INFORMATION.matcher(jdbcUrl).find() || ORACLE_USER_SLASH_PASSWORD.matcher(jdbcUrl).find());
    }
}
