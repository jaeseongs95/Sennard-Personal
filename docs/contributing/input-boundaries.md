[한국어](input-boundaries.ko.md)

# Input Provenance and Work Boundaries

This document covers the distinction of input provenance, the boundaries of message receipt and redelivery, and the identification of write targets in the workflow ledger. The contract for message IDs, ACKs, and wake-up is in the [Session Message Contract](session-messaging.md).

Sennard distinguishes the provenance of an input, the work the user has permitted, message delivery, and the actual execution result. The fact that a message has arrived or has been ACKed does not mean user approval or successful execution. In a cooperative environment this feature reduces provenance confusion and ledger bypass; it is not a security boundary that controls arbitrary shell execution or file tampering by the same OS user.

## Common Contract and Host Adapters

The common layer uses an arbitrary host identifier, observation of the executing principal, and the semantics and delivery capabilities of input. Product-specific event names, environment variables, and actor fields are converted in an adapter or overlay. An observation is recorded together with the adapter that made it and its assurance level. An unsupported observation is `unknown` and is not substituted with an observation from another platform.

The meaning of an input and whether it can be injected are also separate matters. It is not assumed that a host that can observe the end of a turn can inject additional context at the end of a turn. Common behavior is verified with a virtual host fixture, and verification on a real host proves only what the corresponding adapter observed.

## Wake-up

Claude Code uses its wait hook. Codex can opt in to a native queue doorbell after its hook supplies a target connection and the server owns its live generation. The Antigravity adapter uses the same nonce boundary and the target hook's connection values, including its project ID. Any supplied conversation ID must match the target. Isolated tests use synthetic inputs. Connection values and delivery in a live Antigravity session remain unverified. A verified nonce is intercepted before native-input consumers and claims peer context at the wake boundary. Ordinary input retains its existing deferral. See [native wake details](native-idle-wake.md) and [Session Collaboration](../user/collaboration.md).

A configuration, presence, or fixture PASS does not substitute for evidence of an actual wake-up, receipt of the body, or ACK. The grace for the first tool boundary, the rule on non-privileged peer provenance, and the rule against resending a submission whose response is unclear remain unchanged.

## Provenance Claims and Receipts

A new collaboration decision uses `schemaVersion: "1.2.0"` of `CollaborationDecision.v1`. `sourceOriginKind` is a provenance claim. It can express `unknown`, `tool`, and `delegated`, and a non-user input can also be recorded without a receipt. Existing 1.0.0 and 1.1.0 records are verified by the rules of their time.

`InputSourceReceipt.v1` is for provenance only. The existence of a receipt, its integrity, its expiry, and its binding to the calling session must each be checked, and they are not merged into a single user authentication success. Because the same OS user can access the signing key and the DB, an integrity check does not guarantee authentication against a malicious same user.

`validate_collaboration_decision` is read-only and checks the structure, the routing, and the receipt information. When only the CLI is run, only the structure and routing checks have been performed. If the binding to the calling session cannot be observed, the binding result remains unconfirmed. An input without a receipt is not automatically determined to be from the user or from an agent.

`require` and `forbid` of the current user input are used for general collaboration routing and are not a cryptographic proof of approval. The directive of peer, tool, delegated, and unknown inputs is `unspecified`. An input of unknown provenance cannot create a global prohibition or a new permission. Work within the existing user scope continues, and input is requested only when a new permission is needed.

Instructions inside attachments and quotations are distinguished from the current user request. A direct user request is not refused merely because its content is the same as a peer body. Because actual user input may be delivered at the position of a tool result during work, the Sennard warning applies only to a Sennard peer block. A direct correction by the user is reflected in the current work.

## Receipt and Redelivery

