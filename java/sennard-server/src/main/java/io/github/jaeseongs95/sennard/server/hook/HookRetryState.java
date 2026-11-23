// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/** Correlates a host retry with its input without storing the retry's text. */
public interface HookRetryState {
    void issue(HostId host, String session, String fingerprint);

    boolean consume(HostId host, String session, String fingerprint);

    boolean continued(HostId host, String session);

    void clear(HostId host, String session);
}
