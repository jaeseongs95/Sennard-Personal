// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.context;

import java.time.Clock;

import org.springframework.stereotype.Component;

/**
 * When this server process started, read from the {@link Clock} bean as the bean is made: Spring makes it while the context is built, before the web server opens a port,
 * so no request can come before it. The admin page's port is the operating system's choice at each start, so a signature a look-alike page on another localhost port
 * collected for an intent prepared by an earlier run could otherwise be committed after a restart that happens to land on that port. An intent issued before this start
 * belongs to a run whose page and port are gone: the commit refuses it, and the user prepares the change again on the page of this run.
 *
 * <p>The refusal itself is {@link AdminIssuedIntents}' (a restart empties what it holds). The instant is only read to say why a text is not held ({@link #precedes}): the wall clock
 * can be set back after the start, and an intent this process prepared then must not be refused because its instant is earlier than the start.
 */
@Component
public final class AdminServerStart {
    private final long startedAtMillis;

    public AdminServerStart(Clock clock) {
        this.startedAtMillis = clock.millis();
    }

    /** Whether an intent issued at this instant (epoch milliseconds) was issued before this server started. */
    public boolean precedes(long issuedAtMillis) {
        return issuedAtMillis < startedAtMillis;
    }
}
