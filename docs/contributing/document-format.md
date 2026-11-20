[한국어](document-format.ko.md)

# Document Format

This document defines the language rules for the repository's documents and the formats of the release notes and the main README. The release procedure itself is described in [Release](release.md).

## Document Languages

- A document is written in English (`X.md`) and Korean (`X.ko.md`) together. The first line of each file links to the other language edition, for example `[한국어](X.ko.md)` in `X.md` and `[English](X.md)` in `X.ko.md`.
- The two editions have the same meaning. When one is changed, the other is changed in the same change.
- Documents use formal expressions and do not use personal or informal expressions.
- The user documents, the security policy, the contributing guide, the documentation index, and the contributor reference documents in `docs/contributing` are available in English and Korean. Past design records are available in Korean only.
- A new document that is to be published is added to the export list of the source repository; otherwise the public export check fails.

## Release Notes

### Location and Languages

- One file per version: `docs/release-notes/release-notes-v<version>.md`.
- The file contains the English part first, then a line with `---`, then the Korean part. The two parts are not split into separate files or linked to each other as separate documents.
- The first line of the file links to the title of each part in the same file, for example `[English](#sennard-100-release-notes) | [한국어](#sennard-100-릴리스-노트)`.
- The two parts have the same sections in the same order, and the same number of list items, tables, and code blocks.
### Sections

The sections appear in the following order. The Korean part uses the Korean titles.

| Order | English title | Korean title | Required | Contents |
| --- | --- | --- | --- | --- |
| 1 | `# Sennard <version> Release Notes` | `# Sennard <판> 릴리스 노트` | Required | The title, followed by a summary paragraph of the version. |
| 2 | `## Before Installing` or `## Before Upgrading` | `## 설치 전 확인` or `## 업그레이드 전 확인` | Required | Installation requirements for the first release or migration, compatibility, and recovery details for an update. |
| 3 | `## Upgrading from <previous version>` | `## <이전 판>에서 올리는 순서` | When there is a migration or a manual step | The upgrade steps in order. |
| 4 | `## Changes` | `## 바뀐 점` | Required | Feature changes. Fixed defects are written in the `### Fixed Defects` / `### 고친 결함` subsection of this section; if no defect was fixed, the subsection states that there are none. |
| 5 | `## Public Contract Changes` | `## 공개 계약 변경` | Required | Changes to MCP tools, hooks, settings, and other public contracts. If there are none, the section states that there are none. |
| 6 | Sections needed only for that version | Their Korean titles | Optional | For example, behavior when the server is unavailable, or supported platforms. |
| 7 | `## Known Limitations` | `## 알려진 한계` | Required | Limitations that remain in this version. |
| 8 | `## Verification` | `## 검증` | Required | What was run and what was not run. |

## Main README

- `README.md` (English) is the first page of the repository. A language switch line below the title links to `README.ko.md` (Korean), and `README.ko.md` links back to `README.md` in the same position.
- The two files have the same sections in the same order:

  | Order | `README.md` | `README.ko.md` |
  | --- | --- | --- |
  | 1 | Title and language switch line | 제목과 언어 전환 줄 |
  | 2 | `## What can I use it for?` | `## 어떤 일에 쓰나요?` |
  | 3 | `## What it contains` | `## 구성` |
  | 4 | `## Installation` | `## 설치` |
  | 5 | `## Usage` | `## 사용하기` |
  | 6 | `## Skills` | `## 스킬` |
  | 7 | `## Documentation` | `## 문서` |
  | 8 | `## License` | `## 라이선스` |

- Both files are updated for every release: the generated version markers (`release-version`, `release-install`, `release-agy`, produced by `:sennard-repo-tools:syncReleaseMetadata`), the installation procedure, the skill table, and the document list.
