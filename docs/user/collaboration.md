[한국어](collaboration.ko.md)
# Session Collaboration

Sennard sessions run on the same computer by the same OS user find each other through the status board and exchange PEER messages. Even with different hosts (Claude Code, Codex, Antigravity), the sessions use the same resident server and can therefore collaborate. The technical contract for message states and storage limits is in [Session Message Contract](../contributing/session-messaging.md).

## Status Board

- Use `update_session_status` to record in one line what this session is currently doing, and use `list_session_status` to see the host, session ID, working folder, one-line summary, and role of other sessions.
- Sennard identifies an Antigravity conversation created with `launch_managed_child` by the conversation ID returned at launch. For Antigravity's own subagents, Sennard learns the relationship from the first matched Sennard tool call. Before that call, a conversation without a summary or role can appear under "Undetermined" on the admin page. Claude Code identifies subagents (`agent_id`) and `--agent` sessions (`agent_type`) in the hook input.
- The board itself keeps no request text; the user's messages are kept in the prompt archive described below. The session ID and working folder are filled in from the hook input.
- For the role, the board shows the role of a binding signed on the admin page if one exists (`roleSource: "signed"`), and otherwise the role the session wrote for itself (`roleSource: "self"`). A role on the status board is neither an access permission nor an execution approval.
- The status board is pull-based; changes are known only by reading it again. It is not a channel for stopping the work of another running session.
- Ended sessions do not appear on the status board, even if they have a summary.

## What the User Said

- The hooks keep each message the user gives a main session, word for word, in the Sennard database on this computer. A subagent's prompts and the agent's answers are not kept. A message over 1 MiB is kept cut at a character boundary and marked as cut, with the size and SHA-256 digest of the whole message. The stored words cannot be changed or deleted by the service: its database role may only read and add them.
- Antigravity can confirm that a conversation is a main session when its first Sennard MCP call matches a recent tool hook for that same conversation. Until then, the server holds its input in memory for up to 30 minutes, subject to a shared 64 MiB limit. Confirmation archives the pending input with its hook receipt time. If the server restarts, the limit is reached, or the input expires before confirmation, the pending text may be lost. Confirmed child conversations are excluded from the archive.
- When the agent attempts to stop, it records what it did in one short summary with `record_turn_recap` (at most 100 Korean, Chinese or Japanese characters or 250 of any other kind; a mix is counted by weighted total). Only the main agent records it: a subagent's call is refused. If the recap is missing, the hook continues processing once per message to ask for it. The session's one-line summary on the session board is required too, because a person reads the session list to know what each session is doing now. While a main session has no summary, or its summary is older than its last message, the agent is asked to write it with `update_session_status` every time a hook can carry the request: when the message arrives, after each finished tool, after a finished model call, and at Stop. At Stop the summary request shares a continuation with the recap request, if one is pending. The summary request can continue processing only if the host has yet to continue it. The request stops as soon as the summary is newer than the last message. It never asks for the text of the request or any secret. A subagent follows the main session's summary. Recording again for the same message replaces the earlier summary. The summary is the agent's claim, not the user's word.
- `recall_user_prompts` reads the messages back, newest first, with the summary recorded for each. `query` finds a part of a message ignoring the case of the ASCII letters A–Z (other letters must match exactly), and `limit` is 20 by default and at most 100. With `scope: "SESSION"` (the default) it reads this session's messages; with `scope: "ROLE"` it reads the messages of every session the user bound to the same role as this one on the admin page. A role a session wrote for itself does not count, and a session with no signed binding is refused.
- `read_prompt_transcript` shows what the agent did after one of this session's messages, read from the host's own transcript: the agent's texts, its tool calls and what the tools returned, up to the user's next message. `promptId` is the one `recall_user_prompts` returned; `limit` is 20 by default and at most 100, and each text is cut at 2000 characters and marked cut. Only the session that received a message can read the transcript behind it: a message said in another session is refused, whatever the role binding. Sennard stores only the position of the message in the transcript, never a copy of the transcript, and reads it from the file the host named when it delivered the message. `status` is `LOCATED` (the entries follow; `more` is true when more entries follow for the message), `NOT_YET_WRITTEN` (the transcript has no entry for the message yet; ask again later), `UNSUPPORTED` or `UNREADABLE` (with a `reason`). Each entry carries a `key`; when `more` is true, pass the key of the last entry as `after` to read on from the entry that follows it (a key must identify an entry for that message). Claude Code supports transcript reads. For Codex, Gemini CLI and Antigravity the status is `UNSUPPORTED`.
- When a request leads to a decision or correction that later sessions need, the agent is asked to save it with `checkpoint_context` or `checkpoint_role_context`; the archive is the record of what was said, not a replacement for the shared context.

## Sending Messages

If you ask an agent, for example, "Find the session responsible for ○○ on the status board and request a review with a PEER message," the agent uses the following tools.

| Tool | What it does |
| --- | --- |
| `prepare_session_message` | Fixes the target, body, and TTL, and receives a system-issued `messageId`. It has no delivery effect. |
| `send_session_message` | Sends the message by passing only the issued `messageId`. |
| `get_session_message_status` | Checks the delivery and ACK status with the same `messageId`. |
| `acknowledge_session_messages` | The receiving side confirms that it has processed the message. |
| `recall_session_message` | Recalls one's own message that the receiving side has not yet retrieved (equivalent to unsending an email). |

The host must identify the active session before any of these tools can act. If you receive `BINDING_REQUIRED`, retry from the active host session once its hooks have run.

