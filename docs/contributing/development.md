[한국어](development.ko.md)

# Development Reference

This document describes the development environment, the verification commands, the generated files, and the procedure for adding a skill. The contribution principles are in the [Contributing Guide](../../CONTRIBUTING.md).

## Development Environment and Verification Commands

Use JDK 25 and the repository's Gradle Wrapper. Only a JDK 17 or later to start the Wrapper (`JAVA_HOME` or `PATH`) is required, and JDK 25 is found or downloaded by the Gradle toolchain (see "Running the Tests" in the [Contributing Guide](../../CONTRIBUTING.md)). Run the commands in `java/`; `java/sennard-repo-tools/build.gradle.kts` is the source of truth for the repository tool tasks.

| Command | What it does |
| --- | --- |
| `./gradlew test` | JUnit tests. They include the repository contract, the message catalog, the domain-neutral wording, the skill check, and the tests of the designated business database |
| `./gradlew :sennard-personal:releaseJar` | Build the one release jar (`sennard-<version>.jar`, a reproducible Spring Boot jar) and its `.sha256` in `build/release` |
| `./gradlew :sennard-repo-tools:hostHookFiles` | Generate the hook definition files from the host adapter output |
| `./gradlew :sennard-repo-tools:claudePlugin`, `:sennard-repo-tools:checkClaudePlugin` | Generate and check the Claude Code distribution |
| `./gradlew :sennard-repo-tools:antigravityPackage -Ptool="--ref <ref> --out <zip>"` | Build the Antigravity installation bundle |
| `./gradlew :sennard-personal:jarRun` | Run the release jar of this build as a user would, from a folder with a non-ASCII name by its file name (install, status, start, stop, upgrade, uninstall). It is not part of `test` |
| `./gradlew :sennard-repo-tools:checkWhitespace -Ptool="--base <sha>"` | The whitespace check of the added lines that `git diff --check` makes (no base: `HEAD` against its parent) |
| `./gradlew :sennard-repo-tools:checkReleaseArchive`, `:sennard-repo-tools:checkReleaseChecksums` | The release workflow's checks of the jar name against the tag, of the checksum and of the setup launchers `setup.cmd` (CRLF) and `setup.sh` (LF) rendered from `release/*.template` (see the task descriptions for the arguments). The attachment to a release is not a Gradle task: the one job that can write to the release checks out nothing and uploads the checked files with `gh release upload` |
| `./gradlew :sennard-repo-tools:syncReleaseMetadata`, `:sennard-repo-tools:checkReleaseMetadata` | Synchronize and check the version surfaces |
| `./gradlew :sennard-repo-tools:updateSourceLock` | Update the integrated checksum of `skills/source-lock.json` |
| `./gradlew :sennard-repo-tools:updateClassificationDigests` | Recalculate the digest of the `classification.json` spans of the skills from the cited files (the line and byte ranges are chosen by a person and are not moved; the task fails if a range leaves the file) |
| `./gradlew :sennard-repo-tools:acceptTranslation` | Update the translation lock after the source message catalog changes |
| `./gradlew :sennard-repo-tools:publicExport -Ptool="check --ref HEAD"` | Classify and check the public export (this task does not exist in the public tree) |

The full verification is `./gradlew test --continue` and `git diff --check` at the repository root. Which tests run where is described in [Testing and CI](testing-and-ci.md).

## Generated Files

`claude-plugin/` and the hook definition files (`hooks/hooks.json`, `hooks.json`, `claude-overlay/hooks/hooks.json`, `gemini-extension/hooks/hooks.json`) are generated files and are not edited directly. When a source that the generated files include (`contracts/`, `skills/`, and so on) is changed, regenerate them with the following command and commit them together.

```bash
cd java
./gradlew :sennard-repo-tools:hostHookFiles :sennard-repo-tools:updateSourceLock :sennard-repo-tools:updateClassificationDigests :sennard-repo-tools:claudePlugin
```

The boundary between the common sources and the host-specific parts follows the "Claude Code distribution" section of [AGENTS.md](../../AGENTS.md).

## Adding a Skill

All skills are managed directly in `skills/<skill-name>/` of this repository. A new specialist skill places the following in the same change.

- `SKILL.md` and its reference files, and `integration/skill-descriptor.json` if needed
- The provider entry in `skills/registry.json`
- If a deterministic check is needed, a `SkillCheck` bean in `java/sennard-server`, a JUnit test in `java/sennard-personal`, and normal, boundary, and failure cases in `tests/<skill-name>/fixtures`
- `skills/source-lock.json` updated with `:sennard-repo-tools:updateSourceLock`

The repository contract test (`RepositoryContractTest`) checks that the skill metadata, the registry, and the source lock agree.

## Contracts and Versions

The public contracts are defined in `contracts/` as JSON Schema 2020-12. Adding a skill that is compatible with the existing versions is managed as a `minor` version, a behavior fix as a `patch`, and a change that breaks the compatibility of a contract, permission, or identifier as a `major` version.
