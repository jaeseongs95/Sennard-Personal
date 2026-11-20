// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.continuity;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The statuses a state cleanup receipt carries ({@code StateCleanupService.CleanupReceipt}). The receipt contract spells the wire text, so each constant
 * carries it ({@code @JsonValue}).
 */
public final class CleanupStatus {
    private CleanupStatus() {
    }

    /** How the whole cleanup ended: it deleted something, only part of it, or there was nothing to delete. */
    public enum Receipt {
        COMPLETED("completed"),
        PARTIAL("partial"),
        NO_OP("no-op");

        private final String wire;

        Receipt(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** How one database's part of the cleanup ended. */
    public enum Database {
        COMPLETED("completed"),
        SKIPPED("skipped"),
        UNAVAILABLE("unavailable"),
        FAILED("failed");

        private final String wire;

        Database(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }
}
