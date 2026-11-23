[English](exceptions-and-messages.md)

# 예외와 메시지

이 문서는 Sennard의 오류 규약입니다. 업무 코드는 코드와 인자만 던지고, 문장은 경계가 메시지 카탈로그에서 한 곳으로 만듭니다. 기준 구현은 현황판과 세션 메시징입니다.

## 출발점

- 문제: 옛 코드는 오류가 `ToolFailure`·`ContractError`·`StoreException`·`ServiceFailure` 등 뿌리 없는 여러 벌이었고, 문구 클래스(`Messages`)도 셋이었습니다. 어떤 예외가 어떤 응답이 되는지 한 곳에서 볼 수 없었습니다.
- 선택: 뿌리 하나(`sealed SennardException`)와 일곱 범주, 범주별 코드 enum, 문장은 Spring `MessageSource` 하나입니다. MCP 결과 변환은 완전 `switch` 한 곳이고, HTTP는 `@RestControllerAdvice` 한 곳입니다. 공통 처리는 AOP로 둡니다(Spring의 선언적 서비스).

## 범주와 코드

| 범주(`Category`) | 예외 클래스 | 코드 enum | 와이어 `error.code` | 뜻 |
| --- | --- | --- | --- | --- |
| `INPUT` | `InvalidInputException` | `InputCode` | `INVALID_INPUT` | 호출자의 입력이 틀렸다. 고치면 된다. |
| `NOT_FOUND` | `NotFoundException` | `NotFoundCode` | `NOT_FOUND` | 호출이 가리키는 것이 없다. |
| `CONFLICT` | `ConflictException` | `ConflictCode` | `CONFLICT` | 다른 상태·다른 쓰기와 부딪쳤다. 다시 읽거나 재시도하면 된다. |
| `GATE` | `GateRefusedException` | `GateCode` | `GATE_FAILED` | 제품 규칙이 지금 이 단계를 거부한다. |
| `BINDING` | `BindingException` | `BindingCode` | `BINDING_INVALID` | 필요한 세션·호출자에 묶이지 않았거나 유효하지 않다. |
| `INTEGRITY` | `IntegrityException` | `IntegrityCode` | `INTEGRITY_FAILED` | 데이터가 스스로 또는 저장소의 제약과 어긋난다. |
| `UNAVAILABLE` | `UnavailableException` | `UnavailableCode` | `MCP_UNAVAILABLE` | 서버가 지금 못 한다(저장소 중단, 한도). 호출자 잘못이 아니다. |

와이어에서 범주 안의 이유는 `error.details.reasonCode`(코드 enum 상수 이름)입니다. 각 코드는 카탈로그 키(`messageKey`)를 가집니다. 코드 이름은 일곱 enum 사이에서 겹치지 않으며 시험이 지킵니다(`ErrorMessagesTest.java:145`).

## 관련 클래스

| 역할 | 클래스 |
| --- | --- |
| 뿌리·범주 | `SennardException`(`sennard-domain/.../domain/error/SennardException.java:16-17`), `Category`(`.../Category.java:10-22`), `ErrorCode`(sealed 인터페이스) |
| 하위 예외·코드 | `InvalidInputException`·`InputCode` 등 일곱 쌍(같은 폴더) |
| 문장 만들기 | `CatalogMessageSource`(`sennard-app/.../message/CatalogMessageSource.java`), `CatalogPattern`(`.../message/CatalogPattern.java`), `SennardMessageResolvable`(`.../message/SennardMessageResolvable.java`) |
| 오류 번역 | `PersistenceErrors`(`sennard-app/.../support/PersistenceErrors.java:45-53`), `ErrorTranslationAdvisor`(`.../ErrorTranslationAdvisor.java:21-24`) |
| MCP 결과 변환 | `SennardToolResults`(`sennard-server/.../entrance/SennardToolResults.java:46-54`): 도구의 `CallToolResult`를 만드는 유일한 곳이라 모든 도구가 봉투 `{schemaVersion, ok, data, error}`로 답합니다. 선언된 예외는 `codex_hook` 하나이며, Codex의 hook 실행기가 그 결과를 hook 자신의 JSON으로 읽기 때문입니다. `ToolEnvelopeServerTest`가 `tools/list`의 나머지 모든 도구를 봉투로 묶어 둡니다 |
| HTTP 결과 변환 | `SennardExceptionHandler`(`sennard-server/.../entrance/SennardExceptionHandler.java`) |
| 카탈로그 | `locales/en/*.json`, `locales/ko/*.json`(예: `error.json`, `services.json`) |

## 동작 방식

### 던지는 쪽

