[한국어](update.ko.md)
# Updates and Migration

For the first public release of Sennard Personal, follow the [1.0.0 installation instructions](../../README.md#installation). This guide covers notifications, upgrades, and plugin hooks for later releases.

## New Version Notifications

The resident server checks only stable tags in the `vMAJOR.MINOR.PATCH` format in a fixed public repository.

- A successful check result is kept for 24 hours. If a check fails, the last successful result is kept and the check is retried after 1 hour. A failed check does not stop a workflow.
- If a stable version newer than the installed version exists, `plugin-update-notice` is appended once after a tool response. The notification for the same version appears only once.
- To check manually, have the agent call `check_for_updates`. With `force: true`, the check runs again regardless of the kept result.
- It only notifies; it does not change plugin files, the installation cache, or marketplace settings, and it does not install automatically.
- When a skill is invoked directly without MCP, updates are not checked.

## Update Procedure

Before upgrading to a later release, read its “Before Upgrading” section for migration, compatibility, and recovery instructions.

To upgrade a Personal installation, follow these steps.

1. Close affected host sessions. Back up the Sennard state folder and any configured business database.
2. On each host that already has Sennard, update the plugin to the exact target version using the host-specific instructions in the [README](../../README.md#installation). Close the Antigravity app before replacing its plugin files. Step 4 covers any remaining hook registration and starting new sessions.

   The Sennard Personal 1.3.0 GitHub release has no separate Gemini CLI installation package among its attached assets. The public source includes `gemini-extension/`. If an older Sennard extension remains at `~/.gemini/extensions/sennard`, the upgrade stops with `HOST_PACKAGE_VERSION_MISMATCH`. Run `gemini extensions uninstall sennard` in a terminal to remove the old extension, then retry the upgrade. See the [Gemini CLI uninstall instructions](https://geminicli.com/docs/extensions/reference/#uninstall-an-extension).
3. Close any host sessions opened to update the plugins. Download the target `sennard-<version>.jar` and its `.sha256`, verify the JAR, and run `java -jar sennard-<version>.jar upgrade` with JDK 25. Sennard checks the detected plugin packages before publishing the new runtime. It extracts the runtime beside the previous one, creates the AOT caches, stops the old server, starts the new server, and writes the host definitions before cleaning up eligible older versions. It prints the new command path. An already completed upgrade to the same version returns `SAME_VERSION` and a lower version returns `DOWNGRADE_REFUSED`. If placement or cleanup was interrupted, run the same JAR's `upgrade` command again to resume it.
4. If you install or update a plugin after the server upgrade, run `sennard hooks`. Then reopen the hosts and start new sessions. In Codex, review and trust the Sennard hooks again in `/hooks`. Skill selection and work-record guidance, work records, the status board, and message delivery depend on those hooks.

When upgrading from 1.3 to 1.4, CMD and the Setup window ask for confirmation only if the upgrade will turn Codex queue wake on where it was off. If prompted in Windows Command Prompt (CMD), enter `yes` in the interactive console to enable wake and continue. `no`, any other answer, or unavailable confirmation cancels setup before it changes the installed version, settings, or server. An upgrade that keeps wake on and a fresh install skip this confirmation.

To keep wake off, set `SENNARD_CODEX_QUEUE_WAKE=0` before running setup and when starting subsequent Codex sessions. Setup uses the older `AGENT_GOVERNANCE_CODEX_QUEUE_WAKE` only to determine the 1.3 setting. When the new variable is absent, setup updates the old plugin data setting where needed to enable wake. Reopen the receiving Codex session after changing its settings. [Codex wake settings and precedence](collaboration.md#waking-an-idle-session)

The upgrade copies `sennard.yaml` from the previous installation ([location](state.md#storage-location)). It keeps the business database link in the settings database of the state folder.

During cleanup, Sennard removes only older versions that it can establish were never exposed to a host or user through a definition. It retains older versions that may still be referenced. It moves each eligible folder out of the installed-version list before deletion and retries tracked cleanup on a later `upgrade`. `sennard status` excludes incomplete runtime directories.

### Plugin Updates and `sennard hooks`

The plugin package contains empty hook and MCP definitions, because a definition names the absolute paths of this PC's `java`, jar, and server address file, which no package can know. `sennard install`, `sennard upgrade`, and `sennard hooks` require all detected host plugin packages to match the exact Sennard version targeted by the command. A mismatched or unreadable package version causes `HOST_PACKAGE_VERSION_MISMATCH` before the command writes host definitions. The error identifies the expected version and affected copies. `sennard status` and `check_for_updates` can list them under `hostPackageMismatches`.

- **Every host:** The host copies the distributed (empty) definitions anew whenever it installs or updates the plugin. If that happens after the matching server version is installed, run `sennard hooks` again and then start a new session. `hostCopiesWithoutDefinitions` of `sennard status` lists the copies that are empty now. If `sennard install` is run before the plugin is installed, "plugin is not installed yet; after installing it, run: sennard hooks" is printed.
- **Where the copies are:** Claude Code through `installed_plugins.json` (below), Antigravity at `~/.gemini/config/plugins/sennard`, Gemini CLI at `~/.gemini/extensions/sennard` (a linked extension is the user's own folder and is not written), and Codex at `~/.codex/plugins/cache/<marketplace>/sennard/<version>/` (`CODEX_HOME` moves the Codex folder). The Codex and Gemini CLI locations were not confirmed on a machine that has the host.

`sennard hooks` finds the Claude Code plugin through the `installPath` that Claude Code records in `installed_plugins.json`. A plugin of a marketplace added with a local folder path is loaded by Claude Code from that marketplace's own folder, whatever `installPath` names, so `sennard hooks` finds that folder through `known_marketplaces.json` and the marketplace's `.claude-plugin/marketplace.json`. A folder outside Claude Code's plugin cache is the user's files and is not written to; `sennard hooks` then ends with an error that names the file Claude Code reads and this installation's definition: copy the definition over that file, or install the plugin from a marketplace that is not a local folder ([README](../../README.md#claude-code)), and run `sennard hooks` again. Until then the hooks and MCP tools of such a plugin have no definition and do not run.

### Stopping and Removing the Server

To stop the server, use `sennard stop`; to start it again, use `sennard start`; to remove it, use `sennard uninstall`. Sennard does not register an OS service. A later session-start hook can start the stopped server. `sennard uninstall` removes the program (and with it `sennard.yaml`) and leaves the state folder and business database. The link of the business database is in the state folder, so a later install on the same state folder finds the database linked. Run it with a copy of the release jar outside the installation folder, not with the jar inside it (`UNINSTALL_FROM_INSTALLED_COPY`).

`sennard uninstall` deletes, among the definition files that Sennard wrote to the host's plugin copy, the files whose content is unchanged from what was written, and puts back a member of a shared file (the `mcpServers` entry of the Gemini CLI manifest) when it is unchanged; it leaves files that were changed afterward while printing their names. It does not remove the host's plugin itself, so remove the Sennard plugin on each host (Claude Code: `/plugin uninstall sennard@sennard-claude`). A remaining plugin has no server to connect to.

If a problem occurs, investigate while preserving the state folder without deleting it. Reinstalling an earlier version alone may not roll back. The revert method for each version is in the release notes of that version.

## Language

There is one package per host. Sennard text read by the model is fixed to a single, token-efficient form for each text regardless of the language of the installation environment (hook guidance, tool descriptions, results, CLI output, and skill descriptions are in English; the common intake criteria in the MCP instructions are in Korean). Skill bodies remain in their original language (mostly Korean). See [Admin Page and Context](admin-page.md#language) for the administration page language.
