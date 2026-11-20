// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.mcp;

import java.util.List;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.domain.Digests;
import io.github.jaeseongs95.sennard.domain.error.SennardException;
import io.github.jaeseongs95.sennard.domain.error.InputCode;
import io.github.jaeseongs95.sennard.domain.error.InvalidInputException;
import io.github.jaeseongs95.sennard.server.contract.CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Makes the {@link OperationKey} of a write tool's call (AGS3-D-0267) from the call's input: the operationKey the caller sent, the scope the key belongs to, and the digest of the rest
 * of the input in canonical JSON. The contract of each tool requires the key and fixes its shape before the tool runs (ContractGuard); the shape is checked again here, so a tool
 * that runs with a key of another shape is refused and not given a receipt. The server never makes a key.
 *
 * <p><b>The one digest.</b> What is not the caller's content is taken out of the input for every tool alike, here and nowhere else ({@link #digest}): the key itself, the session
 * reference (the scope already holds who called, and a host that attaches its session by itself sends none) and the response mode. Two calls of one tool with the same content have
 * the same digest whichever way they named their session.
 *
 * <p><b>The scope</b> is the calling session's server-issued reference, which the tool has already settled (the session the call is bound to), not anything the input says. A tool
 * that is called before its caller has a session ({@code open_session}) has the global scope ({@link #global}); a tool that settles no caller does not use this class's session form.
 */
@Component
public class OperationKeys {
    /** The members of a call that are not its content. */
    private static final List<String> NOT_CONTENT = List.of(OperationKey.FIELD, "sessionRef", "responseMode");

    private final JsonMapper mapper;

    public OperationKeys(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** The operation of a call of the session {@code session}; {@code input} is the tool's input record or its argument map, with the operationKey in it. */
    public OperationKey of(String tool, SelfSession session, Object input) {
        return make(tool, session.sessionRef(), input);
    }

    /** The operation of a call that has no session yet ({@code open_session}): the key is global to the store. */
    public OperationKey global(String tool, Object input) {
        return make(tool, OperationKey.GLOBAL_SCOPE, input);
    }

    /**
     * The digest of a call's content: the canonical JSON of the input without the key, the session reference and the response mode. The workflow tools, which read the key from the
     * arguments themselves, use it too, so that the digest is the same for every write tool.
     */
    public static String digest(JsonNode input) {
        if (!(input.deepCopy() instanceof ObjectNode content)) {
            throw new IllegalArgumentException("The input of a write call is an object.");
        }
        content.remove(NOT_CONTENT);
        return Digests.sha256Hex(CanonicalJson.of(content));
    }

    private OperationKey make(String tool, String scope, Object input) {
        JsonNode tree = mapper.valueToTree(input);
        Object sent = tree.path(OperationKey.FIELD).isString() ? tree.path(OperationKey.FIELD).asString() : null;
        if (!OperationKey.valid(sent) || !tree.isObject()) {
            throw new InvalidInputException(InputCode.CONTRACT_MISMATCH, SennardException.args("name", tool), SennardException.args("field", OperationKey.FIELD));
        }
        return new OperationKey(tool, scope, (String) sent, digest(tree));
    }
}
