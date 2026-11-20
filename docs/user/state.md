[한국어](state.ko.md)
# State Storage and Cleanup

This document describes where Sennard keeps its state on this computer, the settings that change that location, how to delete old state, and the recovery procedure.

## Storage Location

Starting with 6.0.0, Sennard stores its settings in a local H2 database and business state in a separately configured database. All hosts on the same PC use the same server and its configured stores.

The default state folder is `~/.sennard`, where `~` is the `user.home` of the JVM (the same question on every operating system; there is no per-OS location). To change it, set the environment variable `SENNARD_STATE_DIR`, install with `sennard install --state-dir <path>`, or set `sennard.state-dir` in UTF-8 in the installed `sennard.yaml` (location below). A relative installation path is recorded as an absolute path from the command's working folder. A path that cannot be written in the file name encoding of the system (`native.encoding`) is refused with `STATE_DIR_NOT_REPRESENTABLE`; set `SENNARD_STATE_DIR` or `sennard.state-dir` in `sennard.yaml` to a folder that can be written in it. The server announces its address in `server.properties` in this folder (see [Troubleshooting](troubleshooting.md#resident-server)); only one server runs on a state folder.

| Data | Location (relative to the state folder) |
| --- | --- |
| Local settings database (passkeys, the link of the business database) | `settings/sennard-settings` (H2 file database) |
| Session and JEV key material | `service/session-pepper.json`, `service/session-pepper.key`, `service/jev-seal.key` |

The business database is separate from the state folder. The installation folder (`~/.sennard/install` by default, `SENNARD_INSTALL_ROOT` changes it) contains the program and its configuration. If it cannot be written in the file name encoding of the system, `sennard install` ends with `INSTALL_ROOT_NOT_REPRESENTABLE`.

## Business Database

