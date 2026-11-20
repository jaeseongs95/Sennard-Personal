// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.archchecks.processfixtures;

import java.io.IOException;

/** Each way a class starts or reaches a process, in a place that is not on the list: the process rule must find every one. */
final class ProcessBad {
    Process builder() throws IOException {
        return new ProcessBuilder("tool").start();
    }

    long handle() {
        return ProcessHandle.current().pid();
    }

    Process runtime() throws IOException {
        return Runtime.getRuntime().exec(new String[] {"tool"});
    }

    ProcessBuilder.Redirect nested() {
        return ProcessBuilder.Redirect.DISCARD;
    }
}
