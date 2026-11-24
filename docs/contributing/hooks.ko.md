[English](hooks.md)

# 호스트 훅 수신

호스트는 Sennard Java 클라이언트(`sennard-hook-client`, `java -cp`로 시작하는 jar)를 command 훅으로 실행하거나, Sennard stdio 중계(`McpRelayMain`, 같은 jar)로 MCP 서버를 호출한다. 둘 다 서버가 상태 폴더의 `server.properties`에 알린 주소로 Spring Boot 서버를 찾는다. 서버가 시작할 때마다 운영체제가 포트를 정하기 때문이다(2.3절). HTTP 훅은 없다. 호스트의 HTTP 훅은 고정된 URL을 받기 때문이다. curl·셸 따옴표·`sh -c`는 훅 정의에 없다. 플러그인 패키지는 정의를 비워서 싣고, `sennard install`·`sennard upgrade`·`sennard hooks`가 이 PC의 `java`와 jar의 절대 경로를 적은 정의를 호스트의 플러그인 사본에 쓴다(2절).

## 1. 호스트×이벤트 표

「Sennard 수집 사건」은 서버가 `HostEvent`로 읽는 사건이다(`server/hook/HostEvent.java`). 호스트가 공식으로 제공하고 그 사건에 대응하는 이벤트는 모두 받는다. 받지 않는 이벤트는 이유를 적는다.

### 1.1 Claude Code (https://code.claude.com/docs/en/hooks)

| 이벤트 | 받는 방식 | 근거 |
|---|---|---|
| SessionStart | Sennard 클라이언트(command, exec 형식) | 공식: SessionStart는 「Only `type: "command"` and `type: "mcp_tool"` hooks are supported」, mcp_tool은 실행 시점에 건너뜀. 클라이언트가 호스트 PID를 서버에 보낸다 |
| SessionStart 깨우기, Stop 깨우기 | Sennard 클라이언트(command, `asyncRewake`) | 공식: `asyncRewake`는 command 훅의 필드 |
| SubagentStart·SubagentStop·PreCompact·PostCompact·PostModelSwitch·UserPromptSubmit·PreToolUse(governance·continuity·native-peer)·PostToolUseFailure·PostToolUse·Stop·SessionEnd | Sennard 클라이언트(command, exec 형식) | 서버의 포트가 고정이던 때에는 `type: "http"` 훅이었다. 공식: HTTP 훅의 `url`은 환경 변수를 받지 않으므로(`headers`만 치환한다) 어떤 정의도 주소를 적을 수 없고, command는 `server.properties`에서 읽는다. 도구 실행 관문 세 곳은 `"onFailure": "block"`(Claude Code 2.1.295 이상)과 `--on-failure=block-stderr`를 사용한다. `mcp_tool` 훅(호스트의 다른 형식)은 택하지 않았다. `onFailure`가 없고 `input`에 적은 필드만 받으므로 게이트의 실패 동작을 확인하지 못한다 |
| StopFailure·UserPromptExpansion | 받지 않음 | 대응하는 Sennard 수집 사건이 없다 |
| PermissionRequest·Notification·TaskCreated 등 나머지 | 받지 않음 | 대응하는 Sennard 수집 사건이 없다 |

### 1.2 Codex (https://learn.chatgpt.com/docs/hooks)

| 이벤트 | 받는 방식 | 근거 |
|---|---|---|
| SessionStart | Sennard 클라이언트(command, 호스트 PID 전달) | 공식: 「SessionStart hooks can run before an MCP server is ready」 |
| SessionEnd | Sennard 클라이언트(command, 호스트 한도 1초·최대 3초) | 공식: 「SessionEnd doesn't support MCP tool hooks」 |
| UserPromptSubmit·PreCompact·PostCompact·PreToolUse(peer-wait·continuity·native-peer)·PostToolUse·PreModelRequest·PostModelRequest | Sennard 클라이언트(command, 이벤트 전체를 표준 입력으로 받음) | 이 이벤트들에서 `agent_id`는 선택 필드다. 호스트 소스(`codex-rs/hooks/src/schema.rs`, `events/model_request.rs`)는 서브에이전트의 이벤트가 아니면 이 필드를 싣지 않는다. 이벤트에 없는 필드를 가리키는 `mcp_tool` 자리 표시자는 훅 전체를 실패시키므로, command가 이벤트를 읽어 서브에이전트와 메인 세션을 가른다. PreModelRequest·PostModelRequest는 새로 받는다. `ModelCallStarting`·`ModelCallFinished`에 대응하며 응답은 텍스트를 싣지 않는다 |
| Stop | Sennard 클라이언트(command, `--bind-host`) | 메인 세션의 전체 입력을 받는다. 조건을 충족한 Stop은 현재 서버에 로컬 깨우기 워커를 등록할 수 있다 |
| SubagentStart·SubagentStop | `type: "mcp_tool"`(서버 `sennard`, 도구 `codex_hook`) | 두 이벤트 모두 `agent_id`를 전달한다. MCP 도구 훅은 명령 훅과 같은 신뢰 검토 및 출력 규약을 따른다 |
| Interrupt·PermissionRequest | 받지 않음 | 대응하는 Sennard 수집 사건이 없다 |

mcp_tool 훅이 호출하는 `sennard` MCP 서버는 설치가 Codex의 플러그인 사본(`.mcp.json`)에 등록한 Sennard 중계이므로 그 훅도 알린 주소의 서버에 닿는다. mcp_tool 훅은 이벤트 전체가 아니라 `input`에 적은 필드만 도구에 준다(공식: 「Use `${field.nested}` to read a dotted field from the hook event」). 정의가 그 필드를 `${…}`로 나열한다. 호스트는 이벤트 JSON을 따라가 자리 표시자를 채우고, 필드가 없으면 훅을 실패시킨다(「hook input placeholder `${agent_id}` was not found」, `codex-rs/hooks/src/engine/mcp_runner.rs`). 기본값도 선택 형식도 없고, 값이 `null`로 있는 필드는 찾은 것으로 본다. 그래서 정의는 이벤트가 항상 갖는 필드만 적는다(`HookRegistrationFileTest`는 두 서브에이전트 훅의 자리 표시자를 필수 필드에 대조하고, `Stop` 명령 훅과 녹화한 메인 세션 입력을 검사한다).