Sennard does not ship a database driver and does not prescribe a database product. You provide the business database and its JDBC driver, and Sennard connects with them. You link the database on the admin page (the **Business database** screen; see [Admin page and context](admin-page.md#business-database)), not in `sennard.yaml`. The screen asks for:

- **JDBC driver**: the driver you obtained from the database vendor, as the absolute path of one jar or of a folder of jars. Sennard reads the driver from that path only; it does not look for it elsewhere.
- **JDBC URL**: the URL of the database, in the form `jdbc:<subprotocol>://host[:port]/database[?options]`, for example `jdbc:mariadb://127.0.0.1:3306/sennard`. It does not contain a password; a URL that carries one is refused.
- **Account**: the one account Sennard uses. It owns the schema `sennard`, and Sennard creates and migrates it itself.
- **Password file**: the absolute path of a file whose first line is the account's password. Only the path is saved, never the password. Sennard reads the file only if it is a regular file with at least one byte (a link to such a file is followed). Keep it readable by you only: Sennard does not check who else can read it (see "Private Storage" below).
- **Protection**: plain text (loopback address only) or server verification (TLS), as the TLS rule below requires.

Saving the link needs your passkey. The link is kept in the local settings database of the state folder (`settings/sennard-settings`), which is the one place the business database is named: the server, `sennard admin-reset` and `sennard attachments` all read it there. Because it is in the state folder and not in the installation folder, it stays after `sennard uninstall` and is found again by a later install or `sennard upgrade`. A `sennard.store.database.*` key in `sennard.yaml` is not read; if an earlier installation had one, link the database again on the admin page.

You can change the link at any time on the same screen, also while the database is ready. Before the new link is saved, Sennard connects to the database it names and prepares its tables there; a database that cannot be reached or does not pass those checks is not saved, and the old link stays. A link saved while the store is ready is used after the Sennard server is restarted (`sennard stop`, then `sennard start`): the screen says so until then. The records of the previous database are not copied to the new one.

`sennard attachments check`, `rekey`, `keygen` and `purge-orphans` read the link from the settings database, which a running server holds: run `sennard stop` first (the command says so otherwise). `sennard admin-reset` stops the server by itself.

Supported database products. The tests of this repository verify PostgreSQL only; MariaDB is supported through Sennard's SQL dialects (`SqlBuilder`) and the TLS rule below, and is not verified by those tests:

| Product | Example URL | TLS option Sennard requires for a host that is not a loopback IP address |
| --- | --- | --- |
| PostgreSQL | `jdbc:postgresql://127.0.0.1:5432/sennard` | `sslmode=verify-full` and `sslrootcert=<file>` |
| MariaDB (current LTS) | `jdbc:mariadb://127.0.0.1:3306/sennard` | `sslMode=verify-full` |

A product that is not in the table is not verified either. MySQL URLs are accepted (`sslMode=VERIFY_IDENTITY` for a remote host), and the other products Spring Boot can recognize from the URL are accepted on a loopback IP address only; neither has been tested.

Notes:

- A URL without a TLS option is accepted only when its host is a loopback IP address literal (`127.0.0.1` or `[::1]`). The name `localhost` is not one.
- H2 cannot be the business database: it holds only the local settings. A URL that names H2 is refused.
- MariaDB and MySQL: create the database with `CHARACTER SET utf8mb4 COLLATE utf8mb4_bin`. Sennard compares identifiers and states by exact text, which a case-insensitive collation does not. The account needs the right to create the schema (database) `sennard` and full rights on it.
- Each version applies its own schema scripts at start. Back up the database before an upgrade.

`sennard.yaml` is not in the root of the installation folder. It is in the folder of the installed version, `versions/<version>` under the installation folder, beside the application jar, on every operating system: `<installation folder>/versions/<version>/sennard.yaml`. For example, with the default installation folder and version 6.0.0 it is `~/.sennard/install/versions/6.0.0/sennard.yaml`. `sennard upgrade` copies `sennard.yaml` from the old version folder to the new one.

Sennard makes the state folder private to its owner when it makes it: the installer, a session start and the server each create it through the same place, before anything is written in it (see Private Storage below). A state folder that already exists is used as it is, and the personal edition trusts the whole PC.

## Private Storage

The state folder, the key and pepper files in it, the folder of the settings database and the business database's password file are made and read through one place in Sennard. The personal edition follows these rules:

- **What Sennard creates is private to its owner from its first moment.** On Linux and macOS a folder is `rwx------` and a file `rw-------`. On Windows the folder or file gets an access list that does not inherit from its parent: full control for the owner (and for the system account on a folder).
- **On Windows the owner in that list is the user the process runs as, taken from the process's access token and not from a name setting.** Sennard makes an empty probe file in the folder it is about to create in, takes the owner Windows gives it (a principal made from the SID, so a local and a domain account of one name, or an Azure AD account, are not confused) and deletes the probe. A process started with administrator rights may have the Administrators group as the owner of what it creates; only then does Sennard look the account up by name and check that the account found is the one of the token. If the system cannot tell which account that is, Sennard stops with `PRIVATE_STORAGE_USER_UNKNOWN` and creates nothing, instead of granting another account: start Sennard without administrator rights, or make the folders yourself and Sennard will use them.
- **What already exists is read as it is.** Sennard does not refuse a path because the group, others or another account may use it, because another user owns it, or because it is a symbolic link (a link is followed), and it never changes the permissions of an existing path.
- **The checks that remain are checks of the data.** Sennard refuses a folder where a file is needed, a file where a folder is needed, and an empty secret file, and it names the path in the message (`services.privateStorage.*`).

The reason: the personal edition is for one user on one PC and trusts the whole PC. It does not try to keep another OS account of the same PC out, so it has no device for that and does not stop on a path that another account could reach. If other people have accounts on the PC and the secrets must be kept from them, set the permissions of the state folder and of the files yourself (for example `chmod 700` and `chmod 600` on Linux and macOS, or `icacls <path> /inheritance:r /grant:r "%USERNAME%:F"` on Windows); Sennard will work with them.

## What Is Stored

- The workflow store contains the entire `WorkflowReceipt` as plain-text JSON. This includes the provider output, evidence notes, findings, blockers, and error of each stage result.
- Call `list_session_workflows` without arguments to list your session's retained runs and active roots. `runs` includes finished attempts, and `openRoots` includes roots waiting for review or a user decision. Each entry includes its ID, revision and state. Use the IDs with `get_workflow_status` and `get_convergence_status` for details. Empty arrays are a successful result. Caller-binding or storage failures return an error. If two hosts report the same session ID, the tool returns `BINDING_INVALID` because the stored history cannot be attributed to either host.
- The work record store keeps the situational context body and its previous contents as plain-text JSON. The agent API does not expose the previous contents, but it does not prevent access through the same OS user's DB credentials or direct file access.
- A stage result's evidence item names an attachment (`attachmentId`, and `seq`, the number of the file in it) that the recording session made through the upload client. An attachment is a bundle of 1 to 64 files that are checked one by one and kept together under one ID. The server never reads a path on the caller's computer. The attachment is the evidence, so changing or deleting the original file after the upload does not affect `finalize_workflow`. Attachments are stored encrypted in the configured attachment folder, and their keys are in `secrets/attachment-keys`; the database holds metadata only. A file larger than `sennard.attachments.max-file-bytes` (64 MiB by default) is refused at the upload. Back up current and earlier attachment folders, the key folder and the database together. See [Attachments and Uploads](../contributing/attachments.md).
- For one file, use `sennard attachments upload --session-ref <sessionRef> <file>`. Obtain the current session reference from `open_session`. For a bundle, run `sennard attachments prepare --name <relative path> <file>` for each file, call `artifact_begin` once with all file metadata, then run `sennard attachments send --upload-id <id> <file>` for each upload ID. Each send uses multipart and prints its transfer status. Call `artifact_finish` after all transfers succeed. Put the `attachmentId`, file `seq` and file `sha256` in `evidence`, `artifacts` or `outputFile` as applicable. `priorFailure.evidenceRefs` uses the attachment ID. After an interrupted transfer, repeat `send` with the same ID. The client resends the file and the server skips bytes already committed. Use `--port` to select a different server port.
- Application-level encryption is not performed for the other stored data (only keys registered on the page and attachment files are encrypted).
- Therefore, do not put secrets, personal information, or raw logs or code in work records or stage results.

## Cleaning Up Old State

State cleanup does not run automatically. State is also not deleted merely because its retention period has passed.

1. `prepare_state_cleanup` only reads the candidates for deletion and returns an HMAC-signed token.
2. The user reviews the preview.
3. Deletion starts only when the same token is passed to `execute_state_cleanup`.

The token has no expiration time. If the candidates change while waiting for confirmation, the request is refused because of a digest mismatch. The token is bound to the plan ID, policy, cutoff, and candidate digest, and can be used only once.

The fixed retention policy is as follows.

- Terminal workflow runs and `completed`/`abandoned` convergence roots: 180 days
- Continuity snapshot payloads that are not `active`: replaced with a hash tombstone after 30 days
- Continuity tasks that are not `active` and request/tombstone/observation metadata: deleted entirely after 180 days
- Active workflow roots, runs linked to those roots, and active continuity snapshots/tasks: not deleted
- Continuity tasks and snapshots whose session has bound at least one active root: not deleted. A session can bind multiple roots, and `root_id` points only to the most recent root, so when the representative root ends, records owned by another active root (`workflow-root:<rootId>` observation rows) are not deleted.
- Workflow metadata, run sequence, plugin update state, cleanup claims, and signing keys: not deleted

A terminal convergence root and its linked runs are calculated and deleted as one unit. The candidates are recalculated immediately before execution, and if any of them changed, the request is refused as a stale token. Deletion for workflow and continuity is a single transaction holding the module locks of both stores, so the deletions are committed together or rolled back together. There is no intermediate state (`partial`). The receipt status is `completed`, or `no-op` when there were no candidates to delete, and the per-store `databases.*.status` is `completed` or `skipped`.

### Backup

Copies of the deleted rows are kept, together with the plan ID, in the per-module backup table (`continuity_cleanup_backup`, `workflow_cleanup_backup`) within the same transaction. No file backup is created, so `backupPath` in the receipt is `null`. Rows in this table are not deleted automatically. `os-managed-unverified` means that OS protection such as OS account permissions and disk encryption must be used, but the server has not confirmed that it is active.

### Recovery

6.0.0 has no command that automatically restores rows from the backup tables. Rows that become necessary after deletion are investigated based on the copies remaining in the table.

The business database is not in the state folder. Back up both the local state folder and the configured business database using the database administrator's backup procedure; a state-folder copy alone is not a database backup.

1. Stop the server with `sennard stop`.
2. Copy the state folder to a separate location and back up the business database using its administrator's procedure.
3. Restore the corresponding state folder and database backups while the server is stopped, then run `sennard start`.
4. Confirm the recovery with `sennard status` and workflow and continuity queries.

### Protection Counts in the Preview

The optional `rootProtection` in the preview is a protection count calculated in the same workflow query transaction. `activeRoots` is the total number of active roots, the same as the existing `counts.protectedActiveRoots`. `linkedNonTerminalRoots`, `linkedRecentRoots`, and `linkedMissingReceiptRoots` are the numbers of unique roots, among terminal roots older than 180 days, that were excluded because of linked runs. A single root can fall under multiple reasons, and `totalProtectedRoots` is not the sum across reasons but the deduplicated total of active roots and excluded terminal roots. Cases where the terminal root itself is still within its retention period, and continuity tasks, are not included in this count.

The six fields of this object are present together. An observed 0 in a new preview means 0, and when an older response or token does not have the object, the value is unobserved (UNKNOWN). Partial objects or invalid numbers are refused. `previewCreatedAt` is the creation time of the plan, and the same count bound with HMAC is copied into the receipt. The count in the receipt does not represent a re-observation at execution time or the success of deletion. Check the actual number of deletions and whether any failed with the status and deleted fields of `databases`. If only the diagnostics change while the existing candidates and linked metadata stay the same, the execution conditions of the existing token do not change.

The 3.0 types and the plan/receipt output schema support this optional field. A validator from earlier versions with `additionalProperties: false` may reject the new response, so compatibility with every older consumer is not assured.
