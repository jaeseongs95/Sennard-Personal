// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.app.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import io.github.jaeseongs95.sennard.app.AppConfiguration;
import io.github.jaeseongs95.sennard.app.operation.OperationReceipts;
import io.github.jaeseongs95.sennard.app.support.UtcTimestampColumns;

class JournalH2ViewTest {
    @Configuration(proxyBeanMethods = false)
    // ChangeJournalService has the write use cases of the change plan too, which keep the operationKey receipt, so the real OperationReceipts service comes in with it (its clock, its
    // properties, its repository and its mapper are what AppConfiguration and the auto-configurations below already give); the test reads through the read-only use cases only.
    // and the rows of a captured baseline (BaselineRows, with the column conversion it writes the times through).
    @Import({AppConfiguration.class, ChangeJournalService.class, OperationReceipts.class, BaselineRows.class, UtcTimestampColumns.class})
    @ImportAutoConfiguration({JdbcTemplateAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class, DataJdbcRepositoriesAutoConfiguration.class, JacksonAutoConfiguration.class})
    static class Context {
        @Bean EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                    .setName(UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE").build();
        }
    }

    @Test void theJournalIsReadThroughTheReadOnlyUseCasesOfTheService() {
        try (var context = new AnnotationConfigApplicationContext(Context.class)) {
            var sql = context.getBean(JdbcTemplate.class);
            sql.execute("CREATE SCHEMA sennard");
            sql.execute("CREATE TABLE sennard.journal_workflow (workflow_id VARCHAR PRIMARY KEY, baseline_state VARCHAR, version BIGINT)");
            sql.execute("CREATE TABLE sennard.plan_revision (plan_revision_id VARCHAR PRIMARY KEY, workflow_id VARCHAR, revision BIGINT, submitted_at TIMESTAMP, version BIGINT)");
            sql.execute("CREATE TABLE sennard.plan_file (plan_file_id VARCHAR PRIMARY KEY, plan_revision_id VARCHAR, path VARCHAR, path_hash VARCHAR, version BIGINT)");
            sql.execute("CREATE TABLE sennard.observed_file (observed_file_id VARCHAR PRIMARY KEY, workflow_id VARCHAR, path VARCHAR, path_hash VARCHAR, size_bytes BIGINT, modified_at TIMESTAMP, sha256 VARCHAR, version BIGINT)");
            sql.execute("CREATE TABLE sennard.write_root (write_root_id VARCHAR PRIMARY KEY, workflow_id VARCHAR, path VARCHAR, path_hash VARCHAR, version BIGINT)");
            sql.execute("CREATE TABLE sennard.scan_failure (scan_failure_id VARCHAR PRIMARY KEY, workflow_id VARCHAR, path VARCHAR, reason VARCHAR, version BIGINT)");
            sql.execute("CREATE TABLE sennard.change_event (change_event_id VARCHAR PRIMARY KEY, workflow_id VARCHAR, plan_revision BIGINT, path VARCHAR, path_hash VARCHAR, sha256 VARCHAR, size_bytes BIGINT, modified_at TIMESTAMP, recorded_at TIMESTAMP, source VARCHAR, version BIGINT)");
            sql.update("INSERT INTO sennard.journal_workflow VALUES ('h2', 'EMPTY', 0)");
            sql.update("INSERT INTO sennard.plan_revision VALUES ('revision', 'h2', 1, TIMESTAMP '2026-01-01 00:00:00', 0)");
            var journal = context.getBean(ChangeJournalService.class);
            var plan = journal.plan("h2");
            assertEquals(1, plan.revision());
            assertTrue(plan.files().isEmpty());
            assertTrue(journal.changes("h2").events().isEmpty());
            assertEquals(java.util.Optional.of(WorkflowPhase.PLANNING), journal.phase("h2"));
        }
    }
}
