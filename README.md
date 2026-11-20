# Sennard

English | [한국어](README.ko.md)

Sennard (Sennard) is a local plugin for Claude Code, Codex, and Antigravity (`agy`). It helps an agent use the specialist skills a request needs, carry multi-stage work through its acceptance criteria, and carry forward your corrections and unfinished work. It covers documents, research, decisions, guidance materials, and software work. All three hosts connect to the same local server and follow common rules; what differs per host, such as hook events, wake methods, and install formats, is handled by each host's adapter.

**Personal edition.** This public repository is the personal edition of Sennard. You install it on your own computer and there is no operator. It is configured on the assumption that you trust your own computer and the agents you run on it. Its trust boundaries are described in the [Security policy](SECURITY.md).

The user documents, the security policy, the contributing guide, and the contributor reference documents are available in English and Korean.
[Installation](#installation) · [Usage](#usage) · [Skills](#skills) · [Documentation](#documentation)

## What can I use it for?

| What you want to do | How Sennard helps |
| --- | --- |
| Research and make decisions | Sorting out goals, constraints, and acceptance criteria; reviewing contested choices; checking claims against actual evidence |
| Write documents, guides, and service procedures | Checking reader and user flow and accessibility; prompt design; Korean prose editing that preserves facts |
| Build or fix software | Choosing a small implementation; design, testing, code review, and security review; diagnosing repeated failures |
| Continue across stages or sessions | Workflow progress, work records of corrections and unfinished work, the session board, and PEER messages |

## What it contains

- **Skills:** 23 skills in two groups: 15 that correct the agent (including `orchestrator`, which connects them, and `session-board` for the session board) and 8 for specialist fields
- **Local server:** one server per user on this computer, started by the installer, `sennard start`, or a session-start hook. It is the MCP server (`POST /mcp`) for workflows, work records, the session board, session messages, and state cleanup. Its port is not fixed: the operating system assigns a free port at each start, and the server announces its address in `server.properties` in the state folder. It runs on the JDK 25 of the user. Settings are stored locally, and business state uses a separately configured database.
- **Host hooks:** add guidance and observe edit targets at session, turn, and tool boundaries. A host runs a small Sennard Java client as a command hook, which finds the server through `server.properties` and sends the hook input to it; Codex delivers most of its hooks as MCP tool calls. A host reaches the MCP server through a stdio relay that the installer registers.
- **Local admin page:** you change settings that people decide, such as the global context, roles, and JEV, by signing with a passkey. Its port is assigned by the operating system each time the server starts; `sennard status` prints the current address (`adminPage`).

Guidance being present does not mean the agent followed it or that the result was verified. The limits of what Sennard does are in [Usage](docs/user/usage.md#what-sennard-does-not-do).

## Installation

Sennard has two parts: the server, installed once per user on this computer, and a plugin for each host. Install the server first, then the plugin in every host that should collaborate.

You need the host's plugin feature, a [new, empty business database](docs/user/state.md#business-database), and a JDK 25 (`java -version` must report 25). You do not need a clone of this repository.

The release is one jar that every operating system runs; there are no per-OS archives and no bundled runtime. The supported environment is any computer on which JDK 25 runs. See the release notes for the host environments covered by verification.

<!-- release-version:start -->
The current public release is `v1.2.0`, and the examples below install it. Read the [1.2.0 release notes](docs/release-notes/release-notes-v1.2.0.md) before installing or upgrading. JEV is configured only on the admin page. Changes for each personal release are in [GitHub Releases](https://github.com/jaeseongs95/Sennard-Personal/releases) and `docs/release-notes/release-notes-v<version>.md`, in English and Korean.
<!-- release-version:end -->

### Server

From the [releases page](https://github.com/jaeseongs95/Sennard-Personal/releases), download `sennard-<version>.jar` and its `sennard-<version>.jar.sha256` (the SHA-256 in the `sha256sum --check` format). Check the jar against the checksum, then run the installer with your JDK 25:

```bash
sha256sum --check sennard-<version>.jar.sha256
java -jar sennard-<version>.jar install
```

On Windows, compare the value in the checksum file with the output of `Get-FileHash -Algorithm SHA256 sennard-<version>.jar` instead.

On a desktop, open the setup window from the verified JAR with `java -jar sennard-<version>.jar setup`. It shows the installed version, package version, and destination folder. To update, close affected host sessions and back up your Sennard state folder and any configured business database before opening Setup from the newer JAR and choosing **Update and restart**. After the server starts, check the database status and follow the host plugin instructions below. After you install or update a host plugin, select **Register hooks** and start a new host session.

To update from the terminal, run the newer, verified release JAR with the `upgrade` command after the same backup steps.

The installer extracts the jar into the installation folder `~/.sennard/install` (`SENNARD_INSTALL_ROOT` changes it), trains the start-up caches, starts the server, waits for readiness, and writes the hook and MCP definitions into the plugins of the hosts that are installed. The state folder is `~/.sennard` by default (`SENNARD_STATE_DIR` or `--state-dir` changes it). `~` is the `user.home` of the JVM, the same question on every operating system. If the installation folder cannot be written in the file name encoding of the system (`native.encoding`), the installer ends with `INSTALL_ROOT_NOT_REPRESENTABLE` and chooses no other folder: set `SENNARD_INSTALL_ROOT` to a folder that can be written in it. The installer does not register an OS service or add anything to `PATH`.

There is no `sennard` program. In this documentation `sennard <command>` stands for `java -jar <installation folder>/versions/<version>/sennard-<version>.jar <command>`, the command that installation and upgrade print and that `sennard status` shows in its `command` field. The path changes on upgrade. `sennard status` shows server state; `sennard stop` and `sennard start` stop and start it. Only one server runs on a state folder (`SERVER_ALREADY_RUNNING`).

Next, install the plugin for each host. The plugin holds the skills and empty hook and MCP definitions. `sennard install`, `sennard upgrade`, and `sennard hooks` write this PC's definitions, which name the absolute paths of this PC's `java` and jar, into the host's copy of the plugin. Without the server the Sennard tools do not connect.

### Package and language

Each host has one package. Model-facing Sennard text has no language setting: each text is fixed in the form that costs the fewest tokens, so hook guidance, MCP tool descriptions and results, errors, skill check output and skill descriptions are English, and the common intake block in the MCP instructions is Korean; skill text stays in its source language (mostly Korean).

| Host | Package |
| --- | --- |
| Claude Code | `sennard@sennard-claude` |
| Codex | `sennard@sennard` |
| Antigravity | `sennard-antigravity-<tag>.zip` |

### Claude Code

Run inside Claude Code, then start a new session.

```text
/plugin marketplace add jaeseongs95/Sennard-Personal
/plugin install sennard@sennard-claude
```

Run `sennard hooks` after installing or updating the plugin and before starting the new session (on every operating system). The plugin ships empty hook and MCP definitions; `sennard hooks` writes this PC's definitions into the copy of the plugin that Claude Code loads.

### Codex

Run in a terminal:

<!-- release-install:start -->
```bash
codex plugin marketplace add jaeseongs95/Sennard-Personal --ref v1.2.0
codex plugin add sennard@sennard
```
<!-- release-install:end -->

Start a new Codex session, then review and trust the Sennard hooks in `/hooks`. Until you do, Codex does not run plugin hooks, so the turn check, work records, the board, and message delivery do not work. Review them again after an update that changes the hook definitions. Run `sennard hooks` after installing or updating the plugin so that Codex's copy of the plugin holds this PC's definitions. Where Codex keeps that copy (`~/.codex/plugins/cache/<marketplace>/sennard/<version>/`) and whether Codex asks for trust again after the file changes were not confirmed on a real Codex.

### Antigravity (`agy`)

`agy plugin install` accepts only a local directory. Download the install package attached to the release, extract it, and install that directory. The package holds only the files agy reads; installing the whole repository can freeze the Antigravity app's installed-plugins view. Close the Antigravity app while installing; if it is running, the install cannot remove the old plugin folder and fails. On Windows, extract with `Expand-Archive`.

<!-- release-agy:start -->
```bash
curl -LO https://github.com/jaeseongs95/Sennard-Personal/releases/download/v1.2.0/sennard-antigravity-v1.2.0.zip
unzip sennard-antigravity-v1.2.0.zip
agy plugin install ./sennard
agy plugin list
```
<!-- release-agy:end -->

For the Antigravity native app without the `agy` CLI, follow the [official manual installation guide](https://antigravity.google/docs/plugins#manual-plugin-installation). Close the app normally and keep the existing Sennard folder outside the plugin discovery paths for recovery. Place the `sennard` folder from the Antigravity installation ZIP attached to the release under `~/.gemini/config/plugins/` (Windows: `%USERPROFILE%/.gemini/config/plugins/sennard`), restart the app, and check the Sennard version in `Customizations > Installed`. Do not copy the whole repository. This method does not require installing the CLI.

The package holds the skills, an empty MCP registration (`mcp_config.json`), and empty hooks (`hooks.json`). Run `sennard hooks` after installing or updating the Antigravity plugin (`sennard install` and `sennard upgrade` do the same for a plugin that is already installed): it writes this installation's MCP registration (server name `sennard`, the stdio relay with `--host=agy`) and hook definitions into the plugin's copy. `GEMINI.md` and `AGENTS.md` at the root of this repository are instructions for developing Sennard itself and are not in the package.

### After installation

- Run `sennard status` and check that `healthy` is `true`. A host session that started before the server was up has no Sennard tools and does not attach later, so open a new session.
- In a new session, check that the Sennard skills and MCP tools appear. If they do not, see [Troubleshooting](docs/user/troubleshooting.md).
- Open the admin page (the `adminPage` address that `sennard status` prints) and register the first passkey. The first key is accepted without confirmation, so register it early. The personal edition uses one passkey; if it is lost, run `sennard admin-reset`. [Admin page and contexts](docs/user/admin-page.md)
- On the admin page, link the business database (JDBC driver, URL, account and password file), signing with your passkey. The link is kept in the state folder, not in `sennard.yaml`; all tables are created in one schema, `sennard`. [Business database](docs/user/state.md#business-database)

## Usage

Tell the agent the result you want, the conditions to check, and what must not change. You do not need to name a skill or a workflow in every request. The agent matches the request's purpose against each skill's use and exclusion conditions and uses a workflow for changes that run over several stages.

```text
Implement and verify this feature. First set out the scope and the acceptance criteria,
and in the final result list the checks you actually ran and the problems still unresolved.
```

What changes after installation, workflows and the edit gate, and work records are in [Usage](docs/user/usage.md); collaboration across sessions is in [Session collaboration](docs/user/collaboration.md).

## Skills

Sennard has 23 skills in two groups. The group is the `category` of a skill in its `classification.json` (`agent-correction` or `specialist`). It decides only how the skills are listed here and on the admin page; it does not change how a skill is selected, checked, or hooked. The versions of the skills that have a provider follow the [registry](skills/registry.json) and the [source lock](skills/source-lock.json). Work records are handled by MCP tools and hooks, not by a skill.

### Agent correction (15)

These skills keep an agent to the request, the evidence, and the agreed scope: they define the task, delegate, check scope and risk, diagnose repeated failures, audit, and connect the other skills.

| Skill | Version | What it does |
| --- | --- | --- |
| [`orchestrator`](skills/orchestrator/) | 1.2.0 | Chooses which skills a request needs and connects their order, inputs, outputs, and gates. |
| [`session-board`](skills/session-board/) | 1.0.0 | Describes how sessions that work in the same folder record and read their current work on the session board. |
| [`instruction-scope-resolver`](skills/instruction-scope-resolver/) | 1.0.0 | Finds the instruction files that apply to a path and their precedence. |
| [`task-contract`](skills/task-contract/) | 1.1.0 | Turns goal, scope, acceptance criteria, risk, and authority into a task contract. |
| [`coordinate-subagents`](skills/coordinate-subagents/) | 2.1.0 | Decides whether to delegate, sets ownership, handoff context, and verification duties, and selects a model and reasoning effort for each approved assignment from the combinations the host lists. |
| [`minimal-implementation`](skills/minimal-implementation/) | 1.0.0 | For any deliverable (code, documents, data, settings, procedures), chooses the smallest complete result that contains only what is needed, and never reports a pass produced by a substitute as done. |
| [`change-scope-guardian`](skills/change-scope-guardian/) | 1.0.0 | Compares the run's recorded baseline with its recorded changes to find changes under the run's write targets that are outside the plan or in excluded paths; a change outside the write targets is not recorded and not seen. |
| [`mutation-risk-preflight`](skills/mutation-risk-preflight/) | 1.0.3 | Checks target, approval, impact, and recovery right before a hard-to-reverse change. |
| [`independent-deliberation-panel`](skills/independent-deliberation-panel/) | 2.0.0 | Reviews contested decisions through independent viewpoints and rebuttals. |
| [`iteration-frame-auditor`](skills/iteration-frame-auditor/) | 1.0.0 | Audits whether repeated attempts have drifted from the original contract and premises. |
| [`acceptance-evidence-validator`](skills/acceptance-evidence-validator/) | 1.0.0 | Checks acceptance criteria against evidence from the actual result. |
| [`independent-audit-gate`](skills/independent-audit-gate/) | 1.0.0 | Has an auditor separate from whoever made the change check high-risk changes and their evidence. |
| [`blocker-diagnostician`](skills/blocker-diagnostician/) | 1.1.0 | Separates observations from hypotheses in repeated failures and picks the next discriminating check. |
| [`recovery-strategy-selector`](skills/recovery-strategy-selector/) | 0.2.0 | Compares recovery strategies for a diagnosed failure and prepares the handoff. |
| [`evaluation-validity-auditor`](skills/evaluation-validity-auditor/) | 1.0.0 | Audits whether an evaluation's inputs, judging, and aggregation are fair and reproducible. |

### Specialist fields (8)

These skills bring the expertise of one field to the work: software design, tests, code review, security, user interfaces, Korean prose, and prompts.

| Skill | Version | What it does |
| --- | --- | --- |
| [`cs-engineering`](skills/cs-engineering/) | 0.2.0 | Applies CS principles to concurrency, database, messaging, algorithm, and performance problems as invariants and verification duties. |
| [`solid-design`](skills/solid-design/) | 0.1.0 | Applies SOLID to real module, contract, and adapter boundaries and proposes the smallest change. |
| [`test-engineering`](skills/test-engineering/) | 0.1.0 | Designs test cases at requirement and code boundaries and checks that they catch defects. |
| [`code-review`](skills/code-review/) | 0.1.0 | Reviews a fixed diff or PR for real failure paths and separates defects, recommendations, and questions. |
| [`software-security-auditor`](skills/software-security-auditor/) | 0.1.0 | Audits attack paths, controls, and coverage gaps in web/API and CLI/MCP code. |
| [`ui-ux-design`](skills/ui-ux-design/) | 0.1.0 | Designs and reviews the user flow, information hierarchy, accessibility, and edge cases of anything people see and use (screens, print and ads, video, spaces and signage, products, service processes, documents and forms), checking first whether it works for the intended audience. |
| [`korean-prose-editor`](skills/korean-prose-editor/) | 0.1.0 | Edits and verifies Korean prose while preserving facts, numbers, links, and code. |
| [`prompt-engineering`](skills/prompt-engineering/) | 0.1.0 | Designs and diagnoses LLM prompts, instructions, and input templates and builds comparison cases. |

When to use which skill is in [Usage](docs/user/usage.md#when-to-use-skills).

## Documentation

| Audience | Documents |
| --- | --- |
| Users | [Usage](docs/user/usage.md), [Admin page and contexts](docs/user/admin-page.md), [Session collaboration](docs/user/collaboration.md), [State storage and cleanup](docs/user/state.md), [Updates and migration](docs/user/update.md), [Troubleshooting](docs/user/troubleshooting.md) |
| Contributors | [Contributing](CONTRIBUTING.md) (outside contributions are not accepted), [Architecture](docs/contributing/architecture.md), [Development](docs/contributing/development.md), [Testing and CI](docs/contributing/testing-and-ci.md), [Releasing](docs/contributing/release.md) |
| Full list | [Documentation index](docs/README.md), [Release notes](docs/release-notes/), [Security policy](SECURITY.md) |

## License

Sennard is licensed under the [GNU Affero General Public License, version 3 or later](LICENSE) (SPDX: `AGPL-3.0-or-later`), with the additional terms under section 7 of the AGPL stated in [ADDITIONAL-TERMS.md](ADDITIONAL-TERMS.md) (notices, origin, and names and marks). The `korean-prose-editor` skill follows the MIT license of its original author. This project does not accept outside contributions (pull requests); see [Contributing](CONTRIBUTING.md).
