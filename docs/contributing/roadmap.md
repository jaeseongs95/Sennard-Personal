[한국어](roadmap.ko.md)

# Sennard Roadmap

<!-- release-version:start -->
The current public release is `v1.2.0`. The first personal release is described in the [1.0.0 release notes](../release-notes/release-notes-v1.0.0.md). The personal edition runs one resident server (Java 25, Spring Boot) per PC and connects to Claude Code, Codex, and Antigravity through their hooks and MCP. Local settings are stored in H2, and business state is stored in a separately connected database.
<!-- release-version:end -->

This document describes the direction after personal edition 1.0.0. Schedules are managed by the exit conditions of each stage rather than by dates, and work is not moved to the next release scope before the required verification is complete. The current behavior and limits are defined by [Architecture](architecture.md), and the release procedure by [Release](release.md). When this document differs from the implementation, the implementation state and its verification evidence take precedence, and this document is corrected together with them.

## Planning Principles

- A specialist skill owns one role that has value as an independent call. The orchestrator concentrates on request classification, execution order, input and output connection, and result integration.
- Orderings and integrity conditions that must be kept are checked in contracts or in the server execution layer, not only in natural-language instructions.
- Blockers and defects of the current release are resolved before new features.
- A new contract is added together with its schema, registry descriptor, normal, boundary, and failure fixtures, and regression tests.
- When several tasks use the same working tree, implementation does not start until the ownership of existing changes and the expected write paths are confirmed.
- Deployment, tag creation, and updating the public installation package are subject to separate approval. Completing a stage does not imply approval for automatic deployment.

## After Personal Edition 1.0.0

| Item | Description |
| --- | --- |
| Host adapters | Differences between hosts are handled by adapters. The adapter boundary is organized so that new hosts, models, and reasoning levels work without code changes. |

## Items on Hold for Now

- Automatic model allocation based on subscription plans or remaining usage is excluded from the required roadmap. The current method is kept, in which the user chooses the priority among quality, balance, and economy for each task.
- Per-skill on/off switching of Codex plugin skills is a platform feature that is difficult to solve in the repository alone.
- Application-level encryption of the store and automatic expiry without confirmation are excluded from the current policy scope. Deleting backups and protecting OS storage devices are managed by the operator.

## Common Implementation Units

Each new specialist skill or provider is implemented as one set of the following units.

1. `SKILL.md`, which defines the applicable conditions, exclusion conditions, input, output, and failure handling
2. Input and output JSON Schemas and the `ProviderResult.v1` adapter
3. The capability, artifact binding, state mapping, and gate in `skills/registry.json`
4. Normal, boundary, and expected-failure fixtures and regression tests
5. Orchestrator selection, ordering, and failure propagation tests
6. Updates to the manifest, README, and architecture document
7. The full verification result and, for high-risk work, the result of an audit separate from the implementer
