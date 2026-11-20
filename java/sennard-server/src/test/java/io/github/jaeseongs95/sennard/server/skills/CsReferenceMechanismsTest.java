// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The executable reference cases of the cs-engineering cards that need no database: bounded, deterministic witnesses of the mechanisms the cards name (ALG-*, CONC-LOCK-003,
 * CONC-CANCEL-004, DIST-*, PERF-*, and LANG-ALIAS-001, LANG-NUMERIC-002, DESIGN-STATE-002 of the draft pool), in plain Java ({@code java.util.concurrent} where a real race is the
 * point). The cards that are about a database are run by {@code CsQueueReferenceTest} of sennard-store-pg on the shared PostgreSQL. These are witnesses for the cards, not
 * measurements of a model and not a certification of the Sennard product or of a host. Each case's display name starts with the ID of its card; the cards'
 * {@code validationScope} names the method.
 */
class CsReferenceMechanismsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    // ------------------------------------------------------------------------------------------------------------------------------------ the graph

    /** A directed graph by node: what each node points to. */
    private record Graph<N>(Map<N, List<N>> edges) {
        /** The nodes reachable from {@code start}, walked with an explicit stack so that a deep chain cannot overflow the call stack and a cycle ends. */
        Set<N> reachableFrom(N start) {
            Set<N> seen = new HashSet<>();
            Deque<N> todo = new ArrayDeque<>();
            todo.push(start);
            while (!todo.isEmpty()) {
                N node = todo.pop();
                if (seen.add(node)) {
                    edges.getOrDefault(node, List.of()).forEach(todo::push);
                }
            }
            return seen;
        }

        /** The nodes in an order that puts every edge's source before its target; a cycle or a target that is not a node is refused. */
        List<N> topologicalOrder() {
            Map<N, Integer> inDegree = new LinkedHashMap<>();
            edges.keySet().forEach(node -> inDegree.put(node, 0));
            for (List<N> targets : edges.values()) {
                for (N target : new HashSet<>(targets)) {
                    if (!inDegree.containsKey(target)) {
                        throw new IllegalArgumentException("unknown node " + target);
                    }
                    inDegree.merge(target, 1, Integer::sum);
                }
            }
            Deque<N> ready = new ArrayDeque<>();
            inDegree.forEach((node, degree) -> {
                if (degree == 0) {
                    ready.add(node);
                }
            });
            List<N> order = new ArrayList<>();
            while (!ready.isEmpty()) {
                N node = ready.poll();
                order.add(node);
                for (N target : new HashSet<>(edges.get(node))) {
                    if (inDegree.merge(target, -1, Integer::sum) == 0) {
                        ready.add(target);
                    }
                }
            }
            if (order.size() != edges.size()) {
                throw new IllegalStateException("cycle");
            }
            return order;
        }
    }

    private static Graph<Integer> graph(int size, java.util.function.BiPredicate<Integer, Integer> edge) {
        Map<Integer, List<Integer>> edges = new LinkedHashMap<>();
        for (int from = 0; from < size; from++) {
            List<Integer> targets = new ArrayList<>();
            for (int to = from + 1; to < size; to++) {
                if (edge.test(from, to)) {
                    targets.add(to);
                }
            }
            edges.put(from, targets);
        }
        return new Graph<>(edges);
    }

    @Test
    @DisplayName("ALG-TERMINATION-001: a cycle, a disconnected node and a deep chain terminate")
    void cyclesDisconnectedNodesAndADeepChainTerminate() {
        Graph<Integer> cyclic = new Graph<>(Map.of(0, List.of(1), 1, List.of(2), 2, List.of(0), 9, List.of()));
        assertEquals(new TreeSet<>(Set.of(0, 1, 2)), new TreeSet<>(cyclic.reachableFrom(0)));
        Map<Integer, List<Integer>> chain = new HashMap<>();
        for (int node = 0; node < 20_000; node++) {
            chain.put(node, node == 19_999 ? List.of() : List.of(node + 1));
        }
        assertEquals(20_000, new Graph<>(chain).reachableFrom(0).size());
    }

    @Test
    @DisplayName("ALG-ORDER-002: a generated DAG keeps every edge in order; a cycle and an unknown node are refused")
    void aGeneratedDagKeepsEveryEdgeInOrderAndACycleAndAnUnknownNodeAreRefused() {
        for (int seed = 1; seed <= 20; seed++) {
            int factor = seed;
            Graph<Integer> dag = graph(60, (from, to) -> (from * 31 + to * factor) % 17 == 0);
            List<Integer> order = dag.topologicalOrder();
            assertEquals(60, order.size());
            Map<Integer, Integer> position = new HashMap<>();
            for (int at = 0; at < order.size(); at++) {
                position.put(order.get(at), at);
            }
            dag.edges().forEach((from, targets) -> targets.forEach(to -> assertTrue(position.get(from) < position.get(to), from + " before " + to)));
        }
        IllegalStateException cycle = assertThrows(IllegalStateException.class, () -> new Graph<>(Map.of(1, List.of(2), 2, List.of(1))).topologicalOrder());
        assertEquals("cycle", cycle.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new Graph<>(Map.of(1, List.of(3))).topologicalOrder());
    }

    @Test
    @DisplayName("ALG-COST-003: a linear membership loop makes a quadratic number of comparisons")
    void aLinearMembershipLoopMakesAQuadraticNumberOfComparisons() {
        assertEquals(4950, comparisons(100));
        assertEquals(19_900, comparisons(200));
        assertTrue(comparisons(200) > 3.9 * comparisons(100), "doubling the input about quadruples the work");
    }

    /** The comparisons made by adding {@code n} distinct values to a list after looking for each in it. */
    private static long comparisons(int n) {
        List<Integer> items = new ArrayList<>();
        long comparisons = 0;
        for (int value = 0; value < n; value++) {
            for (Integer item : items) {
                comparisons++;
                if (item == value) {
                    break;
                }
            }
            items.add(value);
        }
        return comparisons;
    }

    @Test
    @DisplayName("ALG-KEY-004: a changed version or principal changes the memoization key, the order of its members does not")
    void aChangedVersionOrPrincipalChangesTheMemoizationKey() {
        ObjectNode key = memoKey("a", 1, "p1");
        assertNotEquals(CanonicalJson.digest(key), CanonicalJson.digest(memoKey("a", 2, "p1")));
        assertNotEquals(CanonicalJson.digest(key), CanonicalJson.digest(memoKey("a", 1, "p2")));
        ObjectNode reordered = JSON.createObjectNode();
        reordered.put("principal", "p1");
        reordered.put("version", 1);
        reordered.put("request", "a");
        assertEquals(CanonicalJson.digest(key), CanonicalJson.digest(reordered));
    }

    private static ObjectNode memoKey(String request, int version, String principal) {
        ObjectNode key = JSON.createObjectNode();
        key.put("request", request);
        key.put("version", version);
        key.put("principal", principal);
        return key;
    }

    // ------------------------------------------------------------------------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("CONC-LOCK-003: the lock-order graph finds a circular acquisition; opposite orders time out and release")
    void theLockOrderGraphFindsACircularAcquisition() throws Exception {
        assertEquals(List.of("a", "b"), new Graph<>(new LinkedHashMap<>(Map.of("a", List.of("b"), "b", List.of()))).topologicalOrder());
        assertThrows(IllegalStateException.class, () -> new Graph<>(Map.of("a", List.of("b"), "b", List.of("a"))).topologicalOrder());
        // The same circle with real locks: two threads take the two locks in opposite orders, each holds its first until the other holds its own. With a bounded wait
        // both give up, release what they hold and the locks are free again; without the bound they would wait for each other for ever.
        ReentrantLock first = new ReentrantLock();
        ReentrantLock second = new ReentrantLock();
        CyclicBarrier bothHoldOne = new CyclicBarrier(2);
        CyclicBarrier bothTried = new CyclicBarrier(2);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<Boolean> forward = threads.submit(() -> takeBoth(first, second, bothHoldOne, bothTried));
            Future<Boolean> backward = threads.submit(() -> takeBoth(second, first, bothHoldOne, bothTried));
            assertFalse(forward.get(30, TimeUnit.SECONDS), "the opposite order did not get its second lock");
            assertFalse(backward.get(30, TimeUnit.SECONDS), "the opposite order did not get its second lock");
        }
        assertFalse(first.isLocked());
        assertFalse(second.isLocked());
    }

    @Test
    void delayedAttemptCannotSucceedAfterOtherTimeout() throws Exception {
        CountDownLatch firstAttemptFinished = new CountDownLatch(1);
        ReentrantLock first = new ReentrantLock() {
            @Override public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
                assertTrue(firstAttemptFinished.await(15, TimeUnit.SECONDS));
                return super.tryLock(time, unit);
            }
        };
        ReentrantLock second = new ReentrantLock() {
            @Override public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
                try { return super.tryLock(time, unit); }
                finally { firstAttemptFinished.countDown(); }
            }
        };
        CyclicBarrier bothHoldOne = new CyclicBarrier(2);
        CyclicBarrier bothTried = new CyclicBarrier(2);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<Boolean> forward = threads.submit(() -> takeBoth(first, second, bothHoldOne, bothTried));
            Future<Boolean> backward = threads.submit(() -> takeBoth(second, first, bothHoldOne, bothTried));
            assertFalse(forward.get(30, TimeUnit.SECONDS));
            assertFalse(backward.get(30, TimeUnit.SECONDS));
        }
        assertFalse(first.isLocked());
        assertFalse(second.isLocked());
    }

    private static boolean takeBoth(ReentrantLock held, ReentrantLock wanted, CyclicBarrier bothHoldOne, CyclicBarrier bothTried) throws Exception {
        held.lock();
        try {
            bothHoldOne.await(15, TimeUnit.SECONDS);
            boolean acquired = wanted.tryLock(200, TimeUnit.MILLISECONDS);
            try {
                bothTried.await(15, TimeUnit.SECONDS);
                return acquired;
            } finally {
                if (acquired) wanted.unlock();
            }
        } finally {
            held.unlock();
        }
    }

    private enum JobState { RUNNING, CANCELLED, DONE }

    @Test
    @DisplayName("CONC-CANCEL-004: cancel and complete in either order, or at once, have one terminal winner")
    void cancelAndCompleteHaveOneTerminalWinner() throws Exception {
        for (List<JobState> order : List.of(List.of(JobState.CANCELLED, JobState.DONE), List.of(JobState.DONE, JobState.CANCELLED))) {
            AtomicReference<JobState> state = new AtomicReference<>(JobState.RUNNING);
            long winners = order.stream().filter(target -> state.compareAndSet(JobState.RUNNING, target)).count();
            assertEquals(1, winners);
            assertEquals(order.get(0), state.get(), "the first terminal request wins and the second does not overwrite it");
        }
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 500; round++) {
                AtomicReference<JobState> state = new AtomicReference<>(JobState.RUNNING);
                CyclicBarrier start = new CyclicBarrier(2);
                IntFunction<Future<Boolean>> request = which -> threads.submit(() -> {
                    start.await(15, TimeUnit.SECONDS);
                    return state.compareAndSet(JobState.RUNNING, which == 0 ? JobState.CANCELLED : JobState.DONE);
                });
                Future<Boolean> cancel = request.apply(0);
                Future<Boolean> complete = request.apply(1);
                assertTrue(cancel.get(30, TimeUnit.SECONDS) ^ complete.get(30, TimeUnit.SECONDS), "exactly one request wins");
                assertNotEquals(JobState.RUNNING, state.get());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------------ distributed

    /** A service whose effect happens once per intent key: a repeated key returns the first result, the same key with another payload is a conflict. */
    private static final class IdempotentService {
        private record Applied(String payload, int result) {
        }

        private final Map<String, Applied> seen = new HashMap<>();
        private int effects;

        int apply(String key, String payload) {
            Applied old = seen.get(key);
            if (old != null) {
                if (!old.payload().equals(payload)) {
                    throw new IllegalStateException("key/payload conflict");
                }
                return old.result();
            }
            int result = ++effects;
            seen.put(key, new Applied(payload, result));
            return result;
        }

        int effects() {
            return effects;
        }
    }

    @Test
    @DisplayName("DIST-ACK-001: a lost ACK repeats the delivery without repeating the protected effect")
    void aLostAckRepeatsTheDeliveryWithoutRepeatingTheProtectedEffect() {
        IdempotentService protectedService = new IdempotentService();
        protectedService.apply("request-1", "payload"); // the service acted, then the ACK was lost
        protectedService.apply("request-1", "payload"); // the sender delivers again
        assertEquals(1, protectedService.effects());
        int unprotected = 0; // the same two deliveries to a service that keeps no key
        for (int delivery = 0; delivery < 2; delivery++) {
            unprotected++;
        }
        assertEquals(2, unprotected);
    }

    @Test
    @DisplayName("DIST-IDEMPOTENCY-002: the same intent key is stable, a different payload under it is rejected")
    void theSameIntentKeyIsStableAndADifferentPayloadIsRejected() {
        IdempotentService service = new IdempotentService();
        assertEquals(service.apply("k", "x"), service.apply("k", "x"));
        assertThrows(IllegalStateException.class, () -> service.apply("k", "y"));
        service.apply("k2", "x");
        assertEquals(2, service.effects());
    }

    private static final class PermanentFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @FunctionalInterface
    private interface Attempt {
        void run(int number);
    }

    /** Calls {@code attempt} until it succeeds, at most {@code budget} times in all; a permanent failure is not retried. */
    private static void withRetries(Attempt attempt, int budget) {
        for (int number = 1; ; number++) {
            try {
                attempt.run(number);
                return;
            } catch (PermanentFailure failure) {
                throw failure;
            } catch (RuntimeException failure) {
                if (number >= budget) {
                    throw failure;
                }
            }
        }
    }

    @Test
    @DisplayName("DIST-RETRY-003: a bounded retry stops at its total budget and does not retry a permanent error")
    void aBoundedRetryStopsAtItsBudgetAndSkipsPermanentErrors() {
        int[] calls = {0};
        assertThrows(IllegalStateException.class, () -> withRetries(number -> {
            calls[0]++;
            throw new IllegalStateException("temporary");
        }, 3));
        assertEquals(3, calls[0]);
        calls[0] = 0;
        assertThrows(PermanentFailure.class, () -> withRetries(number -> {
            calls[0]++;
            throw new PermanentFailure();
        }, 3));
        assertEquals(1, calls[0]);
    }

    private record Position(String epoch, int revision) {
    }

    /** The newest position seen; an event is applied only when it is of the same epoch and a later revision (epochs are names, not numbers to compare). */
    private static final class Replica {
        private Position current = new Position("e2", 4);

        boolean apply(Position event) {
            if (!event.epoch().equals(current.epoch()) || event.revision() <= current.revision()) {
                return false;
            }
            current = event;
            return true;
        }
    }

    @Test
    @DisplayName("DIST-ORDER-004: stale revisions are rejected and epochs are not compared as bare numbers")
    void staleRevisionsAreRejectedAndEpochsAreNotComparedAsNumbers() {
        Replica replica = new Replica();
        assertFalse(replica.apply(new Position("e2", 3)));
        assertFalse(replica.apply(new Position("e1", 999)));
        assertTrue(replica.apply(new Position("e2", 5)));
        assertFalse(replica.apply(new Position("e2", 5)));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------ performance

    private record Measurement(String workload, String cache, String environment) {
        boolean comparableWith(Measurement other) {
            return equals(other);
        }
    }

    @Test
    @DisplayName("PERF-MEASURE-001: a comparison rejects a different workload or cache policy")
    void aComparisonRejectsAMismatchedWorkloadOrCachePolicy() {
        Measurement base = new Measurement("100k-edges", "cold", "local");
        assertTrue(base.comparableWith(new Measurement("100k-edges", "cold", "local")));
        assertFalse(base.comparableWith(new Measurement("100k-edges", "warm", "local")));
        assertFalse(base.comparableWith(new Measurement("10-edges", "cold", "local")));
    }

    @Test
    @DisplayName("PERF-BOUND-002: a bounded queue never silently exceeds its capacity")
    void aBoundedQueueNeverSilentlyExceedsItsCapacity() {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(17);
        int denied = 0;
        for (int item = 0; item < 10_000; item++) {
            if (!queue.offer(item)) {
                denied++;
            }
        }
        assertEquals(17, queue.size());
        assertEquals(9983, denied, "what did not fit was refused and counted, not dropped unseen");
        queue.poll();
        assertTrue(queue.offer("resume"));
        assertEquals(17, queue.size());
    }

    /** A cache whose entries expire after a time to live and whose size is capped; the oldest entry goes first. */
    private static final class TtlCache {
        private record Entry(Object value, long expires) {
        }

        private final Map<Object, Entry> entries = new LinkedHashMap<>();
        private final int capacity;
        private final long timeToLive;

        TtlCache(int capacity, long timeToLive) {
            this.capacity = capacity;
            this.timeToLive = timeToLive;
        }

        void put(Object key, Object value, long now) {
            entries.values().removeIf(entry -> entry.expires() <= now);
            entries.remove(key);
            entries.put(key, new Entry(value, now + timeToLive));
            while (entries.size() > capacity) {
                entries.remove(entries.keySet().iterator().next());
            }
        }

        int size() {
            return entries.size();
        }
    }

    @Test
    @DisplayName("PERF-CACHE-003: a time to live and a capacity solve different failure paths")
    void aTimeToLiveAndACapacitySolveDifferentFailurePaths() {
        TtlCache cache = new TtlCache(3, 10);
        for (int key = 0; key < 1000; key++) {
            cache.put(key, key, 0);
        }
        assertEquals(3, cache.size(), "the capacity bounds what has not yet expired");
        cache.put("later", 1, 11);
        assertEquals(1, cache.size(), "the time to live removed what was still within the capacity");
    }

    /** A batch that is released when it is full or when its oldest item has waited for the deadline. */
    private static final class Batch {
        private final List<String> items = new ArrayList<>();
        private long since;

        void push(String item, long now) {
            if (items.isEmpty()) {
                since = now;
            }
            items.add(item);
        }

        List<String> flush(long now) {
            if (!items.isEmpty() && (items.size() >= 3 || now - since >= 10)) {
                List<String> released = List.copyOf(items);
                items.clear();
                return released;
            }
            return List.of();
        }
    }

    @Test
    @DisplayName("PERF-BATCH-004: a partial batch is released at its deadline")
    void aPartialBatchIsReleasedAtItsDeadline() {
        Batch batch = new Batch();
        batch.push("only-one", 0);
        assertEquals(List.of(), batch.flush(9));
        assertEquals(List.of("only-one"), batch.flush(10));
        assertEquals(List.of(), batch.flush(100));
    }

    // ------------------------------------------------------------------------------------------------------------------------------------ draft pool

    @Test
    @DisplayName("LANG-ALIAS-001: a nested snapshot is detached from a later change by the caller, a shallow copy is not")
    void aNestedSnapshotIsDetachedFromALaterChangeByTheCaller() {
        ObjectNode original = JSON.createObjectNode();
        ArrayNode list = original.putArray("x");
        list.addObject().put("v", 1);
        ObjectNode snapshot = original.deepCopy();
        String before = CanonicalJson.digest(snapshot);
        ((ObjectNode) list.get(0)).put("v", 2);
        assertEquals(before, CanonicalJson.digest(snapshot));
        assertNotEquals(before, CanonicalJson.digest(original));
        // The failure the card names: a shallow copy shares the nested element, so the caller's change reaches it.
        Map<String, Integer> inner = new HashMap<>(Map.of("v", 1));
        List<Map<String, Integer>> shallow = List.copyOf(List.of(inner));
        inner.put("v", 2);
        assertEquals(2, shallow.get(0).get("v"));
    }

    @Test
    @DisplayName("LANG-NUMERIC-002: an integer past 2^53 is not a distinct double, and a non-finite number has no canonical form")
    void anIntegerPastTwoToTheFiftyThirdIsNotADistinctDouble() {
        long beyond = (1L << 53) + 1;
        assertEquals((double) (1L << 53), (double) beyond, "the double of 2^53 + 1 is the double of 2^53");
        assertTrue(1L << 53 > (1L << 53) - 1, "2^53 is the first integer past the safe range");
        assertEquals("9007199254740992", CanonicalJson.of(LongNode.valueOf(beyond)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.of(DoubleNode.valueOf(Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.of(DoubleNode.valueOf(Double.NaN)));
    }

    private enum Execution { EXECUTED, NOT_RUN }

    private enum Result { PASS, FAIL }

    private record Verification(Execution execution, Result result) {
        boolean proves() {
            return execution == Execution.EXECUTED && result == Result.PASS;
        }
    }

    @Test
    @DisplayName("DESIGN-STATE-002: NOT_RUN is not an empty success")
    void notRunIsNotAnEmptySuccess() {
        assertFalse(new Verification(Execution.NOT_RUN, Result.PASS).proves());
        assertTrue(new Verification(Execution.EXECUTED, Result.PASS).proves());
        assertFalse(new Verification(Execution.EXECUTED, Result.FAIL).proves());
    }
}
