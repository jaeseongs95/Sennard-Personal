[한국어](admin-page.ko.md)
# Admin Page and Contexts

The admin page is a local web page on which the user directly changes the Sennard settings that a person decides. Agents cannot change these settings; they can only leave requests. The user reviews a request on the page and approves it by signing with a passkey. This document describes how to use the page and the global, role, and situational contexts that the page manages. The format and verification procedure of the signed records are described in [Admin Signed Records](../contributing/admin-log.md).

## Opening the Page

- The page is a separate port of the resident server and can be opened while the server is running. Its port is not fixed and there is no setting for it: the operating system assigns a free port each time the server starts, so a program that holds a port cannot stop the server, and two servers started on one computer each have their own page.
- The current address is the `adminPage` value that `sennard status` prints. The messages that ask the user to decide something, such as a request to approve a root or to bind a role, carry the same address.
- A passkey is bound to the host name `localhost`, not to a port, so a passkey registered earlier keeps working when the page opens on another port. Signed records keep the port of the start that signed them, and they remain valid.
- The page is bound only to `127.0.0.1`. It cannot be opened from another computer.
- Always open the address with `localhost`. A passkey (WebAuthn) cannot use an IP address as the relying party ID. If you access `http://127.0.0.1:<port>/`, the page shows a notice explaining the reason and redirects to `http://localhost:<port>/`.

There is no separate server per host; there is one server per computer, so the page is the same regardless of which host it is opened from.

## First Use: Registering a Passkey

When you open the page for the first time, you register the first passkey. The first key signs its own registration, so it must be registered before any other program on the same computer does so. The personal edition uses one passkey, because the computer and its user are one: the server refuses to register another one while a passkey exists (`ADMIN_CHANGE_INVALID`, reason `SINGLE_PASSKEY`), and the only passkey cannot be revoked. The passkey screen shows the passkey with its fingerprint and registration time. If the passkey is lost, run `sennard admin-reset` on the computer and register a new one (see "Resetting When All Passkeys Are Lost" below).

## What the Page Changes and What It Only Displays

