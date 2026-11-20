// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The judgment of the encoding probe (no database). The text is two Korean syllables and an emoji: 3 code points, which a Korean code page cannot keep whole.
 * <ol>
 * <li>3 characters and the text itself: accepted.</li>
 * <li>10 (a database that counts the bytes of the UTF-8 text, such as PostgreSQL's SQL_ASCII), and 4 (UTF-16 units): DB_ENCODING_MISMATCH; where the count is not judged (H2) the text decides.</li>
 * <li>3 characters but the value without the emoji ({@code 한글?}, a Korean code page): DB_ENCODING_MISMATCH. Korean alone ({@code 한글}) is not the text either.</li>
 * <li>No value (null): DB_ENCODING_MISMATCH.</li>
 * <li>MariaDB and MySQL character sets: the database's verdict ({@link io.github.jaeseongs95.sennard.service.database.SqlBuilder#generateSchemaCharsetProbe}) is what passes or refuses; a verdict
 * of yes (also for a name in capitals) passes, and a verdict of no for utf8mb3, utf8, latin1 or an unreadable name, on either side, is refused.</li>
 * </ol>
 * The real database answering with the UTF-8 text is {@code MigrationRobustnessTest} (the business-db case).
 */
class EncodingProbeJudgmentTest {
    private static final String TEXT = "한글😀";

    @Test
    void theTextCountedAsThreeCharactersAndGivenBackIsAccepted() {
        assertDoesNotThrow(() -> DataSourceFactory.judgeEncoding(new DataSourceFactory.EncodingProbe(3, TEXT), true));
        assertDoesNotThrow(() -> DataSourceFactory.judgeEncoding(new DataSourceFactory.EncodingProbe(4, TEXT), false), "a count that is not judged does not refuse the text");
    }

    @Test
    void aCountOfBytesOrOfUtf16UnitsIsRefused() {
        assertEquals(StoreCode.DB_ENCODING_MISMATCH, refused(new DataSourceFactory.EncodingProbe(10, TEXT)));
        assertEquals(StoreCode.DB_ENCODING_MISMATCH, refused(new DataSourceFactory.EncodingProbe(4, TEXT)));
    }

    @Test
    void aValueThatLostTheEmojiIsRefusedAlthoughTheCountIsRight() {
        assertEquals(StoreCode.DB_ENCODING_MISMATCH, refused(new DataSourceFactory.EncodingProbe(3, "한글?")));
        assertEquals(StoreCode.DB_ENCODING_MISMATCH, refused(new DataSourceFactory.EncodingProbe(2, "한글")));
        assertEquals(StoreCode.DB_ENCODING_MISMATCH,
                assertThrows(StoreException.class, () -> DataSourceFactory.judgeEncoding(new DataSourceFactory.EncodingProbe(3, "한글?"), false)).code());
    }

    @Test
    void noValueIsRefused() {
        assertEquals(StoreCode.DB_ENCODING_MISMATCH, refused(new DataSourceFactory.EncodingProbe(3, null)));
    }

    @Test
    void onlyWhatTheDatabaseJudgesAsUtf8mb4ForTheSchemaAndTheServerIsAccepted() {
        assertDoesNotThrow(() -> DataSourceFactory.judgeCharacterSets(new DataSourceFactory.CharacterSets("utf8mb4", "utf8mb4", true)));
        // The database folds the case of the names, so a spelling in capitals is its verdict "yes" as well.
        assertDoesNotThrow(() -> DataSourceFactory.judgeCharacterSets(new DataSourceFactory.CharacterSets("UTF8MB4", "Utf8Mb4", true)));
        // The verdict of the database for a schema or a server that is not utf8mb4, whichever side it is.
        for (String[] pair : new String[][] {{"latin1", "utf8mb4"}, {"utf8mb4", "latin1"}, {"utf8mb3", "utf8mb4"}, {"utf8", "utf8"}, {"UTF8MB3", "UTF8MB4"}, {null, "utf8mb4"}}) {
            assertEquals(StoreCode.DB_ENCODING_MISMATCH,
                    assertThrows(StoreException.class, () -> DataSourceFactory.judgeCharacterSets(new DataSourceFactory.CharacterSets(pair[0], pair[1], false))).code(), pair[0] + "/" + pair[1]);
        }
    }

    @Test
    void theVerdictOfTheDatabaseDecidesAndTheNamesAreOnlyInTheMessage() {
        StoreException refused = assertThrows(StoreException.class, () -> DataSourceFactory.judgeCharacterSets(new DataSourceFactory.CharacterSets("utf8mb3", "utf8mb4", false)));
        assertTrue(refused.getMessage().contains("utf8mb3") && refused.getMessage().contains("utf8mb4"), refused.getMessage());
        assertThrows(StoreException.class, () -> DataSourceFactory.judgeCharacterSets(new DataSourceFactory.CharacterSets("utf8mb4", "utf8mb4", false)),
                "the names are not compared here: a verdict of no refuses whatever they look like");
    }

    // ------------------------------------------------------------------------------------------------------------ what the probe's statement can fail with

    /**
     * Cases of {@code verifyEncoding} when the probe's statement fails, on a connection of the JDK's own dynamic proxy that fails the statement (no database):
     * a timeout or a cancel, a lost connection and a transient failure are not the encoding (the failure's own StoreCode); a statement the database refuses and the vendor code HY000 of
     * MySQL (1366) are the encoding. A rollback that fails is added to the failure, which stays the reason.
     */
    @Test
    void aTimeoutOrACancelOfTheProbeIsNotReportedAsAnEncodingMismatch() {
        StoreException failure = failing(new SQLTimeoutException("canceling statement due to statement timeout", "57014"), null);
        assertEquals(StoreCode.STORE_TIMEOUT, failure.code());
    }

    @Test
    void aLostOrTransientConnectionIsNotReportedAsAnEncodingMismatch() {
        StoreException lost = failing(new SQLNonTransientConnectionException("connection closed", "08006"), null);
        assertEquals(StoreCode.STORE_UNREACHABLE, lost.code());
        StoreException transientFailure = failing(new SQLTransientConnectionException("no connection", "08001"), null);
        assertNotEquals(StoreCode.DB_ENCODING_MISMATCH, transientFailure.code());
    }

    @Test
    void aStatementTheDatabaseRefusesIsAnEncodingMismatchWhateverTheSqlStateClass() {
        assertEquals(StoreCode.DB_ENCODING_MISMATCH, failing(new SQLException("invalid byte sequence for encoding", "22021"), null).code());
        assertEquals(StoreCode.DB_ENCODING_MISMATCH, failing(new SQLException("Incorrect string value", "HY000", 1366), null).code(), "MySQL refuses with HY000, not class 22");
    }

    @Test
    void aRollbackThatFailsIsAddedToTheFailureAndDoesNotReplaceIt() {
        SQLException rollbackFailed = new SQLException("connection is closed", "08003");
        StoreException timeout = failing(new SQLTimeoutException("statement timeout", "57014"), rollbackFailed);
        assertEquals(StoreCode.STORE_TIMEOUT, timeout.code());
        assertTrue(List.of(timeout.getCause().getSuppressed()).contains(rollbackFailed), "the rollback's failure is attached to the failure the reason wraps");
    }

    private static StoreException failing(SQLException statementFailure, SQLException rollbackFailure) {
        Connection connection = failingConnection(statementFailure, rollbackFailure);
        return assertThrows(StoreException.class, () -> DataSourceFactory.verifyEncoding(connection));
    }

    /** A connection of a database that says it is PostgreSQL and fails every statement it is asked to prepare; its rollback fails when {@code rollbackFailure} is given. */
    private static Connection failingConnection(SQLException statementFailure, SQLException rollbackFailure) {
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(EncodingProbeJudgmentTest.class.getClassLoader(), new Class<?>[] {DatabaseMetaData.class},
                (proxy, method, args) -> "getDatabaseProductName".equals(method.getName()) ? "PostgreSQL" : defaultOf(method.getReturnType()));
        return (Connection) Proxy.newProxyInstance(EncodingProbeJudgmentTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getMetaData":
                    return metaData;
                case "prepareStatement":
                    throw statementFailure;
                case "rollback":
                    if (rollbackFailure != null) {
                        throw rollbackFailure;
                    }
                    return null;
                default:
                    return defaultOf(method.getReturnType());
            }
        });
    }

    private static Object defaultOf(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }

    private static StoreCode refused(DataSourceFactory.EncodingProbe answer) {
        return assertThrows(StoreException.class, () -> DataSourceFactory.judgeEncoding(answer, true)).code();
    }
}
