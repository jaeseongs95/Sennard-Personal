// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.hookclient;

/** The address every hook of every host reaches the server at: one path scheme, so a host's own HTTP hook and the Sennard hook client post to the same endpoint. */
public final class HookEndpoints {
    /** The module of the idle wake's endpoint (see {@code WakeController}). */
    public static final String WAKE_MODULE = "wake";

    private HookEndpoints() {
    }

    /**
     * {@code <serverBase>/hooks/v1/<label>/<event>[/<module>]}. An address, not a file system path: its segments are separated by {@code /} on every operating system, which
     * {@link java.nio.file.Path} would not write on Windows, so it is joined as text (the client, which has no Spring context, builds it too).
     */
    public static String url(String serverBase, String label, String event, String module) {
        String path = String.join("/", serverBase, "hooks", "v1", label, event);
        return module == null ? path : String.join("/", path, module);
    }
}
