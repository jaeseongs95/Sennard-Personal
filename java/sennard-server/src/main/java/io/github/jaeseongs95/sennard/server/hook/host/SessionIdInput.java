// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import tools.jackson.databind.JsonNode;

/**
 * The fields of a hook's stdin that two hosts share ({@code session_id}, {@code hook_event_name} and the event's own): what {@link SessionIdSchemaAdapter}
 * reads. A text is null when the host left it out, and the empty text when the host wrote it as something else (see {@link HostInput}).
 */
interface SessionIdInput {
    String sessionId();

    String transcriptPath();

    String cwd();

    String workspaceId();

    String model();

    String turnId();

    String prompt();

    String source();

    String reason();

    String toolName();

    String toolUseId();

    /** The tool's arguments: any tool's, so the host's tool defines them. */
    JsonNode toolInput();

    /** What the tool returned: any tool's, so the host's tool defines it. */
    JsonNode toolResponse();

    /** Why a tool or a compaction failed: the host writes a text or an object. */
    JsonNode error();

    Boolean stopHookActive();
}
