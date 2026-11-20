// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;

import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * The board cases of {@link AbstractBoardServiceTest} with this application's connection: {@link PreparingDataSource} named {@code store}, and the
 * {@link StoreTransactionManager} (the begin failures of the store are its own reasons). Besides them:
 *
 * <pre>
 * i1  start before ready  the context is refreshed while the DataSource answers STORE_PREPARING (Spring Data JDBC asks the database for nothing at start,
 *                         spring.data.jdbc.dialect states the dialect); a service call then is an UnavailableException; after makeReady it works
 * </pre>
 */
class BoardServiceAppConnectionTest extends AbstractBoardServiceTest {
    @Override
    RewrittenAreasContext open(Instant start) {
        return RewrittenAreasContext.appConnection(start);
    }

    @Test
    void theContextStartsBeforeTheDatabaseIsReadyAndServesAfterwards() throws Exception {
        try (RewrittenAreasContext early = RewrittenAreasContext.appConnectionNotReady(Instant.parse("2026-01-01T00:00:00Z"))) {
            BoardService board = early.bean(BoardService.class); // the refresh did not need a connection (it would have thrown STORE_PREPARING)
            StoreException preparing = assertThrows(StoreException.class, () -> early.dataSource().getConnection());
            assertEquals(StoreCode.STORE_PREPARING, preparing.code());
            var builder = early.bean(io.github.jaeseongs95.sennard.service.database.SqlBuilder.class);
            StoreException assemblyPreparing = assertThrows(StoreException.class, () -> builder.query("SELECT 1").build());
            assertEquals(StoreCode.STORE_PREPARING, assemblyPreparing.code());
            StoreException limitPreparing = assertThrows(StoreException.class, () -> builder.generateLimit("?"));
            assertEquals(StoreCode.STORE_PREPARING, limitPreparing.code());
            UnavailableException unavailable = assertThrows(UnavailableException.class, () -> board.readEntry("claude", "s1"));
            assertEquals("STORE_PREPARING", unavailable.arguments().get("reason"), "the service's caller is told the store is preparing: " + unavailable.arguments());

            early.makeReady();
            assertEquals("SELECT 1", builder.query("SELECT 1").build());
            try (java.sql.Connection connection = BusinessDb.connect()) {
                builder.requireSame(connection.getMetaData().getDatabaseProductName()); // the product the database reports is the one the builder found
            }
            board.recordInput("claude", "s1", "/work", true);
            assertEquals("/work", board.readEntry("claude", "s1").orElseThrow().cwd());
        }
    }

    @Test
    void theDialectIsStatedBySpringDataJdbcSettingAndDetectingItWouldNeedAConnectionAtStart() {
        assertInstanceOf(RewrittenAreasContext.dialectType(), context().bean(JdbcDialect.class), "the dialect is the one spring.data.jdbc.dialect names");
        // Without spring.data.jdbc.dialect Boot asks the database for its product name while the context refreshes: the DataSource that is not ready refuses it.
        RuntimeException failure = assertThrows(RuntimeException.class, () -> RewrittenAreasContext.appConnectionNotReady(Instant.parse("2026-01-01T00:00:00Z"), null));
        boolean preparing = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            preparing |= cause instanceof StoreException store && store.code() == StoreCode.STORE_PREPARING;
        }
        assertTrue(preparing, "the start failed because the store was preparing: " + failure);
    }
}