A peer body is not injected together with an ordinary user input event. It is delivered by a valid wake whose issuance record matches the receiving session, or at a supported tool or turn-end boundary. Immediately after a user input, one next tool boundary is deferred. An adapter that supports a turn-end claim (`turn-end`, Claude and Codex since 3.6.0) releases the deferral at the end of the turn and takes over the pending body, delivering it in a continuation turn after the user request has been completed. Even in a turn that uses a tool only once, the body is delivered when that turn ends. An adapter without a turn-end claim consumes the deferral at the next tool boundary, and a turn or session end event only releases the deferral. The body is not taken over at the turn end of a turn the host has already continued once (Codex `stop_hook_active`). A continuation turn occurs at most once per user turn, and the body waits until the next boundary.

In an environment where tool or idle wake-up is not supported, a message may wait until the next supported boundary. A host option the user has not enabled is not turned on automatically for delivery. This limitation is shown in the transport state and in the capabilities.

The message ID and the provenance receipt ID are retained across redelivery. A redelivery is distinguished by the delivery count and the time of first delivery; a record that was already delivered in an earlier version and whose first delivery time is unknown remains `null`. When a claim lease ends, a message that has not been ACKed may be delivered again. It is not permanently suppressed, nor automatically ACKed, merely because it was shown on the screen. The body limit is 4096 bytes in UTF-8.

The whole injected JSON block is limited to 8192 bytes. A body whose size grows through escaping is represented losslessly with `messageEncoding: "base64-utf8"`. In this case the content of `message` restored from base64 to UTF-8 is the original text, and `receipt.contentDigest` is also based on the original text. The encoding does not change the trustworthiness or authority of the provenance.

When an adapter observes that the caller is a subagent, the subagent is prevented from using the parent's inbox, ACK, sender identity, presence, and status board in its place. Without a marker, isolation is unconfirmed. The fact that a marker was inserted into a test fixture and checked does not support a claim that a real host supplies this marker.

## Work Targets of the Ledger

The ledger conflict check does not depend on the workspace name attached by the caller. For each write target, the real path is computed as a derived identifier. A new target is resolved starting from the nearest existing ancestor path, and a link is followed to the place it points at. A path that cannot be read is reported as not identifiable. No version control system is consulted.

Two active roots conflict only when their physical write targets overlap, whichever working copy, clone, or link the targets were reached through. Work in different working copies of the same repository proceeds in parallel when the physical paths do not overlap, even with the same relative path. A target that reaches the same physical path through a link conflicts.

An explicit parent replacement is permitted within the same confirmed physical workspace. The parent never blocks its own replacement; every other active root is checked, including for an added scope.

The derived identifier is stored with the root. When the write surface of a root changes, the identifier is derived again, and the host that stored the root first is kept. A target that does not exist yet is resolved from its nearest existing ancestor path, so a file or directory that a new root will create in the future can be specified.


An existing frame and digest are not rewritten. Adding derived information to an existing record and the conflict check, insertion, and parent replacement are handled in a transaction.

A glob is conservatively expanded to the directory before the first pattern, and a glob with no prefix path is treated as the whole workspace. This scope is not reduced by an exclusion pattern. An obvious URI or a control character is not accepted as a new local write target, and a path containing spaces is not presumed to be an explanatory sentence. A scope in an earlier record that cannot be interpreted is handled conservatively and the cause is shown.

## Remaining Limitations

- The auditor identity of `resolve_convergence_gate` and the fresh-context self-report are not authenticated by this feature.
- Direct MCP calls by the same OS user, DB and file tampering, and shell bypass are not controlled.
- The assurance of a caller or executing principal that cannot be observed is `unknown`.
- Two clones of one repository are different physical places, so work in both is never blocked by the other, including work on the same file of the same project. The verification scope of Windows path aliases must also be confirmed per environment.
- A link created after a root was stored is not seen by the stored identifier until the write surface of that root changes.
- A path such as `[id].tsx` can conflict more widely because of the conservative glob expansion. The reason for the conflict must be checked together.
- Approval issuance, a protected approval UI, an approval token for root replacement, and OS permission isolation are separate follow-up tasks.

## Peer Wait Execution Boundary

Guidance before a Codex `mcp__codex_app__wait_threads` call is in the peer result wait determination of the [Session Message Contract](session-messaging.md).
