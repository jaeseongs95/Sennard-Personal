[English](update.md)

# 업데이트와 이전

처음 설치는 [README의 설치 절](../../README.ko.md#설치)을 봅니다. 이 문서는 새 판 알림, 업데이트 절차, 언어 패키지 바꾸기, 이전 판에서 올릴 때 확인할 것을 설명합니다.

## 새 판 알림

상주 서버는 고정된 공개 저장소에서 `vMAJOR.MINOR.PATCH` 형식의 안정 tag만 확인합니다.

- 성공한 확인 결과는 24시간 동안 보관합니다. 확인에 실패하면 마지막 성공 결과를 유지하고 1시간 뒤 다시 시도합니다. 확인 실패는 workflow를 멈추지 않습니다.
- 설치된 판보다 높은 안정 판이 있으면 도구 응답 뒤에 `plugin-update-notice`를 한 번 붙입니다. 같은 판의 알림은 한 번만 나옵니다.
- 직접 확인하려면 에이전트에게 `check_for_updates`를 호출하게 합니다. `force: true`이면 보관한 결과와 관계없이 다시 확인합니다.
- 알림만 할 뿐 플러그인 파일, 설치 캐시, 마켓플레이스 설정을 바꾸거나 자동으로 설치하지 않습니다.
- 스킬을 MCP 없이 직접 호출한 경우에는 업데이트를 확인하지 않습니다.

## 업데이트 절차

먼저 새 판의 릴리스 노트에서 「업그레이드 전 확인」이나 깨지는 변경을 확인합니다.
6.0.0 이후의 판으로 올릴 때는 다음을 합니다.

1. 호스트 세션을 모두 닫습니다.
2. 새 판의 `sennard-<판>.jar`와 `.sha256`을 받아 jar를 대조한 뒤 JDK 25로 `java -jar sennard-<판>.jar upgrade`를 실행합니다. 새 판을 옛 판 옆에 풀고 AOT 캐시를 만든 뒤 서버를 멈추고(프로세스가 끝나기를 기다립니다) 새 판을 시작합니다. 서버가 정상으로 뜬 뒤 옛 판 폴더를 지우고, 호스트 정의를 다시 쓰고, 새 명령 경로를 출력합니다. 같은 판이면 `SAME_VERSION`, 낮은 판이면 `DOWNGRADE_REFUSED`로 거절합니다. 설치된 판 폴더의 `sennard.yaml`([위치](state.ko.md#저장-위치))은 새 판으로 복사됩니다. 업무 데이터베이스의 연결은 그 파일에 있지 않고 상태 폴더의 설정 데이터베이스에 있으므로 업그레이드가 그대로 둡니다.
3. 각 호스트에 [README의 설치 절](../../README.ko.md#설치)대로 새 판의 플러그인을 설치합니다.
4. 새 세션을 시작합니다. 호스트별로 다음을 합니다.
   - **Claude Code:** 플러그인을 설치한 뒤 `sennard hooks`를 실행합니다. 그다음 새 세션을 시작합니다.
   - **Codex:** `/hooks`에서 Sennard hook을 다시 검토하고 신뢰합니다. 신뢰하기 전에는 hook이 실행되지 않아 턴 안내, 작업 기록, 현황판, 메시지 전달이 동작하지 않습니다.
   - **Antigravity:** 앱을 닫고 새 설치본을 설치한 뒤 앱을 다시 시작합니다. 앱이 켜져 있으면 기존 설치 폴더를 지우지 못해 설치가 실패합니다. 플러그인을 설치한 뒤 `sennard hooks`를 실행합니다.

### 플러그인 업데이트와 `sennard hooks`

플러그인 패키지에는 빈 hook·MCP 정의가 들어 있습니다. 정의에는 이 PC의 `java`, jar, 서버 주소 파일의 절대 경로가 적히는데 패키지는 이를 알 수 없기 때문입니다. `sennard install`·`sennard upgrade`·`sennard hooks`가 호스트가 읽는 플러그인 사본에 이 설치의 정의를 쓰며, 모든 운영체제에서 같습니다. 같은 주 판(major)의 플러그인 사본에만 쓰며, 다른 주 판의 사본은 그대로 두고 그 사실을 출력합니다.

- **모든 호스트:** 호스트는 플러그인을 설치하거나 업데이트할 때 배포본의 (빈) 정의를 새로 복사합니다. 그때마다 `sennard hooks`를 다시 실행한 뒤 새 세션을 시작합니다. `sennard status`의 `hostCopiesWithoutDefinitions`가 지금 비어 있는 사본을 알려 줍니다. 플러그인이 아직 없을 때 `sennard install`을 실행하면 「plugin is not installed yet; after installing it, run: sennard hooks」가 출력됩니다.
- **사본의 위치:** Claude Code는 `installed_plugins.json`으로 찾고(아래), Antigravity는 `~/.gemini/config/plugins/sennard`, Gemini CLI는 `~/.gemini/extensions/sennard`(연결(link)한 확장은 사용자의 폴더이므로 쓰지 않습니다), Codex는 `~/.codex/plugins/cache/<마켓플레이스>/sennard/<판>/`(`CODEX_HOME`이 Codex 폴더를 옮깁니다)입니다. Codex와 Gemini CLI의 위치는 해당 호스트가 있는 컴퓨터에서 확인하지 않았습니다.

`sennard hooks`는 Claude Code가 `installed_plugins.json`에 기록한 `installPath`로 Claude Code 플러그인을 찾습니다. 로컬 폴더 경로로 추가한 마켓플레이스의 플러그인은 `installPath`가 무엇을 가리키든 Claude Code가 그 마켓플레이스 폴더에서 읽으므로, `sennard hooks`는 `known_marketplaces.json`과 마켓플레이스의 `.claude-plugin/marketplace.json`으로 그 폴더를 찾습니다. Claude Code의 플러그인 캐시 밖 폴더는 사용자의 파일이므로 쓰지 않고, `sennard hooks`가 Claude Code가 읽는 파일과 이 설치의 정의를 알리는 오류로 끝납니다. 그 정의를 해당 파일에 덮어쓰거나 로컬 폴더가 아닌 마켓플레이스로 플러그인을 설치한 뒤([README](../../README.ko.md#claude-code)) `sennard hooks`를 다시 실행합니다. 그때까지 그 플러그인의 hook과 MCP 도구에는 정의가 없어 실행되지 않습니다.

### 서버 멈춤과 제거

서버를 멈출 때는 `sennard stop`, 다시 켤 때는 `sennard start`, 지울 때는 `sennard uninstall`을 씁니다. Sennard는 OS 서비스에 등록하지 않습니다. 서버를 멈춘 뒤에도 다음 세션 시작 훅이 서버를 다시 띄울 수 있습니다. `sennard uninstall`은 프로그램(그리고 `sennard.yaml`)을 지우고 상태 폴더와 업무 데이터베이스는 남깁니다. 업무 데이터베이스의 연결은 상태 폴더에 있으므로, 같은 상태 폴더에 나중에 설치하면 데이터베이스가 연결된 채로 시작합니다. 설치 폴더 안의 jar가 아니라 설치 폴더 밖에 둔 릴리스 jar의 사본으로 실행합니다(`UNINSTALL_FROM_INSTALLED_COPY`).

`sennard uninstall`은 Sennard가 호스트의 플러그인 사본에 써 둔 정의 파일 중 쓴 내용 그대로인 파일을 지우고, 여러 설정이 든 파일의 한 항목(Gemini CLI 매니페스트의 `mcpServers` 항목)은 바뀌지 않았으면 원래대로 되돌립니다. 그 뒤 바뀐 파일은 남긴 채 이름을 출력합니다. 호스트의 플러그인 자체는 지우지 않으므로 각 호스트에서 Sennard 플러그인을 제거합니다(Claude Code: `/plugin uninstall sennard@sennard-claude`). 남은 플러그인은 연결할 서버가 없습니다.

문제가 생기면 상태 폴더를 지우지 않고 보존한 채 조사합니다. 이전 판을 다시 설치하는 것만으로 롤백되지 않을 수 있습니다. 각 판의 되돌리기 방법은 그 판의 릴리스 노트에 있습니다.

## 언어

4.0.0부터 패키지는 호스트마다 하나입니다. 모델이 읽는 Sennard 문구는 설치 환경의 언어와 관계없이 문구마다 토큰이 적은 형태 하나로 고정됩니다(hook 안내·도구 설명·결과·CLI 출력·스킬 description은 영어, MCP instructions의 공통 접수 기준은 한국어). 스킬 본문은 원문(대부분 한국어) 그대로입니다. 표시 언어 설정과 `SENNARD_LOCALE`은 더 이상 효과가 없습니다([관리자 페이지와 컨텍스트](admin-page.ko.md#언어)).

## 이전 판에서 올릴 때

- **6.0.0:** 서버 설치 단계가 새로 생겼습니다. 릴리스는 사용자의 JDK 25로 실행하는 jar 하나(`java -jar`)이며, 서버의 포트는 시작할 때마다 운영체제가 정합니다. 로컬 설정은 H2에, 업무 상태는 별도로 연결한 데이터베이스에 저장합니다. 공개 계약 변경은 [6.0.0 릴리스 노트](../release-notes/release-notes-v6.0.0.md#sennard-600-릴리스-노트)를 따릅니다.
- **영어 전용 패키지:** 영어 전용 패키지(`sennard-en`, agy `-en.zip`)는 더 이상 배포하지 않습니다. 그것을 설치했다면 지우고 `sennard`를 설치합니다.
