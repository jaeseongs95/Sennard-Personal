[English](tool-input-mapping.md)

# 도구 입구 매핑

이 문서는 MCP 도구의 입력이 평평한 공개 계약 키에서 입력 record와 서비스 호출까지 가는 길, 그리고 그 방식을 고른 근거입니다.

## 실호출로 정한 방식(4단계 확인)

문제: Spring AI의 `@McpTool` 매개변수에 record를 두면 도구 입력이 평평한 키로 오는지, `{"input":{...}}`로 감싸이는지 몰랐습니다. 공개 계약(`contracts/*-request.v1.schema.json`)은 평평한 키입니다.

실측(현재 BOM 판의 Spring AI 스타터, 실제 Tomcat, SDK의 MCP 클라이언트로 호출: `McpToolInputShapeTest`):

| 선언 방식 | 공표되는 스키마 | 평평한 키 `{"summary":"s"}` | 감싼 키 `{"input":{...}}` |
| --- | --- | --- | --- |
| `@McpToolParam` record 매개변수 하나 | 속성이 하나(`input`)이고 그 안에 record 성분 | 거절: `INVALID_INPUT`, `UNDECLARED_ARGUMENT` | record로 도착 |
| `@McpToolParam` 평면 매개변수들 | 평평 | 도착(메서드가 record를 만든다) | 해당 없음 |
| `SyncToolSpecification` 빈 + Jackson `convertValue` | 평평(스키마를 직접 줌) | 도착(`convertValue`가 record를 만든다) | 거절 |

선택: (b) `SyncToolSpecification` 빈과 `convertValue`입니다. 근거는 다음과 같습니다.

1. 공개 계약이 평평한 키이고 이미 JSON Schema 파일이 정본입니다. (a) 평면 매개변수는 같은 정보(이름·필수·설명)를 매개변수 어노테이션에 다시 적고, 공표 스키마를 계약 파일로 바꾸는 후처리가 어차피 필요합니다. 둘이 되면 어긋납니다.
2. 입력 record 하나가 도구의 입력 모양이 됩니다. 매개변수 목록을 반복해 record를 손으로 조립하지 않습니다.
3. 입력 검사(Jakarta Validation)와 오류 번역, 세션 결속을 한 곳(`ToolEntrance`)에 둘 수 있습니다.
4. 공표 스키마 교체는 `ContractGuard`(또는 스펙 빈 한 곳)이고, 리플렉션 표가 없습니다.

발견한 제약: 스타터는 `List<SyncToolSpecification>` 형 빈을 모읍니다. 스펙 하나만 빈으로 두면 공표되지 않습니다(`McpToolInputShapeTest`의 `Beans`). MCP 전송은 STATELESS 하나로 고정입니다(`application.yaml`의 `spring.ai.mcp.server.protocol`, 다른 값이면 서버가 뜨지 않습니다: `StatelessMcpProtocol`).

## 관련 클래스

| 역할 | 클래스 |
| --- | --- |
| 입구 | `ToolEntrance`(`sennard-server/.../entrance/ToolEntrance.java`) |
| 결과 변환 | `SennardToolResults`(`.../entrance/SennardToolResults.java:46-54`) |
| 스키마 공표·강제 | `ContractGuard`(`sennard-server/.../tools/ContractGuard.java`의 `OWN_TOOLS`) |
| 도구 선언 | `SessionBoardTools`(`.../board/SessionBoardTools.java:24-45`), `SessionMessageTools`(`.../messaging/SessionMessageTools.java:28-58`) |
| 시험 | `McpToolInputShapeTest`, `ToolEntranceTest`(`sennard-personal/src/test/.../serverit/`) |

## 동작 방식

```java
// SessionBoardTools.java:30-36
@Bean
List<SyncToolSpecification> sessionBoardTools(ToolEntrance entrance, SessionBoardService service) {
    return List.of(
        entrance.tool("update_session_status", UpdateInput.class, hints(false),
            (call, input) -> service.update(call.session().host(), ToolSupport.sessionId(call.session()), input.summary(), input.role())),
        ...
```

한 호출에서 `ToolEntrance`가 하는 일은 이 순서입니다.

1. 세션(`McpSessionResolver`, `sessionRef` 인자 포함)을 정합니다.
2. 인자(`sessionRef`를 뺀 평평한 맵)를 Jackson으로 입력 record로 바꿉니다. 모르는 인자는 실패입니다.
3. 입력 record의 선언된 제약을 검사합니다([검증](validation.ko.md)).
4. 핸들러의 결과는 `ApiResult.ok`로, `SennardException`은 `SennardToolResults`의 완전 `switch`로, 옛 영역의 예외는 옛 방식으로 답합니다.

입력 스키마(모델이 읽는 것)는 계약 파일에 `sessionRef`를 더한 것입니다. `ContractGuard`가 `OWN_TOOLS`에 든 도구의 스키마를 공표하고 인자를 강제하며, 입구가 주는 스키마는 이 가드가 바꿔 끼우는 자리표시자입니다. 그래서 입구로 선언한 도구는 모두 `OWN_TOOLS`에 있어야 하고, 시험이 이를 확인합니다(`ToolEntranceTest.java:235`, `everyToolDeclaredWithTheEntranceIsPublishedByItsContractFile`).

## 새 도구를 옮기는 순서

1. `contracts/<도구>-request.v1.schema.json`이 있는지 보고, 없으면 만듭니다(정본).
2. 입력 record를 만들고 제약을 선언합니다. 성분 이름 = 와이어 키입니다.
3. 서비스 메서드(`sennard-app`)를 만듭니다([DB와 트랜잭션](db-and-transactions.ko.md)).
4. `@Configuration` 클래스에 `List<SyncToolSpecification>` 빈을 더합니다(`entrance.tool(...)`).
5. `ContractGuard.OWN_TOOLS`에 도구 이름을 더합니다.
6. 시험: 실제 MCP 클라이언트로 평평한 키 호출, 계약 위반, 서비스 규칙 거절, 실제 DB 제약 번역(`ToolEntranceTest` 패턴). 공표 스키마가 바뀌면 골든(`L-tools_list.json`)을 갱신하고 이유를 적습니다.

## 주의사항

- 입력 record의 성분에 `sessionRef`를 두지 않습니다. 입구가 먼저 떼어 세션 결속에 씁니다.
- 선택 인자가 「없음」이어야 하고 `null`은 허용하지 않는 계약이면 record의 해당 성분은 `null`이 「없음」입니다(`UpdateInput.role`).
- 도구 이름·키·봉투·오류 코드가 바뀌면 공개 계약입니다. `contracts/*.schema.json`·골든·영한 문서를 같은 변경에서 맞춥니다. 입구로 옮기면서 두 도구의 `title`(빈 문자열)과 `annotations`의 기본값이 골든에서 바뀌었습니다(`L-tools_list.json`).
- `@McpTool` 어노테이션 방식은 옮기지 않은 도구에 아직 남아 있습니다. 옮길 때 위 순서대로 바꿉니다.

## 사용 예시

- 평평한 키 호출 시험: `ToolEntranceTest.java:115`(`theBoardToolsTakeAndAnswerByTheContractsFlatKeys`).
- 방식 비교의 근거 시험: `McpToolInputShapeTest.java`(네 사례).
- 메시징의 인자가 많은 도구와 중첩 record: `SessionMessageTools.java:28-58`.
