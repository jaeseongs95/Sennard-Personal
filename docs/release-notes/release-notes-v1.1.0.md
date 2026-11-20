[English](#sennard-110-release-notes) | [한국어](#sennard-110-릴리스-노트)

# Sennard 1.1.0 Release Notes

Sennard Personal 1.1.0 improves workflow management in the admin console and restores saved context at session boundaries.

## Before Upgrading

- Use JDK 25. Download `sennard-1.1.0.jar` and `sennard-1.1.0.jar.sha256`, then verify the checksum before running the JAR. For Antigravity, use `sennard-antigravity-v1.1.0.zip`.
- Back up your state folder and business database before upgrading. Close host sessions, run `java -jar sennard-1.1.0.jar upgrade`, then update the host plugins and refresh hook registrations. For recovery, use the 1.0.0 JAR and its matching plugin files with the pre-upgrade backup. [Update guide](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.1.0/docs/user/update.md)

## Changes

- In the admin console, **Work → Workflow management** shows counts by state and lets you search, filter and sort open workflows. The release confirmation warns when an owner session is online. **Analytics → Workflow statistics** shows starts by kind and lets you filter recent runs and expand the breakdown of declared skills.
- Session start restores the common global, role and situation context when available. Codex and Claude Code also restore it after successful compaction.

### Fixed Defects

Ordinary prompts and tool or model calls no longer receive repeated copies of the saved context body. Hosts still receive short context notices, and the first delivery of subagent role context is preserved.

## Public Contract Changes

- Context restoration after compaction uses the `SessionStart` event with `source=compact` on Codex and Claude Code.
- Use `load_shared_context` for shared context and `load_context` for current session memory when explicit retrieval is needed.

## Known Limitations

- Antigravity and Gemini CLI lack a successful-compaction hook for automatic restoration. Retrieve context with the MCP tools when needed.
- Automated hook tests verify the server's responses. Live model receipt of the restored text and native plugin installation on all hosts remain unverified for this release.

## Verification

The workflow UI and PostgreSQL integration suites exercised the admin console and its server endpoints. HTTP hook integration tests covered session start, compaction and ordinary calls. See the release's GitHub Actions runs for the public source build and package checks.

---

# Sennard 1.1.0 릴리스 노트

Sennard Personal 1.1.0은 관리자 화면의 워크플로우 관리를 개선하고 세션 경계에서 저장된 문맥을 복원합니다.

## 업그레이드 전 확인

- JDK 25를 사용합니다. `sennard-1.1.0.jar`와 `sennard-1.1.0.jar.sha256`을 내려받아 실행 전에 체크섬을 대조합니다. Antigravity는 `sennard-antigravity-v1.1.0.zip`으로 설치합니다.
- 업그레이드하기 전에 상태 폴더와 업무 데이터베이스를 백업합니다. 호스트 세션을 닫고 `java -jar sennard-1.1.0.jar upgrade`를 실행한 뒤 호스트 플러그인을 업데이트하고 훅을 다시 등록합니다. 복구할 때는 업그레이드 전 백업과 1.0.0 JAR 및 같은 버전의 플러그인 파일을 사용합니다. [업데이트 안내](https://github.com/jaeseongs95/Sennard-Personal/blob/v1.1.0/docs/user/update.ko.md)

## 바뀐 점

- 관리자 화면의 **작업 → Workflow 관리**에서 상태별 건수를 확인하고 열린 워크플로우를 검색·필터링·정렬할 수 있습니다. 소유 세션이 온라인이면 해제 확인 화면에 경고가 표시됩니다. **분석 → workflow 통계**에서는 종류별 시작 건수를 보고, 최근 실행을 필터링하고, 선언 스킬 집계를 펼쳐 볼 수 있습니다.
- 세션을 시작할 때 저장된 공통 전역·역할·상황 문맥이 있으면 복원합니다. Codex와 Claude Code에서는 압축이 성공한 뒤에도 복원합니다.

### 고친 결함

일반 입력과 도구·모델 호출에 저장 문맥 본문을 반복해서 전달하지 않습니다. 호스트에는 짧은 문맥 안내를 제공하며, 하위 에이전트에 역할 문맥을 처음 전달하는 동작은 유지합니다.

## 공개 계약 변경

- Codex와 Claude Code는 `source=compact`인 `SessionStart` 이벤트에서 압축 후 문맥을 복원합니다.
- 문맥을 직접 불러올 때는 공통 문맥에 `load_shared_context`를, 현재 세션 메모리에 `load_context`를 사용합니다.

## 알려진 한계

- Antigravity와 Gemini CLI는 압축 성공을 알리는 훅을 제공하지 않습니다. 필요할 때 MCP 도구로 문맥을 불러옵니다.
- 자동 훅 시험은 서버 응답을 검증합니다. 실제 모델이 복원된 문맥을 받는지와 모든 호스트에서의 플러그인 설치 여부는 이번 판에서 확인하지 못했습니다.

## 검증

워크플로우 UI 시험과 PostgreSQL 통합 시험으로 관리자 화면 및 서버 엔드포인트를 확인했습니다. HTTP 훅 통합 시험은 세션 시작, 압축과 일반 호출을 대상으로 했습니다. 공개 소스 빌드와 패키지 확인 결과는 이 릴리스의 GitHub Actions 실행 기록에 있습니다.
