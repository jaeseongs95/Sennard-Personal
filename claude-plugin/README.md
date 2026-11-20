# Sennard for Claude Code

이 폴더는 Gradle 작업 `:sennard-repo-tools:claudePlugin`이 생성한다. 직접 수정하지 말고 저장소 루트의 원본이나 `claude-overlay/`를 고친 뒤 그 작업을 실행한다.

## 설치

서버를 먼저 설치한 뒤 플러그인을 설치한다. 서버 설치와 지원 환경은 저장소의 `README.ko.md`를 본다.

```text
/plugin marketplace add jaeseongs95/sennard
/plugin install sennard@sennard-claude
```

## 구성

- 플러그인에는 스킬, `hooks/hooks.json`, MCP 설정만 들어 있고 실행 코드는 없다. 서버는 이 PC에 따로 설치된 상주 서버 하나다.
- 패키지의 `.mcp.json`(`{"mcpServers":{}}`)과 `hooks/hooks.json`(`{"hooks":{}}`)은 비어 있다. 서버의 포트는 시작할 때마다 운영체제가 정하고 정의에는 이 PC의 `java`·jar 절대 경로가 들어가므로 패키지가 적을 수 없다. `sennard install`·`sennard upgrade`·`sennard hooks`가 이 PC의 정의를 Claude Code가 읽는 플러그인 사본(플러그인 루트의 `.mcp.json`과 `hooks/hooks.json`)에 쓴다. 플러그인을 설치하거나 갱신한 뒤에는 `sennard hooks`를 다시 실행한다. `.claude-plugin/plugin.json`에는 `mcpServers`가 없다. 플러그인 루트는 `claude-plugin/`이며 저장소 루트의 Codex용 `hooks/hooks.json`, `.mcp.json`, `.codex-plugin/`은 읽지 않는다.
- MCP는 `.mcp.json`에 stdio 서버로 등록한다. 서버 이름은 `sennard`로 같아서 도구 이름 `mcp__plugin_sennard_sennard__*`는 그대로이며, 실행하는 것은 Sennard stdio 중계(`McpRelayMain`, `--host=claude`)다. 중계가 서버가 `server.properties`에 알린 주소의 `/mcp`로 메시지를 전달한다.
- 훅은 모두 Claude Code의 command 훅(exec 형식)으로 Sennard Java 클라이언트(`HookClientMain`)를 실행하고, 클라이언트가 같은 주소 파일로 서버의 `POST /hooks/v1/claude/<이벤트>`에 입력을 보낸다. `type: "http"` 훅은 없다. HTTP 훅의 `url`은 환경 변수를 받지 않아 시작할 때마다 바뀌는 포트를 적을 수 없기 때문이다. governance 훅은 `"onFailure": "block"`을 유지한다. 형식은 서버의 어댑터가 생성한 정의가 정하며, 호스트 이름 분기는 어댑터 경계 안에만 있다.
- 서버는 `initialize` 응답의 `instructions`로 `skills/orchestrator/references/intake.md`의 공통 접수 기준을 내보낸다. orchestrator `SKILL.md`는 그 파일을 가리키는 한 줄만 두어 같은 기준이 두 번 실리지 않게 한다. 서버는 이 원문을 소스에 복사하지 않고 설치본에서 읽는다. 접수 기준의 내용은 이 문서에 옮겨 적지 않고 원문을 따른다.
- `adaptations/orchestrator.json`은 생성된 orchestrator `SKILL.md`에서 공통 접수 안내 줄 뒤, navigation 앞에 "Claude Code에서의 호출과 관측" 절을 넣는다. 이 절은 Skill 도구와 `/sennard:<skill-name>` 호출 방식, 독립 감사 서브에이전트 유형, SessionStart 훅의 역할과 실행 관측만 정하며 공통 선택 기준은 바꾸지 않는다.
- 상태는 상주 서버의 저장소에 있다.

## 공개 도구 스키마

서버는 모든 호스트에 같은 입력 스키마를 내보낸다. 도구별 프로필(`SENNARD_TOOL_SCHEMA_PROFILE`)은 없어졌다. 스키마의 `$defs`·`$ref`를 그대로 내보내며, 세 호스트 실측에서 모두 받아들여졌다. 최상위 `oneOf`는 쓰지 않는다.

## 세션 메시지

대기 훅은 `SessionStart`와 `Stop`에 하나씩 두 개다. 세션이 시작된 때와 턴이 끝난 때 그 이벤트의 훅이 서버의 깨우기 경로(`/hooks/v1/claude/<이벤트>/wake`)에 머무른다. 이 대기는 사람이 붙어 있는 대화형 세션에서만 한다. Claude Code가 훅에 넘기는 `CLAUDE_CODE_SESSION_ATTENDED`가 `1`일 때만 기다리고 그 밖에는 요청 없이 exit 0으로 끝나므로 `-p`로 띄운 세션은 깨우지 않는다. SDK로 띄운 세션은 측정하지 않았다. 이 변수는 Claude Code 공식 문서에 없다(Claude Code 2.1.319에서 측정. 별도로 Windows의 Claude Code 2.1.316에서 측정한 값은 `-p` 세션 `0`, 사람이 붙은 대화형 세션 `1`). Claude Code의 `asyncRewake` 설정으로 뒤에서 기다리다가 메시지가 오면 본문을 stderr로 내고 exit 2로 끝나 쉬던 세션을 깨운다. 메시지가 없거나 시간이 지나면 exit 0으로 끝나 깨우지 않는다. 본문 전달의 내구성은 서버의 메시지 큐와 ACK가 맡고 깨우기는 유실될 수 있는 힌트다. 모델이 처리한 `messageId`를 `acknowledge_session_messages`로 ACK하기 전까지 TTL 안에서 재전달될 수 있다.

