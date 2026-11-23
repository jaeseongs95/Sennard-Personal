[English](#sennard-130-release-notes) | [한국어](#sennard-130-릴리스-노트)

# Sennard 1.3.0 Release Notes

Sennard Personal 1.3.0 requires a workflow procedure for every request, restores context at host lifecycle events, and tightens version checks during installation and upgrades.

## Before Upgrading

- Use JDK 25. Verify `sennard-1.3.0.jar` against `sennard-1.3.0.jar.sha256` before running it. For Antigravity, use the matching `sennard-antigravity-v1.3.0.zip` package.
- Close connected host sessions, run `sennard stop`, and stop any other Sennard servers that use the same business database. Then back up the Sennard state and any configured business database. Keep the 1.2.0 JAR, its matching plugins, and the backups for recovery. [Update guide](../user/update.md)
- If a host already has the Sennard plugin, install its matching 1.3.0 package before choosing **Update and restart** in Setup or running the 1.3.0 JAR's `upgrade` command. Both paths refuse a detected package version mismatch.

### Rolling Back to 1.2.0

The V27 migration adds workflow tracking columns and a blocked-plan table to the business database. Rolling back restores both the state and the database to their pre-upgrade backups. Changes made after the backups are lost from the restored state and database. The `upgrade` command refuses a downgrade.

1. Reinstall the matching 1.2.0 plugin in each host that uses Sennard. Verify the plugin versions before continuing.
   - **Claude Code:** Remove the existing `sennard-claude` marketplace registration if present. Run `/plugin marketplace add jaeseongs95/Sennard-Personal#v1.2.0` and `/plugin install sennard@sennard-claude` in the scopes that previously used the plugin. [Claude Code marketplace instructions](https://code.claude.com/docs/en/discover-plugins#add-a-marketplace)
   - **Codex:** Remove the existing `sennard` marketplace registration if present. Run `codex plugin marketplace add jaeseongs95/Sennard-Personal --ref v1.2.0` and `codex plugin add sennard@sennard`. [Codex installation instructions](../../README.md#codex)
   - **Antigravity:** Close the app. Download and extract `sennard-antigravity-v1.2.0.zip` from the 1.2.0 release, then install its `sennard` folder. [1.2.0 release](https://github.com/jaeseongs95/Sennard-Personal/releases/tag/v1.2.0) · [Antigravity installation instructions](../../README.md#antigravity-agy)
2. Close all connected host sessions, including those used to reinstall the plugins. Use `sennard stop` to stop the 1.3.0 server and stop any other Sennard servers that use the same business database.

   Remove the old installation before restoring the backups so its hooks and commands cannot restart 1.3.0. Uninstall deletes the entire installation directory. Confirm that the state folder, database files, JDBC driver and password file, backups, and host configuration are outside it before continuing. Also check the current attachment folder, all previous attachment folders, and the attachment key folder. If any of these folders is inside the installation directory, follow the [attachment backup guidance](../user/state.md#what-is-stored) and back up the folders outside that directory before uninstalling. Save any `sennard.yaml` you want to refer to in a separate location. Set `SENNARD_INSTALL_ROOT` to the old installation directory, then run `java -jar sennard-1.3.0.jar uninstall` using a copy of the release JAR outside that directory.
3. Restore the matching Sennard state and business database backups taken before the upgrade. Keep the connected hosts closed during the restore and until the 1.2.0 installation is ready.
4. Set `SENNARD_INSTALL_ROOT` to an empty installation directory and run `java -jar sennard-1.2.0.jar install --state-dir <restored-state-folder>`. Use the 1.2.0 installation to run `sennard hooks` and check `sennard status` before reopening the hosts. In Codex, review and trust the Sennard hooks again with `/hooks`.

The new installation uses fresh settings. Review the saved `sennard.yaml` and reapply any settings that 1.2.0 supports. If the Claude Code settings file was retained, `env.CLAUDE_CODE_STOP_HOOK_BLOCK_CAP` can remain at `"0"`. To undo this change, remove the key or restore its previous value.

## Upgrading from 1.2.0

1. Close connected host sessions, run `sennard stop`, and stop any other Sennard servers that use the same business database. Then back up the Sennard state folder and any configured business database.
2. Update installed host plugins to 1.3.0 using the [host installation instructions](../../README.md#installation). Close Antigravity before replacing its plugin files.
3. Close the host sessions used to update the plugins. Verify the 1.3.0 JAR against its SHA-256 file, then run `java -jar sennard-1.3.0.jar upgrade` with JDK 25 or choose **Update and restart** in Setup.
4. If you install or update a host plugin after the server upgrade, run `sennard hooks`. Reopen the hosts and start new sessions. In Codex, review and trust the Sennard hooks again with `/hooks`.

## Changes

- **T-0162** — Sennard restores available global, role, and situational context when Claude Code or Codex reports a session start with source `startup`, `resume`, or `compact`. In Antigravity, it restores context when the host reports `invocationNum=0` at the start of a model invocation. When the installed intake guidance and skill targets are available, workflow entry points to the guidance and lists the targets.
- **T-0164** — The English and Korean operational guides and system flow diagrams now describe the workflow procedures, Stop rechecks, and how users can unblock a plan with a passkey. [System flow](../user/system-flow.md)

### Fixed Defects

- **D-0049** — Starting or resuming an operation workflow now includes guidance for verifying the work.
- **D-0363** — In Claude Code, an agent can again use `SendMessage` to contact its own subagents. Messages to other targets still go through the tool gate.
- **D-0411** — The workflow gate explicitly refuses Claude Code's Code Mode `REPL` calls and explains how to disable Code Mode and restart the session.
- **D-0413** — Baseline scanning skips an inaccessible nested junction when file attributes identify it as a link or special entry. Access denied on a normal directory still prevents baseline registration.
- **D-0414** — Installation, upgrades, and hook registration require the detected host plugin packages to match the target Sennard version before writing host definitions. `sennard status` and `check_for_updates` can report mismatched or unreadable package versions.
- **D-0415** — `sennard status` excludes incomplete runtime directories. Upgrade cleanup moves eligible old versions out of the installed-version list before deleting them and retries tracked cleanup on a later upgrade. It retains older versions that may still be referenced by host definitions.
- **D-0419** — If a command's edit targets are unknown and the workflows handling the request cannot change files, the refusal explains how to use a dedicated working copy in a CHANGE workflow, set `writeTargets` to `["."]`, and list the affected files in `changePlan`.
- **D-0420** — Every request requires a workflow procedure, including answers without tool calls. Stop checks apply separate recheck limits to requests without a workflow and to active workflows with recorded violations. A continuable Stop can spend a recheck. When no recheck is available, Sennard records the outcome and ends the request or marks the still-violating active workflow `BLOCKED`. It refuses the same blocked plan for the same workspace and workflow kind until the user unblocks it with a passkey. In Antigravity, Sennard adds a pending Stop notice to the model context at the next eligible input. [Workflow gate](../user/system-flow.md#handling-a-user-request)

  When Sennard detects an installed Claude Code plugin, registering its hooks sets `env.CLAUDE_CODE_STOP_HOOK_BLOCK_CAP` to the string `"0"` in the Claude Code user settings. This disables Claude Code's consecutive Stop-hook block cap so the Sennard limits apply. Sennard asks before replacing a value other than the string `"0"`. If it cannot get approval, it stops before writing files. The setting remains after uninstall if the Claude Code user `settings.json` is outside the Sennard installation root. If the file is directly in the installation root or in one of its subdirectories, uninstall deletes it along with the root. To undo the change in a retained file, remove or edit `CLAUDE_CODE_STOP_HOOK_BLOCK_CAP` under `env`.

- **D-0437** — Sennard isolates damaged records from earlier workflows so they no longer block context restoration or Stop checks for the current request.

## Public Contract Changes

`start_operation_workflow` and `resume_workflow` include verification guidance for operation workflows. A verdict-mapping refusal from `record_stage_result` can include `inputPath`, `allowedValues`, `valueMissing`, and, when present, `receivedValue`. The request format and the requirements for recording a passing result are unchanged. `check_for_updates` can return `hostPackageMismatches` with details of affected local plugin copies. When available, the optional `installation` field identifies the affected installation, for example by its plugin ID and scope.

## Known Limitations

- **D-0399** — Stage-result rejections now identify the input and allowed values for a missing or invalid provider verdict. If a result marked as passing has no evidence, the rejection explains how to attach evidence and resubmit it. Workflow finalization can still reject build outputs created outside the declared change plan.
- **D-0348** — The workflow guard can refuse `git switch -c` as a write to an unknown path, even when creating a branch at the current commit.
- **D-0411** — Claude Code's experimental Code Mode remains unsupported. Remove `CLAUDE_CODE_EXPERIMENTAL_CODE_MODE` from the environment and restart Claude Code to use Sennard.
- **D-0299** — Workflow finalization and change-plan submission timed out in a 1.2.0 run with 53 declared paths. The applied deadline and the point where the timeout occurred remain unconfirmed.
- **D-0417** — An agent can omit a requirement from a role context that was supplied earlier. In the reported case, the release plan omitted the required Personal packaging step.
- **D-0421** — On some hosts, a tool call may proceed without a gate decision if the hook process is terminated or fails to respond. A change to route tool execution through Sennard is planned for 2.0.0 so Sennard can block calls when the hook is unavailable.
- **D-0428** — If the Sennard installation root is the host's configuration directory or contains it, uninstall deletes the host configuration files along with the installation root.

## Verification

The candidate includes regression tests for operation guidance, stage-result recovery, access denied on Windows junctions and directories, host package version checks, and workflow enforcement. **D-0405** updates the `check_for_updates` golden test to check `currentVersion` against `release/version.json`. Repository changes and commit signatures were checked.

The Gradle integration run on the integrated release candidate completed with JDK 25 and PostgreSQL. Of 5,315 cases, 5,295 passed, 20 were skipped, and 0 failed. The run covered 3,464 `test` cases and 1,851 `businessDbTest` cases.

On Windows, the published 1.2.0 JAR matched its SHA-256 checksum and installed in an isolated environment. The test used the recorded Codex `SessionStart` hook and MCP metadata from the 1.2.0 test suite to complete one inquiry workflow. After the 1.2.0 server stopped, the 1.3.0 release candidate ran `upgrade` on a copy of the state directory and exited with code 0. The existing workflow kept its run ID, revision 1, and `PASSED` state, and the four session and presence rows kept their keys. PostgreSQL gained the V27 table and all three V27 columns. The 1.3.0 server completed a new inquiry workflow over MCP with a distinct run ID.

In an isolated Windows rollback test with PostgreSQL, replaying the recorded Claude Code `SessionStart` after restoring the 1.2.0 backups restarted the 1.3.0 server and reapplied V27. The 1.2.0 installer reused that running server. After the 1.3.0 server stopped, the 1.2.0 server started and logged `MIGRATION_MISSING` while preparing the business database. In the follow-up test, the plugin fixture was replaced first. After stopping 1.3.0, `java -jar sennard-1.3.0.jar uninstall` exited with code 0 and preserved the state folder and the database migration history. After the pre-upgrade backups were restored, attempts to use the old `start` command and recorded hook failed to start 1.3.0. For the restored state, the test set `SENNARD_INSTALL_ROOT` to an empty directory and ran `java -jar sennard-1.2.0.jar install --state-dir <restored-state-folder>`. With the same root and release JAR, the test ran `start`, `hooks`, and `status`. All four commands exited with code 0. MCP returned the original workflow with the same run ID, revision 1, and `PASSED` state. The migration history contained no V27 entry, and the server log contained no `MIGRATION_MISSING` error.

Clean installation and live integration with Claude Code, Codex, and Antigravity, and rollback with other database engines, remain unverified.

---

# Sennard 1.3.0 릴리스 노트

Sennard Personal 1.3.0은 모든 요청에 워크플로 절차를 요구하고, 호스트 세션의 시작·재개 등의 시점에 컨텍스트를 복원하며, 설치와 업그레이드 때 버전 확인을 강화합니다.

## 업그레이드 전 확인

- JDK 25를 사용합니다. JAR를 실행하기 전에 `sennard-1.3.0.jar`를 `sennard-1.3.0.jar.sha256`과 대조합니다. Antigravity는 같은 버전의 `sennard-antigravity-v1.3.0.zip` 패키지를 사용합니다.
- 연결된 호스트 세션을 닫고 `sennard stop`을 실행합니다. 같은 업무 데이터베이스를 사용하는 다른 Sennard 서버도 모두 중지한 뒤 Sennard 상태와 연결된 업무 데이터베이스를 백업합니다. 복구를 위해 1.2.0 JAR, 같은 버전의 플러그인, 백업을 보관합니다. [업데이트 안내](../user/update.ko.md)
- 호스트에 Sennard 플러그인이 이미 설치되어 있으면 설치 창에서 **업데이트 및 재시작**을 선택하거나 1.3.0 JAR의 `upgrade` 명령을 실행하기 전에 해당 호스트의 1.3.0 플러그인 패키지를 설치합니다. 두 경로 모두 패키지 버전 불일치를 감지하면 업그레이드를 거절합니다.

### 1.2.0으로 되돌리는 순서

V27 마이그레이션은 업무 데이터베이스에 워크플로 추적 열과 차단된 계획을 기록하는 테이블을 추가합니다. 롤백할 때는 업그레이드 전에 백업한 상태와 데이터베이스를 함께 복원합니다. 백업 이후의 변경은 복원한 상태와 데이터베이스에서 사라집니다. `upgrade` 명령은 낮은 버전으로의 변경을 거절합니다.

1. Sennard 플러그인이 설치된 각 호스트에서 1.2.0판 플러그인을 다시 설치합니다. 플러그인의 판을 확인한 뒤 다음 단계로 진행합니다.
   - **Claude Code:** `sennard-claude` 마켓플레이스가 등록되어 있으면 기존 등록을 제거합니다. `/plugin marketplace add jaeseongs95/Sennard-Personal#v1.2.0`과 `/plugin install sennard@sennard-claude`를 실행해 플러그인을 기존 설치 범위에 다시 설치합니다. [Claude Code 마켓플레이스 안내](https://code.claude.com/docs/en/discover-plugins#add-a-marketplace)
   - **Codex:** `sennard` 마켓플레이스가 등록되어 있으면 기존 등록을 제거합니다. `codex plugin marketplace add jaeseongs95/Sennard-Personal --ref v1.2.0`과 `codex plugin add sennard@sennard`를 실행합니다. [Codex 설치 안내](../../README.ko.md#codex)
   - **Antigravity:** 앱을 닫습니다. 1.2.0 릴리스에서 `sennard-antigravity-v1.2.0.zip`을 받아 풀고 그 안의 `sennard` 폴더를 설치합니다. [1.2.0 릴리스](https://github.com/jaeseongs95/Sennard-Personal/releases/tag/v1.2.0) · [Antigravity 설치 안내](../../README.ko.md#antigravity-agy)
2. 플러그인 재설치에 사용한 세션을 포함해 연결된 호스트 세션을 모두 닫습니다. `sennard stop`으로 1.3.0 서버를 중지하고 같은 업무 데이터베이스를 사용하는 다른 Sennard 서버도 모두 중지합니다.

   기존 훅과 명령이 1.3.0을 다시 시작하지 못하도록 백업을 복원하기 전에 기존 설치본을 제거합니다. 제거 명령은 설치 폴더 전체를 삭제합니다. 상태 폴더, 데이터베이스 파일, JDBC 드라이버와 비밀번호 파일, 백업, 호스트 설정이 모두 설치 폴더 밖에 있는지 확인한 뒤 진행합니다. 현재 첨부 폴더, 이전 첨부 폴더 전부, 첨부 키 폴더의 위치도 확인합니다. 이 중 하나라도 설치 폴더 안에 있다면 [첨부 백업 안내](../user/state.ko.md#무엇이-저장되나)에 따라 설치 폴더 밖에 백업한 뒤에만 제거합니다. 다시 참고할 `sennard.yaml`은 별도 위치에 보관합니다. `SENNARD_INSTALL_ROOT`를 기존 설치 폴더로 지정하고, 그 폴더 밖에 둔 릴리스 JAR 사본으로 `java -jar sennard-1.3.0.jar uninstall`을 실행합니다.
3. 업그레이드 전에 함께 백업한 Sennard 상태와 업무 데이터베이스를 복원합니다. 복원 중에는 호스트를 닫아 두고 1.2.0 설치가 준비된 뒤에만 다시 엽니다.
4. 빈 설치 폴더를 `SENNARD_INSTALL_ROOT`로 지정하고 `java -jar sennard-1.2.0.jar install --state-dir <restored-state-folder>`를 실행합니다. 1.2.0 설치본에서 `sennard hooks`를 실행하고 `sennard status`를 확인한 뒤 호스트를 다시 엽니다. Codex는 `/hooks`에서 Sennard 훅을 다시 검토하고 신뢰합니다.

새 설치본은 새 설정을 사용합니다. 보관한 `sennard.yaml`을 검토해 1.2.0이 지원하는 설정을 다시 적용합니다. Claude Code 설정 파일이 보존됐다면 `env.CLAUDE_CODE_STOP_HOOK_BLOCK_CAP` 값 `"0"`이 남을 수 있습니다. 이 변경을 되돌리려면 해당 키를 지우거나 이전 값으로 복원합니다.

## 1.2.0에서 올리는 순서

1. 연결된 호스트 세션을 닫고 `sennard stop`을 실행합니다. 같은 업무 데이터베이스를 사용하는 다른 Sennard 서버도 모두 중지한 뒤 Sennard 상태 폴더와 연결된 업무 데이터베이스를 백업합니다.
2. [호스트별 설치 안내](../../README.ko.md#설치)에 따라 설치된 호스트 플러그인을 1.3.0으로 업데이트합니다. Antigravity는 플러그인 파일을 교체하기 전에 앱을 닫습니다.
3. 플러그인 업데이트에 사용한 호스트 세션을 닫습니다. 1.3.0 JAR를 SHA-256 파일과 대조한 뒤 JDK 25로 `java -jar sennard-1.3.0.jar upgrade`를 실행하거나 설치 창에서 **업데이트 및 재시작**을 선택합니다.
4. 서버 업그레이드 뒤에 호스트 플러그인을 설치하거나 업데이트했다면 `sennard hooks`를 실행합니다. 호스트를 다시 열고 새 세션을 시작합니다. Codex는 `/hooks`에서 Sennard 훅을 다시 검토하고 신뢰합니다.

## 바뀐 점

- **T-0162** — Claude Code와 Codex가 세션 시작 사유로 `startup`, `resume`, `compact`를 보내면 사용 가능한 전역·역할·상황 컨텍스트를 복원합니다. Antigravity에서는 모델 호출 시작 시 호스트가 `invocationNum=0`을 보내면 복원합니다. 설치된 접수 안내와 스킬 적용 대상이 있으면 워크플로에 진입할 때 안내 위치와 대상 목록을 제공합니다.
- **T-0164** — 영문·한글 운영 안내와 시스템 흐름도에 워크플로 절차, Stop 재점검, 사용자가 패스키로 계획 차단을 해제하는 방법을 반영했습니다. [시스템 흐름](../user/system-flow.ko.md)

### 고친 결함

- **D-0049** — 실행 워크플로를 시작하거나 재개할 때 작업 검증 방법을 안내합니다.
- **D-0363** — Claude Code에서 에이전트가 `SendMessage`로 자신이 띄운 서브에이전트에 다시 메시지를 보낼 수 있습니다. 다른 대상에 보내는 메시지에는 도구 관문을 계속 적용합니다.
- **D-0411** — 워크플로 관문이 Claude Code의 Code Mode `REPL` 호출을 명시적으로 거절하고 Code Mode를 끈 뒤 세션을 다시 시작하는 방법을 안내합니다.
- **D-0413** — 접근할 수 없는 중첩 정션은 파일 속성에서 링크 또는 특수 항목으로 확인되면 기준선 스캔에서 건너뜁니다. 일반 디렉터리의 접근이 거부되면 기준선 등록을 막습니다.
- **D-0414** — 설치, 업그레이드, 훅 등록 시 감지된 호스트 플러그인 패키지의 버전이 대상 Sennard 버전과 일치해야 호스트 정의를 씁니다. `sennard status`와 `check_for_updates`는 버전이 일치하지 않거나 버전을 읽을 수 없는 패키지를 표시할 수 있습니다.
- **D-0415** — `sennard status`는 런타임 파일이 불완전한 폴더를 설치 버전 목록에서 제외합니다. 업그레이드 중 정리할 수 있는 이전 버전은 설치 버전 목록에서 먼저 분리한 뒤 삭제합니다. 중단된 정리가 기록에 남아 있으면 이후 업그레이드에서 다시 시도합니다. 호스트 정의의 참조가 남아 있을 수 있는 이전 버전은 보존합니다.
- **D-0419** — 명령의 변경 대상 경로를 알 수 없고 요청을 처리하는 워크플로에서 파일을 변경할 수 없으면 명령을 거절합니다. 이때 전용 작업 사본에서 CHANGE 워크플로를 열어 `writeTargets`를 `["."]`로 지정하고 변경할 파일을 `changePlan`에 적는 방법을 안내합니다.
- **D-0420** — 도구를 호출하지 않는 답변을 포함해 모든 요청에 워크플로 절차가 필요합니다. Stop 점검은 워크플로를 선택하지 않은 요청과 위반이 기록된 실행 중 워크플로에 별도의 재점검 한도를 적용합니다. 호스트가 계속 진행할 수 있는 Stop에서 재점검 횟수를 사용할 수 있습니다. 재점검 기회가 없으면 결과를 기록하고 요청을 종료하거나 위반 상태가 계속되는 실행 중 워크플로를 `BLOCKED`로 표시합니다. 사용자가 패스키로 차단을 해제할 때까지 같은 작업 폴더와 워크플로 종류에서 같은 계획을 다시 제출할 수 없습니다. Antigravity에서는 다음으로 조건을 충족하는 입력이 들어오면 보류한 Stop 알림을 모델 컨텍스트에 추가합니다. [워크플로 관문](../user/system-flow.ko.md#사용자-요청-처리)

  Sennard가 설치된 Claude Code 플러그인을 감지하면 훅을 등록할 때 Claude Code 사용자 설정의 `env.CLAUDE_CODE_STOP_HOOK_BLOCK_CAP`을 문자열 `"0"`으로 설정합니다. Claude Code의 연속 Stop 훅 차단 횟수 제한을 끄고 Sennard 한도를 적용하기 위한 설정입니다. 기존 값이 문자열 `"0"`과 다르면 바꾸기 전에 확인하며, 동의를 받지 못하면 파일을 쓰기 전에 중단합니다. Claude Code 사용자 `settings.json`이 Sennard 설치 루트 바깥에 있으면 제거 후에도 이 설정값이 남습니다. 파일이 설치 루트에 직접 있거나 그 하위 폴더에 있으면 제거할 때 설치 루트와 함께 삭제됩니다. 파일이 남아 있으면 `env`의 `CLAUDE_CODE_STOP_HOOK_BLOCK_CAP` 항목을 지우거나 값을 바꿔 설정을 되돌릴 수 있습니다.

- **D-0437** — 이전 워크플로의 손상된 기록을 격리하여 현재 요청의 컨텍스트 복원이나 Stop 점검을 막지 않도록 했습니다.

## 공개 계약 변경

`start_operation_workflow`와 `resume_workflow`는 실행 워크플로의 검증 방법을 안내합니다. `record_stage_result`에서 제공자 판정 값을 거절할 때 `inputPath`, `allowedValues`, `valueMissing`과 입력값이 있을 때의 `receivedValue`를 반환할 수 있습니다. 요청 형식과 통과 결과를 기록하는 조건은 그대로입니다. `check_for_updates`는 해당 로컬 플러그인 사본의 정보를 `hostPackageMismatches`로 반환할 수 있습니다. 설치를 식별할 수 있으면 선택 필드 `installation`에 플러그인 ID와 설치 범위 등의 정보가 포함됩니다.

## 알려진 한계

- **D-0399** — 제공자 판정 값이 없거나 유효하지 않으면 단계 결과 거절 응답에 입력 위치와 허용 값을 표시합니다. 통과로 표시한 결과에 증거가 없으면 첨부해 다시 제출하는 방법을 안내합니다. 선언한 변경 계획 밖에 빌드 산출물이 생기면 워크플로 종료가 여전히 거절될 수 있습니다.
- **D-0348** — 현재 커밋에 브랜치를 만들 때도 워크플로 관문이 `git switch -c`를 경로를 알 수 없는 쓰기로 분류해 거절할 수 있습니다.
- **D-0411** — Claude Code의 실험 기능인 Code Mode는 지원하지 않습니다. Sennard를 사용하려면 `CLAUDE_CODE_EXPERIMENTAL_CODE_MODE` 환경 변수를 지우고 Claude Code를 다시 시작합니다.
- **D-0299** — 1.2.0에서 변경 경로 53개를 선언한 워크플로의 종료와 변경 계획 등록이 시간 초과로 실패했습니다. 실제 적용된 제한 시간과 시간 초과가 발생한 지점은 확인되지 않았습니다.
- **D-0417** — 에이전트가 앞서 전달된 역할 컨텍스트의 요구사항을 놓칠 수 있습니다. 보고된 사례에서는 릴리스 계획에 필수 개인판 패키징 단계가 빠졌습니다.
- **D-0421** — 훅 프로세스가 강제 종료되거나 응답하지 않으면 일부 호스트에서 관문 판정 없이 도구 호출이 실행될 수 있습니다. 2.0.0에서는 도구 실행이 Sennard를 거치도록 구조를 바꿔 훅을 사용할 수 없는 경우에도 호출을 차단할 계획입니다.
- **D-0428** — Sennard 설치 루트가 호스트 설정 폴더와 같거나 그 폴더를 포함하면 제거할 때 설치 루트와 함께 호스트 설정 파일도 삭제됩니다.

## 검증

후보에는 실행 워크플로 안내, 단계 결과 거절 후 복구 안내, Windows 정션과 일반 디렉터리의 접근 거부 처리, 호스트 패키지 버전 확인, 워크플로 관문 적용을 확인하는 회귀 시험이 포함되어 있습니다. **D-0405**의 `check_for_updates` 골든 테스트는 `release/version.json`과 `currentVersion`을 대조하도록 바뀌었습니다. 저장소 변경과 커밋 서명을 확인했습니다.

통합 릴리스 후보의 Gradle 시험은 JDK 25와 PostgreSQL 환경에서 완료했습니다. 전체 5,315건 중 5,295건이 통과했고 20건을 건너뛰었으며 실패는 0건입니다. `test` 3,464건과 `businessDbTest` 1,851건을 확인했습니다.

Windows에서 공개 1.2.0 JAR의 SHA-256을 대조하고 격리 환경에 설치했습니다. 1.2.0 시험에 포함된 Codex `SessionStart` 훅 기록과 MCP 메타데이터를 사용해 문의 워크플로 1건을 완료했습니다. 1.2.0 서버를 멈춘 뒤 상태 폴더의 사본에서 1.3.0 릴리스 후보로 `upgrade`를 실행해 종료 코드 0을 확인했습니다. 기존 워크플로의 실행 ID, 리비전 1, `PASSED` 상태가 유지됐으며 세션과 접속 기록의 행 4건도 같은 키로 남았습니다. PostgreSQL에는 V27 테이블과 열 3개가 생성됐습니다. 1.3.0 서버는 MCP에서 새 문의 워크플로를 별도의 실행 ID로 완료했습니다.

Windows의 격리 PostgreSQL 롤백 시험에서 1.2.0 백업 복원 뒤 기록된 Claude Code `SessionStart` 입력을 재실행하자 1.3.0 서버가 다시 시작되면서 V27이 재적용됐습니다. 1.2.0 설치 프로그램은 실행 중인 1.3.0 서버를 재사용했습니다. 1.3.0 서버를 중지한 뒤 1.2.0 서버는 시작됐으며 업무 데이터베이스를 준비하는 과정에서 `MIGRATION_MISSING`을 로그에 남겼습니다. 후속 시험에서 시험용 플러그인 패키지를 먼저 교체했습니다. 1.3.0 서버를 중지한 뒤 `java -jar sennard-1.3.0.jar uninstall`을 실행한 결과 종료 코드가 0이었으며 상태 폴더와 데이터베이스의 마이그레이션 이력이 보존됐습니다. 업그레이드 전 백업을 복원한 뒤 이전 `start` 명령과 기록된 훅으로는 1.3.0이 다시 시작되지 않았습니다. 복원한 상태를 사용하도록 `SENNARD_INSTALL_ROOT`에 빈 설치 폴더를 지정한 뒤 `java -jar sennard-1.2.0.jar install --state-dir <restored-state-folder>`를 실행했습니다. 같은 설치 폴더를 지정한 상태에서 같은 릴리스 JAR로 `start`, `hooks`, `status`를 실행했습니다. 네 명령의 종료 코드는 모두 0이었습니다. MCP에서 기존 워크플로의 실행 ID, 리비전 1, `PASSED` 상태를 확인했습니다. 마이그레이션 이력에 V27은 0건이었고 서버 로그에 `MIGRATION_MISSING` 오류가 없었습니다.

Claude Code, Codex, Antigravity의 새 설치와 실제 연동, 다른 데이터베이스 제품에서의 롤백은 미검증입니다.
