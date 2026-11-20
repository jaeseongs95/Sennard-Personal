// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;

import io.github.jaeseongs95.sennard.app.board.BoardService;
import io.github.jaeseongs95.sennard.domain.error.UnavailableException;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;

/**
 * D-0191: the repositories and the Spring Data JDBC dialect are made at their first use (the dialect is asked from the connection), so a request that arrives before the
 * business database is ready must get STORE_PREPARING as it did when they were made at the start. The server has no database here and the store is never ready: no pool is
 * handed in (H2 is no business database; the case with the real database's connection is {@link StoreReadyDialectServerTest}).
 */
class StorePreparingServerTest {
    /** A type no converter knows: the conversion service then asks the converters that accept any type, among them Spring Data's DomainClassConverter if it is registered. */
    private record Probe(int value) {
    }

    private static boolean madeAlready(RunningServer server, String beanName) {
        return server.context.getBeanFactory().containsSingleton(beanName);
    }

    private static StoreException storeFailureIn(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StoreException store) {
                return store;
            }
        }
        return null;
    }

    /** What went wrong before: converting a request parameter built Spring Data's Repositories, which made every repository, and the first of them failed for the dialect. */
    @Test
    void convertingARequestParameterDoesNotMakeTheRepositories() throws Exception {
        try (RunningServer server = RunningServer.start(List.of(ServerFixtures.Beans.class))) {
            ConversionService conversion = server.context.getBean("mvcConversionService", ConversionService.class);
            conversion.canConvert(String.class, Probe.class); // the answer is the converters' business; that it is asked without a database is the point
            assertFalse(madeAlready(server, "boardSessionRepository"), "a conversion made a repository");
            assertFalse(madeAlready(server, "jdbcDialect"), "a conversion asked for the dialect");
        }
    }

    /**
     * Before the store is ready the service's transaction is refused with STORE_PREPARING and nothing of Spring Data JDBC is made. What the first use after it makes, from the
     * connection of the real business database, is {@link StoreReadyDialectServerTest}.
     */
    @Test
    void aCallBeforeTheStoreIsReadyIsStorePreparingAndMakesNothingOfSpringDataJdbc() throws Exception {
        try (RunningServer server = RunningServer.start(List.of(ServerFixtures.Beans.class))) {
            BoardService board = server.bean(BoardService.class);
            UnavailableException refused = assertThrows(UnavailableException.class, () -> board.readEntry("host", "session"));
            StoreException store = storeFailureIn(refused);
            assertInstanceOf(StoreException.class, store, "the refusal carries the store's own failure");
            assertEquals(StoreCode.STORE_PREPARING, store.code());
            assertFalse(madeAlready(server, "boardSessionRepository"));
            assertFalse(madeAlready(server, "jdbcDialect"));
        }
    }
}