### 1.3 Gemini CLI (https://geminicli.com/docs/hooks/reference/, https://geminicli.com/docs/extensions/reference/)

확장 폴더 `gemini-extension/`(`gemini-extension.json`, `hooks/hooks.json`)이다. 저장소 루트의 `hooks/hooks.json`은 Codex의 정의라서 Gemini 확장은 따로 둔다.

| 이벤트 | 받는 방식 | 대응하는 사건 |
|---|---|---|
| SessionStart | Sennard 클라이언트(호스트 PID 전달) | SessionStarted. 안내는 `hookSpecificOutput.additionalContext` |
| SessionEnd | Sennard 클라이언트 | SessionEnded |
| BeforeAgent | Sennard 클라이언트 | UserPromptSubmitted. 차단은 `decision: "deny"` |
| AfterAgent | Sennard 클라이언트 | TurnEnded. 턴 이어가기는 `decision: "deny"`와 `reason` |
| BeforeModel | Sennard 클라이언트 | ModelCallStarting |
| BeforeTool | Sennard 클라이언트 | ToolRequested. 차단은 `decision: "deny"` |
| AfterTool | Sennard 클라이언트 | ToolFinished. 텍스트는 `hookSpecificOutput.additionalContext`. 결과는 거절하지 않는다. 서브에이전트의 결과(`invoke_agent`)도 같다. Gemini CLI는 거절한 결과 자리에 "Tool result blocked"를 넣으므로 부모가 서브에이전트가 돌려준 것을 잃는다(`packages/core/src/core/coreToolHookTriggers.ts`) |
| PreCompress | Sennard 클라이언트 | CompactionStarting |
| AfterModel | 받지 않음 | 공식: 응답 조각마다 불리는 이벤트라서 「모델 호출이 끝남」이 아니다 |
| BeforeToolSelection·Notification | 받지 않음 | 대응하는 Sennard 수집 사건이 없다 |

Gemini 훅 호출마다 JVM을 한 번 기동한다. `BeforeModel`은 모델 호출마다 실행된다. 훅 클라이언트는 `llm_request`의 모델 이름을 보내고 서버는 관측에 사용한다.

### 1.4 Antigravity (https://antigravity.google/docs/hooks)

| 이벤트 | 받는 방식 |
|---|---|
| SessionStart(호스트 PID 전달)·PreInvocation·PostInvocation·Stop·PreToolUse·PostToolUse | Sennard 클라이언트(공식: `type: "command"`만 있다). 여섯 이벤트 전부 받는다 |

### 1.5 컨텍스트 복원

어댑터는 공통 이벤트에 전문 복원 여부를 표시한다. 공통 처리기는 확인된 전역·담당 계층과 현재 상황 스냅샷 및 workflow 카드 중 저장된 내용을 전달한다. workflow 진입이 성공하면 응답에 공통 접수 기준 경로와 스킬 대상 목록을 넣는다.

| 호스트 | 복원 신호와 응답 |
|---|---|
| Claude Code·Codex | `SessionStart`의 `source=startup`, `resume`, `compact`에 `hookSpecificOutput.additionalContext`로 응답한다. `PostCompact`에서는 압축 결과를 기록한다. |
| Antigravity | `PreInvocation`의 정수 `invocationNum=0`에서 임시 메시지인 `injectSteps`로 보낸다. 기록된 fixture의 첫 호출은 0이다. 후속 요청과 재개 세션의 0 복귀 시점은 미확인이다. 요청 중 자동 압축 뒤에는 다음 0번 호출까지 기다린다. |
| Gemini CLI | 시작·재개 `SessionStart`와 매 `BeforeAgent`에서 `hookSpecificOutput.additionalContext`로 보낸다. 두 훅이 같은 컨텍스트를 보낼 수 있다. 요청 중 자동 압축 뒤에는 다음 `BeforeAgent`에서 복원한다. 훅 클라이언트는 `llm_request.messages`와 `llm_request.config`를 전송 대상에서 제외한다. |

## 2. Sennard 훅 클라이언트

클라이언트는 `java/sennard-hook-client` 모듈이다. JDK(`java.base`)만 쓰는 프로그램이며 Spring·Jackson·SLF4J·Logback이 없다. command만 받는 호스트의 훅은 훅마다 새 JVM이 뜨므로 그 JVM이 올리는 것이 곧 훅의 지연이고, 이 모듈은 그 지연을 빈 JVM의 비용에 가깝게 두려고 있다. 서버가 클라이언트와 나눠 쓰는 것도 이 모듈에 있다: `HookClientCall`(훅 명령의 인자), `HookEndpoints`(경로), `HookHeaders`(헤더 이름). 서버는 그것을 여기서 가져다 쓰고 따로 적지 않는다.

설치된 훅 정의는 `sennard install`을 실행한 `java`(사용자의 JDK 25. 런처 프로그램도 동봉 런타임도 없다)로 클라이언트를 직접 실행한다.

```
<java> -XX:+UseSerialGC -Xmx256m -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable -XX:AOTMode=auto -XX:AOTCache=<판 폴더>/sennard-hook.aot
    -cp <판 폴더>/lib/sennard-hook-client-<버전>.jar io.github.jaeseongs95.sennard.hookclient.HookClientMain
    --server-file=<상태 폴더>/server.properties --jar=<판 폴더>/sennard-<버전>.jar hook <호스트> <이벤트> [옵션]
```

`hook`은 클라이언트 자신의 첫 단어이며 `sennard`의 명령이 아니다. Sennard CLI에는 `hook` 명령이 없다.

판 폴더는 `<설치 폴더>/versions/<버전>/`이다. 단어는 하나씩 적는다(`HookClientCommand`). 인자 목록을 받는 호스트(Claude Code의 exec 형태)는 그대로 받고, 명령 문자열을 받는 호스트는 공백이 든 단어마다 큰따옴표로 감싼 문자열을 받는다(`HookInstallation.commandLine`). 100% Java이고, 프로그램을 띄우는 것은 세션 시작이 서버를 찾지 못했을 때 설치된 jar를 한 번 실행하는 경우뿐이다(`java -jar <jar> start --within=<초>`, 2.1절). 중계는 같은 명령에 주 클래스 `McpRelayMain`과 인자 `--host=<라벨> --server-file=… --jar=… --start-server=30`을 준 것이다(2.3절).

