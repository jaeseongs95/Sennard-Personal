# Sennard

[English](README.md) | 한국어

Sennard(Sennard)는 Claude Code, Codex, Antigravity(`agy`)에 설치하는 로컬 플러그인입니다. 에이전트가 요청에 맞는 전문 스킬을 쓰고, 여러 단계의 일을 완료 기준에 따라 진행하며, 사용자의 교정과 남은 일을 이어 가도록 돕습니다. 문서·조사·의사결정·안내물·소프트웨어 작업에 쓸 수 있습니다. 세 호스트는 같은 로컬 서버에 연결해 공통 규칙을 따르며, hook 이벤트·깨우기 방식·설치 형식처럼 호스트마다 다른 부분은 호스트별 어댑터가 맡습니다.

**개인용 판.** 이 공개 저장소는 Sennard의 개인용 판입니다. 사용자 본인의 컴퓨터에 설치해 사용하며 별도의 운영자가 없습니다. 본인의 컴퓨터와 그 컴퓨터에서 실행하는 에이전트를 신뢰하는 구성을 전제로 합니다. 신뢰 경계는 [보안 정책](SECURITY.ko.md)에 있습니다.

사용자 문서, 보안 정책, 기여 안내, 기여자 참고 문서는 영문판과 한글판이 함께 있습니다.
[설치](#설치) · [사용하기](#사용하기) · [스킬](#스킬) · [문서](#문서)

## 어떤 일에 쓰나요?

| 하려는 일 | Sennard가 돕는 부분 |
| --- | --- |
| 자료를 조사하고 결정을 내리기 | 목표·제약·완료 기준 정리, 의견이 갈리는 선택의 검토, 주장과 실제 근거의 대조 |
| 문서·안내문·서비스 절차 만들기 | 독자와 이용자의 흐름·접근성 점검, 프롬프트 설계, 사실을 보존한 한국어 산문 편집 |
| 소프트웨어를 만들거나 고치기 | 작은 구현 방향 선택, 설계·시험·코드 리뷰·보안 검토, 반복 실패의 원인 판별 |
| 여러 단계나 세션에 걸쳐 이어 가기 | workflow 진행 상태, 교정·남은 일의 작업 기록, 세션 현황판과 PEER 메시지 |

## 구성

- **스킬:** 두 묶음 23개: 에이전트를 교정하는 스킬 15개(흐름을 잇는 `orchestrator`, 현황판 사용을 돕는 `session-board` 포함)와 전문분야 스킬 8개
- **로컬 서버:** 이 컴퓨터의 사용자당 하나이며 설치 명령, `sennard start` 또는 세션 시작 훅이 띄웁니다. MCP 서버(`POST /mcp`)로서 workflow, 작업 기록, 현황판, 세션 메시지, 상태 정리 도구를 제공합니다. 포트는 고정되지 않습니다. 서버가 시작할 때마다 운영체제가 빈 포트를 정해 주며, 서버가 상태 폴더의 `server.properties`에 주소를 알립니다. 사용자의 JDK 25로 실행합니다. 설정은 로컬에, 업무 상태는 별도로 연결한 데이터베이스에 저장합니다.
- **호스트 hook:** 세션·턴·도구 경계에서 안내를 넣고 편집 대상을 관측합니다. 호스트가 작은 Sennard Java 클라이언트를 command hook으로 실행하면, 이 클라이언트가 `server.properties`로 서버를 찾아 hook 입력을 보냅니다. Codex는 hook 대부분을 MCP 도구 호출로 전달합니다. 호스트는 설치 명령이 등록한 stdio 중계로 MCP 서버에 닿습니다.
- **로컬 관리자 페이지:** 전역 컨텍스트, 역할, JEV처럼 사람이 정하는 설정을 패스키로 서명해 바꿉니다. 포트는 서버가 시작할 때마다 운영체제가 정해 주며, 현재 주소는 `sennard status`가 출력합니다(`adminPage`).

안내가 있다는 사실만으로 에이전트가 지시를 따랐거나 결과가 검증됐다고 볼 수는 없습니다. Sennard가 하지 않는 일은 [사용하기](docs/user/usage.ko.md#sennard가-하지-않는-일)에 있습니다.

## 설치

Sennard는 이 컴퓨터의 사용자당 한 번 설치하는 서버와 호스트별 플러그인으로 나뉩니다. 서버를 먼저 설치하고, 협업할 호스트마다 플러그인을 설치합니다.

호스트의 플러그인 기능, [새 빈 업무 데이터베이스](docs/user/state.ko.md#업무-데이터베이스), JDK 25(`java -version`이 25를 보고해야 합니다)가 필요합니다. 이 저장소의 clone은 필요하지 않습니다.

릴리스는 모든 운영체제가 실행하는 jar 하나이며 운영체제별 압축 파일과 동봉 런타임은 없습니다. 지원 환경은 JDK 25가 도는 컴퓨터입니다. 검증한 호스트 환경은 릴리스 노트에 정리했습니다.

<!-- release-version:start -->
현재 공개 릴리스는 `v1.3.0`이며 아래 설치 예시는 이 버전 기준입니다. 설치하거나 업그레이드하기 전에 [1.3.0 릴리스 노트](docs/release-notes/release-notes-v1.3.0.md#sennard-130-릴리스-노트)를 읽습니다. JEV는 관리자 페이지에서만 설정합니다. 개인판의 버전별 변경 사항은 [GitHub 릴리스](https://github.com/jaeseongs95/Sennard-Personal/releases)와 영한 `docs/release-notes/release-notes-v<버전>.md`에 있습니다.
<!-- release-version:end -->

### 서버

[릴리스 페이지](https://github.com/jaeseongs95/Sennard-Personal/releases)에서 `sennard-<판>.jar`와 `sennard-<판>.jar.sha256`(`sha256sum --check` 형식의 SHA-256)을 받습니다. JDK 25로 실행하기 전에 JAR를 체크섬과 대조합니다. 새로 설치하려면 다음 명령을 실행합니다.

```bash
sha256sum --check sennard-<판>.jar.sha256
java -jar sennard-<판>.jar install
```

Windows에서는 체크섬 파일의 값을 `Get-FileHash -Algorithm SHA256 sennard-<판>.jar`의 출력과 대조합니다.

데스크톱에서 새로 설치할 때는 `java -jar sennard-<판>.jar setup`으로 설치 창을 열 수도 있습니다. 창에는 설치된 버전, 패키지 버전, 설치 폴더가 표시됩니다. 서버를 설치한 뒤 아래의 호스트별 플러그인 안내를 따릅니다.

#### 기존 설치 업데이트

1. 연동 중인 호스트 세션을 닫습니다. Sennard 상태 폴더와 연결된 업무 데이터베이스를 백업합니다.
2. 아래 호스트별 안내에 따라 설치된 모든 Sennard 호스트 플러그인을 대상 JAR와 정확히 같은 버전으로 업데이트합니다. 플러그인 업데이트에 사용한 호스트 세션을 닫습니다.
3. 체크섬을 확인한 대상 JAR를 JDK 25로 실행합니다. 데스크톱에서는 `java -jar sennard-<판>.jar setup`으로 설치 창을 열고 세션 종료와 백업을 확인하는 체크박스에 표시한 뒤 **업데이트 및 재시작**을 선택합니다. 터미널에서는 `java -jar sennard-<판>.jar upgrade`를 실행합니다. Sennard가 기존 Claude Code Stop 설정을 바꿀지 묻는다면 변경 내용을 검토하고 동의해 계속합니다.
4. 새 서버가 시작되면 서버와 데이터베이스의 상태를 확인합니다. 서버 업데이트 뒤에 같은 버전의 플러그인을 설치하거나 업데이트했다면 설치 창에서 **훅 등록**을 선택하거나 새 설치본으로 `sennard hooks`를 실행합니다. 그다음 새 호스트 세션을 시작합니다. Codex에서는 `/hooks`에서 훅을 다시 검토하고 신뢰합니다.

복구와 재시도 방법은 [업데이트 절차](docs/user/update.ko.md#업데이트-절차)를 참고합니다.

`HOST_PACKAGE_VERSION_MISMATCH` 오류로 서버 업데이트가 중단되면 오류에 표시된 필요 버전과 플러그인 사본을 확인합니다. 아래의 호스트별 안내에 따라 해당 사본의 버전을 맞추고, 수정에 사용한 세션을 닫은 뒤 서버 업데이트를 다시 실행합니다.

설치 명령은 jar를 설치 폴더 `~/.sennard/install`(`SENNARD_INSTALL_ROOT`로 바꿉니다)에 풀고, 시작 캐시를 학습하고, 서버를 시작해 준비를 기다린 뒤, 설치된 호스트의 플러그인에 hook·MCP 정의를 씁니다. 상태 폴더의 기본값은 `~/.sennard`입니다(`SENNARD_STATE_DIR` 또는 `--state-dir`로 바꿉니다). `~`는 JVM의 `user.home`이며 모든 운영체제에서 같은 질문의 답입니다. 설치 폴더를 시스템의 파일 이름 인코딩(`native.encoding`)으로 쓸 수 없으면 설치 명령은 `INSTALL_ROOT_NOT_REPRESENTABLE`로 끝나며 다른 폴더를 고르지 않습니다. `SENNARD_INSTALL_ROOT`를 그 인코딩으로 쓸 수 있는 폴더로 설정합니다. OS 서비스에 등록하거나 `PATH`에 무엇을 넣지는 않습니다.

`sennard`라는 프로그램은 없습니다. 이 문서에서 `sennard <명령>`은 `java -jar <설치 폴더>/versions/<판>/sennard-<판>.jar <명령>`을 뜻합니다. 설치·업그레이드가 출력하고 `sennard status`의 `command` 필드에 표시되는 명령입니다. 업그레이드하면 경로가 바뀝니다. `sennard status`로 서버 상태를 보고 `sennard stop`과 `sennard start`로 끄고 켭니다. 상태 폴더 하나에는 서버가 하나만 실행됩니다(`SERVER_ALREADY_RUNNING`).

플러그인에는 스킬과 빈 hook·MCP 정의가 들어 있습니다. `sennard install`, `sennard upgrade`, `sennard hooks`는 감지된 각 플러그인 사본의 버전이 명령의 대상 Sennard 버전과 정확히 일치할 때 이 PC의 정의를 호스트 사본에 씁니다. 정의에는 이 PC의 `java`와 JAR의 절대 경로가 들어 있습니다. Sennard 도구는 로컬 서버에 연결합니다.

Sennard 개인판 1.3.0의 GitHub 릴리스 첨부 파일에는 Gemini CLI 설치용 별도 패키지가 없습니다. 공개 소스에는 `gemini-extension/`이 포함됩니다. `~/.gemini/extensions/sennard`에 이전 버전의 Sennard 확장이 남아 있으면 `HOST_PACKAGE_VERSION_MISMATCH` 오류로 업그레이드가 중단됩니다. 터미널에서 `gemini extensions uninstall sennard`로 이전 확장을 제거합니다. `gemini extensions list`에 Sennard가 표시되지 않는지 확인한 뒤 업그레이드를 계속합니다. [Gemini CLI 확장 관리 안내](https://geminicli.com/docs/extensions/reference/)를 참고합니다.

### 패키지와 언어

패키지는 호스트마다 하나입니다. 모델이 읽는 Sennard 문구는 언어 설정 없이 문구마다 토큰이 적은 형태 하나로 고정됩니다. 그래서 hook 안내, MCP 도구 설명·결과, 오류, 스킬 검사 출력, 스킬 description은 영어이고 MCP instructions의 공통 접수 기준은 한국어이며, 스킬 본문은 원문(대부분 한국어) 그대로입니다.

| 호스트 | 패키지 |
| --- | --- |
| Claude Code | `sennard@sennard-claude` |
| Codex | `sennard@sennard` |
| Antigravity | `sennard-antigravity-<tag>.zip` |

### Claude Code

새로 설치할 때는 Claude Code 안에서 다음 명령을 실행합니다.

```text
/plugin marketplace add jaeseongs95/Sennard-Personal
/plugin install sennard@sennard-claude
```

기존 설치를 업데이트하려면 터미널에서 `claude plugin list --json`을 실행해 Sennard 설치 항목을 확인합니다. 서버에서 버전 불일치 오류가 발생하면 오류에 표시된 플러그인 ID, 설치 범위, 프로젝트 경로로 해당 항목을 찾습니다. `claude plugin update <ID> --scope <범위>`에 해당 ID와 `claude plugin list --json`에 표시된 범위를 그대로 넣어 실행하고, 프로젝트 경로가 표시된 항목은 해당 프로젝트에서 실행합니다. Claude Code의 [플러그인 목록](https://code.claude.com/docs/en/plugins/cli-reference#plugin-list)과 [업데이트](https://code.claude.com/docs/en/plugins/cli-reference#plugin-update) 명령을 참고합니다.

`claude plugin list --json`을 다시 실행해 각 설치 항목의 버전이 대상 JAR 버전과 정확히 일치하는지 확인합니다. 로컬 폴더에서 등록한 마켓플레이스가 그 폴더의 Sennard를 직접 불러오는 경우 오류가 가리키는 경로의 패키지를 확인하고 해당 폴더를 대상 버전으로 업데이트합니다. 서버를 업데이트하기 전에 플러그인 업데이트나 버전 확인에 사용한 Claude Code 세션을 닫습니다.

플러그인과 서버의 버전을 맞춥니다. 플러그인을 서버보다 나중에 설치하거나 업데이트했다면 `sennard hooks`를 실행합니다. 이 명령은 Claude Code가 읽는 플러그인 사본에 이 PC의 훅과 MCP 정의를 씁니다. 그다음 새 세션을 시작합니다.

### Codex

터미널에서 실행합니다.

<!-- release-install:start -->
```bash
codex plugin marketplace add jaeseongs95/Sennard-Personal --ref v1.3.0
codex plugin add sennard@sennard
```
<!-- release-install:end -->

기존 설치를 업데이트하려면 `codex plugin marketplace list`를 실행합니다. 목록에 `sennard` 마켓플레이스가 있으면 대상 태그를 등록하기 전에 `codex plugin marketplace remove sennard`로 기존 등록을 제거합니다. 그런 다음 위 명령 두 개를 실행하되 `--ref`에는 대상 릴리스 태그를 지정합니다. `codex plugin list --marketplace sennard`를 실행해 `sennard@sennard`가 설치되어 있으며 그 버전이 대상 JAR 버전과 정확히 일치하는지 확인합니다. [Codex 마켓플레이스 명령 안내](https://developers.openai.com/plugins/build/plugins#add-a-marketplace-from-the-cli)를 참고합니다.

서버에서 버전 불일치 오류가 발생하면 오류에 표시된 설치 ID와 캐시 경로로 해당 마켓플레이스를 찾습니다. `codex plugin list --marketplace <마켓플레이스>`로 플러그인을 확인합니다. 계속 사용하는 설치는 해당 마켓플레이스에서 대상 릴리스 버전을 다시 설치합니다. 사용하지 않는 설치는 `codex plugin remove sennard@<마켓플레이스>`로 제거합니다. Sennard가 버전을 `[?]`로 표시하면 해당 ID에도 같은 재설치 또는 제거 절차를 따릅니다. 같은 ID에서 Codex가 선택하지 않는 이전 버전 캐시는 그대로 둘 수 있습니다. [Codex 플러그인 CLI 안내](https://learn.chatgpt.com/docs/developer-commands?surface=cli#cli-codex-plugin)를 참고합니다.

플러그인과 서버의 버전을 맞춥니다. 플러그인을 서버보다 나중에 설치하거나 업데이트했다면 `sennard hooks`를 실행합니다. 새 Codex 세션을 시작한 뒤 `/hooks`에서 Sennard 훅을 검토하고 신뢰합니다. 턴 안내, 작업 기록, 현황판, 메시지 전달은 신뢰한 훅을 통해 동작합니다. 훅 정의가 바뀐 판으로 업데이트하면 다시 검토합니다. 파일이 바뀐 뒤 Codex가 신뢰 여부를 다시 묻는 동작은 실제 Codex에서 확인이 필요합니다.

### Antigravity (`agy`)

`agy plugin install`은 로컬 디렉터리만 받습니다. 릴리스에 첨부한 설치본을 받아 풀고 그 디렉터리를 설치합니다. 설치본에는 agy가 읽는 파일만 들어 있습니다. 저장소 전체를 설치하면 Antigravity 앱의 설치됨 화면이 멈출 수 있습니다. 설치하는 동안 Antigravity 앱을 닫아 둡니다. 앱이 켜져 있으면 기존 설치 폴더를 지우지 못해 설치가 실패합니다. Windows에서는 `Expand-Archive`로 풉니다.

<!-- release-agy:start -->
```bash
curl -LO https://github.com/jaeseongs95/Sennard-Personal/releases/download/v1.3.0/sennard-antigravity-v1.3.0.zip
unzip sennard-antigravity-v1.3.0.zip
agy plugin install ./sennard
agy plugin list
```
<!-- release-agy:end -->

업데이트할 때는 대상 릴리스의 ZIP을 사용합니다. Antigravity CLI 세션에서 `/plugin`을 열고 **Installed** 탭에서 Sennard를 선택한 뒤 **Version**을 대상 JAR 버전과 대조합니다. 서버를 업데이트하기 전에 해당 세션을 닫습니다. [Antigravity CLI 플러그인 관리자 안내](https://antigravity.google/docs/marketplace?tab=cli)를 참고합니다.

`agy` CLI가 없는 Antigravity 네이티브 앱에서는 [공식 수동 설치 안내](https://antigravity.google/docs/plugins#manual-plugin-installation)를 따릅니다. 앱을 정상 종료하고 기존 Sennard 폴더를 플러그인 검색 경로 밖에 복구용으로 보존한 뒤, 릴리스에 첨부된 Antigravity 설치용 ZIP의 `sennard` 폴더를 `~/.gemini/config/plugins/` 아래에 둡니다(Windows: `%USERPROFILE%/.gemini/config/plugins/sennard`). 앱을 다시 시작하고 `Customizations > Installed`에서 Sennard 버전을 확인합니다. 저장소 전체를 복사하지 마십시오. 이 방식에는 CLI 추가 설치가 필요하지 않습니다.

업데이트할 때는 해당 화면에서 설치된 버전이 대상 JAR 버전과 정확히 일치하는지 확인한 뒤 서버를 업데이트하기 전에 앱을 다시 닫습니다.

설치본에는 스킬과 빈 MCP 등록(`mcp_config.json`), 빈 hook(`hooks.json`)이 들어 있습니다. 플러그인과 서버의 버전을 맞춥니다. 플러그인을 서버보다 나중에 설치하거나 업데이트했다면 `sennard hooks`를 실행합니다. 이 명령은 이 설치의 MCP 등록(서버 이름 `sennard`, `--host=agy`를 준 stdio 중계)과 훅 정의를 플러그인 사본에 씁니다. 그다음 새 세션을 시작합니다. 이 저장소 최상위의 `GEMINI.md`와 `AGENTS.md`는 Sennard 자체를 개발할 때 쓰는 지침이며 설치본에 들어 있지 않습니다.

### 설치 뒤 확인

- `sennard status`로 `healthy`가 `true`인지 확인합니다. 서버가 뜨기 전에 시작한 호스트 세션에는 Sennard 도구가 없고 나중에 연결되지 않으므로 새 세션을 엽니다.
- 새 세션에서 Sennard 스킬과 MCP 도구가 보이는지 확인합니다. 보이지 않으면 [문제 해결](docs/user/troubleshooting.ko.md)을 봅니다.
- 관리자 페이지(`sennard status`가 출력하는 `adminPage` 주소)를 열어 첫 패스키를 등록합니다. 첫 키는 확인 없이 받으므로 먼저 등록해 두는 것이 좋습니다. 개인용은 패스키를 하나만 쓰며, 잃어버리면 `sennard admin-reset`을 실행합니다. [관리자 페이지와 컨텍스트](docs/user/admin-page.ko.md)
- 관리자 페이지에서 패스키로 서명해 업무 데이터베이스(JDBC 드라이버, URL, 계정, 비밀번호 파일)를 연결합니다. 연결 정보는 `sennard.yaml`이 아니라 상태 폴더에 저장되며, 모든 표는 스키마 `sennard` 하나에 만들어집니다. [업무 데이터베이스](docs/user/state.ko.md#업무-데이터베이스)

## 사용하기

원하는 결과와 확인할 조건, 바꾸면 안 되는 대상을 함께 알려 주십시오. 에이전트는 도구 없이 답하는 경우를 포함해 모든 요청에 워크플로 절차를 선택하고, 어떤 스킬이 적용되는지 확인합니다. 스킬과 워크플로 선택은 에이전트에게 맡기면 됩니다.

```text
이 기능을 구현하고 검증해 줘. 수정 범위와 완료 기준을 먼저 정리하고,
최종 결과에는 실제로 실행한 검사와 아직 해결하지 못한 문제를 함께 적어 줘.
```

설치하면 달라지는 동작, workflow와 편집 관문, 작업 기록은 [사용하기](docs/user/usage.ko.md)에, 여러 세션의 협업은 [세션 협업](docs/user/collaboration.ko.md)에 있습니다.

## 스킬

Sennard의 스킬 23개는 두 묶음입니다. 묶음은 스킬의 `classification.json`에 있는 `category`(`agent-correction` 또는 `specialist`)입니다. 이 값은 여기와 관리자 페이지에 스킬을 나누어 보여 주는 데만 쓰이며, 스킬을 고르는 방식, 검사, hook은 바꾸지 않습니다. provider가 있는 스킬의 버전은 [registry](skills/registry.json)와 [source lock](skills/source-lock.json)을 따릅니다. 작업 기록은 스킬이 아니라 MCP 도구와 hook이 맡습니다.

### 에이전트 교정(15개)

에이전트가 요청, 근거, 합의한 범위를 지키게 하는 스킬입니다. 작업을 정의하고, 위임하고, 범위와 위험을 점검하고, 반복 실패를 진단하고, 감사하고, 다른 스킬을 잇습니다.

| 스킬 | 버전 | 하는 일 |
| --- | --- | --- |
| [`orchestrator`](skills/orchestrator/) | 1.2.0 | 요청에 필요한 스킬을 고르고 그 순서, 입력, 출력, 관문을 잇습니다. |
| [`session-board`](skills/session-board/) | 1.0.0 | 같은 작업 폴더를 다루는 세션들이 현황판에 현재 작업을 기록하고 읽는 방법을 담고 있습니다. |
| [`instruction-scope-resolver`](skills/instruction-scope-resolver/) | 1.0.0 | 경로에 적용되는 지침 파일과 우선순위를 확인합니다. |
| [`task-contract`](skills/task-contract/) | 1.1.0 | 목표, 범위, 완료 기준, 위험, 권한을 작업 계약으로 정리합니다. |
| [`coordinate-subagents`](skills/coordinate-subagents/) | 2.1.0 | 위임할지 판단하고 담당 범위, 전달할 맥락, 검증 책임을 정하며, 승인된 담당마다 호스트가 제시한 조합에서 모델과 추론 수준을 선택합니다. |
| [`minimal-implementation`](skills/minimal-implementation/) | 1.0.0 | 코드, 문서, 자료, 설정, 절차 등 모든 결과물에서 필요한 것만 담은 가장 작은 완전한 결과를 고르고, 대체물로 만든 통과를 완료로 내지 않습니다. |
| [`change-scope-guardian`](skills/change-scope-guardian/) | 1.0.0 | run의 기준 상태와 기록된 변경을 비교해 write target 아래에서 계획 밖이거나 제외된 경로의 변경을 찾습니다. write target 밖의 변경은 기록되지 않아 보지 못합니다. |
| [`mutation-risk-preflight`](skills/mutation-risk-preflight/) | 1.0.3 | 되돌리기 어려운 변경 직전에 대상, 승인, 영향, 복구 조건을 점검합니다. |
| [`independent-deliberation-panel`](skills/independent-deliberation-panel/) | 2.0.0 | 갈리는 결정을 독립 관점과 반론으로 검토합니다. |
| [`iteration-frame-auditor`](skills/iteration-frame-auditor/) | 1.0.0 | 반복 작업에서 원래 계약과 판단 전제가 바뀌었는지 감사합니다. |
| [`acceptance-evidence-validator`](skills/acceptance-evidence-validator/) | 1.0.0 | 완료 기준을 실제 결과의 근거와 대조합니다. |
| [`independent-audit-gate`](skills/independent-audit-gate/) | 1.0.0 | 변경을 만든 작업자와 분리된 감사자가 고위험 변경과 근거를 확인합니다. |
| [`blocker-diagnostician`](skills/blocker-diagnostician/) | 1.1.0 | 반복 실패에서 관측과 가설을 나누고 다음 판별 검사를 고릅니다. |
| [`recovery-strategy-selector`](skills/recovery-strategy-selector/) | 0.2.0 | 원인이 확정된 실패의 복구 전략을 비교하고 인계 자료를 만듭니다. |
| [`evaluation-validity-auditor`](skills/evaluation-validity-auditor/) | 1.0.0 | 평가의 입력·판정·집계가 공정하고 재현 가능한지 감사합니다. |

### 전문분야(8개)

한 분야의 전문성을 작업에 가져오는 스킬입니다. 소프트웨어 설계, 시험, 코드 리뷰, 보안, 사용자 인터페이스, 한국어 산문, 프롬프트를 다룹니다.

| 스킬 | 버전 | 하는 일 |
| --- | --- | --- |
| [`cs-engineering`](skills/cs-engineering/) | 0.2.0 | 동시성·DB·메시징·알고리즘·성능 문제에 CS 원리로 불변조건과 검증 의무를 세웁니다. |
| [`solid-design`](skills/solid-design/) | 0.1.0 | 실제 모듈·계약·어댑터 경계에 SOLID를 적용해 최소 변경 방향을 제시합니다. |
| [`test-engineering`](skills/test-engineering/) | 0.1.0 | 요구와 코드 경계에서 시험 사례를 설계하고 결함 탐지 능력을 확인합니다. |
| [`code-review`](skills/code-review/) | 0.1.0 | 고정된 diff·PR의 실제 실패 경로를 검토하고 결함·권고·질문을 나눕니다. |
| [`software-security-auditor`](skills/software-security-auditor/) | 0.1.0 | 웹·API와 CLI·MCP의 공격 경로, 방어 통제, 검사 공백을 감사합니다. |
| [`ui-ux-design`](skills/ui-ux-design/) | 0.1.0 | 화면, 인쇄·광고, 영상, 공간·안내물, 제품, 서비스 절차, 문서·양식처럼 사람이 보고 쓰는 결과물의 사용자 흐름·정보 계층·접근성·예외 상황을 설계·점검하고, 대상 사용자에게 통하는지부터 확인합니다. |
| [`korean-prose-editor`](skills/korean-prose-editor/) | 0.1.0 | 사실·숫자·링크·코드를 보존하며 한국어 산문을 편집하고 검증합니다. |
| [`prompt-engineering`](skills/prompt-engineering/) | 0.1.0 | LLM 프롬프트·지침·입력 템플릿을 설계·진단하고 비교 검증 사례를 만듭니다. |

언제 어떤 스킬을 쓰는지는 [사용하기의 스킬을 쓰는 시점](docs/user/usage.ko.md#스킬을-쓰는-시점)에 있습니다.

## 문서

| 대상 | 문서 |
| --- | --- |
| 사용자 | [사용하기](docs/user/usage.ko.md), [전체 흐름도](docs/user/system-flow.ko.md), [관리자 페이지와 컨텍스트](docs/user/admin-page.ko.md), [세션 협업](docs/user/collaboration.ko.md), [상태 저장과 정리](docs/user/state.ko.md), [업데이트와 이전](docs/user/update.ko.md), [문제 해결](docs/user/troubleshooting.ko.md) |
| 기여자 | [기여 안내](CONTRIBUTING.ko.md)(외부 기여는 받지 않음), [아키텍처](docs/contributing/architecture.ko.md), [개발 참고](docs/contributing/development.ko.md), [시험과 CI](docs/contributing/testing-and-ci.ko.md), [발행](docs/contributing/release.ko.md) |
| 전체 목록 | [문서 안내](docs/README.ko.md), [릴리스 노트](docs/release-notes/), [보안 정책](SECURITY.ko.md) |

## 라이선스

Sennard는 [GNU Affero General Public License 3판 이상](LICENSE)(SPDX: `AGPL-3.0-or-later`)으로 제공하며, AGPL 제7조에 따른 추가 조항(표시, 출처, 이름과 표장)은 [ADDITIONAL-TERMS.ko.md](ADDITIONAL-TERMS.ko.md)에 있습니다(영문 정본: [ADDITIONAL-TERMS.md](ADDITIONAL-TERMS.md)). `korean-prose-editor` 스킬은 원저작자의 MIT 라이선스를 따릅니다. 이 프로젝트는 외부 기여(pull request)를 받지 않습니다. [기여 안내](CONTRIBUTING.ko.md)를 참고하세요.
