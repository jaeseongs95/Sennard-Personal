[English](settings-and-profiles.md)

# 설정과 프로파일

이 문서는 설정 값을 어떻게 받고, 배치마다 다른 것을 어떻게 가르는지의 규약입니다. 기준 구현은 현황판과 세션 메시징입니다. 프로파일 부분은 규약만 정했고 아직 구현하지 않았습니다(아래에 표시).

## 출발점

- Spring 문서: 「Provide choice at every level. Spring lets you defer design decisions as late as possible ... through configuration without changing your code.」 환경 차이는 업무 코드가 아니라 설정에 둡니다.
- Boot 참조 문서 Externalized Configuration: `@ConfigurationProperties`는 타입이 있는 바인딩이고, 검증(`@Validated`)과 기본값(`@DefaultValue`)을 함께 둡니다. 값마다 `@Value`로 읽지 않습니다.
- 판단 순서(Spring에 있나 → Boot 자동 설정 → 설정 → 빈): DataSource·풀·트랜잭션 관리자·Jackson·`MessageSource`·검증기는 Boot가 조립해 주므로 직접 만들지 않고 `spring.*` 설정으로 바꿉니다.

## 규칙

| 규칙 | 내용 |
| --- | --- |
| 값 | `@ConfigurationProperties` record로 받습니다. `@Value`는 새로 쓰지 않습니다(빌드 규칙 `c-value-annotation`, 현재 27곳이 상한이고 영역을 옮기며 줄입니다). |
| 기본값 | record 성분의 `@DefaultValue`입니다. 코드 안의 `getProperty(키, 기본)`·상수 기본값을 쓰지 않습니다. |
| 제약 | 값 범위는 record에 선언하고 `@Validated`로 시작할 때 검사합니다. 공개 계약에 박힌 값은 `@Max`로 올릴 수 없게 합니다. |
| 시간·길이 | `Duration`·`DataSize` 같은 Spring 타입을 씁니다. 초 단위 정수에 단위를 이름으로 붙이지 않습니다(`retention: 7d`). |
| 시계·식별자·난수·JSON | 빈으로 주입합니다: `Clock`, `IdGenerator`, `SecureRandom`, `JsonMapper`(Boot 자동 설정). `Clock.systemUTC()`·`new SecureRandom()`·`JsonMapper.builder()`를 업무 코드에서 직접 만들지 않습니다. |
| 환경 변수·OS 관례 | 읽지 않습니다. 필요한 JVM 표준 값은 yaml의 `${user.home}` 같은 자리표시자로 받습니다(빌드 규칙 `c-getenv`·`c-getproperty`). |
| 배치 차이 | 프로파일입니다. 업무 코드에서 프로파일 이름으로 분기하지 않고, 구현 빈 선택과 설정 값만 프로파일이 정합니다. |
| 인프라 켜고 끄기 | 설정 한 줄 + 조건 빈(`@ConditionalOnProperty`)입니다. 꺼진 쪽을 mock 빈으로 채우지 않습니다. |

## 관련 클래스

| 역할 | 클래스 |
| --- | --- |
| 영역 설정 | `BoardProperties`(`sennard-app/.../board/BoardProperties.java:17-23`), `MessagingProperties`(`.../messaging/MessagingProperties.java:19-24`) |
| 바인딩 등록 | `AppConfiguration`(`sennard-app/.../app/AppConfiguration.java:27`, `@ConfigurationPropertiesScan`) |
| 주입하는 빈 | `Clock`(`AppConfiguration.java:52-55`), `IdGenerator`(`:58-61`), `MessageSource`(`:40-43`), `MessageSourceAccessor`(`:46-49`) |
| Boot 자체 설정 | `spring.data.jdbc.dialect`(`sennard-personal/src/main/resources/application.yaml:13-18`) |
| 기본 설정 파일 | `sennard-personal/src/main/resources/application.yaml` |

## 동작 방식

### 영역 설정 record

```java
// BoardProperties.java:17-23
@ConfigurationProperties("sennard.board")
@Validated
public record BoardProperties(
    @DefaultValue("200") @Min(1) @Max(200) int summaryMaxLength,
    @DefaultValue("120") @Min(1) @Max(120) int roleMaxLength,
    @DefaultValue("7d") @NotNull Duration retention) { }
```

서비스는 생성자로 받습니다(`BoardService.java:40`). 설정 키는 `sennard.<영역>.<값>`이고 yaml에서는 `sennard.board.retention: 14d`로 바꿉니다. 기본값이 공개 계약의 값과 같은지는 시험이 지킵니다(`MessagingPropertiesTest`). 시험은 `Clock`을 고정 시계로 바꾸어 시간에 의존하지 않고 만료·재시도를 시험합니다(`RewrittenAreasContext`의 `TestClock`).

### 시작할 때 지연되는 인프라: 방언

개인 서버의 업무 DB 연결은 리스너가 뜬 뒤 설정될 수 있습니다. `application.yaml`은 Spring Data JDBC 방언을 지정하지 않습니다. 방언은 연결된 업무 DB의 제품을 따릅니다. 저장소가 처음 필요할 때 그 연결에서 방언을 정하므로(`LazyJdbcRepositories`, `BusinessJdbcConfiguration`) 사용자가 준 JDBC URL과 드라이버(관리 페이지의 연결, 설정 데이터베이스에 저장)만으로 DBMS가 정해집니다. 업무 코드는 방언을 모릅니다.

### 시작하는 환경의 기본값

- `spring.web.locale: en`(`application.yaml`): `Accept-Language`가 없는 요청은 서버 기계의 언어가 아니라 카탈로그의 원어(영어)로 답합니다([예외와 메시지](exceptions-and-messages.ko.md)).
- `management.health.db.enabled: false`: 저장소가 준비 중이면 `STORE_PREPARING`으로 답하므로 DataSource 상태 검사가 서버 장애로 보지 않게 합니다.

