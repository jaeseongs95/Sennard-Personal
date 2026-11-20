[English](#sennard-120-release-notes) | [한국어](#sennard-120-릴리스-노트)

# Sennard 1.2.0 Release Notes

Sennard Personal 1.2.0 adds a desktop window for installing and updating the local server.

## Before Upgrading

- Use JDK 25. Verify `sennard-1.2.0.jar` against `sennard-1.2.0.jar.sha256` before running the JAR. For Antigravity, use `sennard-antigravity-v1.2.0.zip`.
- Close connected host sessions and back up the Sennard state folder and any configured business database. For recovery, keep the 1.1.0 JAR, its matching plugins, and the pre-upgrade backups. [Update guide](../user/update.md)

## Changes

- Run `java -jar sennard-1.2.0.jar setup` to open the English or Korean setup window. It shows the package version, installed version, and destination. Install a new copy or choose **Update and restart** for an existing installation.
- After installation, the window shows server and database status and provides access to the admin page and hook registration. Update each host plugin using its installation instructions, then register hooks before opening a new host session.

### Fixed Defects

This release contains no separate defect fixes.

## Public Contract Changes

The JAR adds the `setup` command. Existing terminal commands and the business database schema are unchanged.

## Known Limitations

Setup requires a graphical desktop. On headless systems, use the `install` and `upgrade` commands. Native host plugin installation on Claude Code, Codex, and Antigravity is outside the isolated setup test coverage.

## Verification

Windows setup tests cover the initial window, installation, updating an existing installation, and server readiness. Host integration tests exercise hook definitions in isolated host directories.

---

# Sennard 1.2.0 릴리스 노트

Sennard Personal 1.2.0은 로컬 서버를 설치하고 업데이트하는 데스크톱 창을 추가합니다.

## 업그레이드 전 확인

- JDK 25를 사용합니다. JAR를 실행하기 전에 `sennard-1.2.0.jar`를 `sennard-1.2.0.jar.sha256`과 대조합니다. Antigravity는 `sennard-antigravity-v1.2.0.zip`을 사용합니다.
- 연결된 호스트 세션을 닫고 Sennard 상태 폴더와 연결된 업무 데이터베이스를 백업합니다. 복구를 위해 1.1.0 JAR, 같은 버전의 플러그인, 업그레이드 전 백업을 보관합니다. [업데이트 안내](../user/update.ko.md)

## 바뀐 점

- `java -jar sennard-1.2.0.jar setup`을 실행하면 영문 또는 한글 설치 창이 열립니다. 창에서 패키지 버전, 설치된 버전, 설치 경로를 확인하고 새로 설치하거나 기존 설치에 **업데이트 및 재시작**을 선택할 수 있습니다.
- 설치가 끝나면 창에 서버와 데이터베이스 상태가 표시됩니다. 창에서 관리자 페이지를 열거나 훅을 등록할 수 있습니다. 각 호스트의 설치 안내에 따라 플러그인을 업데이트한 뒤 훅을 등록하고 새 호스트 세션을 시작합니다.

### 고친 결함

이번 판에는 별도의 결함 수정이 없습니다.

## 공개 계약 변경

JAR에 `setup` 명령을 추가합니다. 기존 터미널 명령과 업무 데이터베이스 스키마는 그대로입니다.

## 알려진 한계

설치 창을 열려면 그래픽 데스크톱이 필요합니다. 그래픽 환경이 없으면 `install`과 `upgrade` 명령을 사용합니다. Claude Code, Codex, Antigravity의 실제 호스트 플러그인 설치는 격리된 설치 창 시험 범위에 포함되지 않습니다.

## 검증

Windows 설치 창 시험은 첫 화면, 신규 설치, 기존 설치 업데이트, 서버 준비 상태를 확인합니다. 호스트 연동 시험에서는 격리된 호스트 폴더에 훅 정의를 적용합니다.
