// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.service.session;

/**
 * What a stored session is, as far as the host has shown ({@code session_ref.kind}, holding the constant's name). Only {@code MAIN} sessions
 * are listed: the session list and the admin page's session binding are for main agents.
 *
 * <p>{@code CHILD} is a conversation a tool call's metadata named as a child of another; it is never changed afterwards, because a
 * conversation does not become a root. {@code PRESET_AGENT} is a session its host started with an agent chosen at launch (Claude Code's
 * {@code --agent}); a later hook of a main agent in the same session makes it {@code MAIN} again.
 */
public enum SessionKind {
    MAIN,
    CHILD,
    PRESET_AGENT
}