- 훅 입력(stdin)을 서버 `POST http://127.0.0.1:<포트>/hooks/v1/<호스트>/<이벤트>[/<모듈>]`(`<포트>`는 호출마다 `--server-file`이 가리키는 파일에서 읽는다. 2.3절)에 JDK `HttpURLConnection`(`java.base`)으로 보내고, 2xx 본문을 stdout에 그대로 쓴다. Spring `RestClient`나 `java.net.http.HttpClient`를 쓰지 않는 이유는 훅마다 새 JVM이 뜨므로 시작 때 올리는 클래스와 초기화가 곧 지연이기 때문이다. `HttpClient`는 만들 때 기본 SSL 컨텍스트를 만들지만 `HttpURLConnection`은 만들지 않는다. 각각의 비용은 발행 전 훅 연결 확인에서 잰다(아래 측정 항목 참고).
- 보내는 것은 호스트 입력 전체가 아니라 서버 어댑터가 읽는 필드뿐이다. 클라이언트는 입력을 자체의 작은 JSON 읽기(`InputReducer`: RFC 8259의 엄격한 문법, 중첩 500단계까지. 서버의 읽기가 받아들이는 범위다)로 한 번 읽고, 그 필드만 호스트가 쓴 그대로 복사해 다시 쓴다. 어떤 필드인지는 모듈 안의 호스트별 파일 `sennard-hook-input/<호스트>.fields`가 정한다. 읽는 것의 유일한 정의는 서버의 입력 record(`HostDefinition.inputType()`)인데 클라이언트는 그것을 올릴 수 없으므로, `HostInputFieldsTest`가 모든 파일을 해당 record와 필드 단위로 대조하고 모든 호스트 정의에 파일이 있음을 확인한다. Gemini `BeforeModel`의 `llm_request.messages`(대화 전체)·`config`는 서버가 읽지 않으므로 가지 않는다. 서버가 400으로 거절할 입력(빈 입력, UTF-8이 아님, JSON 객체가 아님)은 클라이언트도 같은 이유로 거절하고 어디에도 보내지 않는다. `HostInputReductionTest`는 두 읽기에 같은 고정 입력(`hook-fixtures`의 기록된 호스트 입력과 어댑터 골든의 읽기 사례 전부)을 주고 결과가 같음을 확인한다.
- JDK 25 AOT 캐시: 설치 때 클라이언트의 실행을 학습해 `<판 폴더>/sennard-hook.aot`를 만들고(`CommandAotCache`, 정의와 같은 `java`·옵션·클래스 경로로 실행하며, `sennard install`·`sennard upgrade`·`sennard hooks`가 정의에 적힌 그 `java`로 만든다), 옵션이 `-XX:AOTMode=auto -XX:AOTCache=<판 폴더>/sennard-hook.aot -Xlog:disable`을 준다(공식: auto는 캐시를 못 읽으면 캐시 없이 계속한다). 실측에서 캐시가 없거나 깨졌을 때 JVM이 경고를 표준출력에 찍어 호스트가 읽는 답이 깨졌기 때문에 JVM 로그를 끈다. 릴리스 jar에는 넣지 않는다(JDK마다 다르고 설치 때 만든다). JVM은 한 번의 실행만 기록하므로 학습은 아무도 듣지 않는 포트를 향한 `hook gemini BeforeModel` 한 번이고, `--warm=agy=<파일>`은 같은 실행이 먼저 Antigravity `PreToolUse` 입력을 그 호스트의 입력으로 읽고 결과를 버리게 한다. `--warm`은 학습 실행 전용이며 어떤 호스트 정의에도 들어가지 않는다. `sennard` 명령 자체(사용자의 `java -jar`)에는 AOT 캐시가 없다. 사람이 드물게 실행하는 명령이기 때문이다.
- 클라이언트 JVM이 올리는 클래스를 지키는 것은 시간 한도가 아니라 시험이다: `HookClientProcessTest`는 이 모듈의 클래스만 클래스 경로에 둔 JVM에서 클라이언트를 실행하고, `org.springframework`·`com.fasterxml`·`tools.jackson`·`ch.qos`·`org.slf4j`의 클래스가 하나라도 올라오면 실패한다.
- 대안 확인(2026-11-14, 공식 문서): Antigravity(https://antigravity.google/docs/hooks)와 Gemini CLI(https://geminicli.com/docs/hooks/reference/)는 훅 처리기 `type`으로 `"command"`만 문서화한다. Claude Code는 HTTP 훅을 받지만 그 `url`에는 시작할 때마다 정해지는 포트를 적을 수 없으므로(1.1절) 모든 호스트가 command 훅마다 JVM 하나를 띄우고, 줄일 수 있는 것은 그 JVM의 시작 비용, 곧 올리는 클래스다.
- 측정: 발행 전 훅 연결 확인 때 발행 후보를 설치한 컴퓨터에서 재며 여기에는 적지 않는다(예전 구조, 곧 런처 프로그램을 거쳐 클라이언트를 실행하고 애플리케이션 전체의 AOT 캐시를 쓰던 때의 수치는 이 클라이언트를 더는 설명하지 않는다).
- 헤더 `X-Sennard-Time-Zone`(`HookHeaders.TIME_ZONE`; IANA, 환경의 `TZ`)을 보낸다. 호스트가 중계를 거쳐 보내는 mcp_tool 훅에는 이 헤더가 없으므로 서버는 UTC로 처리한다.
- `--bind-host`: JDK `ProcessHandle.current().parent()` 사슬에서 클라이언트 위의 프로세스를 가까운 것부터 최대 여덟 개 헤더 `X-Sennard-Host-Pids`(`HookHeaders.HOST_PIDS`)로 보낸다. 호스트는 그 안에 있지만 어느 것인지는 클라이언트가 읽을 수 없다(호스트와 클라이언트 사이에 호스트가 훅을 위해 띄운 셸이 있을 수 있다). 서버가 훅이 돌아온 뒤에도 살아 있는 프로세스를 호스트로 골라 `HostWatch`(`ProcessHandle.onExit()`)로 감시하고, 끝나면 세션을 끝낸다. 시작 시각이 훅 도착 시각보다 늦은 프로세스 ID는 거부한다.
- `--start-server=<초>`: 세션 시작 정의에만 있다(2.1절). 살아 있는 서버가 알려져 있지 않거나 연결을 거부하면 설치된 서버를 띄우고(`--jar`), 준비될 때까지 이 초만큼 기다린 뒤 원래 사건을 한 번 보낸다. 없으면 서버를 띄우지 않는다.
- 서버가 답하지 않을 때(`--on-failure`): `fail`(stderr·종료 1: `Sennard hook failed: <코드>`. 코드는 `SERVER_NOT_REACHABLE`·`SERVER_REFUSED`·`SERVER_NOT_STARTED` 같은 `HookNoAnswer.Reason`이거나, 서버가 같은 입력에 쓰는 `HOOK_BODY_` 이유 셋 중 하나다. 클라이언트에는 메시지 원천이 없으므로 코드의 문장은 서버와 이 문서가 맡는다), `block-stderr`(도구를 차단하며 stderr에 사유를 쓰고 종료 2), `deny-pre-tool-use`(stdout에 `PreToolUse`의 권한 거절과 사유를 쓰고 종료 0), `guidance-stderr`(Claude 세션 시작 안내·stderr·종료 2), `guidance-stdout`(Codex), `guidance-steps`·`allow-decision`(Antigravity의 `injectSteps`와 `{"decision":"allow"}`), `deny-decision`(`{"decision":"deny","reason":"..."}`, 원인 코드 포함), `guidance-system-message`(Gemini). 옵션 값은 호스트 이름을 담지 않는다(공용 코드는 호스트 이름을 모른다). 등록마다 실패 응답을 선택한다.
- 서버가 게이트의 호출을 거절할 때(`--on-refusal`): `as-failure`(기본값: 거절도 다른 실패와 같으며 `--on-failure` 답이 적용된다) 또는 `deny-decision`(stdout `{"decision":"deny","reason":"..."}`와 종료 0. Antigravity의 PreToolUse와 Gemini CLI의 BeforeTool이 함께 읽는 하나의 deny다). 400 이상의 상태에만 적용한다. 연결 실패, 시간 초과, 서버 기동 실패, 3xx 상태(리다이렉트는 따라가지 않는다)는 거절이 아니다. 게이트 정의만 이 옵션을 가진다(2.2절). 사유는 `SERVER_REFUSED <상태>`가 든 고정 영어 문장이다. 클라이언트에는 메시지 원천이 없기 때문이며, 사용자 카탈로그의 문장은 서버가 만든다(2.2절).
- `--wake`: 서버가 메시지로 답하면 본문을 stderr에 쓰고 종료 2(호스트가 세션을 깨운다). 그 밖의 끝은 조용히 종료 0. `--only-if-env=NAME=VALUE`가 맞지 않으면 요청 없이 끝난다(Claude의 `CLAUDE_CODE_SESSION_ATTENDED=1`).

읽을 수 없는 옵션(숫자가 아닌 포트, 없는 실패 답, 두 번 쓴 옵션)은 클라이언트를 종료 2와 `Sennard hook failed: HOOK_OPTION_INVALID <문제> <옵션>=<값>`으로 끝낸다. 호스트의 정의는 이런 옵션을 만들지 않는다.

플러그인 패키지의 훅 정의는 비어 있고(`{"hooks":{}}`) MCP 등록도 비어 있다. exec 형식의 명령은 홈 폴더 변수를 받지 못하고 패키지 자신의 폴더를 가리키는 변수는 호스트마다 다르므로, 패키지는 설치된 `java`, jar, 주소 파일의 위치를 적을 수 없다. `sennard install`·`sennard upgrade`·`sennard hooks`가 위의 단어들을 이 PC의 절대 경로로 호스트의 플러그인 사본에 쓴다. 파일 전체로 쓰는 것(Claude Code `hooks/hooks.json`·`.mcp.json`, Codex `hooks/hooks.json`·`.mcp.json`, Antigravity `hooks.json`·`mcp_config.json`, Gemini CLI `hooks/hooks.json`)과 다른 설정이 든 파일의 한 항목으로 쓰는 것(Gemini CLI 확장의 `gemini-extension.json`의 `mcpServers` 항목이며 나머지는 보존한다)이 있다. 모든 운영체제에서 같다. 호스트는 플러그인을 설치하거나 갱신할 때 빈 파일을 다시 복사하므로 그 뒤 `sennard hooks`를 다시 실행하며, `sennard status`가 지금 비어 있는 사본을 알려 준다(`hostCopiesWithoutDefinitions`). 쓴 파일은 모두 지문과 함께 `placed.json`에 기록하고, `sennard uninstall`은 쓴 그대로인 것만 되돌린다. 사본은 Claude Code(`installed_plugins.json`으로 찾음), Codex(`~/.codex/plugins/cache/<마켓플레이스>/sennard/<판>/`, `CodexPackageLocation`), Antigravity(`~/.gemini/config/plugins/sennard`), Gemini CLI(`~/.gemini/extensions/sennard`, `GeminiPackageLocation`)의 것이다. 링크한 확장은 사용자의 폴더이므로 쓰지 않는다. 해당 호스트가 있는 기기에서 확인하지 못한 것: Codex 캐시의 구조와 이름, 사본의 파일이 바뀔 때 Codex가 훅 신뢰를 다시 묻는지, Gemini CLI 폴더의 이름과 위치와 링크한 확장의 구조, Antigravity와 Gemini CLI가 빈 `mcpServers`를 받아들이는지.

### 2.1 세션 시작의 서버 기동

OS 서비스 등록(schtasks·systemctl·launchctl)이 없으므로 재부팅 뒤 서버를 띄우는 곳은 `sennard start`와 세션 시작 훅이다. 코드는 `Installer.startForSession`이고 `sennard start`의 `applySavedPreset`·`ServerStarter`를 그대로 쓴다. 클라이언트 프로그램은 이것을 직접 부를 수 없다(설치기는 Spring과 설정 데이터베이스이고 클라이언트는 그것을 올리면 안 되며, 설치 위치는 설치된 jar만 안다). 그래서 설치된 jar를 한 번 실행한다(`JarServerStart`): `<자기 java> -XX:+UseSerialGC -Xmx256m -XX:TieredStopAtLevel=1 -jar <jar> start --within=<초>`(정의의 `--jar=<경로>`. 프로세스의 출력은 버리고, 기다리는 시간보다 긴 기한이 지나면 그 프로세스와 자식을 죽인다). 서버 자체는 그 명령이 설치 폴더를 작업 폴더로 삼아 `<java> <프리셋 옵션> -cp <애플리케이션 jar> SennardPersonalApplication`으로 띄운다. `--jar` 없이 시작된 클라이언트는 아무것도 띄우지 않고 `SERVER_NOT_STARTED`로 끝난다.

- **대상.** 네 호스트의 세션 시작 정의(Claude 첫 command, Codex, Gemini, Antigravity)만 `--start-server=30`을 갖는다(`HookClientCall.START_SERVER_SECONDS`). 깨우기·SessionEnd·그 밖의 이벤트는 모델 호출·도구마다 서버를 띄우려 하지 않도록 옵션이 없고, 서버가 없으면 지금처럼 안내만 낸다(시험: `anEventOtherThanTheSessionStartStartsNothingWhenThereIsNoServer`).
- **언제.** `server.properties`에 살아 있는 서버가 알려져 있지 않을 때(파일이 없거나, 파일이 가리키는 프로세스가 그 시작 시각으로 살아 있지 않을 때)나 연결 거부(`java.net.ConnectException`)일 때만이다. 서버가 받고도 2xx가 아니거나 `--timeout` 안에 답하지 않으면 서버는 떠 있는 것이므로 띄우지 않는다. 입력이 서버가 거절할 모양(JSON 객체가 아님 등)이면 요청 전에 끝나므로 띄우지 않는다.
- **설치되지 않았으면** 띄우지 않는다. 설치된 판이 없으면 훅은 `--on-failure` 안내를 낸다(설치 폴더에 아무것도 만들지 않는다).
- **한 번에 하나.** 상태 폴더의 `server-start.lock`에 `FileChannel.tryLock`(JDK, 프로세스가 끝나면 OS가 푼다)을 잡은 프로세스 하나만 서버를 띄우고 준비를 기다린다. 못 잡은 훅은 준비 확인만 하며 잠금을 다시 시도한다. 살아 있는 서버가 이미 알려져 있으면(다른 곳이 먼저 띄움) 띄우지 않고 준비를 기다린다. 잠금 파일은 남고 잠금은 남지 않는다. 이 잠금은 시작을 순서대로 세울 뿐이다. 상태 폴더에서 서버가 하나만 도는 것은 두 번째 잠금인 `server.lock`이 지킨다(`ServerInstanceLock`, 서버가 시작 초기에 `FileChannel.tryLock`을 잡고 프로세스가 끝날 때까지 쥔다). 두 번째 서버는 `SERVER_ALREADY_RUNNING`으로 끝난다.
- **준비 기준.** 알림 파일이 살아 있는 서버를 가리키고, 그 서버의 `/actuator/health`가 2xx이고, 저장소를 준비하는 설치면 `/actuator/sennard`의 `phase`가 `READY`(`sennard install`·`sennard upgrade`가 기다리는 것과 같은 기준)이다. `FAILED`면 즉시 실패한다. 한 번의 확인은 2초(`ServerControl`의 응답 한도)이고 200ms 간격으로 다시 본다. 서버가 받고 답하지 않아도 훅의 시간을 다 쓰지 않는다.
- **서버 프로세스.** `ServerStarter.ofProcess()`: 표준 입력을 닫고 표준 출력·오류를 `Redirect.DISCARD`로 보낸다(서버가 자기 로그 파일을 쓴다). 훅의 stdout 파이프를 쥐지 않으므로 호스트는 훅의 EOF를 기다리지 않는다(시험이 파이프로 확인한다). 세션 시작 설정을 읽는 Spring 컨텍스트는 로그를 끄고(`InstalledSettings.quietlyAt`) 이 훅의 stdout에는 서버의 답만 나간다.
- **실패 계약.** 설치 없음·jar를 `java`로 시작하지 못함·저장소 준비 실패·상한 초과는 모두 「답이 없음」이다. 훅은 정의가 정한 안내와 종료 코드를 그대로 내고(`FAIL`이면 stderr에 코드 `SERVER_NOT_STARTED`와 무엇이 틀렸는지(예: 시작 명령의 종료 코드)가 적힌다) 세션을 막지 않는다. 상한을 넘겨도 띄우던 서버는 죽이지 않는다. 저장소를 준비하던 서버를 끊으면 반쯤 만든 저장소가 남고, 느린 시작이 매번 상한에서 끊기면 서버가 영영 안 뜨기 때문이다. 그 서버는 다음 세션 시작이 이미 떠 있는 서버로 만난다.
- **시간 한도.** 호스트 공식 문서의 command 훅 기본 timeout은 Claude Code 600초(`Defaults: 600 for command, http, and mcp_tool`, https://code.claude.com/docs/en/hooks), Codex 600초(`If timeout is omitted, Codex uses 600 seconds for most hooks`, https://learn.chatgpt.com/docs/hooks), Gemini CLI 60000ms(`Execution timeout in milliseconds (default: 60000)`, https://geminicli.com/docs/hooks/reference/), Antigravity 30초(`Optional. Timeout in seconds. Defaults to 30`, https://antigravity.google/docs/hooks)이다(2026-11-11 확인). 기동을 기다리는 상한(`--start-server`)은 30초, 정의가 호스트에 주는 세션 시작의 한도(`HookClientCall.SESSION_START_LIMIT_SECONDS`)는 그 두 배인 60초(Gemini는 60000ms)로 정했다. 이전에는 10초였다. 한도가 상한보다 충분히 길어야 시작 명령 자신의 JVM 기동과 사건 요청이 들어간다. Antigravity는 공식 기본(30초)보다 크게 쓰므로 정의의 `timeout`을 호스트가 따르는지 실호스트에서 확인해야 한다(4절).
- **실측.** `sennard install`로 만든 설치본에서 서버가 없을 때 훅 시작부터 사건의 답까지의 시간은 발행 전 훅 연결 확인 때 발행 후보를 설치한 컴퓨터에서 잰다(`HookStartsServerProcessTest.measuresTheStartUntilTheEventIsAnsweredOnARealInstallation`이 기록한다). 수치는 여기에 적지 않는다. 기동을 기다리는 상한 30초는 기대 시간이 아니라 끝내 준비되지 않는 프로세스를 위한 상한이다. 느린 기동이 매번 잘리지 않을 만큼 넉넉하고, 서버가 뜨지 못하는 세션을 오래 붙잡지 않을 만큼 짧게 정했다. 그 시간은 서버의 기동과 저장소 준비에서 생기고 컴퓨터마다 다르며, 이 구조가 더하는 프로세스 하나(시작 명령의 JVM)는 그 일부에 지나지 않는다.
- **시험**(`HookStartsServerProcessTest`, 릴리스 jar의 실제 설치·프로세스·포트): 서버 없음에서 기동·기록·답·서버 생존, 서버가 이미 있으면 프로세스 생성 0과 잠금 파일 없음, 동시 두 훅에 서버 하나, 애플리케이션 jar 손상, 애플리케이션 jar 없음, 준비 상한 초과, 설치 없음, 세션 시작이 아닌 이벤트. `HookClientTest`(`sennard-hook-client`)는 클라이언트의 판단(연결 거부일 때만, 한 번만, 실패는 안내)을 실제 소켓으로 본다.

### 2.2 게이트 훅의 실패 정책

**도구 실행 관문은 서버의 판정을 받을 수 없으면 도구를 차단한다.** 연결 실패, 클라이언트 시간 초과, HTTP 거절이 여기에 해당한다. SessionStart, UserPromptSubmit, PostToolUse, Stop은 각각의 실패 응답을 사용한다.

게이트는 `HostEvent.ToolRequested`에서 도구 호출을 차단할 수 있다. Codex의 `continuity`는 호스트가 보내는 모든 `PreToolUse` 이벤트에 실행된다. Claude Code에서는 `governance`가 Sennard 도구를 처리하고 `continuity`가 나머지 `PreToolUse` 호출을 검사한다. Gemini CLI는 `BeforeTool`을, Antigravity는 `PreToolUse`를 쓴다. Codex, Claude Code와 Antigravity는 호스트의 발신 도구에 별도 `native-peer` 게이트를 등록한다(`session-messaging.ko.md`의 「호스트의 발신 도구」). Antigravity에서는 `default_api:` 접두어 유무와 관계없이 `send_message`에 해당한다. Claude Code는 stderr에 사유를 쓰고 종료 코드 2를 반환한다. Windows PowerShell은 프로세스의 종료 코드 2를 1로 바꿀 수 있으므로 Codex는 stdout에 `hookSpecificOutput.permissionDecision: "deny"`와 사유를 쓰고 종료 코드 0을 반환한다. Antigravity와 Gemini CLI는 stdout에 사유와 `decision: "deny"`를 반환한다. Codex의 `peer-wait`는 대기 시간을 제한하는 안내를 제공한다.

| 상황 | 게이트 | 그 밖의 훅 |
|---|---|---|
| 서버 없음(연결 거부, 기동 안 됨) | `SERVER_NOT_REACHABLE` 사유와 함께 호스트에 차단을 반환한다 | 전과 같이 실패 답 |
| 클라이언트의 `--timeout` 안에 답 없음 | 시간 초과 사유와 함께 호스트에 차단을 반환한다 | 전과 같이 실패 답 |
| 서버가 훅 입력을 읽은 뒤 거절(도메인의 실패: 처음 만들기 경쟁에서 진 409, 그 밖의 4xx, 503, 500) | **도구를 막는다.** 서버가 호스트 고유의 deny 형식으로 200을 답한다(`HookPipeline`이 어댑터의 `respond`로 `HookVerdict.Refuse`를 만든다). 사유는 카탈로그 키 `hooks.gateRefused`의 문장(`locales/en`·`locales/ko`, 모델이 읽는 글은 영어)과 거절의 오류 코드다 | 전과 같은 상태(실패한 훅이며 호스트는 그대로 진행) |
| 서버가 입력을 읽기 전에 거절(깨진 본문, 모르는 호스트·이벤트, 세션 식별자 없음. 컨트롤러 앞의 필터, 예를 들어 저장소를 아직 준비 중인 서버의 503) | `SERVER_REFUSED <상태>` 사유와 함께 호스트에 차단을 반환한다 | 전과 같이 실패 답 |
| 3xx 상태 | 실패 답(리다이렉트는 따라가지 않는다) | 실패 답 |
| 호스트의 JSON이 아닌 2xx 본문 | 클라이언트는 본문을 읽지 않는다. 그대로 stdout에 쓰고 호스트가 판단한다(이 결정이 바꾸지 않는다) | 같다 |

사용자는 호스트의 거절 사유로 `hooks.gateRefused` 문장과 오류 코드(예: `HOOK_HANDLER_CONFLICT:STORE_UNIQUE_VIOLATION`)를 본다. 같은 호출을 다시 시도하고, 계속 실패하면 관리 페이지나 서버 로그에서 코드를 확인한다(`HOOK_GATE_REFUSED <코드>`가 원인과 함께 기록된다). 스스로 다시 시도하는 곳은 없다. 클라이언트도 서버도 재시도하지 않는다.

Claude Code 2.1.295 이상은 도구 실행 관문의 명령을 실행하지 못하거나 호스트의 제한 시간을 넘기면 `"onFailure": "block"`을 적용한다. 클라이언트가 실행되면 클라이언트의 실패 응답이 적용된다. 검사 범위는 호스트가 발생시키는 이벤트에 따른다.

### 2.3 서버 주소와 MCP 중계

서버에는 스스로 고른 포트가 없다. 시작할 때마다 운영체제가 주는 포트에 바인딩하므로(`server.port: 0`, 관리자 페이지의 커넥터와 같다) 호스트의 정의도 클라이언트도 포트를 미리 적을 수 없다. 포트 충돌이 없고, 포트를 쥔 프로그램이 서버의 시작을 막을 수도 없다.

- **알림.** 서버가 준비되면(`ApplicationReadyEvent`, `ServerAddressPublisher`) `<상태 폴더>/server.properties`를 쓴다. `port`(훅·MCP 엔드포인트·Actuator의 포트), `pid`, `processStart`(ISO-8601 시각)가 든 JDK `Properties` 파일이다. 파일은 자기 이름의 임시 파일에 쓴 뒤 한 번에 옮기므로(`ATOMIC_MOVE`) 클라이언트가 반쯤 쓴 파일을 읽는 일이 없다. 서버가 끝날 때(`ContextClosedEvent`)는 파일의 pid가 자기 것일 때만 지운다. 서버는 운영체제가 자기 시작 시각을 알려 주지 않으면 시작하지 않는다.
- **읽기.** `ServerAddress`(`sennard-hook-client`, `java.base`만 쓰며 서버도 같은 클래스를 쓴다)가 호출마다 파일을 읽는다. `ServerAddress.read(file)`은 파일이 없거나 깨졌으면 비어 있고, `live()`는 그 pid의 프로세스가 살아 있고 `processStart`에 시작했을 때만 참이다(나중에 다른 프로세스가 받은 같은 id는 맞지 않는다). 살아 있는 서버를 찾지 못한 클라이언트는 파일을 지우지 않고, 다음 서버가 덮어쓴다. 설치기의 명령(`sennard status`, `stop`, `attachments`, `admin-reset`)도 같은 파일을 읽으며, 어디에도 `--port` 옵션은 없다.
- **상태 폴더당 서버 하나.** `ServerInstanceLock`이 서버 시작 초기에 상태 폴더의 `server.lock`에 `FileChannel.tryLock`을 잡고 프로세스가 끝날 때까지 쥔다. 같은 상태 폴더의 두 번째 서버는 `SERVER_ALREADY_RUNNING`으로 끝난다(상태 폴더가 둘이면 각자 서버와 주소를 갖는다).
- **중계.** 호스트는 MCP 서버를 중계(`McpRelayMain`. `java.base`만 쓰며, 훅 클라이언트처럼 `HookClientProcessTest`가 이를 지킨다)로 등록한다. 어떤 호스트의 설정 파일도 쓰는 시점에 알 수 없는 포트를 적을 수 없기 때문이다. 인자는 `--host=<라벨> --server-file=… --jar=… --start-server=30`이다. stdin의 한 줄마다 JSON-RPC 메시지 하나를 읽어 알려진 주소의 `/mcp`에 `Accept: application/json, text/event-stream`과 헤더 `Sennard-Host-Label`로 보내고, 답(JSON 메시지 하나, 또는 이벤트 스트림의 `data` 메시지들)을 stdout의 줄로 쓴다. `id`가 없는 메시지는 알림이라 답이 없다(서버는 202로 답한다). `initialize` 답에서 합의한 프로토콜 판은 이후 모든 요청에 `MCP-Protocol-Version`으로 보낸다. 주소 파일은 요청마다 다시 읽으므로 다른 포트로 다시 시작한 서버도 찾는다.
- **중계가 서버를 띄우는 경우.** 살아 있는 서버를 찾지 못한 `initialize`만 서버를 띄운다(`--start-server`, 세션당 한 번, 세션 시작 훅과 같은 시작이다. 2.1절). 살아 있는 서버가 없을 때의 다른 요청은 그 요청의 id와 사유 코드를 담은 JSON-RPC 오류로 답한다. 중계는 요청마다 서버를 띄우지도, 서버를 기다리지도 않는다. 이는 2.1절의 규칙(세션 시작만 서버를 띄운다)을 한 곳, 곧 MCP 서버를 쓰는 세션의 첫 요청으로 넓힌 것이다.

## 3. 계약 시험

- `hook-command-not-java-client`(상한 0): 저장소의 플러그인 패키지는 정의를 비워서 싣는다. 저장소의 정의에 어떤 종류든 훅이나 MCP 서버가 있으면 위반이다(`sennard-arch-checks`의 `RepositoryRules`). 설치기가 쓰는 정의는 사용자의 `java`로 클라이언트 jar를 실행하는데, 저장소의 파일이 아니므로 이 규칙은 읽지 않는다. 그 단어는 `HookClientCommandTest`와 `HookRegistrationFileTest`가 지킨다.
- `HostInputFieldsTest`·`HostInputReductionTest`(`sennard-personal`): 클라이언트가 가진 호스트별 필드 목록은 서버의 입력 record와 같고, 클라이언트와 서버는 같은 기록 입력을 같은 결과로 읽으며 같은 입력을 같은 이유로 거절한다. `HookClientProcessTest`(`sennard-hook-client`): 클라이언트의 JVM은 Spring·Jackson·SLF4J·Logback의 클래스를 올리지 않는다.
- 게이트의 실패 정책(2.2절)은 녹화한 호스트 입력과 실제 프로세스로 시험한다. `HookLauncherProcessTest`는 생성된 Antigravity·Gemini 정의의 명령(사용자의 `java`와 클라이언트 jar)을 실제 서버를 상대로 실행하고 녹화한 Antigravity `PreToolUse`를 넣는다. `ClaudeHttpHookEndpointTest`는 녹화한 Claude 본문을 실제 엔드포인트에 보낸다. `CodexHookToolServerTest`는 녹화한 Codex 필드로 실제 `codex_hook` 도구를 부른다. `ContinuityHookRaceServerTest`(R7)는 한 세션의 첫 편집들이 작업 생성 지점에서 만나게 해 실제 PostgreSQL에서 확인한다. `HookClientTest`(`sennard-hook-client`)는 실제 소켓으로 클라이언트를 판정한다. Gemini CLI 입력은 녹화된 적이 없으므로 Gemini 사례는 손으로 쓴 `BeforeTool`로 돌린다(4절).
- `HookCommandPlacesTest`(`sennard-repo-tools`)는 생성된 핸들러의 유형과 수를 검사한다. Claude, Antigravity, Gemini는 command 훅을 사용한다. Codex는 SubagentStart와 SubagentStop에 MCP 도구 훅을 쓰고, Stop을 포함한 나머지 등록 이벤트에는 명령 훅을 사용한다.
- `GeneratedFilesTest`: 정의 파일은 어댑터가 낸 것과 같다(`./gradlew hostHookFiles`).

## 4. 실호스트에서만 확인되는 것

세션 시작의 서버 기동(2.1절)과 실제 서버에 대한 훅 클라이언트·중계는 Windows PC 한 대에서 호스트 없이 확인했다. 다른 OS(특히 POSIX 권한에 따라 갈라지는 분기)와 실호스트는 미검증이고, 7단계 확인 목록은 다음과 같다.

1. 호스트가 훅이 끝날 때 훅이 띄운 자식 프로세스(서버)를 같이 죽이는가(Windows Job Object·프로세스 그룹 종료 등). 공식 문서는 이를 밝히지 않는다. 죽이면 서버는 첫 세션 안에서 끊긴다.
2. MCP 서버가 SessionStart 훅보다 먼저 붙는가. Claude Code 문서는 「`SessionStart` at launch … fire before the session's MCP servers are available to hooks」, Codex는 「SessionStart hooks can run before an MCP server is ready」라고 한다. 훅이 서버를 띄워 기다리면 MCP가 그 뒤에 붙어야 도구가 보인다.
3. 호스트의 훅 시간 한도(`timeout`)가 정의의 값(60초, Gemini 60000ms)대로 적용되는가, 특히 공식 기본이 30초인 Antigravity.
4. 서버를 기다리는 동안 호스트 화면이 어떻게 보이는가(Claude Code `-p`·대화형, Codex, Gemini, Antigravity) 그리고 사용자가 그 시간(실측 시간 이내)을 받아들이는가.
5. 호스트가 훅 자식의 표준 입출력 파이프를 어떻게 쥐는가: 서버가 파이프를 쥐어 호스트가 훅의 EOF를 기다리는지(Windows에서는 파이프 시험으로 쥐지 않음을 확인).
6. 서버를 띄운 훅이 속한 호스트 프로세스가 끝났을 때 서버가 남는 것이 사용자가 기대하는 동작인지(서버는 PC당 하나이고 다른 세션이 쓴다).
7. Linux·macOS에서 같은 동작: `FileChannel.tryLock`, 프로세스 분리(`Redirect.DISCARD`), `java`로 jar 시작.

Codex SessionEnd 1초 안의 기동, mcp_tool의 `${필드}`가 없는 필드를 만났을 때의 동작과 결과 텍스트(JSON 객체) 해석, Claude command 훅의 `"onFailure": "block"`이 클라이언트가 exit 1로 끝날 때 도구를 막는지와 이제 Claude의 모든 훅에서 생기는 JVM 시작 지연(둘 다 연결 확인에서 잰다), 클라이언트가 고른 PID가 실제 호스트인지, Gemini `mcp_context` 필드명과 확장 훅 파일의 스키마, 공백이 든 `java` 경로에서의 Antigravity(Windows `cmd /c`)·Codex·Gemini 명령, Codex의 stdio MCP 서버 시작 timeout(문서의 기본은 10초이며 중계의 `initialize`가 서버를 띄우는 경우(2.3절)에는 이를 넘을 수 있다)과 Codex가 플러그인의 `.mcp.json` 등록을 받아들이는지, 좀비가 된 호스트의 `onExit`(Linux).

## 5. 녹화 표본이 없는 입력(서브에이전트·`--agent`)

`src/test/resources/hook-fixtures/<os>/<호스트>/`의 녹화 표본에는 Claude Code의 서브에이전트(`agent_id`)와 `--agent`로 시작한 세션(`agent_type`)이 0건이다. 그래서 `ClaudeHookInput`의 두 표지(`@Marker`)로 행위자를 가르는 시험(`MainSessionListingServerTest`, `SharedContextHookServerTest.aSubagentGetsTheLayers...`)은 손으로 쓴 입력(`"agent_id":"child-1"` 같은 줄을 녹화 본문에 끼운 것)으로 돈다. 공식 문서의 입력 형식은 표본을 대신하지 않는다. 표본을 얻으려면 실호스트에서 다음을 녹화한다(Windows 시험 서버와 Linux 시험 서버, 기존 표본과 같은 호스트 버전).

| 호스트 | 녹화할 것 | 방법 |
|---|---|---|
| Claude Code | 서브에이전트 하나가 도구를 두 번(셸 도구 1회, Sennard MCP 도구 1회) 쓰는 동안의 `SubagentStart`·`PreToolUse`·`PostToolUse`·`SubagentStop` 본문 전부. `agent_id`·`agent_type`이 어느 이벤트에 있는지가 표본의 요점이다 | 대화형 세션에서 서브에이전트를 쓰라고 시킨다(`Agent` 도구). 훅 본문은 시험 서버에 둔 탐침 플러그인의 훅(표준 입력을 파일로 쓰고 Sennard 서버에도 그대로 보낸다)이 받는다 |
| Claude Code | `--agent <이름>`으로 시작한 세션의 `SessionStart`·`UserPromptSubmit`·`PreToolUse`·`Stop` 본문. `agent_type`만 있고 `agent_id`는 없어야 한다 | `claude --agent <이름>`으로 시작해 프롬프트 한 번, 도구 한 번 |
| Codex | 서브에이전트가 도구를 쓰는 동안의 `PreToolUse`·`PostToolUse`·`SubagentStop` 본문(`agent_id` 위치) | 서브에이전트를 쓰라고 시킨다 |
| Antigravity | 자식 대화가 도구를 쓰는 동안의 `PreToolUse`·`PostToolUse`·`PostInvocation` 본문과 같은 호출의 MCP `_meta`. 훅 본문에 자식을 가리키는 값이 정말 없는지(`conversationId`뿐인지)를 확인한다 | 서브에이전트를 쓰라고 시킨다 |

녹화한 본문에서 세션·경로·사용자 이름은 기존 표본처럼 자리표시값으로 바꾸고, `hook-fixtures/<os>/<호스트>/`에 `Subagent-<이벤트>.json`·`Agent-<이벤트>.json` 식으로 둔다. 시험은 손으로 끼운 줄 대신 이 표본을 읽도록 바꾼다.

이런 표본이 부모를 가리키는 필드를 보여 주기 전까지 Antigravity 훅은 대화가 누구의 자식인지 말할 수 없다(공식 훅 문서에 부모·루트·에이전트 필드가 없다). 그래서 역할 결속도 기록된 루트도 없는 대화의 첫 훅은 `hooks.contextLayers.parentUnknown` 줄을 더해 서브에이전트에게 Sennard 도구를 한 번 부르라고 안내한다. 그 호출의 `_meta`가 대화와 루트를 함께 알려 주고 `HostSessionResolver`가 둘을 잇고, 다음 단계부터 훅이 루트의 역할을 준다(`SharedContextHookServerTest`).
