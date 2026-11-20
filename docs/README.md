[한국어](README.ko.md)

# Documentation

The user documents, the security policy, the contributing guide, the contributor reference documents, and this document are available in English (`X.md`) and Korean (`X.ko.md`). The entry point of the repository is the English [README.md](../README.md), and the Korean version is [README.ko.md](../README.ko.md).

## User Documents

These documents are for people who install and use Sennard. Installation is described in the [README](../README.md#installation).

| Document | Contents |
| --- | --- |
| [Usage](user/usage.md) | How to make requests, what changes after installation, workflows and the edit gate, work records, model selection for subtasks, and what is not guaranteed |
| [Admin page and contexts](user/admin-page.md) | The local admin page, the global, role, and situational contexts, JEV, language, and reset |
| [Session collaboration](user/collaboration.md) | The session board, PEER messages, and wake methods per host |
| [State storage and cleanup](user/state.md) | Storage locations and settings, cleanup, backup, and recovery |
| [Updates and migration](user/update.md) | New version notices, the update procedure, language, and upgrading from earlier versions |
| [Troubleshooting](user/troubleshooting.md) | What to check for each symptom |

## Contributor Documents

These documents are for people who change Sennard itself. Start with the [contributing guide](../CONTRIBUTING.md). Each document below has an English and a Korean edition.
| Document | Contents |
| --- | --- |
| [Architecture](contributing/architecture.md) | Structure of skills, contracts, and the MCP server, execution observation, work records, the session board, the message store, check coverage, and repository layout |
| [Development](contributing/development.md) | Development environment, verification commands, generated files, frozen evaluations, and adding and incorporating skills |
| [Testing and CI](contributing/testing-and-ci.md) | Roles of CI and the test server, and how test scope is divided |
| [Releasing](contributing/release.md) | Version numbers, release notes, pre-release checks, and the supply chain |
| [Document format](contributing/document-format.md) | Document languages, the release note format, and the main README format |
| [Host hook reception](contributing/hooks.md) | Per host and event: MCP tool hook or the Sennard Java client as a command hook, with the documentation behind each choice |
| [Internationalization](contributing/i18n.md) | Fixed model-facing locale, message catalogs, and the single package |
| [Execution observation and host connection](contributing/execution-attestation.md) | Sources of execution observation per host and failure codes |
| [Session message contract](contributing/session-messaging.md) | Message IDs, retries, recall, ACK, wake, presence, and peer wait decisions |
| [Input sources and work boundaries](contributing/input-boundaries.md) | Distinguishing input sources, receiving and forwarding, and write targets of the workflow ledger |
| [Admin signature log and settings reads](contributing/admin-log.md) | Signature log format and verification, settings reads, JEV configuration, and error codes |
| [Database and transactions](contributing/db-and-transactions.md) | Spring Data JDBC repositories, account isolation, concurrency, values made in Java with common column types, ANSI SQL |
| [Attachments and uploads](contributing/attachments.md) | Attachment tables, encrypted segment format, the `artifact_*` tools, settings, error codes, keys, cleaning, backup, the `sennard attachments` command, and the link to stage results |
| [Exceptions and messages](contributing/exceptions-and-messages.md) | The seven `SennardException` categories, code enums, the message catalogs (English and Korean), MCP and HTTP result conversion |
| [Validation](contributing/validation.md) | Jakarta Validation declarations for input records, service methods and settings, and where domain rules live |
| [Tool input mapping](contributing/tool-input-mapping.md) | How an MCP tool takes flat keys into an input record, and the measurement that decided it |
| [Settings and profiles](contributing/settings-and-profiles.md) | `@ConfigurationProperties` records, injected beans, deployment differences and profiles |
| [Logging](contributing/logging.md) | SLF4J and Logback, how an exception is logged, what is forbidden |
| [Roadmap](contributing/roadmap.md) | Direction after personal edition 1.0.0 |

## Release Notes

- [Release notes](release-notes/): One `release-notes-v<version>.md` file per version. The personal edition starts with [1.0.0](release-notes/release-notes-v1.0.0.md); each personal release note contains the English text first, followed by the Korean text. The GitHub Release body is also created from this file. Notes for earlier AGS/Sennard versions are retained as historical records.
