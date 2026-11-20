// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.store.pg;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The store of a server that reaches its database behind its listener: the assembly supplies the {@link PreparedStore} bean. */
@Configuration(proxyBeanMethods = false)
@Import({StoreConfiguration.class, DataSourceFactory.class, JdbcDrivers.class})
public class PreparedStoreConfiguration {
    /** Depends on {@code preparedStore}, so it is closed before the database is let go. */
    @Bean(destroyMethod = "close")
    @Qualifier("store")
    public PreparingDataSource store(PreparedStore preparedStore) {
        return new PreparingDataSource(() -> statusBody(preparedStore.status()));
    }

    /** The body of STORE_PREPARING and of the status endpoint. */
    public static Map<String, Object> statusBody(StorePreparation.Status status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phase", status.phase().name());
        body.put("startedAt", status.startedAt().toString());
        body.put("retryAfterMs", status.retryAfterMs());
        body.put("attempt", status.attempt());
        if (status.reason() != null) {
            body.put("reason", status.reason());
            body.put("detail", status.detail());
        }
        return body;
    }
}
