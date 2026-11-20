[English](#sennard-100-release-notes) | [한국어](#sennard-100-릴리스-노트)

# Sennard 1.0.0 Release Notes

Sennard Personal 1.0.0 is the first public release of Sennard Personal. It includes the local Java server, specialist skills, workflows, work records and session collaboration for Claude Code, Codex and Antigravity.

## Before Installing

- Install with JDK 25. Download `sennard-1.0.0.jar` and `sennard-1.0.0.jar.sha256` from the release and verify the checksum before running the JAR. Use `sennard-antigravity-v1.0.0.zip` for Antigravity.
- Follow the [installation guide](../../README.md#installation) to set up a new, empty business database for Sennard Personal.
- Sennard Personal trusts programs on the same computer. See the [security policy](../../SECURITY.md).

## Changes

- One local Java server per state folder provides MCP, hooks and the passkey-protected admin page. The server records its dynamically assigned addresses in `server.properties`. Local settings use H2 and business state uses a separately connected database.
- Specialist skills work with inquiry, change and operation workflows. Work records carry corrections and unfinished work across sessions. The session board, roles, PEER messages and native wake adapters support collaboration across the three hosts.
- Managed child conversations use the host's own interfaces. The product tracks child identity to resume the intended conversation, distinguishes queueing, claim and failure, and uses PEER for collaboration between the parent and child.
### Fixed Defects

Managed conversations and idle wake distinguish failed requests, host handoff and successful claims. The workflow checks child identity before resuming a conversation and preserves the failure outcome when the host declines the request.

## Public Contract Changes

- The first personal release uses the tag `v1.0.0`. The plugin, CLI, MCP `serverInfo.version` and update check `currentVersion` report `1.0.0`.
- Marketplace and update checks use Sennard-Personal. Update notices retain `automaticInstall: false`.

## Known Limitations

- The final Personal 1.0.0 JAR was not qualified on the designated Linux and Windows test hosts. On an earlier integration candidate, Linux Codex and Claude each completed a model turn, and Antigravity completed two turns in the same child conversation. Read-only MCP verification of the actual Antigravity conversation ID remains pending.
- Antigravity CLI launched in a Windows Administrator desktop command prompt. End-to-end binding to that desktop session and a managed Antigravity model turn on Windows remain unverified.
- Real PEER message bodies, acknowledgement delivery, and exclusion of unintended recipients still require end-to-end verification across the parent and child. The passkey flow, business database signing, and preservation of authenticated human input with automated input excluded also remain unverified on the designated hosts.
- A host plugin update can restore empty hook and MCP definitions. Run `sennard hooks`, check `sennard status`, and start a new host session. See [updates and migration](../user/update.md#plugin-updates-and-sennard-hooks).

## Verification

For the original release build, the local Gradle suite passed 3,367 tests with 11 skipped. The release metadata and generated Claude plugin passed their checks. The built JAR returned version `1.0.0`, and the JAR checksum matched when rebuilt from the public source snapshot. The Antigravity ZIP passed archive integrity and manifest version checks. See Known Limitations for the outstanding host checks.

---

# Sennard 1.0.0 릴리스 노트

Sennard Personal 1.0.0은 첫 공개판입니다. 로컬 Java 서버, 전문 스킬, 워크플로, 작업 기록과 Claude Code·Codex·Antigravity의 세션 협업 기능을 제공합니다.

## 설치 전 확인

- JDK 25로 설치합니다. 릴리스에서 `sennard-1.0.0.jar`와 `sennard-1.0.0.jar.sha256`을 받아 실행 전에 체크섬을 대조합니다. Antigravity는 `sennard-antigravity-v1.0.0.zip`으로 설치합니다.
- [설치 안내](../../README.ko.md#설치)에 따라 Sennard Personal 전용 빈 업무 데이터베이스를 준비합니다.
- Sennard Personal은 같은 컴퓨터의 프로그램을 신뢰합니다. [보안 정책](../../SECURITY.ko.md)을 참고합니다.

## 바뀐 점

- 상태 폴더마다 로컬 Java 서버 하나가 MCP, 훅과 패스키로 보호하는 관리자 페이지를 제공합니다. 서버는 실행 시 할당된 주소를 `server.properties`에 기록합니다. 로컬 설정은 H2에 저장하고, 업무 상태는 별도로 연결한 데이터베이스에 저장합니다.
- 전문 스킬은 조회·변경·운영 워크플로와 함께 동작합니다. 작업 기록으로 교정과 미완료 작업을 다음 세션에 이어갑니다. 현황판, 역할, PEER 메시지와 호스트별 깨우기 어댑터로 세 호스트의 협업을 지원합니다.
- 관리형 자식 대화는 호스트가 제공하는 인터페이스를 사용합니다. 자식 식별자를 추적해 지정한 대화를 재개하고, 대기열 등록·인수·실패를 구분합니다. 부모와 자식은 PEER로 협업합니다.
### 고친 결함

관리형 대화와 유휴 세션 깨우기는 요청 실패, 호스트에 넘긴 상태, 대기 작업을 인수한 상태를 구분합니다. 대화를 재개하기 전에 자식 식별자를 확인하고, 호스트가 요청을 거부하면 실패 결과를 보존합니다.

## 공개 계약 변경

- 개인판 첫 릴리스 태그는 `v1.0.0`입니다. 플러그인, CLI, MCP의 `serverInfo.version`, 업데이트 확인의 `currentVersion`은 `1.0.0`을 표시합니다.
- 마켓플레이스와 업데이트 확인은 Sennard-Personal을 사용합니다. 업데이트 알림은 `automaticInstall: false`를 유지합니다.

## 알려진 한계

- 최종 개인판 1.0.0 JAR는 지정 Linux·Windows 시험 호스트에서 검증하지 못했습니다. 이전 통합 후보에서는 Linux의 Codex와 Claude가 각각 모델 응답 1회를 완료했고 Antigravity는 같은 자식 대화에서 두 차례 응답했습니다. 읽기 전용 MCP를 통한 Antigravity의 실제 대화 ID 확인은 남아 있습니다.
- Windows 관리자 데스크톱의 명령 프롬프트에서 Antigravity CLI 실행을 확인했습니다. 해당 데스크톱 세션과의 연결 및 관리형 Antigravity 자식 대화의 모델 응답은 Windows에서 아직 검증하지 못했습니다.
- 실제 부모·자식 간 PEER 본문과 수신 확인 전달, 지정하지 않은 수신자에게 전달되지 않는지에 대한 종단간 검증이 남아 있습니다. 패스키 인증과 업무 데이터베이스 서명, 인증된 사람의 입력 원문 보존과 자동화 입력 제외도 지정 호스트에서 아직 검증하지 못했습니다.
- 호스트 플러그인을 업데이트하면 빈 훅과 MCP 정의가 복원될 수 있습니다. `sennard hooks`를 실행하고 `sennard status`를 확인한 뒤 호스트 세션을 새로 엽니다. [업데이트와 이전](../user/update.ko.md#플러그인-업데이트와-sennard-hooks)을 참고합니다.

## 검증

최초 릴리스 빌드의 로컬 Gradle 시험은 3,367건이 통과했고 11건을 건너뛰었습니다. 릴리스 메타데이터와 Claude 플러그인 생성물을 검증했습니다. 빌드한 JAR는 버전 `1.0.0`을 반환했고, 공개 소스 스냅샷에서 다시 빌드한 JAR와 체크섬이 일치했습니다. Antigravity ZIP은 압축 파일 무결성과 매니페스트 버전을 확인했습니다. 지정 호스트에서 남은 시험은 알려진 한계에 정리했습니다.
