// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;

import io.github.jaeseongs95.sennard.service.store.RootState;
import io.github.jaeseongs95.sennard.service.store.StoreCode;
import io.github.jaeseongs95.sennard.service.store.StoreException;
import io.github.jaeseongs95.sennard.service.store.modules.ContinuityStore;
import io.github.jaeseongs95.sennard.service.store.modules.WorkflowStore;
import tools.jackson.databind.json.JsonMapper;

class WorkflowDocumentsTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String ROOT_ID = "root-1";
    private static final String NOW = "2026-11-22T00:00:00Z";
    private static final String ROOT = """
            {"rootId":"root-1","revision":0,"currentEpoch":1,"state":"OPEN","updatedAt":"2026-11-22T00:00:00Z",
             "taskEnvelope":{"objective":"Complete a workflow","scope":{},"authorization":{}}}
            """;
    private static final String RUN = """
            {"runId":"run-2","revision":0,"state":"PASSED","plan":{}}
            """;
    private static final String LEASE = """
            {"rootId":"root-1","leaseId":"lease-1","rootRevision":0,"epoch":1,"ordinal":1,"state":"CONSUMED"}
            """;
    private static final String OUTCOME = """
            {"rootId":"root-1","leaseId":"lease-1","rootRevision":0,"epoch":1,"ordinal":1,"state":"PASSED"}
            """;

    private static WorkflowStore.Snapshot snapshot(String root, List<String> leases, List<String> outcomes, String... runs) {
        return new WorkflowStore.Snapshot(root, List.of(), leases, outcomes, List.of(), List.of(runs));
    }

    static Stream<Arguments> damaged() {
        return Stream.of(
                arguments("null root document", snapshot(null, List.of(), List.of()), Map.of()),
                arguments("JSON null root", snapshot("null", List.of(), List.of()), Map.of()),
                arguments("empty root", snapshot("", List.of(), List.of()), Map.of()),
                arguments("empty object", snapshot("{}", List.of(), List.of()), Map.of()),
                arguments("missing root state", snapshot(ROOT.replace("\"state\":\"OPEN\",", ""), List.of(), List.of()), Map.of()),
                arguments("unknown root state", snapshot(ROOT.replace("OPEN", "UNRECOGNIZED"), List.of(), List.of()), Map.of()),
                arguments("missing task objective", snapshot(ROOT.replace("\"objective\":\"Complete a workflow\",", ""), List.of(), List.of()), Map.of()),
                arguments("damaged older run", snapshot(ROOT, List.of(), List.of(), "run-1", "run-2"), Map.of("run-1", "{", "run-2", RUN)),
                arguments("older run missing the plan", snapshot(ROOT, List.of(), List.of(), "run-1", "run-2"),
                        Map.of("run-1", RUN.replace("run-2", "run-1").replace(",\"plan\":{}", ""), "run-2", RUN)),
                arguments("JSON null latest run", snapshot(ROOT, List.of(), List.of(), "run-2"), Map.of("run-2", "null")),
                arguments("missing run state", snapshot(ROOT, List.of(), List.of(), "run-2"), Map.of("run-2", RUN.replace("\"state\":\"PASSED\",", ""))),
                arguments("wrong run identity", snapshot(ROOT, List.of(), List.of(), "run-2"), Map.of("run-2", RUN.replace("run-2", "run-3"))),
                arguments("invalid lease", snapshot(ROOT, List.of("null"), List.of(), "run-2"), Map.of("run-2", RUN)),
                arguments("missing lease epoch", snapshot(ROOT, List.of(LEASE.replace("\"epoch\":1,", "")), List.of(OUTCOME), "run-2"), Map.of("run-2", RUN)),
                arguments("invalid outcome", snapshot(ROOT, List.of(LEASE), List.of("{"), "run-2"), Map.of("run-2", RUN)),
                arguments("missing outcome state", snapshot(ROOT, List.of(LEASE), List.of(OUTCOME.replace(",\"state\":\"PASSED\"", "")), "run-2"), Map.of("run-2", RUN)),
                arguments("nonterminal outcome", snapshot(ROOT, List.of(LEASE), List.of(OUTCOME.replace("PASSED", "RUNNING")), "run-2"), Map.of("run-2", RUN)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("damaged")
    void unavailableDocumentsKeepTheBoundEpoch(String scenario, WorkflowStore.Snapshot snapshot, Map<String, String> receipts) {
        WorkflowDocuments documents = new WorkflowDocuments(new DocumentStore(snapshot, receipts), MAPPER);
        var unavailable = assertInstanceOf(WorkflowDocuments.Unavailable.class, documents.read(ROOT_ID));
        assertEquals(ROOT_ID, unavailable.rootId());
        assertFalse(settled(documents), scenario);
    }

    @Test
    void rootDamageDoesNotImplicateAnUninspectedReceipt() {
        var documents = documents(snapshot("null", List.of(), List.of(), "run-2"), RUN);
        assertNull(assertInstanceOf(WorkflowDocuments.Unavailable.class, documents.read(ROOT_ID)).runId());
        assertNull(assertInstanceOf(WorkflowDocuments.Unavailable.class, documents.readCurrent(ROOT_ID)).runId());
    }

    @Test
    void inactiveCurrentReadDoesNotLoadAttemptHistory() {
        var store = new DocumentStore(snapshot(ROOT.replace("OPEN", "COMPLETED"), List.of(), List.of(), "run-2"), Map.of()) {
            @Override
            public Snapshot getConvergenceSnapshot(String rootId) {
                throw new AssertionError("Current activity does not need the full snapshot");
            }

            @Override
            public List<String> workflowRunIds(String rootId) {
                throw new AssertionError("An inactive root does not need its receipts");
            }
        };
        var read = assertInstanceOf(WorkflowDocuments.Readable.class, new WorkflowDocuments(store, MAPPER).readCurrent(ROOT_ID));
        assertNull(read.latestRun());
        assertEquals(RootState.COMPLETED, read.root().state());
    }

    @Test
    void activeCurrentReadChecksOnlyItsLatestReceipt() {
        var documents = documents(snapshot(ROOT, List.of("null"), List.of("{"), "run-1", "run-2"), RUN);
        var read = assertInstanceOf(WorkflowDocuments.Readable.class, documents.readCurrent(ROOT_ID));
        assertEquals("run-2", read.latestRun().runId());
        assertInstanceOf(WorkflowDocuments.Unavailable.class, documents.read(ROOT_ID));

        var damaged = documents(snapshot(ROOT, List.of(), List.of(), "run-1", "run-2"), "null");
        assertEquals("run-2", assertInstanceOf(WorkflowDocuments.Unavailable.class, damaged.readCurrent(ROOT_ID)).runId());
    }

    @Test
    void onlyTerminalRunsWithRecordedConsumedAttemptsSettle() {
        assertTrue(settled(documents(snapshot(ROOT, List.of(LEASE), List.of(OUTCOME), "run-2"), RUN)));
        assertFalse(settled(documents(snapshot(ROOT, List.of(LEASE), List.of(), "run-2"), RUN)));
        assertFalse(settled(documents(snapshot(ROOT, List.of(), List.of(), "run-2"), RUN.replace("PASSED", "RUNNING"))));
        assertTrue(settled(documents(snapshot(ROOT.replace("OPEN", "COMPLETED"), List.of(), List.of()), RUN)));
        assertFalse(settled(documents(snapshot(ROOT, List.of(), List.of()), RUN)));
    }

    @Test
    void integrityErrorsAreEvidenceOfDamageButConnectionErrorsStillFailSettlement() {
        var integrity = new StoreException(StoreCode.INTEGRITY_FAILED, "receipt row mismatch");
        var unavailable = assertInstanceOf(WorkflowDocuments.Unavailable.class, failingRun(integrity).read(ROOT_ID));
        assertEquals("run-2", unavailable.runId());
        assertSame(integrity, unavailable.cause());

        var connection = new StoreException(StoreCode.STORE_UNREACHABLE, "fixture connection failure");
        assertSame(connection, assertThrows(StoreException.class, () -> settled(failingRun(connection))));
    }

    @Test
    void springJdbcConnectionFailuresPropagateOutOfSettlement() {
        SQLException connection = new SQLException("fixture connection loss", "08006");
        var dataSource = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw connection;
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
            }
        };
        WorkflowDocuments documents = new WorkflowDocuments(new WorkflowStore(JdbcClient.create(dataSource), MAPPER, null), MAPPER);

        DataAccessException failure = assertThrows(DataAccessException.class, () -> settled(documents));
        assertSame(connection, failure.getMostSpecificCause());
    }

    private static WorkflowDocuments failingRun(StoreException failure) {
        return new WorkflowDocuments(new DocumentStore(snapshot(ROOT, List.of(), List.of(), "run-2"), Map.of()) {
            @Override
            public String getRun(String runId) {
                throw failure;
            }
        }, MAPPER);
    }

    private static WorkflowDocuments documents(WorkflowStore.Snapshot snapshot, String receipt) {
        return new WorkflowDocuments(new DocumentStore(snapshot, Map.of("run-2", receipt)), MAPPER);
    }

    private static boolean settled(WorkflowDocuments documents) {
        var current = task(1, ROOT_ID);
        var store = new ContinuityStore(null, MAPPER, null, null) {
            @Override
            public List<String> boundRoots(String correlation, long epoch) {
                return List.of(ROOT_ID);
            }

            @Override
            public Task rotateEpoch(String correlation, String now, Expected expected) {
                assertEquals(current.currentEpoch(), expected.epoch());
                assertEquals(current.rootId(), expected.rootId());
                return expected.isTerminal().getAsBoolean() ? task(2, null) : current;
            }
        };
        return new WorkflowSettlement(store, documents).settle(current, NOW).currentEpoch() == 2;
    }

    private static ContinuityStore.Task task(long epoch, String rootId) {
        return new ContinuityStore.Task("task", epoch, rootId, false, null, null, null, null, null, null, false, null);
    }

    private static class DocumentStore extends WorkflowStore {
        private final Snapshot snapshot;
        private final Map<String, String> receipts;

        DocumentStore(Snapshot snapshot, Map<String, String> receipts) {
            super(null, MAPPER, null);
            this.snapshot = snapshot;
            this.receipts = receipts;
        }

        @Override
        public Snapshot getConvergenceSnapshot(String rootId) {
            return snapshot;
        }

        @Override
        public String getRootDocument(String rootId) {
            return snapshot.rootJson();
        }

        @Override
        public List<String> workflowRunIds(String rootId) {
            return snapshot.workflowRunIds();
        }

        @Override
        public String getRun(String runId) {
            return receipts.get(runId);
        }
    }
}
