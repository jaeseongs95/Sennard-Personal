[한국어](native-idle-wake.ko.md)

# Native idle wake

The server reserves one outstanding nonce per recipient and generation. The additive V23 migration adds `native_wake_reservations` and advances the messaging module to version 5. `wake_paths` records the boundary at which the peer message is claimed.

The installed hook client starts a local worker from the Sennard jar when the server echoes the registration key. Codex offers the key at `SessionStart`. Antigravity starts with an unclassified conversation. A Sennard MCP call establishes the actor when its conversation ID, tool and arguments match its preceding hook. Once the main conversation is established, it can register at `SessionStart` or at a continuable `Stop` that ends without a continuation. Repeated idle stops keep the current worker while it starts or its lease stays live.

The worker takes its target and configuration from the accepted hook and obtains a reservation marker through a loopback poll. A subsequent accepted session start replaces the relay. With an expired worker lease, queued messages remain available at ordinary claim boundaries. Claude Code uses the asynchronous hook response to wake its host.

The server treats a target as idle when the agent stops at an event where the host could have continued processing. A committed queue event can then dispatch against the locally watched owner and the live database generation. The server commits `STARTED` before offering the marker to one poll. The worker executes the native command directly and returns a fixed outcome for that marker. `SUBMITTED` records CLI handoff. A lost response after handoff, ambiguous nonzero exit, timeout or post-start failure leaves the outcome `UNKNOWN`. A confirmed non-send can be retried after five seconds at a later message or boundary.

The hook pipeline intercepts `[sennard wake:<uuid>]` before the ordinary input consumers. Consumption requires the live session and generation, the matching nonce and the current server incarnation. The transaction consumes the marker, claims the peer body and creates its source receipt. Replayed, retired, invalid and previous-server markers claim nothing. Normal native input retains its ordinary first-boundary deferral.

A body claim or ACK rearms the reservation. Normal native input can retire an uncertain attempt after 70 minutes. The server retains unobserved `STARTED`, `SUBMITTED` and `UNKNOWN` across restarts. Recovery cancels a reservation that never started and converts an older `STARTED` to `UNKNOWN` for the current owned session and generation. A server or session shutdown closes the relay and invalidates its capability.

## Host requirements

- Codex queue wake defaults to off. Set `AGENT_GOVERNANCE_CODEX_QUEUE_WAKE=1` to enable it, or `=0` to disable it. When that variable is absent, the worker can read `{"schemaVersion":"1.0.0","codex":{"queueWake":true}}` from `session-messaging.json` under an absolute `PLUGIN_DATA` directory. It reads at most 4096 bytes and requires a valid configuration. It resolves the native executable from the managed package directory or the hook's PATH and invokes `queue --thread <session> --message <marker>`.
- Antigravity resolves the native executable from an absolute `ANTIGRAVITY_AGENTAPI_EXE` or the hook's PATH. If present, `ANTIGRAVITY_CONVERSATION_ID` must match the observed conversation. For interactive main sessions, `agentapi send-message <conversation> <marker>` also requires `ANTIGRAVITY_PROJECT_ID`, `ANTIGRAVITY_CSRF_TOKEN` and a loopback `ANTIGRAVITY_LS_ADDRESS` using `localhost`, canonical IPv4 loopback or `[::1]`, with a port from 1 to 65535.
- For Antigravity managed children, the worker starts `agy acp` in the main session's workspace, checks the host's model and reasoning options and keeps ownership of the returned session ID for the life of the ACP connection. The first accepted hook uses the worker's existing binding. The ACP request contains just the reserved marker, and the server supplies peer messages through the invocation hook. ACP control exchanges have a ten-second deadline and inbound frames are limited to 1 MiB. The host applies its existing MCP permission rules and the worker cancels interactive permission requests.

Native `agentapi` invocations have a ten-second deadline and a 64 KiB output limit. The allowlisted host environment stays in the local worker and native child. Wake polls carry the capability, the target, the marker and the outcome. With missing prerequisites, delivery waits for an ordinary claim boundary.

After upgrading the server and hook client together, reopen the target session to register a new worker. An older server and a new hook client can still process session hooks, with relay wake unavailable until both are upgraded. Restore the matching server and hook client versions together to roll back, then reopen the target session. Confirm wake in each real host by observing the marker input, peer claim and ACK.
