[한국어](update.ko.md)
# Updates and Migration

For the initial installation, see the [Installation section of the README](../../README.md#installation). This document describes new version notifications, the update procedure, changing the language package, and what to check when upgrading from an earlier version.

## New Version Notifications

The resident server checks only stable tags in the `vMAJOR.MINOR.PATCH` format in a fixed public repository.

- A successful check result is kept for 24 hours. If a check fails, the last successful result is kept and the check is retried after 1 hour. A failed check does not stop a workflow.
- If a stable version newer than the installed version exists, `plugin-update-notice` is appended once after a tool response. The notification for the same version appears only once.
- To check manually, have the agent call `check_for_updates`. With `force: true`, the check runs again regardless of the kept result.
- It only notifies; it does not change plugin files, the installation cache, or marketplace settings, and it does not install automatically.
- When a skill is invoked directly without MCP, updates are not checked.

## Update Procedure

First, check the "Before Upgrading" section or breaking changes in the release notes of the new version.

When upgrading to a version after 6.0.0, do the following.

1. Close all host sessions.
2. Download `sennard-<version>.jar` and its `.sha256` of the new version, check the jar, and run `java -jar sennard-<version>.jar upgrade` with your JDK 25. It extracts the new version beside the old one, creates the AOT caches, stops the server (and waits for its process to end), and starts the new version. After the server starts normally, it deletes the old version folder, writes the host definitions again, and prints the new command path. It refuses the same version with `SAME_VERSION` and a lower version with `DOWNGRADE_REFUSED`. `sennard.yaml` in the installed version folder ([location](state.md#storage-location)) is copied to the new version. The link of the business database is not in that file: it is in the settings database of the state folder, so an upgrade leaves it as it is.
3. On each host, install the plugin of the new version as described in the [Installation section of the README](../../README.md#installation).
4. Start a new session. Do the following for each host.
   - **Claude Code:** Run `sennard hooks` after installing the plugin. Then start a new session.
   - **Codex:** Review and trust the Sennard hooks again in `/hooks`. Until they are trusted, the hooks do not run, so turn guidance, work records, the status board, and message delivery do not work.
   - **Antigravity:** Close the app, install the new installation package, and then restart the app. If the app is running, the existing installation folder cannot be deleted and the installation fails. Run `sennard hooks` after installing the plugin.

### Plugin Updates and `sennard hooks`

The plugin package contains empty hook and MCP definitions, because a definition names the absolute paths of this PC's `java`, jar, and server address file, which no package can know. `sennard install`, `sennard upgrade`, and `sennard hooks` write this installation's definitions to the plugin copy that the host reads; this is the same on every operating system. They write only to plugin copies of the same major version; copies of a different major version are left as they are, and that fact is printed.

- **Every host:** The host copies the distributed (empty) definitions anew whenever it installs or updates the plugin. Each time, run `sennard hooks` again and then start a new session. `hostCopiesWithoutDefinitions` of `sennard status` lists the copies that are empty now. If `sennard install` is run before the plugin is installed, "plugin is not installed yet; after installing it, run: sennard hooks" is printed.
- **Where the copies are:** Claude Code through `installed_plugins.json` (below), Antigravity at `~/.gemini/config/plugins/sennard`, Gemini CLI at `~/.gemini/extensions/sennard` (a linked extension is the user's own folder and is not written), and Codex at `~/.codex/plugins/cache/<marketplace>/sennard/<version>/` (`CODEX_HOME` moves the Codex folder). The Codex and Gemini CLI locations were not confirmed on a machine that has the host.

`sennard hooks` finds the Claude Code plugin through the `installPath` that Claude Code records in `installed_plugins.json`. A plugin of a marketplace added with a local folder path is loaded by Claude Code from that marketplace's own folder, whatever `installPath` names, so `sennard hooks` finds that folder through `known_marketplaces.json` and the marketplace's `.claude-plugin/marketplace.json`. A folder outside Claude Code's plugin cache is the user's files and is not written to; `sennard hooks` then ends with an error that names the file Claude Code reads and this installation's definition: copy the definition over that file, or install the plugin from a marketplace that is not a local folder ([README](../../README.md#claude-code)), and run `sennard hooks` again. Until then the hooks and MCP tools of such a plugin have no definition and do not run.

### Stopping and Removing the Server

To stop the server, use `sennard stop`; to start it again, use `sennard start`; to remove it, use `sennard uninstall`. Sennard does not register an OS service. A later session-start hook can start the stopped server. `sennard uninstall` removes the program (and with it `sennard.yaml`) and leaves the state folder and business database. The link of the business database is in the state folder, so a later install on the same state folder finds the database linked. Run it with a copy of the release jar outside the installation folder, not with the jar inside it (`UNINSTALL_FROM_INSTALLED_COPY`).

`sennard uninstall` deletes, among the definition files that Sennard wrote to the host's plugin copy, the files whose content is unchanged from what was written, and puts back a member of a shared file (the `mcpServers` entry of the Gemini CLI manifest) when it is unchanged; it leaves files that were changed afterward while printing their names. It does not remove the host's plugin itself, so remove the Sennard plugin on each host (Claude Code: `/plugin uninstall sennard@sennard-claude`). A remaining plugin has no server to connect to.

If a problem occurs, investigate while preserving the state folder without deleting it. Reinstalling an earlier version alone may not roll back. The revert method for each version is in the release notes of that version.

## Language

Starting with 4.0.0, there is one package per host. Sennard text read by the model is fixed to a single, token-efficient form for each text regardless of the language of the installation environment (hook guidance, tool descriptions, results, CLI output, and skill descriptions are in English; the common intake criteria in the MCP instructions are in Korean). Skill bodies remain in their original language (mostly Korean). The display language setting and `SENNARD_LOCALE` no longer have any effect ([Admin Page and Context](admin-page.md#language)).

## Upgrading from Earlier Versions

- **6.0.0:** A server installation step was added. The release is one jar that you run with your JDK 25 (`java -jar`), and the server's port is chosen by the operating system at each start. Local settings use H2 and business state uses a separately configured database. For public contract changes, follow the [6.0.0 release notes](../release-notes/release-notes-v6.0.0.md).
- **English-only packages:** English-only packages (`sennard-en`, agy `-en.zip`) are no longer distributed. If you installed one, remove it and install `sennard`.
