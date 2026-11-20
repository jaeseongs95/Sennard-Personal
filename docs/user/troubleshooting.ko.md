[English](troubleshooting.md)

# 문제 해결

| 증상 | 확인할 것 |
| --- | --- |
| 스킬이나 MCP 도구가 보이지 않는다 | 서버를 설치했는지 `sennard status`로 확인합니다. 설치 뒤나 서버가 뜬 뒤에 새 세션을 시작했는지, 호스트에서 플러그인과 MCP가 켜져 있는지 확인합니다. 세션을 시작할 때 서버가 없었다면 그 세션에는 나중에 연결되지 않습니다. |
| MCP 서버가 시작되지 않는다 | 아래 [상주 서버](#상주-서버)를 봅니다. |
| `sennard install`이 `INSTALL_ROOT_NOT_REPRESENTABLE` 또는 `STATE_DIR_NOT_REPRESENTABLE`로 끝난다 | 폴더 이름에 시스템의 파일 이름 인코딩(`native.encoding`)으로 쓸 수 없는 글자가 있습니다. `SENNARD_INSTALL_ROOT` 또는 `SENNARD_STATE_DIR`를 그 인코딩으로 쓸 수 있는 폴더로 지정합니다. 아래 [시스템 인코딩으로 쓸 수 없는 폴더](#시스템-인코딩으로-쓸-수-없는-폴더)를 봅니다. |
| `sennard`가 `NOT_A_RELEASE_JAR` 또는 `JAVA_EXECUTABLE_UNKNOWN`으로 끝난다 | `NOT_A_RELEASE_JAR`: 클래스 폴더가 아니라 릴리스 jar에서 `java -jar sennard-<판>.jar <명령>`으로 실행합니다. `JAVA_EXECUTABLE_UNKNOWN`: JVM이 자신이 실행된 `java` 프로그램을 알려 주지 않았습니다. `java`를 전체 경로로 시작합니다. |
| Codex에서 안내·현황판·메시지가 동작하지 않는다 | `/hooks`에서 Sennard hook을 검토하고 신뢰했는지 확인합니다. hook 정의가 바뀐 판으로 업데이트하면 다시 신뢰해야 합니다. |
| Antigravity 설치가 실패한다 | 앱을 닫고 릴리스에 첨부한 설치본을 설치합니다. 저장소 전체를 설치하지 않습니다. |
| 플러그인을 설치하거나 업데이트한 뒤 Sennard hook과 MCP 도구가 사라졌다 | 플러그인에는 빈 정의가 들어 있고, 호스트는 플러그인을 복사할 때마다 이 PC의 정의를 그 빈 정의로 바꿉니다. `sennard hooks`를 실행하고 새 세션을 시작합니다. `sennard status`의 `hostCopiesWithoutDefinitions`가 비어 있는 사본을 알려 줍니다. Sennard를 제거했다면 호스트의 Sennard 플러그인도 제거합니다. [플러그인 업데이트와 `sennard hooks`](update.ko.md#플러그인-업데이트와-sennard-hooks) |
| 파일 편집이 workflow를 요구하며 거부된다 | 거부 문구가 알리는 workflow를 엽니다. 변경이면 `start_workflow`에 목표·단계·`skills`·수용 기준·`writeTargets`(그리고 그 파일을 담은 `changePlan`)를 주거나, 계획에 그 파일이 있는 열린 변경 workflow를 `resume_workflow`로 재개합니다. workflow가 아예 없는 요청은 workflow를 열거나 재개할 때까지 도구마다, 턴 끝에서 붙잡힙니다(답변은 `start_inquiry_workflow`, 실행은 `start_operation_workflow`). 진행 중인 workflow와 다른 작업이면 겹치지 않는 범위로 하나 더 시작합니다. [편집 관문](usage.ko.md#workflow와-편집-관문) |
| `ROOT_CONFLICT`로 시작할 수 없다 | 오류 `details`에 있는 기존 root·상태·다음 행동을 확인합니다. 자기 root는 `release_convergence_root`로 해제할 수 있습니다. 다른 세션의 root는 소유자가 온라인이 아니고 사용자 결정이 있을 때만 해제합니다. 소유 세션이 끝났다면 사용자가 관리자 페이지의 열린 root 화면에서 해제할 수 있습니다. |
| `BINDING_REQUIRED` 같은 실행 관측 오류가 난다 | [실행 관측과 호스트 연결](../contributing/execution-attestation.ko.md)의 호스트별 원인을 확인합니다. 모델 선택 조언과 실행 관측은 별개입니다. |
| `coordinate-subagents`의 모델 선택이 지원 정보 부족으로 호출을 보류한다 | 현재 호스트의 모델·추론 지원 근거를 확인합니다. catalog나 요청값으로 실제 적용을 추측하지 않습니다. |
| 관리자 페이지가 열리지 않는다 | 서버가 떠 있는지 `sennard status`로 확인하고, `localhost`로 열었는지, 현재 주소로 열었는지 확인합니다. 페이지의 포트는 서버가 시작할 때마다 바뀌며, 주소는 `sennard status`의 `adminPage` 값이고 에이전트가 받은 안내나 요청 응답의 `adminUrl`에도 있습니다. [관리자 페이지와 컨텍스트](admin-page.ko.md#여는-법) |
| 패스키를 잃었고 관리자 페이지가 무결성 실패를 보여 준다, 또는 업무 데이터베이스를 연결하기 전에 패스키를 잃었다 | Sennard를 설치한 OS 사용자로 `sennard admin-reset`을 실행합니다(실제로 하려면 `--yes`). 업무 데이터베이스가 한 번도 준비된 적이 없으면 필요 없고, 준비된 적이 있으면 필요하며 닿지 않으면 `ADMIN_RESET_DATABASE_UNREACHABLE`로 끝납니다. 실행 중인 서버를 먼저 멈추며(다음 hook 호출 때 다시 시작됩니다), 명령이 설정 데이터베이스를 열 때 서버가 아직 종료 중이거나 다시 떠 있으면 `ADMIN_RESET_SETTINGS_LOCKED`로 끝나므로 다시 실행합니다. [패스키를 모두 잃었을 때: 재설정](admin-page.ko.md#패스키를-모두-잃었을-때-재설정) |
| JEV가 「켜짐」으로 나오지 않는다 | 페이지가 적는 빠진 조건(JEV 사용 켜짐, 모델, 정보 전송 허용, key, 예산, 프리셋)을 채웁니다. 3.x 설치 설정의 JEV 항목은 읽지 않습니다. [JEV](admin-page.ko.md#jev-스킬-선택-조언) |
| hook이 「검증 실패 · rN 적용 중」을 알린다 | 관리자 페이지에서 빨간 띠가 붙은 대상을 확인하고 [서명해서 채택]이나 [버리기]를 고릅니다. 에이전트는 고칠 수 없습니다. |
| 다른 세션이 보이지 않거나 메시지에 ACK만 온다 | hook 신뢰, 호스트별 깨우기 방식을 확인하고 같은 `messageId`로 상태를 조회합니다. 실제 작업 결과는 따로 요청합니다. [세션 협업](collaboration.ko.md) |
| 업데이트 뒤 예전 동작이 남는다 | `sennard status`의 `versions`가 새 판인지, 플러그인을 함께 올렸는지 확인하고 [업데이트 절차](update.ko.md#업데이트-절차)대로 새 세션으로 연결합니다. |
| 업데이트 뒤 Antigravity에 Sennard MCP 서버가 둘로 보인다 | 앱을 다시 시작하고 새 대화를 엽니다. |

## 상주 서버

MCP와 hook은 사용자·PC당 하나인 상주 서버(Spring Boot, `127.0.0.1`)를 함께 씁니다. 서버의 포트도 관리자 페이지의 포트도 고정이 아닙니다. 시작할 때마다 운영체제가 각각 빈 포트를 정해 주므로 풀어야 할 포트 충돌이 없습니다. 서버는 상태 폴더의 `server.properties`에 주소(`port`, `pid`, `processStart`)를 알리고 끝날 때 그 파일을 지웁니다. hook 클라이언트와 MCP 중계는 호출마다 이 파일을 읽으며, 파일이 가리키는 프로세스가 그 시작 시각으로 살아 있지 않으면 무시합니다. 상태 폴더 하나에는 서버가 하나만 실행되며 두 번째 서버는 `SERVER_ALREADY_RUNNING`으로 끝납니다. Sennard는 OS 서비스에 등록하지 않으며 설치 명령, `sennard start` 또는 세션 시작 훅이 서버를 띄웁니다.

`sennard status`는 설치 폴더, 판, 명령 경로, 포트, `healthy`, 저장소 준비 상태(`store`), 관리자 페이지의 현재 주소(`adminPage`)를 JSON으로 보여 줍니다. 서버 로그는 상태 폴더 아래 `logs/sennard-server.log`(기본 `~/.sennard/logs/sennard-server.log`)에 있습니다. 경고와 시작 실패 원인이 남고, 10 MB마다 새 파일로 넘어가며 최대 7일과 100 MB를 보관합니다. 별도로 연결한 업무 데이터베이스의 로그는 해당 관리자와 확인합니다.

| 증상 | 확인과 조치 |
| --- | --- |
| 세션 시작 때 `Sennard server is not reachable.`이 나오거나 Sennard 도구가 호출 오류로 끝남 | 세션 시작 hook이 설치된 서버를 띄우려 했으나 못 했습니다. Sennard가 설치되어 있지 않거나, 설치된 jar를 `java`로 시작하지 못했거나, 저장소 준비가 실패(`FAILED`)했거나, 서버가 30초 안에 준비되지 않았습니다. `sennard status`와 상태 폴더의 로그로 확인합니다. 꺼져 있으면 `sennard start`, 설치가 없으면 `sennard install`을 실행합니다. 서버가 뜬 뒤 세션을 다시 시작합니다(hook이 포기할 때 아직 뜨는 중이던 서버는 그때쯤 대개 준비되어 있습니다). |
| 도구 호출이 `The Sennard server refused to check this tool call (...)`로 거절됨 | 서버는 떠 있지만 도구를 확인하는 호출을 거절했습니다(예: 두 요청이 같은 순간에 세션의 첫 기록을 만들려 했거나, 저장소가 답하지 못했습니다). 도구는 실행되지 않았고 아무것도 바뀌지 않았습니다. 같은 호출을 다시 시도합니다. 계속 거절되면 관리 페이지나 서버 로그(`HOOK_GATE_REFUSED <코드>` 줄)에서 오류 코드를 확인합니다. |
| 도구 호출이 `The Sennard hook could not verify this tool call (...)`로 차단됨 | 훅 클라이언트가 서버의 판정을 받지 못했습니다. 메시지에 실패 코드가 표시됩니다. `SERVER_NOT_REACHABLE`이면 `sennard status`로 서버를 확인하고 정상적으로 응답할 때 다시 시도합니다. 계속 실패하면 관리 페이지나 서버 로그를 확인합니다. |
| `sennard install`이나 `sennard start`가 `START_FAILED`로 끝남 | 메시지가 원인을 알려 줍니다. 다른 서버 시작이 기다리는 시간 안에 끝나지 않았거나(`otherStartPending`), 서버가 기다리는 시간 안에 준비되지 않았거나 정상 상태가 되지 않았거나(주소 파일 이름이 나옵니다), 프로그램을 시작하지 못했습니다. 상태 폴더의 로그와 `sennard status`를 확인한 뒤 `sennard start`를 실행합니다. 시작할 때마다 운영체제가 빈 포트를 주므로 포트는 원인이 될 수 없습니다. 이미 다른 서버가 그 상태 폴더를 쥐고 있으면 `SERVER_ALREADY_RUNNING`으로 끝납니다. 처음 설치가 이렇게 멈췄다면 이어서 `sennard hooks`를 실행합니다. |
| `sennard install`이 `ALREADY_INSTALLED`로 거절됨 | 이미 설치되어 있습니다. 새 판으로 바꾸려면 새 릴리스 jar로 `java -jar sennard-<판>.jar upgrade`를 실행합니다. |
| 도구가 `STORE_PREPARING`을 돌려줌 | 연결한 저장소를 준비하는 중입니다. `sennard status`의 `store`로 단계를 보고 기다립니다. 단계가 `NOT_CONFIGURED`이면 연결된 업무 데이터베이스가 없는 것이므로 관리 페이지에서 연결합니다([업무 데이터베이스](state.ko.md#업무-데이터베이스)). |
| `store`의 단계가 `FAILED`이거나 `STORE_PREPARATION_FAILED` | `reason`을 확인해 원인을 고친 뒤 `sennard prepare --retry`를 실행합니다. 연결 자체가 틀렸다면 관리 페이지에서 고친 연결을 저장합니다(데이터베이스가 한 번도 준비된 적이 없으면 저장소가 바로 그 연결로 준비합니다). |
| `STORE_POOL_EXHAUSTED` | DB 연결이 잠시 모자랍니다. 재시도하면 처리됩니다. |

## 시스템 인코딩으로 쓸 수 없는 폴더

설치 명령은 설치 폴더, 상태 폴더, jar를 `java`의 명령줄로 넘깁니다. Windows의 `java` 실행기는 명령줄을 시스템의 파일 이름 인코딩(JDK의 표준 속성 `native.encoding`이며 Windows에서는 ANSI 코드 페이지, 곧 「유니코드를 지원하지 않는 프로그램용 언어」 설정)으로 읽습니다. 그래서 그 인코딩으로 쓸 수 없는 글자가 폴더 이름에 있으면 프로그램에는 다른 폴더로 전달됩니다. Sennard는 운영체제의 이름이 아니라 JVM에 물어 이를 판정합니다. `native.encoding`의 인코더가 경로 전체를 쓸 수 있으면 받아들입니다.

- 설치 폴더(`~/.sennard/install` 또는 `SENNARD_INSTALL_ROOT`)를 그 인코딩으로 쓸 수 없으면 `sennard install`이 `INSTALL_ROOT_NOT_REPRESENTABLE`로 끝납니다. Sennard는 다른 곳에 설치하지 않습니다. `SENNARD_INSTALL_ROOT`를 그 인코딩으로 쓸 수 있는 폴더(ASCII만으로 이뤄진 폴더는 항상 됩니다)로 지정하고 명령을 다시 실행합니다.
- 상태 폴더를 그 인코딩으로 쓸 수 없으면 `STATE_DIR_NOT_REPRESENTABLE`로 끝납니다. `SENNARD_STATE_DIR`(또는 `sennard.yaml`의 `sennard.state-dir`)를 그 인코딩으로 쓸 수 있는 폴더로 지정합니다. [저장 위치](state.ko.md#저장-위치)를 봅니다.
- 내려받은 jar가 든 폴더는 이 규칙으로 검사하지 않습니다. 설치는 jar를 풀 때 jar의 경로를 자식 `java`에 넘기므로, 인코딩으로 쓸 수 없는 글자가 jar 폴더에 있으면 `sennard install`이 `INSTALL_FAILED`(`Could not extract ...`)로 끝날 수 있습니다. 이 경우는 측정하지 않았습니다. 그런 일이 생기면 jar를 인코딩으로 쓸 수 있는 폴더로 복사합니다.

릴리스 jar 시험(`jarRun`)은 시험 PC의 인코딩이 쓸 수 있는 글자로 만든, ASCII 밖의 이름을 가진 폴더에서 `install`, `status`, `start`, `stop`, `upgrade`, `uninstall`을 실행합니다. 시험은 Windows에서만 했습니다.