업무 코드는 문장을 쓰지 않습니다. 코드와, 문장이 쓸 인자(이름 붙은 값)만 줍니다.

```java
// sennard-domain: BoardRules.java
throw new InvalidInputException(InputCode.SUMMARY_INVALID, SennardException.args("maxLength", maxLength));
```

용량 한도처럼 와이어에 상세를 더 실어야 하면 `details`를 줍니다(`UnavailableException`의 생성자). 예외 메시지(`getMessage()`)는 코드 이름뿐입니다. 본문·경로 같은 값이 로그 줄로 새지 않게 하려는 것입니다.

모델이나 사용자가 읽는 문장은 `messageKey`로 카탈로그(영어·한국어)에서 오며 코드에 쓰지 않습니다. 입력에 대해 할 말이 있는 거절(거꾸로 된 시간 범위, 설치되지 않은 스킬 id)은 `locales/`에 자기 문장을 가진 별도 코드이고, 그 문장은 인자로 받은 값을 이름 붙여 말합니다. 카탈로그에서 오지 않는 문장은 `details.validationErrors`에 담기는 JSON Schema 검증기의 문장 하나뿐이며, JVM의 기본 언어와 상관없이 항상 영어입니다(`SchemaLocale.IN_ENGLISH`, 모든 검증 호출이 씁니다). 한 서버가 어떤 거절은 영어로, 어떤 거절은 기계의 언어로 답하지 않게 하려는 것입니다.

### `sennard-domain`은 Spring을 모른다

그래서 예외는 Spring의 `MessageSourceResolvable`을 직접 구현하지 못하고, 같은 모양을 내놓습니다(`messageKey()`·`arguments()`). `sennard-app`의 `SennardMessageResolvable`이 이를 `MessageSourceResolvable`로 감쌉니다. 규칙은 빌드가 지킵니다: `sennard-domain` 소스가 `org.springframework`·`java.sql`·`tools.jackson.databind`를 import하면 `o-domain-imports`가 실패합니다.

### 문장 만들기

`CatalogMessageSource`는 Spring `MessageSource`이고, 문장은 이 빈 하나를 `MessageSourceAccessor`로 불러 만듭니다. 카탈로그는 `locales/<언어>/*.json`(키 하나에 `java.text.MessageFormat` 패턴 하나)이고, 인자는 이름으로 씁니다. 값에 따라 달라지는 문구는 키 `키#인자=값`으로 씁니다(`docs/contributing/i18n.md` 2장). 인자는 `Map` 하나를 `Object[]{map}`로 넘기면 이름 인자입니다. 해당 언어에 키가 없으면 영어로 찾습니다. 번역(`ko`) 기록은 `locales/ko/translations.lock.json`이고 `:sennard-repo-tools:acceptTranslation -Ptool="ko"`로 갱신합니다.

새 코드를 만들 때 카탈로그 키가 없으면 시험이 실패합니다(`ErrorMessagesTest.java:73`, 영어·한국어 모두). 같은 코드를 두 언어로 풀고 인자가 채워지는지도 확인합니다(`:92`).

### 번역: 데이터 접근 예외가 한 곳에서 바뀐다

서비스 메서드를 둘러싼 어드바이스가 Spring과 Jakarta Validation의 예외를 `SennardException`으로 바꿉니다. 서비스는 `DataAccessException`을 잡아 자기 예외로 다시 만들지 않습니다.

| 들어오는 예외 | 나가는 예외 |
| --- | --- |
| `ConstraintViolationException` | `InvalidInputException(VALIDATION_FAILED)`, `details.violations` |
| `DuplicateKeyException` | `ConflictException(DUPLICATE)` |
| `ConcurrencyFailureException`(직렬화 실패·교착·낙관적 잠금) | `ConflictException(CONCURRENT_UPDATE)` |
| `DataIntegrityViolationException` | `IntegrityException(REFERENCE_VIOLATION 또는 VALUE_NOT_ALLOWED)` |
| 그 밖의 `DataAccessException` | `UnavailableException(STORE_UNAVAILABLE)` |

`ErrorTranslationAdvisor`는 트랜잭션 바깥에서 실행되어 커밋 실패도 번역합니다. 데이터베이스 경합 오류는 `PersistenceErrors`에서 `CONCURRENT_UPDATE`로 번역합니다. 제품은 경합 결과를 반환하며 트랜잭션을 다시 실행하지 않습니다. 외래키 위반과 CHECK 위반을 가르는 데에 ISO 표준 SQLSTATE 23503 하나만 읽습니다(`PersistenceErrors.java`의 `isForeignKey`). 직접 만든 SQLSTATE 표는 없습니다. 커밋 때 던지는 옛 `StoreException`(`sennard-api`)은 코드로 읽으며, 옛 스토어가 사라지면 이 분기도 사라집니다.

