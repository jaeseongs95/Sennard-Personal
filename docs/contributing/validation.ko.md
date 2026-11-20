[English](validation.md)

# 검증

이 문서는 입력과 설정의 검증 규약입니다. 규칙은 Jakarta Validation 3.1 규격의 제약 선언으로 한 곳에 적고, 검사 실행은 Spring이 합니다.

## 출발점

- 입력 검증은 Jakarta Validation 규격이 다루는 것을 직접 만들지 않고 규격으로 합니다. 도구마다 필수가 다른 것은 그룹, 메서드 입력 검사는 메서드 검증, 중첩·컬렉션은 `@Valid`와 컨테이너 요소 제약, 특수 규칙은 `ConstraintValidator`, 메시지는 보간과 `MessageSource`로 규격이 다룹니다(Jakarta Validation 3.1 규격 3.1.1.2, 2.4.3·5.6, 3.4, 6.3, 11.3.4 record 지원).
- 그래서 직접 만든 검증 코드(문자열 정규식·`if` 사슬)를 새로 쓰지 않고, 입력 record와 서비스 메서드 매개변수에 제약을 선언합니다. 구현체는 Boot가 가져오는 Hibernate Validator(`spring-boot-starter-validation`)입니다.

## 어디서 무엇을 검사하나

| 위치 | 검사 | 실패하면 |
| --- | --- | --- |
| 공개 계약의 구조·필수·길이·패턴 | `contracts/<도구>-request.v1.schema.json`을 `ContractGuard`가 공표하고 검사 | `INVALID_INPUT`(`workflow.schema.contractMismatch`, `details.validationErrors`) |
| 도구 입력 record의 선언된 제약 | `ToolEntrance`가 입력 record를 만든 뒤 `Validator.validate` | `ConstraintViolationException` → `VALIDATION_FAILED` |
| 서비스 메서드 매개변수 | `@Validated` 서비스의 메서드 검증(`@NotBlank` 등) | 같은 번역 |
| 업무 규칙(한 줄, 길이, 바이트 수, 범위) | `sennard-domain`의 순수 규칙(`BoardRules`, `MessageRules`)이 코드와 인자로 던짐 | 규칙의 `InputCode` |
| 설정 값 | `@ConfigurationProperties` record의 제약, 시작할 때 | 시작 실패(Boot의 `FailureAnalyzer`가 설명) |

모르는 인자를 거절하는 것은 검증이 아니라 Jackson 역직렬화 설정(`FAIL_ON_UNKNOWN_PROPERTIES`)의 몫입니다(`ToolEntrance.java:87`).

## 관련 클래스

| 역할 | 클래스 |
| --- | --- |
| 도구 입력 record + 제약 | `SessionBoardTools.UpdateInput`(`sennard-server/.../board/SessionBoardTools.java:24`), `SessionMessageTools`의 `PrepareInput`·`AcknowledgeInput`(`.../messaging/SessionMessageTools.java:28-45`) |
| 입력 검증 실행 | `ToolEntrance.input`(`sennard-server/.../entrance/ToolEntrance.java:143-154`) |
| 서비스 메서드 검증 | `BoardService`(`@Validated`, `.../board/BoardService.java:30,49,59`), `MessagingService.java:134,168` |
| 제약 번역 | `PersistenceErrors.java:48`(`ConstraintViolationException` → `VALIDATION_FAILED`) |
| 업무 규칙 | `BoardRules`(`sennard-domain/.../board/BoardRules.java`), `MessageRules`(`sennard-domain/.../messaging/MessageRules.java`) |
| 설정 제약 | `BoardProperties.java:20-23`, `MessagingProperties.java:23-24` |

## 동작 방식

### 입력 record

```java
// SessionBoardTools.java:24
public record UpdateInput(String schemaVersion, @NotBlank String summary, String role) { }
```

`ToolEntrance`가 평평한 인자 맵을 Jackson으로 이 record로 바꾸고(`convertValue`), `Validator.validate`로 선언된 제약을 검사합니다. 위반이 있으면 `ConstraintViolationException`을 던지고, 서비스 어드바이스와 같은 번역이 `InvalidInputException(VALIDATION_FAILED)`로 바꿉니다(`details.violations`에 필드와 메시지). 중첩 record·목록은 `@Valid`와 요소 제약을 씁니다.

