[English](logging.md)

# 로깅

이 문서는 로그를 남기는 규약입니다. Spring Boot가 한 묶음으로 주는 로그 라이브러리를 쓰고, 수준·형식·파일은 설정으로 정합니다.

## 출발점

- Spring Boot에 함께 들어 있는 로그 라이브러리를 쓰고, `System.out`으로 찍지 않으며, 예외 스택 트레이스를 `printStackTrace()`로 직접 출력하지 않습니다.
- Boot 참조 문서 Logging: `spring-boot-starter-logging`(SLF4J API + Logback)이 기본입니다. 로그 수준·패턴·파일·회전은 `logging.*` 설정이 정합니다.

## 규칙

| 규칙 | 내용 |
| --- | --- |
| 로거 | SLF4J `Logger`(`LoggerFactory.getLogger(클래스)`)만 씁니다. 다른 로깅 API를 섞지 않습니다. |
| 금지 | `System.out`·`System.err`(시험에서 자식 프로세스의 표준 출력 자체가 검사 대상인 탐침 셋만 예외), `printStackTrace()`. 빌드 규칙 `e-console-output`·`e-print-stack-trace`가 검사합니다. |
| 예외 | throwable을 마지막 인자로 넘깁니다: `LOG.warn("설명 {}", 값, failure)`. 스택은 로거가 남깁니다. `e.getMessage()`만 찍지 않습니다. |
| 삼키기 금지 | 잡은 예외는 로그를 남기거나 원인(`cause`)을 붙여 다시 던집니다. 빌드 규칙 `f-swallowed-catch`가 검사합니다(현재 상한 147, 영역을 옮기며 0으로). |
| 수준·형식 | 코드에서 정하지 않고 `logging.*` 설정과 프로파일로 정합니다. |
| 값 | 본문·경로·토큰 같은 값을 로그에 넣지 않습니다. 예외 메시지는 코드 이름뿐입니다(`SennardException`의 `getMessage()`). |
| 비밀값 | 비밀번호·토큰·API 키·서명 키·페퍼는 `String`·`byte[]`·`char[]` 필드나 레코드 구성요소, `@ConfigurationProperties` 값으로 두지 않고 `SecretValue`(`sennard-domain`)로 다룹니다. `toString()`은 `SecretValue[redacted]`이므로 이를 가진 레코드의 문자열, 로그 한 줄, 예외 메시지에 값이 나오지 않으며, JSON 작성기는 이를 거절합니다(JSON으로 쓰는 경우는 사람이 입력한 값을 읽을 때뿐입니다). 값은 라이브러리(JDBC 드라이버, HTTP 헤더, HMAC)에 넘기는 호출에서만 `reveal()`·`revealBytes()`로 꺼내고, 꺼낸 사본은 `SecretValue.clear`로 지웁니다. 파일이나 요청에서 읽어 다른 곳에 넘기기만 하는 중간 `SecretValue`는 다 쓴 코드가 `destroy()`(`javax.security.auth.Destroyable`)로 파기합니다. 값 자신의 바이트를 덮어쓰고 이후에는 읽을 수 없습니다. 덮어쓸 뿐 지켜 주지는 않으므로 라이브러리가 만든 사본, 아무도 파기하지 않은 값, 스왑된 페이지, 메모리 덤프는 막지 못합니다. 파일에 둔 비밀은 판이 구현하는 `PrivateStorage` 포트의 `readSecret`으로만 읽습니다. |
| CLI 결과 | 사용자가 받는 JSON 결과는 로그가 아니므로 주입받는 출력 빈 하나로 모읍니다(5단계). |

## 관련 클래스·설정

| 역할 | 위치 |
| --- | --- |
| 로거 사용 예 | `SessionBoardService`(`sennard-server/.../board/SessionBoardService.java:88-89,120-121`), `ToolEntrance`(`.../entrance/ToolEntrance.java:130,133`) |
| 수준·파일·회전 | `sennard-personal/src/main/resources/application.yaml`의 `logging.level.root: WARN`(`:61`), `SennardPersonalApplication`이 주는 `logging.file.name`·`logging.logback.rollingpolicy.*`(`SennardPersonalApplication.java:32-36`) |
| 규칙 검사 | `ArchitectureRulesTest`의 `e-console-output`, `e-print-stack-trace`, `f-swallowed-catch`(`sennard-arch-checks/src/test/resources/ceilings.properties`) |

## 동작 방식

### 예외를 남기는 모양

```java
// SessionBoardService.java:88-89
} catch (UnavailableException failed) {
    LOG.warn("The board rows could not be read; the list shows presence only.", failed);
```

처리한 뒤에도 요청은 계속되므로 삼키는 것이 아니라 로그를 남기고 줄인 답을 줍니다. 처리할 수 없으면 원인을 붙여 다시 던집니다(`SessionBoardService.java:67-68`).

```java
// ToolEntrance.java:130
LOG.warn("TOOL_CALL_FAILED {}", name, failure);
```

도구 입구가 알 수 없는 예외를 받으면 이름만 인자로, throwable은 마지막 인자로 넘깁니다. 호출자에게 가는 답에는 원문이 아니라 고정된 코드만 갑니다(`UnavailableCode.STORE_UNAVAILABLE`).

### 로그 설정

개인 서버는 `SennardPersonalApplication`이 기본 속성으로 파일 위치(`${sennard.state-dir}/logs/sennard-server.log`)와 회전(10MB, 7개, 총 100MB)을 줍니다. 콘솔이 없는 환경(로그온 작업)에서도 시작 실패의 흔적을 읽을 수 있게 하려는 것입니다. 사용자는 `sennard.yaml`에서 `logging.*`로 바꿉니다. 코드에서 수준을 바꾸지 않습니다.

### 요청 식별자(규약, 5단계에서 구현)

요청 단위 식별자는 서블릿 `Filter` 빈 하나가 MDC에 넣습니다. 로그 패턴은 설정에서 MDC 키를 출력합니다. `new`로 만든 Filter를 쓰지 않습니다. 현재 구현은 없습니다.

## 주의사항

- 로거는 클래스마다 `private static final Logger`입니다(빌드 규칙 `d-stateful-static`은 불변 상수로 봅니다).
- 영역을 옮길 때 `System.out`·`printStackTrace`·삼키는 `catch`를 같이 없앱니다. 상한이 0이 될 때까지 규칙이 줄어든 만큼 상한을 낮추라고 알려 줍니다.
- 시험에서 진단 출력이 필요하면 로거 또는 단정 메시지로 합니다.
- 큰 본문·비밀 값은 로그 인자로 넘기지 않습니다. 예외 메시지에도 넣지 않습니다.

## 사용 예시

- 로그 + 줄인 답: `SessionBoardService.java:84-90`.
- 원인을 붙여 다시 던지기: `SessionBoardService.java:67-68`, `:77-79`.
- 입구의 예상 못 한 예외: `ToolEntrance.java:128-135`.