### MCP 결과 변환: 완전 `switch` 한 곳

```java
// SennardToolResults.java:46-54
return result(ApiResult.failed(switch (failure) {
    case InvalidInputException input -> body(input);
    case NotFoundException missing -> body(missing);
    ...
    case UnavailableException unavailable -> body(unavailable);
}));
```

`default`가 없습니다. 범주를 하나 더하면 이 `switch`가 컴파일되지 않아 모든 경계가 답을 정해야 합니다. 문장은 모델이 읽는 영어로 고정합니다(`_meta`의 `contentLanguage`가 `en`). 한국어는 사람이 읽는 HTTP 경계의 몫입니다.

### HTTP

HTTP 경계는 `@RestControllerAdvice` 한 곳(`SennardExceptionHandler`, `sennard-server/.../entrance/SennardExceptionHandler.java`)이 `SennardException`을 RFC 9457 `ProblemDetail`(`application/problem+json`)로 바꿉니다. 상태는 범주별 완전 `switch`로 정합니다: `INPUT` 400, `NOT_FOUND` 404, `CONFLICT` 409, `GATE` 422, `BINDING` 401, `INTEGRITY` 422, `UNAVAILABLE` 503. 문장은 `Accept-Language`로 `MessageSource`에서 풀고, 헤더가 없으면 `spring.web.locale: en`입니다. `code`·`reasonCode`·`messageKey`·`details`는 문제의 속성입니다. 범주마다의 응답과 영어·한국어 문장은 `SennardExceptionHandlerTest`가 실제 Tomcat으로 확인합니다. 서비스 메서드 밖에서 저장소가 던진 `DataAccessException`과 설정 저장소의 `SettingsException`은 응용 계층과 같은 번역(`PersistenceErrors.ofDataAccess`·`ofSettings`)을 거쳐 같은 방식으로 답합니다. 경합은 409 `CONFLICT`(`CONCURRENT_UPDATE`), 제약 위반은 422, 저장소가 답하지 못할 때만 503(`MCP_UNAVAILABLE`, `reasonCode: STORE_UNAVAILABLE`)입니다. 그 밖의 `SennardException`이 아닌 예외는 이 핸들러가 가져가지 않습니다. 옛 영역의 HTTP 입구(관리 화면 컨트롤러)는 옮길 때 이 핸들러를 씁니다.

## 주의사항

- 문장을 업무 코드에 쓰지 마십시오. 키가 카탈로그에 없으면 시험이 실패합니다.
- 와이어를 바꾸는 코드(범주·이유)는 공개 계약입니다. 바꾸면 `contracts/api-result.v1.schema.json`·골든·영한 문서를 같은 변경에서 맞춥니다. 예: 메시징 모르는 대상은 `MCP_UNAVAILABLE`/`details.reason`에서 `NOT_FOUND`/`reasonCode=SESSION_NOT_FOUND`로 바뀌었습니다.
- 넓은 `catch`로 삼키지 마십시오. 잡았으면 로그를 남기거나 원인(`cause`)을 붙여 다시 던집니다(`SessionBoardService.java:67-68`, `:88-89`).
- 한 호출을 위한 문구 감싸기(예: `send`의 「다시 보내지 말고 영수증을 비교하라」)는 코드로 표현합니다(`UnavailableCode.SEND_REJECTED`). 문자열을 이어 붙이지 않습니다.
- 세션 레지스트리와 호출을 세션에 묶는 일도 다른 영역처럼 `SennardException`을 던집니다. `SessionBinding.require()`는 `BindingException(BINDING_REQUIRED)`를 던지고, 레지스트리의 거절(`CURRENT_GENERATION_PROTECTED`·`GENERATION_CHANGED`·`GENERATION_STALE`·`STORE_CONFLICT`·`SESSION_NOT_FOUND`)은 `ConflictException`과 `NotFoundException`입니다. `ServiceFailure`는 없어졌으므로 도구 입구가 답할 실패 모델은 하나입니다.

## 사용 예시

- 던지기: `BoardRules.java`(`SUMMARY_INVALID`), `MessagingService.java`의 용량 거절(`UnavailableCode.MESSAGE_*_FULL`과 `details`).
- 어댑터에서 범주를 바꿔 쓰기: `SessionBoardService.java:67-68`(`STORE_UNAVAILABLE` → `BOARD_NOT_RECORDED`).
- 결과 변환 시험: `ToolEntranceTest.java:185`(규칙 거절과 실제 DB 제약이 입구에서 번역됨).
- 카탈로그 시험: `ErrorMessagesTest.java:73,92,108,145`.