## 큰 stage 출력

stage 결과는 provider 출력(`output.output`)을 `record_stage_result`로 넘겨 schema와 게이트 검사를 받는다. 저장소 크기에 비례하는 출력은 도구 인자로 넘기기 어려우므로, `record_stage_result`는 `outputFile: { attachmentId, seq, digest }`를 받는다. 결과를 기록하는 세션이 먼저 업로드한 JSON 첨부(16 MiB 이하, `sennard attachments upload --session-ref <sessionRef> <file>`로 등록)를 읽어 SHA-256을 확인하고, 인라인 출력과 똑같이 검사한 뒤 receipt에는 참조만 남긴다. receipt 정책이 있는 stage는 다음 stage가 저장된 출력에서 actor를 비교하므로 인라인 출력만 받는다. orchestrator 지침(`adaptations/orchestrator.json`)의 "큰 stage 출력" 절이 이 방식을 안내한다.

## 실행 관측

실행 관측의 출처와 한계는 저장소의 `docs/contributing/execution-attestation.md`에 있다. Claude Code는 PreToolUse 훅이 `tool_use_id`와 입력·transcript에서 관측한 모델·추론 수준을 서버에 남기고, 서버는 MCP 호출의 `claudecode/toolUseId`로 그 입장 행을 한 번 회수한다. 도구 인자로 넣은 값은 관측으로 쓰지 않는다. 서명 토큰과 그것을 위한 스크립트 훅은 없다. 같은 OS 사용자로 실행되는 프로세스의 위조는 막지 못한다.

## 공용 원본과 Claude 전용 부분

- 공용: 루트 `skills/`, `runtime/`, `contracts/`를 그대로 복사한다. `skills/`는 Codex 플러그인이 그대로 설치하는 트리이므로 Claude 때문에 고치지 않는다.
- Claude 전용 파일: `claude-overlay/` 아래에 생성물과 같은 경로로 둔다. 공용 파일을 통째로 덮어쓰면 생성이 실패한다.
- Claude 전용 문구: 스킬마다 `claude-overlay/adaptations/<스킬명>.json` 한 파일에 모은다.
- 갱신 시점: 공용 원본을 바꾸면 같은 변경에서 `:sennard-repo-tools:claudePlugin`으로 이 폴더를 다시 생성한다. 저장소 계약 시험이 차이를 실패로 보고한다.

## Claude용 보정

- `adaptations/<스킬명>.json`의 `description`: `SKILL.md` frontmatter의 `description` 필드를 통째로 바꾼다. Claude Code는 Codex의 `agents/openai.yaml` 암시 호출 정책을 읽지 않고 `description` 한 줄로만 스킬을 고르므로, 제외 조항보다 트리거 상황("커밋 전", "삭제·배포 직전", "같은 실패가 반복될 때" 등)을 앞세운다. 공용 description의 문구와 무관하게 적용되므로 원본 description이 바뀌어도 생성은 실패하지 않는다.
- `adaptations/<스킬명>.json`의 `replacements`: 호스트 중립 표현이 없는 문장만 스킬 폴더 기준 경로(`file`)와 정확히 한 번 나오는 원문(`find`)으로 바꾼다. 원문이 사라지거나 두 번 이상 나오면 해당 adaptation 파일 이름과 함께 생성이 실패한다.
- 배포하는 스킬을 Codex 방식으로 호출한 `$스킬명` 표기는 `/sennard:스킬명`으로 자동 변환한다.
- 이 변환을 거친 뒤에도 생성된 `SKILL.md`, `references/*.md`, `agents/*.md`에 Codex 전용 표현(`fork_turns`, 배포하지 않는 스킬의 `$스킬명` 호출 등)이 남으면 생성이 실패한다. 두 호스트를 함께 설명하는 `coordinate-subagents` 문서는 예외다.
- `agents/independent-auditor.md`, `agents/deliberation-reviewer.md`: 부모 대화를 상속하지 않고, 파일 수정, 재위임과 스킬 호출을 막은 서브에이전트 정의다.
- SessionStart 응답의 스킬 안내: 서버의 Claude 어댑터가 Claude Code 호출 방식 한 줄과 설치 스킬별 대상 목록을 `additionalContext`로 낸다. 공통 접수 원문은 MCP `instructions`가 싣기 때문에 다시 싣지 않는다.
- `instruction-scope-resolver`의 검사는 `AGENTS.md` chain만 계산한다. `CLAUDE.md` 계층은 `references/claude-code-instructions.md`에 따라 스킬이 따로 확인한다.
