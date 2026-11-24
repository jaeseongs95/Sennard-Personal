[English](#sennard-150-release-notes) | [한국어](#sennard-150-릴리스-노트)

# Sennard 1.5.0 Release Notes

Sennard Personal 1.5.0 explains what each workflow gate is for when an agent first sees it and shows Codex session names on the session board. This release also updates the shared code used by the supported hosts to register hooks and send wake messages.

## Before Upgrading

- Use JDK 25. Verify `sennard-1.5.0.jar` against `sennard-1.5.0.jar.sha256` and use the matching host plugins. [Installation instructions](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.5.0/README.md#installation)
- Close connected host sessions. Run `sennard stop` and stop any other Sennard servers that use the same business database. Back up the state folder and business database together. This release adds database migrations for execution records and session identity, including session names. Keep the 1.4.0 JAR and matching plugins for recovery. [Update procedure](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.5.0/docs/user/update.md#update-procedure)

## Upgrading from 1.4.0

1. Update the Sennard plugin in each host to version 1.5.0. Close any sessions you opened to update the plugins.
2. Run `java -jar sennard-1.5.0.jar upgrade`. Open new sessions after the update. In Codex, review and trust the Sennard hooks again with `/hooks`.

### Rolling Back to 1.4.0

Stop Sennard and keep the connected hosts closed. Restore the state folder and business database from the same pre-upgrade backup point. Reinstall the 1.4.0 host plugins and install the 1.4.0 server into a separate installation directory with the restored state folder. Work saved after the backup point will be missing from the restored state. [Sennard 1.4.0 downloads](https://github.com/jaeseongs95/Sennard-Personal/releases/tag/v1.4.0)

## Changes

- The session board shows the name you set with Codex `/rename` on the matching session card.
- The first workflow gate notice explains when to use Inquiry, Change, Execution, or Transition. The notice lists workflows to resume when the session has open work. Agents can use `get_skill_inventory` to look up individual skills as needed.
- Sennard restores saved global, role, and situational context at startup, resume, and completed compaction in Claude Code and Codex. Antigravity restores context on the first model invocation reported as zero. After in-request compaction, it restores context on the next eligible request.

### Fixed Defects

- An active Inquiry stays available when the harness asks the agent to resume it.
- After a user approves a role binding, the notice indicates whether the assigned role has saved context. The agent can read that context with `load_shared_context`.
- Claude Code's session messaging input schema matches the shared tool contract.

## Public Contract Changes

- The session board response can include `userTitle` for a session with a name from its host.

## Known Limitations

- Session title lookup in this release uses the host's recorded session name in Codex. The board can show a name after Codex records the rename.
- A tool gate relies on the host receiving the hook response. If the hook exits abnormally, returns no response, or reaches the time limit set by the host, enforcement depends on how that host handles the failure. Verification across all supported hosts is pending for these cases. The enforcement architecture is scheduled for revision in 2.0.0.

## Verification

- On the designated Linux test server with JDK 25, the general suite recorded 3,351 passes and 43 skips in this run. Gradle reused results from up-to-date tasks with 537 passes and 3 skips. The business database suite, run with an isolated PostgreSQL instance, recorded 2,023 passes and 16 skips. Both suites completed with zero failures.
- Package checks covered the server JAR and its checksum, the Windows and Linux setup scripts, and the Antigravity ZIP.
- On the designated Linux test server, plugin installation and hook configuration were verified in Codex, Claude Code, and Antigravity. MCP integration in live host sessions is pending.
- The upgrade from the public 1.4.0 release and recovery to 1.4.0 remain unverified.

---

# Sennard 1.5.0 릴리스 노트

Sennard Personal 1.5.0은 에이전트가 관문을 처음 접할 때 각 관문의 용도를 설명하고, Codex 세션 이름을 현황판에 표시합니다. 지원 호스트의 훅 등록과 깨우기 송신에 공통으로 사용하는 코드도 정리했습니다.

## 업그레이드 전 확인

- JDK 25를 사용합니다. `sennard-1.5.0.jar`를 `sennard-1.5.0.jar.sha256`과 대조하고 같은 버전의 호스트 플러그인을 사용합니다. [설치 안내](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.5.0/README.ko.md#설치)
- 연결된 호스트 세션을 닫습니다. `sennard stop`을 실행하고 같은 업무 데이터베이스를 쓰는 다른 Sennard 서버도 중지합니다. 상태 폴더와 업무 데이터베이스를 함께 백업합니다. 이번 판은 실행 기록과 세션 이름을 포함한 세션 식별 정보에 데이터베이스 마이그레이션을 추가합니다. 복구에 사용할 1.4.0 JAR와 같은 버전의 플러그인을 보관합니다. [업데이트 순서](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.5.0/docs/user/update.ko.md#업데이트-절차)

## 1.4.0에서 올리는 순서

1. 각 호스트의 Sennard 플러그인을 1.5.0으로 업데이트합니다. 플러그인 업데이트를 위해 연 세션을 닫습니다.
2. `java -jar sennard-1.5.0.jar upgrade`를 실행합니다. 업데이트 후 새 세션을 시작합니다. Codex에서는 `/hooks`에서 Sennard 훅을 다시 확인하고 신뢰합니다.

### 1.4.0으로 되돌리기

Sennard를 중지하고 연결된 호스트를 닫은 상태로 유지합니다. 상태 폴더와 업무 데이터베이스를 업그레이드 전 같은 시점의 백업으로 복원합니다. 1.4.0 호스트 플러그인을 다시 설치하고 별도 설치 디렉터리에 복원한 상태 폴더를 지정해 1.4.0 서버를 설치합니다. 백업 이후 저장한 작업은 복원한 상태에 포함되지 않습니다. [Sennard 1.4.0 다운로드](https://github.com/jaeseongs95/Sennard-Personal/releases/tag/v1.4.0)

## 바뀐 점

- Codex `/rename`으로 지정한 이름을 세션 현황판의 해당 카드에서 볼 수 있습니다.
- 첫 관문 안내에서 조회·변경·실행·상태 전이 관문을 언제 쓰는지 설명합니다. 세션에 열린 워크플로우가 있으면 이어서 처리할 목록을 보여 줍니다. 에이전트는 필요한 스킬의 상세 설명을 `get_skill_inventory`로 조회할 수 있습니다.
- Claude Code와 Codex에서는 시작·재개·압축 완료 시 저장된 전역·역할·상황 컨텍스트를 복원합니다. Antigravity에서는 호스트가 0번으로 보고한 첫 모델 호출에서 복원합니다. 요청 중 압축이 일어나면 다음 복원 대상 요청에서 컨텍스트를 복원합니다.

### 고친 결함

- 하네스의 재개 안내 뒤에도 조회 관문이 열려 있어 에이전트가 이어서 처리할 수 있습니다.
- 사용자가 역할 결속을 승인하면 안내에 해당 역할의 컨텍스트가 저장되어 있는지 표시합니다. 에이전트는 `load_shared_context`로 그 컨텍스트를 읽을 수 있습니다.
- Claude Code의 세션 메시지 입력 스키마가 공통 도구 계약과 일치합니다.

## 공개 계약 변경

- 호스트에서 지정한 이름이 있는 세션은 현황판 응답에 `userTitle`을 포함할 수 있습니다.

## 알려진 한계

- 이번 판은 Codex가 기록한 세션 이름을 조회합니다. Codex가 이름 변경을 기록하면 현황판에서 이름을 확인할 수 있습니다.
- 도구 실행 관문은 호스트가 훅 응답을 받아야 적용됩니다. 훅이 비정상 종료하거나 응답이 없거나 호스트의 시간 한도에 도달하면 차단 여부는 호스트의 실패 처리 방식에 따라 달라집니다. 이 경우에 대한 전체 지원 호스트 검증은 남아 있습니다. 관문 구조 개선은 2.0.0에서 진행할 예정입니다.

## 검증

- 지정 Linux 시험 서버에서 JDK 25로 실행한 일반 시험은 3,351건이 통과했고 43건을 건너뛰었습니다. Gradle에서 최신 상태로 판정한 작업의 기존 결과는 통과 537건과 건너뜀 3건입니다. 격리 PostgreSQL에서 실행한 업무 데이터베이스 시험은 2,023건이 통과했고 16건을 건너뛰었습니다. 두 시험 모두 실패는 0건입니다.
- 서버 JAR와 체크섬, Windows·Linux 설치 스크립트, Antigravity ZIP을 검증했습니다.
- 지정 Linux 시험 서버에서 Codex·Claude Code·Antigravity의 플러그인 설치와 훅 설정을 확인했습니다. 실제 호스트 세션의 MCP 연동 검증은 남아 있습니다.
- 공개 1.4.0에서의 업그레이드와 1.4.0으로의 복구는 미검증 상태입니다.