### 서버 JVM 옵션: 프리셋 하나

JVM 옵션은 JVM이 뜨기 전에 정해져야 하고, 서버의 저장소(서명 기록)는 그 JVM 안에서 뜹니다. 그래서 값이 설정 파일 하나(`sennard-jvm.yaml`)를 거쳐, 설치가 서버를 띄울 때 쓰는 JVM 옵션으로 갑니다.

- **프리셋은 enum입니다**(`JvmPreset`, `sennard-domain`): G1 224m·512m·1g·2g·4g, ZGC 4g, ZGC `MaxRAMPercentage=6`. 옵션 문자열을 받는 칸이 없으므로 서명한 변경도 이 밖의 옵션을 넣을 수 없습니다(`JVM_PRESET_SET`은 상수 이름만 받습니다).
- **처음 값은 설치기가 한 번 정합니다**(`Installer.chooseFromMemory`). `OperatingSystemMXBean#getTotalMemorySize`를 한 번 읽고(`HostMemory`) `JvmPresets.estimate`가 표준 크기로 올림한 뒤 그 이하 가장 큰 등급의 프리셋을 고릅니다. 읽은 값과 프리셋을 `sennard-jvm.yaml`(`sennard.jvm.preset`, `sennard.jvm.observed-memory`)에 씁니다. 서버는 기동할 때 메모리를 재지 않습니다. 사람이 쓰는 `sennard.yaml`에는 쓰지 않습니다(프로그램이 다시 쓰는 파일과 분리).
- **JVM 옵션은 한 곳에서 씁니다**(`JvmOptions`). 서버(UTC, 프리셋, 컴팩트 객체 헤더, 서버가 설치 폴더를 작업 폴더로 삼아 도므로 상대 파일 이름을 쓰는 `-Xlog:gc*` 파일 회전, AOT 캐시 자동 모드와 캐시 로그), 훈련·캐시 생성 실행, 호스트가 띄우는 훅 클라이언트와 MCP 중계(같은 작은 옵션에 JVM 로그 끔, 훅 클라이언트 자기 AOT 캐시 `sennard-hook.aot`, `JvmOptions.hookClient`, `hooks.ko.md` 참고)입니다. 서버는 `sennard install`을 실행한 그 `java`로 `<java> <옵션> -cp <애플리케이션 jar> SennardPersonalApplication`으로 띄웁니다. `sennard` 명령 자체는 사용자의 `java -jar`이며 Sennard의 옵션이 없습니다.
- **AOT 캐시는 컬렉터별 파일입니다**(`app-g1.aot`, `app-zgc.aot`). 설치·업그레이드는 선택된 컬렉터의 캐시만 만듭니다. `-XX:AOTMode=auto`라서 캐시가 없거나 맞지 않으면 JVM이 캐시 없이 기동하고 이유를 `jvm-aot.log`에 씁니다(`JvmOptionsTest`가 JVM을 실제로 띄워 확인).
- **로그 폴더는 설치 폴더입니다.** JVM은 로그 파일을 열지 못하면 기동하지 않으므로 항상 있는 폴더에 둡니다.
- **관리자 페이지가 바꿉니다.** 서명한 `JVM_PRESET_SET`이 커밋되면 `PersonalJvmInstallation`이 커밋 뒤(`@TransactionalEventListener`) 파일을 씁니다. 저장소가 준비되면 서명한 값과 파일이 다른 경우(재설치) 서명한 값으로 되돌립니다(`StoreReadyTask`). 변경은 서버를 다시 시작해야 적용됩니다.

### 프로파일(규약, 5단계에서 구현)

환경 축(개인 PC의 서버·시험)을 `application-{프로파일}.yaml`과 `spring.profiles.group`으로 나눕니다. 한 파일에 문서를 여러 개 복제하지 않고 그룹으로 줄입니다. 프로파일은 (1) 설정 값, (2) 구현 빈 선택(`@Profile` 또는 조건 빈)만 정합니다. 현재 `@Profile`은 쓰지 않고 `application.yaml` 한 벌이 개인 서버의 값입니다. 시작할 때 DB가 이미 떠 있는 실행의 값(DB 주소는 `spring.datasource.*`, 방언은 비움)은 프로파일 파일로 둡니다.

## 주의사항

- 설정 record의 값을 올릴 수 있게 하기 전에 공개 계약을 봅니다. 계약이 막는 값은 `@Max`로 막아 둡니다.
- `@ConfigurationProperties` record는 `@EnableConfigurationProperties`나 `@ConfigurationPropertiesScan`으로 등록해야 바인딩됩니다(`AppConfiguration.java:27`이 `sennard.app` 아래를 훑습니다).
- 시험 컨텍스트에서 설정을 바꿀 때는 속성 소스를 컨텍스트에 더합니다(`RewrittenAreasContext.java:126-`). 시스템 속성이나 환경 변수를 바꾸지 않습니다.
- 정적 필드에 설정값을 보관하지 않습니다(빌드 규칙 `d-stateful-static`).

## 사용 예시

- 설정 record와 서비스: `BoardProperties.java:17-23` → `BoardService.java:40-46,59-61`.
- 설정으로 한도를 바꾸는 시험: `AbstractMessagingServiceTest.java`(한도 시험, `MessagingProperties`를 시험용 값으로 줌).
- 방언 설정 시험: `BoardServiceAppConnectionTest.java`의 `theDialectIsStatedBySpringDataJdbcSettingAndDetectingItWouldNeedAConnectionAtStart`.
