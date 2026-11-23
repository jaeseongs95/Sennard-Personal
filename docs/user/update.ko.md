[English](update.md)

# 업데이트와 이전

Sennard Personal의 첫 공개판인 1.0.0 설치 절차는 [README의 설치 절](../../README.ko.md#설치)에 있습니다. 이 문서는 후속 판의 알림, 업데이트 절차와 플러그인 훅 설정을 설명합니다.

## 새 판 알림

상주 서버는 고정된 공개 저장소에서 `vMAJOR.MINOR.PATCH` 형식의 안정 tag만 확인합니다.

- 성공한 확인 결과는 24시간 동안 보관합니다. 확인에 실패하면 마지막 성공 결과를 유지하고 1시간 뒤 다시 시도합니다. 확인 실패는 workflow를 멈추지 않습니다.
- 설치된 판보다 높은 안정 판이 있으면 도구 응답 뒤에 `plugin-update-notice`를 한 번 붙입니다. 같은 판의 알림은 한 번만 나옵니다.
- 직접 확인하려면 에이전트에게 `check_for_updates`를 호출하게 합니다. `force: true`이면 보관한 결과와 관계없이 다시 확인합니다.
- 알림만 할 뿐 플러그인 파일, 설치 캐시, 마켓플레이스 설정을 바꾸거나 자동으로 설치하지 않습니다.
- 스킬을 MCP 없이 직접 호출한 경우에는 업데이트를 확인하지 않습니다.

## 업데이트 절차

후속 판으로 업데이트하기 전에 해당 릴리스 노트의 「업그레이드 전 확인」에서 이관·호환성·복구 절차를 확인합니다.
개인판을 업데이트할 때는 다음 절차를 따릅니다.

1. 연동 중인 호스트 세션을 닫고 Sennard 상태 폴더와 연결된 업무 데이터베이스를 백업합니다.
2. Sennard가 이미 설치된 호스트에서는 [README의 호스트별 설치 절차](../../README.ko.md#설치)에 따라 플러그인을 대상 판과 정확히 같은 판으로 업데이트합니다. Antigravity 플러그인 파일을 교체하기 전에는 앱을 닫습니다. 훅을 추가로 등록하고 새 세션을 시작하는 방법은 4단계를 따릅니다.

   Sennard 개인판 1.3.0의 GitHub 릴리스 첨부 파일에는 Gemini CLI 설치용 별도 패키지가 없습니다. 공개 소스에는 `gemini-extension/`이 포함됩니다. `~/.gemini/extensions/sennard`에 이전 버전의 Sennard 확장이 남아 있으면 `HOST_PACKAGE_VERSION_MISMATCH` 오류로 업그레이드가 중단됩니다. 터미널에서 `gemini extensions uninstall sennard`로 이전 확장을 제거한 뒤 업그레이드를 다시 실행합니다. [Gemini CLI 공식 제거 안내](https://geminicli.com/docs/extensions/reference/#uninstall-an-extension)를 참고합니다.
3. 플러그인 업데이트에 사용한 호스트 세션을 모두 닫습니다. 대상 판의 `sennard-<판>.jar`와 `.sha256`을 받아 JAR를 대조한 뒤 JDK 25로 `java -jar sennard-<판>.jar upgrade`를 실행합니다. Sennard는 감지된 플러그인 패키지의 버전을 확인한 다음 새 런타임을 게시합니다. 새 런타임을 이전 판 옆에 풀고 AOT 캐시를 만든 뒤 기존 서버를 멈추고 새 서버를 시작합니다. 호스트 정의를 쓴 다음 정리할 수 있는 이전 판을 정리하고 새 명령 경로를 출력합니다. 이미 같은 판으로 업그레이드를 마쳤으면 `SAME_VERSION`, 더 낮은 판이면 `DOWNGRADE_REFUSED`로 거절합니다. 호스트 정의 쓰기나 이전 판 정리가 중단됐다면 같은 JAR의 `upgrade` 명령을 다시 실행해 이어서 처리합니다.
4. 서버 업그레이드 뒤에 플러그인을 설치하거나 업데이트하면 `sennard hooks`를 실행합니다. 그다음 호스트를 다시 열고 새 세션을 시작합니다. Codex는 `/hooks`에서 Sennard 훅을 다시 검토하고 신뢰합니다. 스킬 선택 안내, 작업 기록 안내와 저장, 현황판, 메시지 전달에 이 훅이 필요합니다.

업그레이드는 이전 설치의 `sennard.yaml`([위치](state.ko.md#저장-위치))을 새 판에 복사합니다. 업무 데이터베이스의 연결은 상태 폴더의 설정 데이터베이스에 그대로 둡니다.

이전 판을 정리할 때는 정의를 통해 호스트나 사용자에게 제공된 적이 없음을 확인할 수 있는 판만 삭제합니다. 호스트 정의 등이 여전히 가리킬 수 있는 이전 판은 보존합니다. 정리 대상 폴더는 삭제 전에 설치 버전 목록에서 분리하고, 정리가 중단된 기록이 있으면 이후 `upgrade`에서 다시 시도합니다. `sennard status`는 런타임 파일이 불완전한 폴더를 설치 버전 목록에서 제외합니다.

### 플러그인 업데이트와 `sennard hooks`

플러그인 패키지에는 빈 hook·MCP 정의가 들어 있습니다. 정의에는 이 PC의 `java`, jar, 서버 주소 파일의 절대 경로가 적히는데 패키지는 이를 알 수 없기 때문입니다. `sennard install`·`sennard upgrade`·`sennard hooks`는 감지된 모든 호스트 플러그인 패키지의 버전이 각 명령의 대상 Sennard 버전과 정확히 일치해야 합니다. 일치하지 않거나 읽을 수 없는 버전이 있으면 호스트 정의를 쓰기 전에 `HOST_PACKAGE_VERSION_MISMATCH`로 중단합니다. 오류에는 필요한 버전과 해당 사본이 표시됩니다. `sennard status`와 `check_for_updates`는 해당 사본을 `hostPackageMismatches`에 표시할 수 있습니다.

- **모든 호스트:** 호스트는 플러그인을 설치하거나 업데이트할 때 배포본의 (빈) 정의를 새로 복사합니다. 같은 버전의 서버를 설치한 뒤에 플러그인을 설치하거나 업데이트했다면 `sennard hooks`를 다시 실행하고 새 세션을 시작합니다. `sennard status`의 `hostCopiesWithoutDefinitions`가 지금 비어 있는 사본을 알려 줍니다. 플러그인이 아직 없을 때 `sennard install`을 실행하면 「plugin is not installed yet; after installing it, run: sennard hooks」가 출력됩니다.
- **사본의 위치:** Claude Code는 `installed_plugins.json`으로 찾고(아래), Antigravity는 `~/.gemini/config/plugins/sennard`, Gemini CLI는 `~/.gemini/extensions/sennard`(연결(link)한 확장은 사용자의 폴더이므로 쓰지 않습니다), Codex는 `~/.codex/plugins/cache/<마켓플레이스>/sennard/<판>/`(`CODEX_HOME`이 Codex 폴더를 옮깁니다)입니다. Codex와 Gemini CLI의 위치는 해당 호스트가 있는 컴퓨터에서 확인하지 않았습니다.

`sennard hooks`는 Claude Code가 `installed_plugins.json`에 기록한 `installPath`로 Claude Code 플러그인을 찾습니다. 로컬 폴더 경로로 추가한 마켓플레이스의 플러그인은 `installPath`가 무엇을 가리키든 Claude Code가 그 마켓플레이스 폴더에서 읽으므로, `sennard hooks`는 `known_marketplaces.json`과 마켓플레이스의 `.claude-plugin/marketplace.json`으로 그 폴더를 찾습니다. Claude Code의 플러그인 캐시 밖 폴더는 사용자의 파일이므로 쓰지 않고, `sennard hooks`가 Claude Code가 읽는 파일과 이 설치의 정의를 알리는 오류로 끝납니다. 그 정의를 해당 파일에 덮어쓰거나 로컬 폴더가 아닌 마켓플레이스로 플러그인을 설치한 뒤([README](../../README.ko.md#claude-code)) `sennard hooks`를 다시 실행합니다. 그때까지 그 플러그인의 hook과 MCP 도구에는 정의가 없어 실행되지 않습니다.

### 서버 멈춤과 제거

서버를 멈출 때는 `sennard stop`, 다시 켤 때는 `sennard start`, 지울 때는 `sennard uninstall`을 씁니다. Sennard는 OS 서비스에 등록하지 않습니다. 서버를 멈춘 뒤에도 다음 세션 시작 훅이 서버를 다시 띄울 수 있습니다. `sennard uninstall`은 프로그램(그리고 `sennard.yaml`)을 지우고 상태 폴더와 업무 데이터베이스는 남깁니다. 업무 데이터베이스의 연결은 상태 폴더에 있으므로, 같은 상태 폴더에 나중에 설치하면 데이터베이스가 연결된 채로 시작합니다. 설치 폴더 안의 jar가 아니라 설치 폴더 밖에 둔 릴리스 jar의 사본으로 실행합니다(`UNINSTALL_FROM_INSTALLED_COPY`).

`sennard uninstall`은 Sennard가 호스트의 플러그인 사본에 써 둔 정의 파일 중 쓴 내용 그대로인 파일을 지우고, 여러 설정이 든 파일의 한 항목(Gemini CLI 매니페스트의 `mcpServers` 항목)은 바뀌지 않았으면 원래대로 되돌립니다. 그 뒤 바뀐 파일은 남긴 채 이름을 출력합니다. 호스트의 플러그인 자체는 지우지 않으므로 각 호스트에서 Sennard 플러그인을 제거합니다(Claude Code: `/plugin uninstall sennard@sennard-claude`). 남은 플러그인은 연결할 서버가 없습니다.

문제가 생기면 상태 폴더를 지우지 않고 보존한 채 조사합니다. 이전 판을 다시 설치하는 것만으로 롤백되지 않을 수 있습니다. 각 판의 되돌리기 방법은 그 판의 릴리스 노트에 있습니다.

## 언어

패키지는 호스트마다 하나입니다. 모델이 읽는 Sennard 문구는 설치 환경의 언어와 관계없이 문구마다 토큰이 적은 형태 하나로 고정됩니다(hook 안내·도구 설명·결과·CLI 출력·스킬 description은 영어, MCP instructions의 공통 접수 기준은 한국어). 스킬 본문은 원문(대부분 한국어) 그대로입니다. 관리자 페이지의 표시 언어는 [관리자 페이지와 컨텍스트](admin-page.ko.md#언어)를 참고합니다.
