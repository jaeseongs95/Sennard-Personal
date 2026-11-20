// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.hook.host;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.jaeseongs95.sennard.server.hook.HookBody;
import io.github.jaeseongs95.sennard.server.hook.HostId;
import io.github.jaeseongs95.sennard.server.hook.host.HostInput.Text;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/** Publishes a native hook as an MCP tool. The registration supplies the host independently of the call arguments. */
public interface McpHookRegistration extends ServerContextOnly {
    HostId host();

    McpSchema.Tool tool();

    default Route route(JsonMapper mapper, HookBody body) {
        return body.bind(mapper, Route.class);
    }

    /** Names the event and optional hook module in the call. The host's adapter reads the remaining fields. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Route(@Text String event, @Text String module) {
    }
}
