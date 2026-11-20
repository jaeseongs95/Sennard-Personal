// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Jaeseong Song
// See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.

package io.github.jaeseongs95.sennard.serverit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.jaeseongs95.sennard.app.operation.OperationKey;
import io.github.jaeseongs95.sennard.domain.Digests;

/**
 * The caller's side of the {@code operationKey} (AGS3-D-0267): a workflow write tool refuses a call that has none, and the server makes none. A test that is not about the key calls
 * through a helper that gives the call a key of its own, as a client does for each operation; a test that sends the same operation again passes the key itself, and one that
 * tests the refusal of a missing or malformed key calls the tool without this helper.
 */
public final class OperationKeys {
    /** The tools of the workflow that change its state, which take an operationKey. */
    public static final Set<String> WORKFLOW_WRITE_TOOLS = java.util.stream.Stream.concat(
            Set.of("plan_workflow", "open_convergence_root", "claim_workflow_attempt", "start_guarded_workflow", "resolve_convergence_gate", "start_workflow",
                    "record_stage_result", "finalize_workflow", "abort_workflow", "pause_workflow", "release_convergence_root", "submit_change_plan").stream(),
            // The tool of every workflow kind takes a key (the kind's tool is written from the enum, so a kind that is added is here with it).
            io.github.jaeseongs95.sennard.server.workflow.WorkflowKind.toolNames().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** The other write tools that take an operationKey (the workflow's are {@link #WORKFLOW_WRITE_TOOLS}); a call to one of them through a helper gets a key. */
    private static final Set<String> OTHER_WRITE_TOOLS = Set.of("checkpoint_context", "purge_direct_context", "update_session_status", "prepare_session_message", "send_session_message",
            "acknowledge_session_messages", "recall_session_message", "record_turn_recap", "suppress_context_restore", "mark_turn_unchanged", "execute_state_cleanup",
            "bind_session_role", "checkpoint_global_context", "checkpoint_role_context", "open_session", "run_skill_check", "launch_managed_child");

    private OperationKeys() {
    }

    /** Every tool that takes an operationKey. */
    public static boolean takesKey(String tool) {
        return WORKFLOW_WRITE_TOOLS.contains(tool) || OTHER_WRITE_TOOLS.contains(tool);
    }

    /** A new key of the common shape ({@code ^[A-Za-z0-9_.:-]{16,128}$}). */
    public static String fresh() {
        return "test-operation-" + UUID.randomUUID();
    }

    /** The operation of one call of a use case that a test makes directly (not through a tool): the scope of the caller and a key of its own, so that no two calls share a receipt. */
    public static OperationKey operation(String tool, String scope) {
        String key = fresh();
        return new OperationKey(tool, scope, key, Digests.sha256Hex(key));
    }

    /** The arguments as they are for a tool that takes no key or that already has one; otherwise a copy with a new key. */
    public static Map<String, Object> withKey(String tool, Map<String, Object> arguments) {
        if (!takesKey(tool) || arguments.containsKey("operationKey")) {
            return arguments;
        }
        Map<String, Object> keyed = new LinkedHashMap<>(arguments);
        keyed.put("operationKey", fresh());
        return keyed;
    }
}