```java
// SessionMessageTools.java:39
public record AcknowledgeInput(String schemaVersion, @NotNull List<@NotNull String> messageIds, List<@NotNull @Valid Receipt> receipts) { }
```

### 서비스 메서드

서비스 클래스에 `@Validated`를 두면 `@NotBlank String host` 같은 매개변수 제약을 프록시가 검사합니다. 입력 record를 거치지 않고 서비스를 부르는 쪽(훅 핸들러 등)도 같은 규칙을 지키게 됩니다.

### 업무 규칙은 도메인의 순수 함수

한 줄인지, 몇 글자인지, UTF-8 몇 바이트인지는 업무 규칙이라 `sennard-domain`에 두고, 길이 같은 값은 인자로 받습니다(설정에서 옵니다).

```java
// BoardService.java:59-61
String line = BoardRules.summary(summary, properties.summaryMaxLength());
```

선언 제약으로 쓸 수 있는 것(필수·크기·패턴)은 제약 어노테이션으로 선언하고, 어노테이션으로 못 쓰는 것(설정값에 따른 길이, 바이트 수, 상태에 따른 규칙)만 도메인 함수로 씁니다. 두 곳에 같은 규칙을 적지 않습니다.

### 그룹과 메타데이터(5단계 몫)

도구마다 필수가 다르면 `groups`와 `@GroupSequence`로 가르고 `@ConvertGroup`으로 중첩에 넘깁니다. 공개 JSON 스키마(외부 규격이고 골든이 바이트를 고정)와 선언된 제약이 어긋나지 않게 하는 방법은 Jakarta Validation의 메타데이터 API(규격 7장, `Validator.getConstraintsForClass`)로 만든 결과를 시험에서 스키마와 대조하는 것입니다. 현재 두 영역은 계약 파일이 스키마의 정본이고 record의 제약은 그 부분집합이므로, 어긋남은 아직 시험이 잡지 못합니다. 영역을 옮기는 담당이 도구마다 이 대조 시험을 하나씩 추가합니다.

### 설정 값

```java
// BoardProperties.java:20
@DefaultValue("200") @Min(1) @Max(200) int summaryMaxLength,
```

공개 계약에 박힌 값은 `@Max`로 올릴 수 없게 합니다. 계약보다 긴 값을 설정으로 허용하면 이 검사는 통과하고 계약이 거절합니다. 기본값이 계약과 같은지는 시험이 확인합니다(`MessagingPropertiesTest`).

## 주의사항

- 검증 메시지는 `MessageSource`로 풉니다. 제약의 기본 문구가 아니라 Sennard의 코드(`VALIDATION_FAILED`)와 `details.violations`가 와이어입니다.
- `@Validated`는 프록시로 동작하므로 같은 클래스 안의 자기 호출에는 적용되지 않습니다.
- 입력 record의 컴포넌트 이름이 와이어 키와 같습니다. 이름이 다르면 경계에서만 `@JsonProperty`·`@JsonAlias`로 맞춥니다(도메인 안쪽은 새 이름).
- `sennard-domain`의 record에 제약 어노테이션이 필요하면 `jakarta.validation-api`(어노테이션만 있는 의존성)를 더할 수 있지만, 지금은 `sennard-domain`에 의존성이 없습니다.

## 사용 예시

- 제약 선언 + 검사: `ToolEntranceTest.java:165`(`aSummaryOfOnlyBlanksIsStoppedByTheRecordsDeclaredConstraint`).
- 서비스 메서드 검증: `AbstractBoardServiceTest.java:257`(`invalidInputIsRefusedWithItsCodeAndStoresNothing`, 빈 host → `VALIDATION_FAILED`).
- 계약이 먼저 막는 경우: `ToolEntranceTest.java:173`(두 줄 요약은 스키마 `pattern`이 먼저 `INVALID_INPUT`).