- A message records sending, body delivery, and processing confirmation (ACK) separately. **An ACK is only a confirmation that the message was processed; it is neither task completion nor execution approval.** Review results and evidence are received as a separate message.
- If the receiving session is offline, the sending session does not stop to wait and continues with work that does not need the reply.
- A message whose outcome is unclear is not resent with a new ID. Query the status or retry the send with the same `messageId`. Sending with a new ID can deliver one intent as two messages.
- The body is limited to 4096 byte in UTF-8. The TTL is 30 seconds to 24 hours, with a default of 1 hour.
- On the receiving side, a message is delivered at a tool boundary or a supported Stop event. Immediately after user input, delivery is deferred by one following tool boundary.

## Creating a Managed Child Conversation

From the active main session, call `launch_managed_child` with the model and reasoning effort that its host supports. When the connected host worker accepts the request, `CREATED` returns the new `childId`. Creation leaves the conversation idle. To send work, use the main session's host and session ID as the PEER destination and put the returned `childId` in `targetActorId`. The child sends its reply to the main session through PEER and acknowledges the messages it processed.

An Antigravity managed conversation uses the host's existing `agy` sign-in and permission rules. Its worker resumes it over the same ACP connection. Tool calls that require interactive approval are cancelled. Running PEER tools unattended requires the corresponding Antigravity permissions. See [Antigravity MCP permissions](https://antigravity.google/docs/cli/mcp/#mcp-permissions-and-access-control).

The caller receives `SELECTION_UNSUPPORTED` for an unsupported selection and `NOT_SENT` when the worker could not send the creation request. For `UNKNOWN` or a lost response, use the original `operationKey` and identical input to read the retained result. A new key can create another conversation.

## Waking an Idle Session

How an idle session is woken after a message is queued differs by host.

| Host | Default behavior |
| --- | --- |
| Claude Code | Two wait hooks are registered, on session start and on Claude Code’s `Stop`. Each hook waits for messages from the server when its event fires. When a message arrives, it wakes the idle session while delivering that message. When the session resumes work, the wait ends and messages are delivered at the boundaries below. A single wait lasts at most 24 hours. If the session has been idle longer than that, or if the Sennard server restarted in the meantime, it can be woken again at the next `Stop` event. This works only in an interactive session with a person attached; a session started with `-p` is not woken, and sessions started through the SDK have not been measured. The distinction relies on the value of `CLAUDE_CODE_SESSION_ATTENDED` that Claude Code passes to hooks, a variable that is not in Claude Code's official documentation (measured with Claude Code 2.1.319; in a separate measurement with Claude Code 2.1.316 on Windows, the value was `0` in a `-p` session and `1` in an interactive session with a person attached). |
| Codex | Opt-in native queue doorbell. Requires the target's fresh SessionStart environment, a native executable and a live generation owned by this server. Otherwise messages wait for an ordinary hook boundary. |
| Antigravity | The interactive main session uses `agentapi` with connection values from its own hook. The worker wakes its managed children over ACP. Messages use ordinary delivery boundaries when native wake is unavailable. |

An advisory `autoWake` is attached to `send_session_message` results and to `get_session_message_status`. The sessions on the status board do not carry it. It only reports the fact of whether the receiving session's wake path can be used now, and it is not evidence of delivery, processing, or completion.

- `available`: A wait hook is held, or the native target connection and live generation are ready.
- `degraded`: The target is active, prerequisites are missing, an attempt is outstanding, or retry backoff remains. It does not prove delivery.
- `unsupported`: The native queue option is disabled.
- `no-live-relay`: No usable target binding or owned live generation is known. The message remains queued for an ordinary boundary; this is not proof that the session is offline.

For Codex, set `AGENT_GOVERNANCE_CODEX_QUEUE_WAKE=1` to enable queue wake or `=0` to disable it. A present invalid or empty value does not fall back to a file. Without the variable, the target's absolute `PLUGIN_DATA/session-messaging.json` may contain `{"schemaVersion":"1.0.0","codex":{"queueWake":true}}` (4096-byte limit). The default is off. Reopen the target session after changing the setting or restarting Sennard so its hook supplies a fresh binding.

To wake an interactive AGY main session, its hook must expose `ANTIGRAVITY_PROJECT_ID`, a loopback `ANTIGRAVITY_LS_ADDRESS`, `ANTIGRAVITY_CSRF_TOKEN` and an absolute `ANTIGRAVITY_AGENTAPI_EXE` or a native `agy` on the exposed PATH. If exposed, `ANTIGRAVITY_CONVERSATION_ID` must match the target. Sennard uses values supplied by that hook and keeps tokens out of command arguments, database records and diagnostics. Check the marker arrival, message claim and ACK separately from the CLI handoff. See [native wake details and recovery](../contributing/native-idle-wake.md).

`status.wake` is always `null`.

## Trying It with Two Sessions

Make the following requests to two sessions that have the same project open. No code is changed.

```text
[Review session]
Record the current task on the Sennard status board as "Sennard-DEMO-REVIEW · Review of how to run tests".
When a review request arrives as a PEER message, read the referenced files yourself and check whether the explanation is correct,
leave an ACK, and reply with the result and evidence in a separate PEER message. Do not modify files or run tests.

[Investigation session]
Find how to run the tests for this project in its documents and configuration, and summarize the commands and the locations of the supporting files.
Find the session handling "Sennard-DEMO-REVIEW" on the status board, request a review with a PEER message,
and report the ACK and the actual review result separately. Do not modify files or run tests.
```

## Local Processes

Session messages are handled by the resident server, one per user per PC, and stored in the server's store. There is no message-specific process or per-session resident process. Session liveness is checked through a lease in which the server watches the host process. Network use by the host itself or by external model services is separate from this messaging structure.

## Limitations

- A program with the same OS user's privileges can read the server's DB credentials and read or modify the DB. A session identifier is not an authenticated user.
- A PEER message does not substitute for approval, permission, or intent to make external changes.
- There is no assurance that the receiving model executes the task in a message exactly once.
