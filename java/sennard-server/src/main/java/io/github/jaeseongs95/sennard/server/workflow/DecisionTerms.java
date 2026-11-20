// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.workflow;

import java.util.EnumSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The closed value sets of the independent-deliberation-panel skill's decision record, which the workflow gate and the canonical semantic check
 * both read. The skill's own contract and its checks spell the wire text, so each constant carries it ({@code @JsonValue}); the two lists of values
 * that used to sit in the semantic check as sets of text are constants of these enums.
 */
final class DecisionTerms {
    private DecisionTerms() {
    }

    /** The depth of the panel the record says it ran; the constant's name is the wire text. */
    enum Stage {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL;

        /** The two stages that carry the full reviewer, judge and strictness requirements. */
        boolean isHighOrCritical() {
            return this == HIGH || this == CRITICAL;
        }
    }

    /** How independent the panel's work was. */
    enum Assurance {
        INDEPENDENT("independent"),
        PARTIALLY_INDEPENDENT("partially_independent"),
        SINGLE_AGENT("single_agent"),
        PROVISIONAL("provisional");

        /** The assurances a run with a panel can claim. */
        static final Set<Assurance> PANEL = EnumSet.of(INDEPENDENT, PARTIALLY_INDEPENDENT, PROVISIONAL);

        private final String wire;

        Assurance(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    /** What the panel concluded. */
    enum ConsensusStatus {
        CONSENSUS("consensus"),
        CONDITIONAL_CONSENSUS("conditional_consensus"),
        NO_CONSENSUS("no_consensus");

        private final String wire;

        ConsensusStatus(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    /** Where a panel worker is in its lifecycle. */
    enum WorkerStatus {
        PLANNED("planned"),
        RUNNING("running"),
        COMPLETED("completed"),
        FAILED("failed"),
        SKIPPED("skipped"),
        REUSED("reused");

        /** The states of a worker that was started and so must be instantiated. */
        static final Set<WorkerStatus> LIFECYCLE = EnumSet.of(COMPLETED, REUSED, FAILED);

        private final String wire;

        WorkerStatus(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    /** A phase of the panel a worker took part in. */
    enum PanelPhase {
        ROUND1("round1"),
        CROSS_EXAMINATION("cross_examination"),
        EVIDENCE("evidence"),
        ADAPTIVE_SPECIALIST("adaptive_specialist"),
        REDELIBERATION("redeliberation"),
        FINAL_JUDGE("final_judge");

        private final String wire;

        PanelPhase(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    /** What a panel worker is. */
    enum WorkerClass {
        REVIEWER("reviewer"),
        JUDGE("judge"),
        ADAPTIVE_SPECIALIST("adaptive_specialist");

        private final String wire;

        WorkerClass(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    /** How far a source of a material claim has been checked. */
    enum EvidenceStatus {
        VERIFIED("verified"),
        UNVERIFIED("unverified"),
        REFUTED("refuted"),
        NOT_OBSERVABLE("not_observable");

        private final String wire;

        EvidenceStatus(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }

    /** Where an entry of the issue ledger stands; the constant's name is the wire text. */
    enum IssueStatus {
        CONFIRMED,
        REFUTED,
        PARTIALLY_SUPPORTED,
        UNRESOLVED,
        NOT_OBSERVABLE;

        /** An issue in this status stops the panel from reaching an unconditional consensus. */
        boolean blocksConsensus() {
            return this == UNRESOLVED || this == NOT_OBSERVABLE;
        }
    }

    /** What every text in the record's {@code observability} section says: the run's own facts Sennard cannot observe. The only value is the constant's name. */
    enum Observability {
        NOT_OBSERVABLE
    }

    /** Whether the panel cross-examined its reviewers' items. */
    enum CrossDecision {
        RUN("run"),
        SKIP("skip");

        private final String wire;

        CrossDecision(String wire) {
            this.wire = wire;
        }

        @JsonValue
        String wire() {
            return wire;
        }
    }
}
