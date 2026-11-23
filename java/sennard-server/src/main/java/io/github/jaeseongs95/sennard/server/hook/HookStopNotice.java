// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/** Defers the reason when a host can end its current input but cannot display the reason at Stop. */
public interface HookStopNotice {
    void rememberStop(HostId host, String session, String reason);

    String takeStopNotice(HostId host, String session);
}