| Category | Items |
| --- | --- |
| Changed (passkey signature) | Global context, role registry (add, rename, delete), role context body (also written by agents of bound sessions), binding and unbinding sessions and approving binding proposals, first passkey registration (signed by the new passkey), JEV on/off (off by default), primary and fallback models, permission to send information, budget, presets, adding and removing provider keys (the page shows their fingerprints and stores the values encrypted), edit gate exception paths (absolute paths only), server JVM preset (applied at the next start), Resource limits (open workflows per session), Attachment storage (absolute storage directory, maximum file size in MiB and total attachment capacity in MiB), [convergence limits](#convergence-limits) (attempts per epoch and number of epochs for full attempts are fixed when a root opens. Each continuable Stop uses the current attempts-per-epoch setting for rechecks, including for existing roots), [open workflow release](#open-workflows-release-by-the-user), approval of requests to change a workflow's scope or end its work, **Sign and adopt** or **Discard** after a signed-record verification failure, [blocked workflow plan release](#blocked-workflow-plans), [business database link](#business-database) |
| Changed without a signature | Rejection of proposals to change the global context, bind a session to a role, or remove a session's role binding, rejection of workflow requests, page language (한국어 / English) |
| Display only | The registered passkey, JEV spending, JEV model list and refresh status, items sent to JEV, session status, change history, skill statistics, workflow statistics |
| Not on the page | Tool schema profile, host execution observation settings, state paths, JEV provider address and request format (built into the Sennard source), vendor route runtime and digest, wake method |

Before the business database is ready for the first time, only the passkey and business database tabs appear.

If the page shows a signed-record verification failure at the top, choose **Sign and adopt** when available to sign the recorded change again. **Discard** keeps the state currently in effect. Both actions require a passkey signature.

Before signing, the page shows what will change. If you press the passkey without reading the content, Sennard cannot prevent it.

## Skill Statistics

The skill statistics screen shows, by period, how many times each host called each Sennard skill. Skills with no calls are also listed with 0. The skills are listed in two groups, agent correction and specialist fields, each with its title row and its skills in order of calls; the group is the `category` in the skill's `classification.json`. The server retains skill call records for 90 days. Only the skill name, time, host, and session ID are stored; conversation content is not stored.

Each host counts by a different criterion, and the criterion is written under the host name in the table.

| Criterion | What is counted |
| --- | --- |
| Skill tool call | Use of the host's skill invocation tool |
| SKILL.md read | The number of times a Sennard `SKILL.md` was opened with a file tool or a read command, in the installation's `skills` folder or in the host's own installed copy of the plugin (the copy Codex or Antigravity reads from). Any other copy is not counted, and neither is a host whose installed copy cannot be located. A repeated read is counted again |

Skills opened in a way the host does not expose to hooks (for example, when the user invoked the skill directly by slash command or name and its body was inserted directly) and skills outside Sennard are not counted. On hosts that do not indicate subagents, subagent calls are also counted as main calls.

## Workflow Statistics

Under **Analytics → Workflow statistics**, you can see which kinds of workflow agents chose in a given period. The screen reads existing run records and shows these sections in order.

1. **Started by kind.** How many workflows of each kind started (answer or investigation, change, execution or operation), and how many of them started without skills. The kinds come from the kinds Sennard defines, so a kind added later appears here by itself.
2. **Recent workflows.** The list shows up to 50 of the most recent runs in the period. Search the list or filter it by kind, state or host. Expand a run for its ID and declared skills.
3. **Declared skills by kind.** Expand this section to see the skills declared for each kind and how many workflows declared them. The screen counts each kind separately because skills are optional for answers and investigations.

A workflow with no recorded kind (one started by the multi-step tools, or one whose record was cleaned up) is counted as a change; the screen says how many were counted that way. The screen is view-only, so no passkey is needed. The periods are the same as in the skill statistics.

## Global, Role, and Situational Contexts

Sennard has three kinds of context. There is no project-level context.

| Context | Recipients | Body limit (UTF-8) | Who changes it |
| --- | --- | --- | --- |
| Global | All sessions connected to Sennard on this computer and their subagents | 4000 byte | The user, by signing with a passkey on the admin page |
| Role | Sessions bound to that role and their subagents | 2000 byte | Sessions bound to that role (written by agents; not subject to signing) |
| Situational | That session and its subagents | 2000 byte | That session (`checkpoint_context`) |

- All three contexts are kept in the server's store. If nothing has been saved, nothing is injected.
- **Injection timing.** Claude Code and Codex report startup, resume, and completed compaction. Antigravity restores on a first model invocation reported as zero. In-request compaction on Antigravity is covered at the next eligible request boundary. At subagent start, Sennard can also send the parent session's situational context. Antigravity subagents receive their initial role context once the parent link is verified.
- **Roles and binding:** Who can access a role context is determined only by signed bindings. A binding can be requested only for a role in the role registry (otherwise `ROLE_NOT_REGISTERED`). Bindings are per root session, and subagents inherit the root session's binding. A binding is kept when the same session is resumed and is removed 30 days after its last use.
- The session status board (`list_session_status`) shows the role of a signed binding if one exists (`roleSource: "signed"`), and otherwise shows the role the session wrote for itself (`roleSource: "self"`). A role written by the session itself is for display only and is not an access key.
- The **Undetermined** group on the session binding screen shows Antigravity conversations whose relationship to a main session is still unknown and that have neither a summary nor a role. This can include an Antigravity subagent before its first matched Sennard tool call. Sennard identifies its own managed children from their launch IDs. Assign a role once you know a conversation is a main session. A conversation moves to **Ended** when the session ends. The page keeps nothing in the browser because its address changes at every start. See [Session collaboration](collaboration.md).
- **Saving a role context:** A bound session reads the latest body and `stateToken` with `load_shared_context`, rebuilds the entire body, and sends it to `checkpoint_role_context` together with `expectedStateToken`. If another session of the same role has saved in the meantime, the request is rejected with `STALE_REVISION` and nothing is written. The most recent 50 history entries are kept for each role.
- **Verification failure:** A target whose signature verification fails operates with its last valid revision (or the default if there is none). The hook inserts one line, "Sennard verification failed · rN in use · tell the user · agents cannot fix this", and the page shows a red banner on that target with [Sign and adopt] and [Discard]. Global and role contexts are read separately, so if one cannot be read, the other is still injected.

## Agent Requests and Approval

Agents leave only requests, using the following tools.

| Tool | What it does |
| --- | --- |
| `load_shared_context` | Reads the global and role contexts. |
| `checkpoint_global_context` | Requests a change to the global context. |
| `bind_session_role` | Requests that this session be bound to a role. |
| `checkpoint_role_context` | A bound session saves the role context (saved directly, not as a request). |

The request tools return `pending`, `proposalId`, and `adminUrl`. If the same session submits a new request for the same target, it replaces the previous request. A request takes effect only after the user reviews the difference on the page and approves it with a passkey. Rejecting a request does not require a signature, and decided requests are deleted after 30 days.

### Root Approval

When user approval is required to replace an existing root with `open_convergence_root` (`replace-root`) or to release one with `release_convergence_root` (`release-root`), the tool returns `details.reason=USER_APPROVAL_REQUIRED`, the page address (`adminPage`), and the `scopeDigest` of the approval scope while there is no approval. After the user checks the displayed action, root, actor, and scope on the page and approves with a passkey, making the same call again reuses that approval and executes it. An approval applies only to the same action, root, actor, and scope, and arguments passed by a model alone do not constitute approval.

## Open Workflows: Release by the User

An open workflow keeps other workflows out of the files it covers until it is released. If the session that opened it ends suddenly, you can release it from **Work → Workflow management**. The screen lists open workflows, including paused workflows and those waiting for review or user input. Search the list, filter by state or owner, or sort by last activity. Each entry shows the workflow state, objective, owner presence and last activity. Expand the details for the root ID, most recent run ID and creation time. The screen uses your browser’s time zone. The owner appears as a short opaque reference, with the host when its session is online.

- **Releasing needs your passkey.** The signature covers the workflow, the revision the page showed, and your reason. A workflow that is gone, already closed, or changed since the page was loaded is refused before the passkey is asked, and again if it changed before the signature arrived; in that case nothing is recorded.
- **What a release does.** The workflow is closed and cannot be continued, and a run still in progress in it is closed as blocked. A new workflow can then cover the same files.
- **An owner that is online.** The page warns you first. The release is still allowed, because only you can know whether the work should stop, and the release records that the owner session was online.
- **What is recorded.** The release names the admin page as the releaser, your reason, the time, the owner's presence as it was read, and the approval record, which names the signed record and the passkey. The same data is shown by `get_convergence_status`.
- **Nothing releases a workflow by itself.** There is no timeout. A session can release only its own workflow directly; no tool releases a workflow that is not its own without your decision.

## JEV: Skill Selection Advice

JEV provides advice for agent decisions. This version uses it to suggest skills automatically. Agents choose which skills to use.

When JEV can be called (every condition below is met), the agent does not have to ask for advice. The server starts the classification itself when a user prompt arrives, sends the prompt and the skill list to JEV under the signed permission to send, and does not make the hook wait for the answer. When the answer is ready, the next hook of the same session (after a model call or a tool) adds a few lines once: the skills JEV judged needed with its confidence, at most 8, never the prompt or the whole list. The agent can still call `classify_skills`; for the same prompt it receives the answer already obtained and JEV is not called, or paid, a second time. When JEV is off, a condition is unmet or the budget is spent, nothing is sent and nothing is added or logged; only a call that was made and failed is written to the server log.

JEV is managed entirely by the server and this page. Provider definitions (call address, model list address, request format, key name) are included in Sennard, and the only provider in this release is TypeSafe. Installation configuration files and environment variables are not used for JEV.

### Conditions for Using JEV

The JEV tab shows the status of automatic skill advice using the saved settings. Turn on **Use automatic skill advice**, configure the model, provider key and budget, allow transmission, and save with your passkey. If setup is incomplete after saving, the page shows what is missing. Both the page and the server use the following conditions.

1. Automatic skill advice is turned on (off until configured and saved)
2. A model is selected
3. "Allow sending information to JEV" is on
4. The key of the selected model's provider (for TypeSafe, `JEV_API_KEY`) is registered on this page
5. The budget limit is at least the reserved amount for one call and has a remaining balance

### Item Descriptions

- **Model:** Select it on the page and sign. The list is retrieved from the provider's model list address and refreshed every 24 hours (1 hour later on failure). If there is no key, the list included in Sennard is shown. A change in the list does not automatically change the stored model; if the model is removed from the list, it remains displayed as "(not in the list)".
- **Allow sending information to JEV:** This item is signed separately. If it is not allowed, JEV is not called. The items actually sent can be checked under "Show the information sent in detail". For each task, the user's original request (`originalPrompt`), the confirmed task context (`confirmedContext`), the revision, references, and rules of the classification criteria, and the model name are sent; for each installed skill, the ID, revision, description, whether it is used, installed, and supported by the host, capabilities, actions, targets, constraints, apply and exclude conditions, dependencies, and stages are sent. Skill source file paths and digests (`sourceRefs`) are not sent. The key is not included in the body and is sent only in the `Authorization` header.
- **Budget:** Only the signed limit is used. There is no separate cap on the server side. If it is left empty, JEV is not called. For each call (and for each try of a call that is tried again), the maximum cost (about 0.0110 USD) is reserved in advance, and the reservation is released when the call is over. A call that was answered is recorded with an estimate calculated from the token counts in its response; a call that got no answer reported no tokens and is not counted. JEV responses do not include the billed amount, so spending is an estimate. The estimated running total is kept in the store, is shared by all Sennard servers of this PC, and continues after the server restarts; for each call the reservations of the calls in flight are added to this total and compared with the limit.
- **Automatic skill advice:** The switch enables JEV and the built-in skill classification preset together.
- **Fallback evaluator:** Optional; choose a model (of any installed provider) other than the selected one and sign. It is asked when the selected model cannot advise for a reason that asking it again would not fix: no key, a refused key (HTTP 401 or 403), an answer in the wrong form, another HTTP 4xx, or an input or answer that is too large. JEV can switch to the fallback evaluator once per request and gives it the same information. Communication failures at the fallback evaluator can trigger retries at that evaluator. It needs a selected model and must differ from it.
- **When JEV cannot evaluate.** Communication failures (connection failures, interruptions, timeouts before an answer, HTTP 429 or 5xx) can trigger retries at the same evaluator. JEV uses a valid `Retry-After` delay when supplied and otherwise uses an increasing delay. The request deadline (`sennard.jev.retry.deadline`, 60 seconds by default), the budget for each attempt, and the validity of the request limit further retries. A call already in progress is allowed to finish. If a failure is eligible for fallback, JEV tries the configured fallback evaluator. If no evaluation yields advice, the agent makes the selection.
- **Key:** For the JEV key of a provider (`JEV_API_KEY` for TypeSafe), only the value registered on this page is used. An environment variable with the same name is not used for JEV. The page shows only the fingerprint, and the value is stored encrypted.
- **Display-only information:** JEV spending, model list and refresh status, and items sent to JEV.

## Edit Gate Exception Paths

- **Edit gate exception paths:** Only the list signed on this page is used. No environment variable or server setting adds an exception path. A saved list applies from the next edit. If the exception paths cannot be read, no exceptions are added.
- **Only the user sets the exception paths.** The signed list is the one saved on this page with a passkey; no MCP tool lets an agent change it. The default is an empty list, which adds nothing.
- **How to write a path.** A Windows path (`D:\work\notes`), a POSIX path (`/home/me/notes`) and a Git Bash drive path (`/d/work/notes`) are accepted. A path with no drive, such as the `/tmp` that Git Bash uses on Windows, is written as that very string. It is compared as text with the paths the Git Bash shell writes, not as a path of the file system.
- **How a path is matched.** The match is on whole path segments, so `/tmp` covers `/tmp/a` and does not cover `/tmpx`. `..` is resolved before the comparison, so `/tmp/../etc/a` is judged as `/etc/a`, and a `..` past the root stops at the root.

## Convergence Limits

- **Two values bound how often one piece of work may be repeated.** A workflow allows a number of full attempts in each epoch, and a number of epochs. When the attempts of an epoch are used up, a further full attempt needs an independent review first; after the last epoch, a review alone no longer renews the budget.
- **The defaults are 3 attempts per epoch and 2 epochs.** They apply while nothing is signed on this page.
- **Each value is a whole number of at least 1, with no upper bound.** Zero, a negative number, a decimal number and text are refused before a signature is asked for.
- **A workflow’s full-attempt budget is fixed when its convergence root opens.** The server puts the saved attempts-per-epoch and epoch-count values into the new root’s frame, replacing values supplied by the agent. The workflow status shows the fixed values.
- **Stop rechecks use the current attempts-per-epoch setting.** At each Stop the host can continue, the server reads the current setting to check a workflow that still fails its checks or an input without a selected gate. Each has its own recheck count, separate from the workflow’s full-attempt budget.
- **Only the user sets the limits.** The setting is the one signed on this page with a passkey; no MCP tool lets an agent change it.

When Sennard registers Claude Code hooks, it sets `env.CLAUDE_CODE_STOP_HOOK_BLOCK_CAP` to the string `"0"` in the Claude Code user `settings.json`. This disables Claude Code's separate cap on consecutive Stop-hook blocks. If a different value is already present, Sennard shows it in a confirmation dialog, or prompts in the terminal if a window is unavailable. Setup stops before writing files if you decline or Sennard cannot receive a response. The setting remains after uninstall if the Claude Code user `settings.json` is outside the Sennard installation root. If the file is directly in the installation root or in one of its subdirectories, uninstall deletes it along with the root. To undo the change in a retained file, remove or edit `CLAUDE_CODE_STOP_HOOK_BLOCK_CAP` under `env`.

## Blocked workflow plans

In **General settings**, this list appears below the convergence limits. The page displays the message “The same plan cannot be submitted again for the same workspace and workflow kind until you unblock it.”

Each entry shows **Workspace**, **Workflow kind**, **Plan SHA-256**, **Blocked workflow**, **Reason**, and **Blocked at**. To allow the plan to be submitted again, select **Unblock with passkey** on its entry and confirm with a passkey.

## Server JVM Preset

- **One setting chooses the collector and the heap.** The server's JVM is configured by a preset: G1 with a heap of 224 MB, 512 MB, 1 GB, 2 GB or 4 GB, ZGC with a heap of 4 GB, or ZGC with a heap of about 6% of the memory. There is no field for a JVM option; the page offers these values only, and no signed change can name anything else.
- **The installer chooses the first value, once.** `sennard install` reads the machine's total memory from the JVM one time, rounds it up to the smallest standard size (4, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256 GB and so on) and takes the preset of the largest class not above that size: up to 4 GB G1 224 MB, 8 GB G1 512 MB, 16 GB G1 1 GB, 32 GB G1 2 GB, 64 GB G1 4 GB (ZGC with 4 GB can be chosen), 128 GB and more ZGC with about 6% of the memory. A size between two classes (12, 24, 48, 96 GB) takes the class below it. The server never measures memory when it starts.
- **The memory is shown as an estimate.** The JVM reports what the operating system gives it, which is less than the RAM installed, so the page says, for example, "Estimated memory 32GB (calculated from the 29.7GB capacity confirmed by the JVM. It may differ from the RAM actually installed.)".
- **Saving applies at the next start.** The signed preset is written beside the server (`sennard-jvm.yaml`) after the signature is recorded; restart the server (`sennard stop`, then `sennard start`) to use it. An upgrade carries the file along. After a reinstall, the first start uses the installer's choice, and the server writes your signed preset back as soon as its store is ready, so the next start uses it.
- **The server's JVM also runs in UTC and writes its garbage collection log** (`jvm-gc.log`, rotated by the JVM, five files of 10 MB) and its AOT cache log (`jvm-aot.log`) in the installation folder. The AOT cache is made for the collector the installer chose; after a change of collector the server starts without a cache (and `jvm-aot.log` says so) until the next upgrade makes one. The `sennard` command is the user's own `java -jar` run and does not follow the preset.

## Business Database

The **Business database** screen shows the state of the store (waiting for a database, connecting, preparing the tables, ready, failed), the reason of the last failure, and the saved link. It is also where you link the database: the JDBC driver, the URL, the account, the password file and the protection (see [Business Database](state.md#business-database) in the state document for each item). The link is the one source of the business database: it is kept in the settings database of the state folder, and `sennard.yaml` does not name it.

- **The first link.** Register the first passkey, then save the link with it. Sennard connects in the background and prepares the tables; the screen follows until the store is ready or failed. A failed link can be replaced by another one from the same screen.
- **Changing the link.** You can change it at any time, also while the database is ready, by saving a new link with your passkey. Before it is saved, Sennard connects to the database the new link names and prepares its tables there; if that fails, nothing is saved and the old link stays. The running store is not swapped: the saved link is used after you restart the Sennard server (`sennard stop`, then `sennard start`), and the screen says so, with that next step, until then. The records of the previous database are not copied to the new one.

## Language

The Sennard text read by models is fixed, without a language setting, to the single form with the fewest tokens for each text. Hook notices, MCP tool descriptions and results, skill check output, and skill descriptions are in English. The common intake criteria in the MCP instructions are in Korean (English uses fewer tokens, but exceeds the length at which hosts truncate instructions). Agents write the answers shown to the user in the user's language. The admin page follows the language of the request: it is shown in English or in Korean according to the browser's language preference, and in English for any other language or when the browser states none. This applies to every screen, to the page that moves a visit of the loopback address to localhost, and to the page title.

- The language switch in the page header (한국어 / English) chooses the language of the page. The choice is kept in a cookie of the page (`sennard-admin-language`, `en` or `ko`, `SameSite=Strict`, not readable by a script, holding nothing else) and is followed before the browser's language; with no choice the browser's language is followed. Changing it draws the page again; if you have unsaved edits the page asks first, and nothing is changed if you decline. It is not a signed setting of the server.

## Resetting When All Passkeys Are Lost

A reset cannot be performed over HTTP or MCP. On the same computer, run `sennard admin-reset` as the OS user who installed Sennard. When run without `--yes`, it only shows what will be archived and writes nothing; `sennard admin-reset --yes` performs the actual reset. Both open the settings database, which a running server holds, so the command first stops the server of this installation if it is running (and says so in its output; nothing is stopped when no server is running). The server starts again at the next hook call. If the server is still shutting down (its port closes before it releases the settings database) or has already started again by the time the command opens the database, it ends with `ADMIN_RESET_SETTINGS_LOCKED` and changes nothing; run it again.

There are two cases, and the command picks the one that applies by a single fact: whether the business database has ever been ready.

- **Before the business database has ever been ready** (the passkey was lost after it was first registered and before the first successful connection, including after a link that failed): the signed records are only in the settings database under the state folder, and there is no business log yet. `sennard admin-reset` needs no business database then, which may not be set at all. It empties the passkey registry and moves the records made before the link to the archive of the settings database. What authorizes it is that the settings database is a file of the OS user who installed Sennard. The link settings saved from the page are not signed records and stay, so you can link again after registering a new first key.
- **After the business database has been ready once:** the log is also in the business database and has to be archived too, so the command needs the business database and its account's password file. If the database cannot be reached, the command ends with `ADMIN_RESET_DATABASE_UNREACHABLE`; reach the database and run it again.

- The entire record is moved to an archive table and the fact of the reset is recorded. Pending requests are processed as rejected. The JEV key registered on the page is also deleted.
- All items decided by a person return to their default values. A reset does not grant any permission; a first passkey must be registered again before anything can be saved.
- The fact of the reset remains visible at the top of the page.

## What Is Not Prevented

The following are deliberate attacks by programs (including agents) running as the same OS user. This design does not prevent such attempts; it only makes them visible.

- **Taking the first passkey first:** If no key exists and a program on the same computer registers first, that key becomes the first key. If a key already exists, a new first registration is rejected with `ADMIN_ALREADY_BOOTSTRAPPED`.
- **Reset followed by a new first key:** A program running as the same OS user can run `sennard admin-reset --yes` without a passkey. The signed record is archived, every signed item returns to its default, pending requests are processed as rejected, and the JEV key is deleted. The program can then register its own key as the first key. The fact of the reset remains visible at the top of the page.
- **Patching installation files:** Modifying Sennard installation files can bypass verification itself.
- **Replacing the entire record:** The same OS user can read the DB credentials, replace the signed record entirely, and plant their own key as the first key. Because the initial registration time and fingerprint change, this becomes visible on the page.
- **Truncating the tail:** If the last few records are deleted, an earlier revision appears valid. The resident MCP server reports `ADMIN_LOG_REGRESSED` if the record becomes shorter than what it has seen, but it cannot detect this after that server restarts.
- **Reading the JEV key as the same OS user:** The key is stored encrypted, but the data key file is also in the same user folder, so reading both allows the value to be decrypted. Encryption protects only against the store contents being copied or read on their own.
- **Rejecting requests:** A program on the same computer can reject pending requests. No setting changes as a result.
- Role context bodies are written by agents and are therefore not subject to signing.

Viewing (`GET /`, `GET /api/state`) is available without a passkey. Users and agents on the same computer can read the store directly, so blocking viewing would provide no benefit. Reading or writing by other web pages is blocked by Host and Origin checks and similar measures.
