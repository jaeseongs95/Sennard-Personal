// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.pg.BusinessDb;
import io.github.jaeseongs95.sennard.service.store.pg.PreparingDataSource;

/**
 * D-0191 on the real business database (PostgreSQL): the store is ready once the test hands the server the pool of the real
 * database, and the first use after that makes the repositories and the Spring Data JDBC dialect from that connection. The dialect that is expected is the one of the
 * product the URL names, so the case holds on every database the tests run on, and H2, which is no business database, is not used.
 */
@Tag("business-db")
class StoreReadyDialectServerTest {
    private static StoreException storeFailureIn(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StoreException store) {
                return store;
            }
        }
        return null;
    }

    @Test
    void theFirstUseAfterTheStoreIsReadyMakesTheDialectOfTheConnectedProduct() throws Exception {
        try (RunningServer server = RunningServer.start(List.of(ServerFixtures.Beans.class))) {
            BoardService board = server.bean(BoardService.class);
            HikariDataSource pool = BusinessDb.dataSources().create(BusinessDb.config(2));
            server.bean(PreparingDataSource.class).ready(pool);

            // The schemas may or may not be there (an earlier class of this run made them): the call either answers or fails in the database; it is no longer STORE_PREPARING.
            try {
                board.readEntry("host", "session");
            } catch (RuntimeException afterReady) {
                StoreException notReady = storeFailureIn(afterReady);
                assertTrue(notReady == null || notReady.code() != StoreCode.STORE_PREPARING, "after the store is ready the answer is no longer STORE_PREPARING: " + afterReady);
            }
            assertTrue(server.context.getBeanFactory().containsSingleton("boardSessionRepository"), "the repository was made by the first use");
            assertInstanceOf(JdbcPostgresDialect.class, server.bean(JdbcDialect.class), "the dialect of the connected product");
        }
    }
}
