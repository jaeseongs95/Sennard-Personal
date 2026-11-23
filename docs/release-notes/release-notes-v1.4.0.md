[English](#sennard-140-release-notes) | [한국어](#sennard-140-릴리스-노트)

# Sennard 1.4.0 Release Notes

Sennard Personal 1.4.0 restores idle wake registration in Codex and improves how Sennard reports and routes idle wake in Antigravity.

## Before Upgrading

- Use JDK 25 and verify `sennard-1.4.0.jar` against `sennard-1.4.0.jar.sha256`. Use the matching 1.4.0 host plugins, including `sennard-antigravity-v1.4.0.zip` for Antigravity. [Installation instructions](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.4.0/README.md#installation)
- Close connected host sessions. Stop Sennard and any other Sennard servers using the same business database before backing up the state folder and any configured business database. Keep the 1.3.0 JAR, matching plugins, and backups for recovery. This release adds no database schema migrations. [Update and recovery instructions](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.4.0/docs/user/update.md#update-procedure)

## Upgrading from 1.3.0

1. Update each installed Sennard host plugin to 1.4.0. Close any host sessions you opened to update the plugins.
2. With JDK 25, run `java -jar sennard-1.4.0.jar upgrade` in an interactive Windows Command Prompt (CMD) or choose **Update and restart** in Setup. Both ask for confirmation only if the upgrade will turn Codex queue wake on where it was off. If prompted in CMD, enter `yes` to enable wake and continue. `no`, other input, or unavailable confirmation stops the upgrade before changing the installed version, settings, or server. After upgrading, start new host sessions. In Codex, review and trust the Sennard hooks again with `/hooks`.

### Rolling Back to 1.3.0

Version 1.4.0 adds no database schema migrations. The `upgrade` command rejects downgrades with `DOWNGRADE_REFUSED`. To roll back, restore the state folder and business database together from the same pre-upgrade backup point. Changes made after that point are lost from the restored state and database.

1. Reinstall and verify the 1.3.0 plugin in each host that uses Sennard.
   - **Claude Code.** Remove the existing `sennard-claude` marketplace registration. Run `/plugin marketplace add jaeseongs95/Sennard-Personal#v1.3.0` and `/plugin install sennard@sennard-claude` in the scopes that previously used the plugin.
   - **Codex.** Remove the existing `sennard` marketplace registration. Run `codex plugin marketplace add jaeseongs95/Sennard-Personal --ref v1.3.0` and `codex plugin add sennard@sennard`.
   - **Antigravity.** Close the app. Download and extract `sennard-antigravity-v1.3.0.zip`, then run `agy plugin install ./sennard` from the directory containing the extracted `sennard` folder. [1.3.0 release](https://github.com/jaeseongs95/Sennard-Personal/releases/tag/v1.3.0)
2. Close all connected host sessions, including those used to reinstall the plugins. Run `sennard stop` and stop any other Sennard servers that use the same business database.

   Uninstall deletes the entire installation directory. Before continuing, check where the state folder, business database files, JDBC driver and password file, current and previous attachment folders, attachment keys, backups, and `sennard.yaml` are stored. Back up anything you need to keep outside the installation directory. Set `SENNARD_INSTALL_ROOT` to the 1.4.0 installation directory and run `java -jar sennard-1.4.0.jar uninstall` using a copy of the JAR outside that directory. [State and attachment backup guidance](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.4.0/docs/user/state.md#what-is-stored)
3. Restore the state folder and business database from the same pre-upgrade backup point. Keep the hosts closed during the restore and until the 1.3.0 installation is ready.
4. Set `SENNARD_INSTALL_ROOT` to an empty installation directory and run `java -jar sennard-1.3.0.jar install --state-dir <restored-state-folder>`. Reapply the settings supported by 1.3.0 from the saved `sennard.yaml`. Run `sennard hooks` and `sennard status` using the 1.3.0 installation before reopening the hosts. In Codex, review and trust the Sennard hooks again with `/hooks`.

In 1.3.0, set `AGENT_GOVERNANCE_CODEX_QUEUE_WAKE=1` to enable Codex wake or `=0` to disable it. With that variable unset, 1.3.0 uses `codex.queueWake` in `PLUGIN_DATA/session-messaging.json` and defaults to off. If upgrading changed that file, restore the desired `codex.queueWake` value before reopening Codex. `SENNARD_CODEX_QUEUE_WAKE` only applies to 1.4.0 in this rollback procedure.

## Changes

### Fixed Defects

- Codex can register a new idle wake worker at the next eligible `Stop` after the Sennard server restarts. Native queue wake is enabled by default. Sennard honors an explicit opt-out and reports when a prerequisite is missing.
- In Antigravity, a main session's wake worker no longer displaces wake for its managed child sessions. Main and child sessions keep their respective wake paths.

## Public Contract Changes

- Set `SENNARD_CODEX_QUEUE_WAKE=0` to disable wake and `=1` to enable it. With the variable unset, Codex uses `codex.queueWake` in `PLUGIN_DATA/session-messaging.json` and defaults to on. Setup uses `AGENT_GOVERNANCE_CODEX_QUEUE_WAKE` only to determine the previous 1.3 setting. To keep wake off during an upgrade, set `SENNARD_CODEX_QUEUE_WAKE=0` before running setup and when starting Codex. Reopen the receiving session after changing its wake settings. [Wake settings](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.4.0/docs/user/collaboration.md)
- Message sending and status checks report `autoWake` as `unsupported` with `no-idle-wake` when wake is disabled, and as `degraded` with `wake-prerequisite-missing` when a prerequisite is missing. A queued message remains available at ordinary delivery boundaries.

## Known Limitations

- Idle wake requires a live receiving session and a usable host wake path. After server restart, the Codex worker becomes available again when the session reaches an eligible `Stop`.

## Verification

- The wake changes passed 172 focused tests, including 20 PostgreSQL tests. The candidate's hook adapter tests passed 4 of 4 after the Codex `Stop` definition was updated in the test fixture. Release metadata passed its consistency check.
- Clean installation and live integration with Claude Code, Codex, and Antigravity are scheduled after the release is published and installed on the verification PC.

---

# Sennard 1.4.0 릴리스 노트

Sennard Personal 1.4.0에서 Codex의 유휴 세션 깨우기 등록을 복구했습니다. Antigravity에서는 메인 세션과 관리형 서브에이전트의 깨우기 경로를 유지하고, 깨우기를 사용할 수 없는 상태를 더 정확히 표시합니다.

## 업그레이드 전 확인

- JDK 25를 사용하고 `sennard-1.4.0.jar`를 `sennard-1.4.0.jar.sha256`과 대조합니다. Antigravity의 `sennard-antigravity-v1.4.0.zip`을 포함해 해당 호스트의 1.4.0 플러그인을 사용합니다. [설치 안내](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.4.0/README.ko.md#설치)
- 연결된 호스트 세션을 닫습니다. Sennard를 중지하고, 같은 업무 데이터베이스를 쓰는 다른 Sennard 서버도 중지한 뒤 상태 폴더와 연결된 업무 데이터베이스를 백업합니다. 복구에 쓸 1.3.0 JAR, 같은 판의 플러그인, 백업을 보관합니다. 이 판에서 추가된 데이터베이스 스키마 마이그레이션은 없습니다. [업데이트 및 복구 안내](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.4.0/docs/user/update.ko.md#업데이트-절차)

## 1.3.0에서 올리는 순서

1. 설치된 Sennard 호스트 플러그인을 각각 1.4.0으로 업데이트합니다. 플러그인 업데이트에 사용한 호스트 세션을 닫습니다.
2. 대화형 Windows 명령 프롬프트(CMD)에서 JDK 25로 `java -jar sennard-1.4.0.jar upgrade`를 실행하거나 설치 창에서 **업데이트 및 재시작**을 선택합니다. 두 방식 모두 꺼져 있던 Codex 대기열 깨우기가 켜지는 경우에만 확인을 요청합니다. CMD에서 확인을 요청하면 `yes`를 입력해 깨우기를 켜고 계속합니다. `no`나 그 밖의 응답을 입력하거나 확인할 수 없으면 설치 버전, 설정, 서버를 변경하기 전에 중단합니다. 업그레이드 후 새 호스트 세션을 시작합니다. Codex에서는 `/hooks`로 Sennard 훅을 다시 검토하고 신뢰합니다.

### 1.3.0으로 되돌리는 순서

1.4.0에서 추가된 데이터베이스 스키마 마이그레이션은 없습니다. `upgrade` 명령은 이전 버전으로 되돌리려 하면 `DOWNGRADE_REFUSED` 오류를 반환합니다. 아래 절차에 따라 업그레이드 전 같은 시점에 백업한 상태 폴더와 업무 데이터베이스를 함께 복원합니다. 백업 이후 변경 사항은 복원한 상태와 데이터베이스에서 사라집니다.

1. Sennard를 사용하는 각 호스트에 1.3.0 플러그인을 다시 설치하고 버전을 확인합니다.
   - **Claude Code.** 기존 `sennard-claude` 마켓플레이스 등록을 제거합니다. `/plugin marketplace add jaeseongs95/Sennard-Personal#v1.3.0`을 실행한 뒤 이전에 플러그인을 사용한 범위에 `/plugin install sennard@sennard-claude`로 설치합니다.
   - **Codex.** 기존 `sennard` 마켓플레이스 등록을 제거합니다. `codex plugin marketplace add jaeseongs95/Sennard-Personal --ref v1.3.0`과 `codex plugin add sennard@sennard`를 차례대로 실행합니다.
   - **Antigravity.** 앱을 종료합니다. `sennard-antigravity-v1.3.0.zip`을 내려받아 압축을 푼 뒤 `sennard` 폴더가 들어 있는 디렉터리에서 `agy plugin install ./sennard`를 실행합니다. [1.3.0 릴리스](https://github.com/jaeseongs95/Sennard-Personal/releases/tag/v1.3.0)
2. 플러그인을 다시 설치할 때 사용한 세션을 포함해 연결된 호스트 세션을 모두 닫습니다. `sennard stop`을 실행하고 같은 업무 데이터베이스를 사용하는 다른 Sennard 서버도 중지합니다.

   제거하면 설치 디렉터리 전체가 삭제됩니다. 제거하기 전에 상태 폴더, 업무 데이터베이스 파일, JDBC 드라이버와 비밀번호 파일, 현재 및 이전 첨부 폴더, 첨부 키, 백업, `sennard.yaml`의 저장 위치를 확인합니다. 보존할 항목은 설치 디렉터리 밖에 백업합니다. `SENNARD_INSTALL_ROOT`를 1.4.0 설치 디렉터리로 지정한 뒤 그 디렉터리 밖에 복사해 둔 JAR로 `java -jar sennard-1.4.0.jar uninstall`을 실행합니다. [상태 및 첨부 백업 안내](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.4.0/docs/user/state.ko.md#무엇이-저장되나)
3. 업그레이드 전 같은 시점에 백업한 상태 폴더와 업무 데이터베이스를 복원합니다. 복원하는 동안과 1.3.0 설치가 준비될 때까지 호스트를 닫아 둡니다.
4. `SENNARD_INSTALL_ROOT`를 빈 설치 디렉터리로 지정하고 `java -jar sennard-1.3.0.jar install --state-dir <restored-state-folder>`를 실행합니다. 보관해 둔 `sennard.yaml`에서 1.3.0이 지원하는 설정을 다시 적용합니다. 설치된 1.3.0으로 `sennard hooks`와 `sennard status`를 실행해 확인한 뒤 호스트를 다시 엽니다. Codex에서는 `/hooks`로 Sennard 훅을 다시 검토하고 신뢰합니다.

1.3.0에서 Codex 깨우기를 켜려면 `AGENT_GOVERNANCE_CODEX_QUEUE_WAKE=1`, 끄려면 `=0`을 설정합니다. 이 변수를 설정하지 않으면 `PLUGIN_DATA/session-messaging.json`의 `codex.queueWake`를 따르며 기본값은 꺼짐입니다. 업그레이드에서 이 파일이 바뀌었다면 Codex를 다시 열기 전에 원하는 `codex.queueWake` 값으로 복원합니다. 이 복구 절차에서 `SENNARD_CODEX_QUEUE_WAKE`는 1.4.0에만 적용됩니다.

## 바뀐 점

### 고친 결함

- Sennard 서버를 다시 시작한 뒤 Codex 세션이 다음으로 등록 조건을 충족하는 `Stop`에 도달하면 유휴 세션을 깨우는 작업자를 새로 등록할 수 있습니다. Codex의 기본값은 깨우기 사용입니다. 명시적으로 끈 설정을 따르며, 깨우기에 필요한 조건이 빠지면 그 상태를 표시합니다.
- Antigravity에서 메인 세션의 깨우기 작업자가 등록된 뒤에도 관리형 서브에이전트를 깨울 수 있습니다. 메인 세션과 서브에이전트는 각자의 깨우기 경로를 유지합니다.

## 공개 계약 변경

- Codex 깨우기를 끄려면 `SENNARD_CODEX_QUEUE_WAKE=0`, 켜려면 `=1`을 설정합니다. 환경변수가 없으면 `PLUGIN_DATA/session-messaging.json`의 `codex.queueWake`를 읽으며 기본값은 켜짐입니다. 설치 프로그램은 1.3의 이전 설정을 판단할 때만 `AGENT_GOVERNANCE_CODEX_QUEUE_WAKE`를 읽습니다. 업그레이드 후에도 끈 상태를 유지하려면 설치를 실행하기 전과 Codex를 시작할 때 `SENNARD_CODEX_QUEUE_WAKE=0`을 설정합니다. 깨우기 설정을 바꾼 뒤에는 수신 세션을 다시 엽니다. [깨우기 설정](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.4.0/docs/user/collaboration.ko.md)
- 메시지 전송과 상태 조회에서 깨우기가 꺼졌으면 `autoWake`가 `unsupported`와 `no-idle-wake`를 표시합니다. 필요한 조건이 빠졌으면 `degraded`와 `wake-prerequisite-missing`을 표시합니다. 대기열에 들어간 메시지는 일반 수신 경로에서 받을 수 있습니다.

## 알려진 한계

- 유휴 세션을 깨우려면 실행 중인 수신 세션과 그 호스트에서 사용할 수 있는 깨우기 경로가 필요합니다. 서버 재시작 후에는 Codex 세션이 등록 조건을 충족하는 `Stop`에 도달하면 깨우기 작업자를 다시 사용할 수 있습니다.

## 검증

- 깨우기 변경을 대상으로 한 집중 시험 172건이 통과했으며, 그중 PostgreSQL 시험은 20건입니다. Codex `Stop` 훅 정의를 시험의 기대값에 반영한 뒤 후보의 훅 어댑터 시험 4건이 모두 통과했습니다. 판 메타데이터 일치 검사도 통과했습니다.
- 새 설치와 Claude Code, Codex, Antigravity 실제 연동은 릴리스를 발행하고 검증용 PC에 설치한 뒤 확인할 예정입니다.
