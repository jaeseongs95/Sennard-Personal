// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.identity;

/**
 * Lease lengths and how often the client renews. The server tells the client, in each response, when to renew. A lease is judged against
 * the service's clock only. This PC has one policy, {@link #LOCAL}; there is no other way to make one.
 */
public final class LeasePolicy {
    /** The starting values for this PC. */
    public static final LeasePolicy LOCAL = new LeasePolicy(15_000, 5_000, 600_000);
    /** A first lease is never shorter than the time a starting client needs. */
    public static final long MIN_FIRST_LEASE_MS = 30_000;

    private final long leaseMs;
    private final long renewAfterMs;
    private final long unownedLeaseMs;

    private LeasePolicy(long leaseMs, long renewAfterMs, long unownedLeaseMs) {
        this.leaseMs = leaseMs;
        this.renewAfterMs = renewAfterMs;
        this.unownedLeaseMs = unownedLeaseMs;
    }

    public long leaseMs() {
        return leaseMs;
    }

    public long renewAfterMs() {
        return renewAfterMs;
    }

    /** The lease of a generation with no owning client, extended by hooks and tool calls. */
    public long unownedLeaseMs() {
        return unownedLeaseMs;
    }
}
