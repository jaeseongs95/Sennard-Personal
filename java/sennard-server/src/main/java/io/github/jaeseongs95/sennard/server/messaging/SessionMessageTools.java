// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.server.messaging;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaeseongs95.sennard.domain.error.BindingCode;
import io.github.jaeseongs95.sennard.domain.error.BindingException;
import io.github.jaeseongs95.sennard.domain.messaging.ClaimReceipt;
import io.github.jaeseongs95.sennard.domain.messaging.PeerAddress;
import io.github.jaeseongs95.sennard.server.mcp.OperationKeys;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession;
import io.github.jaeseongs95.sennard.server.mcp.SelfSession.Provenance;
import io.github.jaeseongs95.sennard.server.entrance.ToolEntrance;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Basis;
import io.github.jaeseongs95.sennard.server.hook.HostContext.Role;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The session message tools, declared through the tool entrance ({@code ToolEntrance}), as the board's are: a name, an input record, the MCP hints and
 * a handler that calls the service with the calling session. The arguments arrive flat, as the public contract has them (contracts/*-session-message*-request.v1.schema.json,
 * published and enforced by ContractGuard); the record's constraints are checked by Jakarta Validation before the handler runs, and the rules of a message (the body, the time to
 * live, an acknowledgement's shape) are {@code MessageRules} of the domain, applied by the service. A refusal is an {@code SennardException} and the entrance answers it by its category:
 * a body or a time to live out of range is {@code INVALID_INPUT}, an unknown session {@code NOT_FOUND}, a full store {@code MCP_UNAVAILABLE} with {@code scope} and
 * {@code earliestReleaseAt} in its details, and a send the queue refused {@code MCP_UNAVAILABLE} with the sentence of the reason (see {@code SessionMessageService}).
 */
@Configuration(proxyBeanMethods = false)
public class SessionMessageTools {
    public record PrepareInput(String schemaVersion, @NotNull String operationKey, @NotNull @Size(min = 1, max = 64) String targetHost, @NotNull @Size(min = 1, max = 200) String targetSessionId, @NotNull String body,
            Integer ttlSeconds, @JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 200) String targetActorId) {
    }

    public record SendInput(String schemaVersion, @NotNull String operationKey, @NotNull String messageId) {
    }

    /** The two tool-input forms of a claim receipt. */
    public record Receipt(@NotNull String messageId, @NotNull String claimReceipt) {
    }

    public record AcknowledgeInput(String schemaVersion, @NotNull String operationKey, @NotNull List<@NotNull String> messageIds, List<@NotNull @Valid Receipt> receipts) {
    }

    public record StatusInput(String schemaVersion, @NotNull String messageId) {
    }

    public record RecallInput(String schemaVersion, @NotNull String operationKey, @NotNull String messageId) {
    }

    // @Bean: a List of the MCP SDK's SyncToolSpecification (library types), made by the tool entrance from the declarations below.
    @Bean
    List<SyncToolSpecification> sessionMessageTools(ToolEntrance entrance, SessionMessageService service, OperationKeys keys) {
        return List.of(
                entrance.tool("prepare_session_message", PrepareInput.class, hints(false, false, false),
                        (session, input) -> service.prepare(caller(session), input.targetHost(), input.targetSessionId(), input.targetActorId(), input.body(), input.ttlSeconds(),
                                keys.of("prepare_session_message", session, input))),
                entrance.tool("send_session_message", SendInput.class, hints(false, false, true),
                        (session, input) -> service.send(caller(session), session.host(), input.messageId(), keys.of("send_session_message", session, input))),
                entrance.tool("acknowledge_session_messages", AcknowledgeInput.class, hints(false, false, true),
                        (session, input) -> service.acknowledge(caller(session), input.messageIds(),
                                input.receipts() == null ? List.of() : input.receipts().stream().map(receipt -> new ClaimReceipt(receipt.messageId(), receipt.claimReceipt())).toList(),
                                keys.of("acknowledge_session_messages", session, input))),
                entrance.tool("get_session_message_status", StatusInput.class, hints(true, false, true), (session, input) -> service.status(caller(session), input.messageId())),
                entrance.tool("recall_session_message", RecallInput.class, hints(false, true, true), (session, input) -> service.recall(caller(session), input.messageId(), keys.of("recall_session_message", session, input))));
    }

    static PeerAddress caller(SelfSession session) {
        if (session.provenance() != Provenance.HOST_OBSERVED) {
            throw new BindingException(BindingCode.BINDING_REQUIRED);
        }
        var actor = session.actor();
        if (actor.rootEstablished()) {
            return PeerAddress.session(session.sessionRef());
        }
        if (actor.role() == Role.CHILD && actor.basis() == Basis.OBSERVED) {
            throw new BindingException(BindingCode.PEER_MAIN_SESSION_REQUIRED);
        }
        throw new BindingException(BindingCode.BINDING_REQUIRED);
    }

    private static ToolAnnotations hints(boolean readOnly, boolean destructive, boolean idempotent) {
        return ToolAnnotations.builder().readOnlyHint(readOnly).idempotentHint(idempotent).destructiveHint(destructive).openWorldHint(false).build();
    }
}
