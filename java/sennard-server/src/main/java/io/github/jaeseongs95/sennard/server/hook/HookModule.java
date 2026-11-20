// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook;

/**
 * The separately registered checks of one event, by the name a host's hook definition gives the registration in its URL ({@link HostEventHandler.HookCall#module()}).
 * A handler answers the calls of its own module and the calls of no module; the others are another handler's.
 */
public enum HookModule {
    /** The edit gate and the context checks of the continuity domain. */
    CONTINUITY("continuity", true),
    /** The tool gate that keeps direct inter-agent sends on the Sennard peer path. */
    NATIVE_PEER("native-peer", true),
    /** The held hook that waits for a session's messages while the host waits on other threads: it never refuses a tool, so it is no gate. */
    PEER_WAIT("peer-wait", false);

    private final String label;
    private final boolean gate;

    HookModule(String label, boolean gate) {
        this.label = label;
        this.gate = gate;
    }

    /**
     * Whether a tool-call hook registered under this module text is a gate, a hook whose refusal stops the tool: the registration of no module (Gemini CLI, Antigravity) and the
     * ones this enum does not list as holds (Claude Code's {@code governance}) check the call, so they are gates.
     */
    static boolean gates(String module) {
        for (HookModule known : values()) {
            if (known.label.equals(module)) {
                return known.gate;
            }
        }
        return true;
    }

    /** Whether a call's module text is the name of this module. */
    boolean isNamed(String text) {
        return label.equals(text);
    }
}
