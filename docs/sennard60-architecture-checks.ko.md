# Sennard 기계 검사

빌드가 아키텍처 규칙을 검사합니다. 규칙마다 현재 코드의 위반 수를 세어 그 값을 상한으로 두고, 상한을 넘으면 빌드가 실패하므로 새 위반이 들어올 수 없습니다.
[영문판](sennard60-architecture-checks.md)

## 실행 위치

- `sennard-arch-checks`(Gradle 모듈, 시험 소스만): `./gradlew :sennard-arch-checks:test`. 검사 클래스 하나만: `./gradlew :sennard-arch-checks:test --tests '*ArchitectureRulesTest'`.
  모든 모듈의 소스·SQL·빌드 스크립트를 파일로, 컴파일된 클래스를 ArchUnit으로 읽으므로 앱 이미지도 PostgreSQL도 필요하지 않습니다.
- `sennard-store-pg`의 `ForeignKeyCatalogTest`(규칙 k): 지정된 시험 PostgreSQL의 실제 스키마를 조회하므로 다른 업무 데이터베이스 시험과 함께 둡니다.
- 둘 다 `./gradlew test`에서 실행됩니다.

## 고른 도구

| 필요 | 선택 | 이유 |
| --- | --- | --- |
| 패키지 의존 순환 | ArchUnit 1.5.1(`com.tngtech.archunit:archunit`) | 규칙 a는 서로 의존하는 패키지 쌍을 셉니다. Spring Modulith `verify()`는 애플리케이션 모듈(`@SpringBootApplication` 클래스 패키지의 바로 아래 하위 패키지) 단위로 모듈 순환만 보고합니다. Modulith 2.1.1은 Spring Boot 4.1.1 기준으로 빌드되어(`spring-modulith-core-2.1.1.pom`의 `spring-boot-autoconfigure:4.1.1`) 같은 릴리스 라인인 4.1.2와 호환됩니다(pom에서 한 추론이며 Modulith 문서에는 호환표가 없습니다). 그러나 `sennard-server`와 `sennard.service.*` 모듈에는 애플리케이션 클래스가 없고 패키지 단위 쌍은 Modulith가 검사하는 대상이 아닙니다(Spring Modulith 참조, Fundamentals·Verification). ArchUnit의 slices(`slices().matching(...).should().beFreeOfCycles()`, ArchUnit 사용자 가이드 8.2)와 의존 모델은 이를 지원합니다. |
| ArchUnit 버전 | `sennard-arch-checks/build.gradle.kts`에 고정 | Spring Boot 4.1.2 BOM도, 프로젝트가 적용하는 다른 BOM도 관리하지 않습니다(`spring-boot-dependencies-4.1.2.pom`에 `com.tngtech` 항목 없음). 1.4.1부터 Java 25 클래스 파일을 지원합니다(ArchUnit 릴리스 노트). |
| 자바 소스 규칙 | JDK 컴파일러 API(`jdk.compiler`, `com.sun.source`) | 파싱만 합니다. 의존성도 JDK 내부 접근도 필요 없습니다. 규칙은 타입이 아니라 소스에 적힌 이름으로 판정합니다(한계 참조). |
| SQL 규칙 | 주석과 문자열 리터럴 내용을 지운 텍스트 | 주석이나 리터럴 안의 단어는 세지 않습니다. |
| Error Prone | 지금은 넣지 않음 | 이 규칙들은 Error Prone 검사 항목이 아닙니다. JDK 16 이상에서 Error Prone은 javac 내부에 `--add-exports`·`--add-opens`가 필요하고(Error Prone 설치 문서, tbroyer/gradle-errorprone-plugin README) 프로젝트는 다른 곳에서 이를 허용하지 않으며, `-Werror` 아래 새 경고가 재작성 범위를 넓힙니다. javac는 `-Xlint:all -Werror`를 유지합니다. |
| 라이브러리 버전 | Boot BOM | Spring Boot 참조 문서 Build Systems > Dependency Management: "you do not need to provide a version". 규칙 h가 검사합니다. |

## 상한

`sennard-arch-checks/src/test/resources/ceilings.properties`가 k를 뺀 모든 상한을 가집니다(k는 `ForeignKeyCatalogTest.CEILING`). 규칙은 상한을 넘을 때도, 밑돌 때도
(「상한을 N으로 내려라」) 실패합니다. 그래서 코드가 나아지면 사람이 상한을 내리고, 고친 위반 자리를 새 위반이 채울 여지가 남지 않습니다. 실패한 규칙은 위반의 `file:line`을 모두 출력합니다.

## 규칙

| Id | 세는 것 | 비고 |
| --- | --- | --- |
| a-package-cycles-direct | 서로 참조하는 패키지 쌍 | 모든 모듈의 클래스. 패키지와 그 하위 패키지는 서로 다른 패키지 |
| b-os-branching | `"os.name"` 속성 이름, `File.separator`, `File.separatorChar`, 파일 시스템의 `getSeparator()` | |
| c-value-annotation | `@Value` | |
| c-getenv | `System.getenv` | |
| c-getproperty | JVM 표준 속성이 아니거나 리터럴이 아닌 키에 대한 `System.getProperty`, `Boolean.getBoolean`, `Integer.getInteger`, `Long.getLong` | |
| c-getproperty-jvm | `java.*`, `jdk.*`, `os.*`, `user.*`, `file.*`, `path.*`, `line.*`, `native.*`, `stdout.*`, `stderr.*`, `sun.*` 속성에 대한 같은 읽기 | 따로 셈 |
| d-stateful-static | final이 아니거나, final이지만 잠금·난수원·가변 컬렉션·배열 등 가변 객체인 정적 필드 | 불변 상수(`String`, 숫자, `Duration`, `Pattern`, `List.of`, `Map.of`, enum 상수)는 제외. 불변 컬렉션 팩토리로 만들지 않은 컬렉션은 불변을 증명할 수 없어 셈 |
| e-console-output | 제품·시험 소스의 `System.out`, `System.err` | 자식 프로세스의 표준 출력 자체가 시험 대상인 탐침 셋(`Utf8BoundaryProbe`, `HostStandIn`, `LocalInstallationProbe`)은 제외(계획 D15) |
| e-print-stack-trace | `printStackTrace()` | |
| f-swallowed-catch | 던지지 않고, 로거(`trace`~`error`, `log`, 이름에 `log`가 든 수신자)를 부르지 않고, 인터럽트 표시를 되살리지 않고, 예외 객체를 넘기지도 않는 catch 블록 | `e.getMessage()`만 쓰는 것은 처리로 보지 않음 |
| g-string-switch | 문자열 상수를 case 라벨로 쓰는 `switch` | |
| g-string-equals | 단어형 리터럴(영문자·숫자·`_`·`.`·`:`·`-`, 두 글자 이상, 영문자로 시작) 또는 같은 파일에서 그런 리터럴로 정한 final String 변수와의 `equals`, `equalsIgnoreCase`, `contentEquals`, `Objects.equals` | `""`, `"/"`, `"."`는 종류가 아니라 구분자라 제외 |
| h-managed-version | Boot BOM이나 Spring AI BOM이 관리하는 라이브러리 옆에 적은 버전 | Boot BOM pom은 Gradle로 받아 읽음. 그 BOM이 import하는 BOM의 그룹은 관리 대상으로 봄(import된 pom은 열지 않으므로 근사) |
| h-boot-version-copy | `val springBootVersion` 밖에 다시 적은 Boot 버전 | |
| i-path-string | 문자열로 다룬 경로: 역슬래시 치환, 드라이브 패턴(`[A-Za-z]:`, `charAt(1) == ':'`), `startsWith`·`endsWith(x + "/")`, 구분자에 대한 `split`·`indexOf`·`lastIndexOf`, `+`로 이은 구분자 | `"a" + "/" + b`는 경로가 아니라 URL이나 식별자를 만들 때도 해당 |
| j-sql-trigger | 마이그레이션의 `CREATE TRIGGER`와 `RETURNS trigger` 함수 | |
| l-sql-logic-object | 마이그레이션의 사용자 정의 `FUNCTION`, `PROCEDURE`, `RULE`, `AGGREGATE`, `OPERATOR`, `DO` 블록 | 질의 안의 내장 함수·집계(`max`, `min`, `count`, `sum`, `coalesce`, `lower` 등)는 로직 객체가 아님 |
| l-sql-udf-call | 마이그레이션이 만든 사용자 정의 함수의 호출(마이그레이션과 코드의 SQL 문자열) | |
| m-pg-syntax | 마이그레이션과 코드 SQL의 PostgreSQL 전용 구문·함수: `::`, `ILIKE`, `ON CONFLICT`, `RETURNING`, `json` 연산자와 `jsonb`, `pg_*`, 전문 검색, `SERIAL`, `SKIP LOCKED`, `ARRAY[`, `unnest`, `string_agg`, `generate_series`, `DISTINCT ON`, `current_setting`, `clock_timestamp`, `now()`, `extract(epoch`, `bytea`, `timestamptz`, 부분 인덱스, `IF [NOT] EXISTS` | 준수 기준 문서: PostgreSQL 매뉴얼 Appendix D SQL Conformance(D.1 Supported Features, D.2 Unsupported Features)의 "Out of 177 mandatory features required for full Core conformance, PostgreSQL conforms to at least 170". 이 목록은 근사치라 기능 코드가 아니라 알려진 PostgreSQL 전용 표기를 셈. `uuid`·`text` 같은 열 타입 이름과 콜레이션 이름 `"C"`는 세지 않음 |
| m-rls | `CREATE/ALTER/DROP POLICY`, `ENABLE/FORCE/DISABLE ROW LEVEL SECURITY`, `set_config` | 업무 DB가 롤·권한·행 수준 보안 없이 한 계정만 쓰므로 상한 0인 전면 금지. m-pg-syntax와 따로 셈 |
| m-advisory-lock | `pg_advisory_*`, `pg_try_advisory_*` | 제거 대상. 따로 셈 |
| n-no-multi-user-columns | 마이그레이션과 코드의 SQL에서 사용자를 구분하는 식별자(한정 여부와 관계없이 식별자 전체: `account`·`account_*`, `ns`, `tenant`·`tenant_*`, `namespace`·`namespace_*`). Spring Data 리포지토리 인터페이스에서는 그런 열을 이름에 적은 파생 쿼리(자체 `@Query`가 있는 것은 제외, 예: `findByAccountIdAndName`, `findByKeyNsAndKeyName`). `@Table` 엔티티(클래스 또는 record)에서는 `accountId`, `ns`, `namespace`, `tenant`, `tenantId`라는 필드 | 개인판은 PC 한 대에 사용자 한 명이므로 사용자·계정·테넌트·이름공간을 가르는 키·열·표가 없습니다. 상한 0의 단순 금지. SQL의 주석과 문자열 리터럴은 읽지 않고(`SqlRules.mask`), 더 긴 식별자(`accounting`, `nsx`)는 다른 이름입니다. 읽는 SQL은 `.sql(...)`과 `@Query`의 인자, 같은 파일의 상수·변수를 거친 값, 매개변수를 `.sql(...)`에 넘기는 도우미 메서드의 인자입니다. `@Table` 엔티티는 어노테이션으로 알아보므로, `@Table`이 없는 중첩 record를 키로 쓰는 엔티티는 리포지토리의 메서드 이름으로만 읽습니다 |
| w-sql-write-verb | 옛 DB 권한이 허용하지 않던 동사로 표를 쓰는 코드의 SQL 문: `sennard.module_schema`·용어집 표는 코드가 쓰지 않음(마이그레이션이 씀), `user_prompt`·`recap_nudge`·`transcript_anchor`·`attachment`·`attachment_file`·`attachment_storage`는 INSERT만(뒤의 셋은 보관 첨부, 묶음에 든 파일, 그 파일이 저장된 사본의 기록이며, 업로드 중인 `upload_staging`은 제한 없이 씀), `turn_recap`은 INSERT·UPDATE만, 그리고 `user_prompt`는 `UserPromptWrites`만, `transcript_anchor`는 `TranscriptAnchorRepository`만 씀(불변식 I4·I5: 사용자 원문은 바뀌지 않고, 용어집은 읽기 전용) | 상한 0. 표 목록은 `SqlRules.ALLOWED_WRITES`, 표 하나의 유일한 쓰기 담당은 `SqlRules.ONLY_WRITER`. 목록에 없는 표는 전부 쓸 수 있음 |
| k-missing-foreign-key | 다른 표의 키를 가리키는데 FK가 없는 열 | 실제 PostgreSQL 카탈로그(`information_schema`) 조회: 이름이 `_id`·`_ref`로 끝나고, 다른 표에 같은 이름·타입의 기본키 열이 있으며, 자기 표의 FK가 그 열을 덮지 않음 |
| o-domain-imports | `sennard-domain` 소스 중 `org.springframework`, `java.sql`, `javax.sql`, `tools.jackson.databind`의 타입을 import(정적·와일드카드 포함)하거나 완전한 이름으로 쓰는 것 | 도메인 모듈은 평범한 Java(업무 판정, 값 record, enum, sealed 오류)입니다. 상한 0의 단순 금지. 그런 이름을 말하는 주석·문자열은 사용이 아니고, 다른 모듈은 이 규칙이 읽지 않음 |
| p-optional-not-return | `Optional`(또는 `OptionalInt`, `OptionalLong`, `OptionalDouble`)을 필드, record 구성요소, 메서드 매개변수로 쓰거나 `List`·`Set`·`Collection`·`Iterable`·`Map`(등 컬렉션)의 요소로 쓰는 것 | `Optional`은 「결과 없음」을 나타내는 메서드 반환 타입에 쓰는 것입니다(JDK `Optional` API Note). 지역 변수, 람다 매개변수, `Supplier<Optional<...>>`은 세지 않습니다. `ceilings.properties`의 상한 0, 단순 금지: 이런 `Optional`은 어느 모듈에도 더 이상 없습니다 |
| p-or-else-null | `orElse(null)` 호출 | `Optional`(또는 Spring `BindResult`)에서 null을 되살리는 것입니다. 답이 없을 수 있는 메서드는 `Optional`을 그대로 돌려주거나 `map`으로 바꾸거나 `orElseThrow`로 예외를 냅니다. 상한에 남은 것은 `sennard-core`와 `sennard-store-pg`에 있습니다 |
| q-process-api | 목록에 없는 곳의 클래스가 `ProcessBuilder`·`ProcessHandle`(또는 그 안의 중첩 타입)에 의존하거나 `Runtime.exec`를 호출하는 것 | ArchUnit으로 컴파일된 클래스를 읽습니다(`ProcessRules`, 검사는 `ProcessRulesTest`이며 `ceilings.properties`에 두지 않고 0이어야 함). 허용 위치는 패키지 4개(호스트 감시, 훅 클라이언트, CLI의 명령, 설치기)와 클래스 2개(`ToolProcess`, `LocalProcessRunner`)이고 각각의 이유가 목록에 적혀 있습니다. 새 위치는 그것을 추가하는 변경에서 목록에 함께 추가합니다 |
| q-host-name-literal | 제품 main 소스의 문자열 리터럴 중 `new HostId("...", "...")`에 적힌 이름(호스트의 훅이 게시하는 레이블, 세션이 저장되는 이름)과 통째로 같은 것으로, 그 호스트를 정의한 파일이 아닌 곳에 있는 것 | 소스를 읽습니다(`HostRules`, 검사는 `HostRulesTest`이며 `ceilings.properties`의 상한 0, 단순 금지). 호스트는 `new HostId(...)`가 적힌 곳(어댑터 패키지의 `<Host>Host` 클래스)에서 정의됩니다. 공통 코드는 `HostId`를 비교하고 전달하며 어댑터는 정의에서 이름을 읽으므로, 새 호스트는 어댑터와 그 정의만 추가하면 됩니다. 리터럴은 통째로 비교합니다(`"claude"`는 이름이고 `".claude"`와 `"claude-plugin"`은 아님). 시험은 읽지 않습니다. 시험은 게시되는 레이블을 바깥에서 쓰는 그대로(URL, 헤더, 시험 자료) 적기 때문입니다 |
| r-prompt-archive | (1) MCP 도구를 정의하는 `..server..`의 클래스(`ToolEntrance`에 의존하거나 `@McpTool`이 붙은 메서드가 있는 것)에서 직접 또는 여러 클래스를 거쳐 닿는 클래스에 `PromptArchiveWriter`, `UserPromptWrites`, `PromptCollector`, 또는 `HookDomain.onEvent`를 부르는 클래스가 있는 것, (2) `PromptArchiveWriter`가 아닌 클래스가 `UserPromptWrites`에 의존하는 것, (3) `PromptArchiveWriter`와 훅 클래스 `PromptCollector`가 아닌 클래스가 `PromptArchiveWriter`에 의존하는 것, (4) `SessionEventHandler`가 아닌 클래스가 `PromptCollector`에 의존하는 것, (5) `SessionEventHandler`가 아닌 클래스가 `HookDomain`의 `onEvent`(인터페이스든 그것을 구현한 클래스든)를 부르는 것 | ArchUnit으로 컴파일된 클래스를 읽습니다(`PromptArchiveRules`, 검사는 `PromptArchiveRulesTest`이며 `ceilings.properties`에 두지 않고 0이어야 함). (1)의 닿는 범위는 도구 클래스와 그 안의 중첩 클래스에서 ArchUnit이 따라가는 간접 의존 전체입니다. 그래서 도구가 부르는 서비스가 `PromptCollector`나 `HookDomain` 빈 묶음을 받아 스스로 만든 원문 이벤트를 넘기는 경우도 찾습니다. 훅 이벤트를 훅 도메인에 넘길 수 있는 것은 훅 처리 쪽 클래스(`SessionEventHandler`)뿐입니다. 사용자의 말은 훅 경로로만 쓰며, 도구는 보관소를 읽고 리캡을 기록할 뿐입니다. 컴파일된 클래스의 의존으로 드러나지 않는 연결(이름으로 찾은 빈, 인터페이스 뒤에서 컨테이너가 고르는 구현)은 보지 못하므로, 데이터베이스가 권한으로 같은 선을 지킵니다(서비스 역할은 `sennard.user_prompt`에 SELECT·INSERT만 가능). `PromptArchiveRulesTest`는 규칙이 제품을 알아보는지(게시판·메시징·원문 보관소의 도구 클래스를 찾고, 원문 보관 도구에서 리캡 리포지토리까지 두 클래스 아래를 따라가며, 목록의 클래스가 규칙이 말하는 대로 쓰는지)를 확인하고, 위반 예시(`promptfixtures`: `ToolBad`, `ToolBadAnnotated`, `StrayBad`, 그리고 서비스와 `FixtureHop`을 거치는 `ToolViaCollector`, `ToolViaDomains`, `ToolViaDispatcher`)로 각 발견이 실패로 바뀌는 것을 보입니다 |
| s-string-code-exception | 이름이 `Exception`, `Failure`, `Error`로 끝나는 타입의 `new` 가운데 첫 인자가 대문자·숫자·`_`로 된 리터럴인 것(실패의 이유를 문자열로 적은 것) | 소스를 읽습니다(`ExceptionRules`, 검사는 `ExceptionRulesTest`이며 `ceilings.properties`의 상한 0, 단순 금지). 업무 실패는 일곱 범주 가운데 하나의 `SennardException`이고 이유는 그 범주 코드 enum의 상수입니다. 스킬 검사의 입력 거절과 CLI 명령의 중단도 같습니다. 실패의 이유를 문자열로 적은 곳은 어느 모듈에도 더 이상 없습니다. 규칙은 소스에 적힌 이름을 읽으므로 타입은 이름으로 실패가 됩니다 |
| t-sennardexception-switch-default | 봉인된 계층의 예외 타입 일곱 가운데(`InvalidInputException`, `NotFoundException`, `ConflictException`, `GateRefusedException`, `BindingException`, `IntegrityException`, `UnavailableException`) 넷 이상에 case가 있으면서 `default`도 있는 `switch` | 소스를 읽습니다(`ExceptionRules`, 검사는 `ExceptionRulesTest`이며 `ceilings.properties`에 두지 않고 0이어야 함). MCP 경계(`SennardToolResults`)와 HTTP 경계(`SennardExceptionHandler`)의 범주별 응답에는 `default`가 없으므로, 계층에 범주를 더하면 그것이 답해질 때까지 컴파일되지 않습니다. 타입 하나나 둘만 골라내는 `switch`(`RuntimeException`을 감싸는 재시도)는 모든 범주에 대한 답이 아닙니다 |
| u-transaction-boundary | (1) `@Service`가 아닌 클래스의 메서드나 클래스에 붙은 `@Transactional`(Spring 것과 Jakarta 것, 서비스에 중첩된 클래스는 그 서비스에 속함), (2) `TransactionTemplate`·`TransactionOperations`에 대한 의존, (3) 서비스 안팎을 가리지 않는 손으로 만든 잠금: 제품 메서드 중 이름이 `lock`과 대문자로 이어지는 것(예: `lockModule`)의 정의 또는 호출. 동시성은 DB의 트랜잭션·행 잠금·제약이 맡습니다, (4) 트랜잭션인 메서드나 트랜잭션인 클래스에 붙은 `@Retryable`(직접 또는 그것이 붙은 제품의 주석. 제품에는 자체 재시도 주석이 없음. 메타 주석까지 읽으므로 나중에 더한 재시도 주석도 잡힘), (5) `@Transactional(propagation = REQUIRES_NEW)`·`NESTED`, (6) 손으로 정한 전파(`setPropagationBehavior`, `new DefaultTransactionDefinition(int)`), 그리고 각각 `ceilings.properties`에 자기 상한(0)을 두고 제품의 main 클래스만 읽는 셋: (7) `u-tx-platform-manager`: `PlatformTransactionManager`의 `getTransaction`·`commit`·`rollback` 호출, (8) `u-tx-jdbc-connection`: `java.sql.Connection`의 `setAutoCommit`·`commit`·`rollback`·`setSavepoint` 호출. 다만 `TransactionBoundaryRules.Names.PRODUCT`에 이유와 함께 이름으로 적은 클래스 셋은 제외합니다(`HistoryMigrator`는 자기 연결로 마이그레이션 스크립트를 적용하며 DDL이 일부 DB에서 트랜잭션을 끝내므로 스크립트마다 이력 행과 함께 끝냅니다. `MigrationRunner`는 그 연결을 열고 자동 커밋을 끕니다. `DataSourceFactory`는 모든 유스케이스 밖의 자기 연결로 기동 점검을 하며 인코딩 탐침만 롤백합니다). 모듈 단위 예외는 없고, 면제된 클래스가 여전히 있고 그런 호출을 여전히 하는지 시험이 확인합니다, (9) `u-tx-no-rollback-for`: 트랜잭션 주석의 `noRollbackFor`·`noRollbackForClassName`(Jakarta의 `dontRollbackOn` 포함). 거절하면 유스케이스 전체를 롤백하기 때문입니다 | ArchUnit으로 컴파일된 클래스를 읽습니다(`TransactionBoundaryRules`, 검사는 `TransactionBoundaryRulesTest`이며 위반 (1)~(6)은 `ceilings.properties`에 두지 않고 0이어야 하며, (7)~(9)는 위에 적은 상한을 둡니다). 유스케이스는 서비스의 public 메서드 하나이고 그 메서드가 트랜잭션 하나입니다. 저장소(store, Spring Data 리포지토리)는 호출자의 트랜잭션에 합류만 하고, 진입점(훅, 도구, 스케줄)은 트랜잭션을 열지 않고 서비스 메서드 하나를 부르며, 서비스를 위해 트랜잭션만 쥐는 빈은 허용하지 않습니다(대신 유스케이스를 다시 나눕니다). 진 트랜잭션은 다시 실행하지 않습니다. 경합의 승패는 조건부 문장 하나의 결과로 가리므로 트랜잭션의 재시도는 위반입니다. 위반 (1)~(7)과 (9)에는 예외도 면제 클래스 목록도 없고, (8)에는 이름으로 적은 클래스 셋뿐입니다. 서버 없이 도는 명령(오프라인 관리자 초기화)은 웹 서버 없는 자기 Spring Boot 컨텍스트를 띄우고, 그 유스케이스인 `AdminReset`은 `@Transactional` 메서드 하나가 트랜잭션인 `@Service`입니다. 시험은 규칙이 이 클래스를 읽는지 확인합니다. 규칙은 컴파일된 클래스가 말하는 것만 읽습니다. 이름으로 찾은 빈을 거친 트랜잭션이나 서비스 자신의 문자열 SQL로 건 잠금은 보이지 않습니다. 픽스처(`transactionfixtures`)가 각 위반이 실패(red)로 나오는 것(재시도 주석 셋, 메타 주석만 있을 때, 두 전파)과, 서비스·그 람다와 중첩 클래스·트랜잭션이 아닌 재시도 메서드·트랜잭션 없는 읽기가 통과(green)하는 것을 증명합니다. 같은 픽스처가 (7)~(9)도 증명합니다(매니저 호출 셋, 연결 호출 넷, `noRollbackFor` 형태 셋이 실패로 나오고, 면제 클래스와 그 중첩 클래스, `Connection.close`나 매니저의 이름 조회는 통과합니다). 리플렉션이나 람다에 든 `TransactionCallback`으로 쓴 트랜잭션 매니저·연결, 호출자 대신 커밋하는 `JdbcTemplate`·`DataSourceUtils` 보조 메서드는 보이지 않습니다 |

## 한계(검사가 보지 못하는 것)

- 소스 규칙은 구문으로 판정합니다. 컴파일러만 아는 이름은 보지 못합니다: 다른 클래스의 문자열 상수와의 `equals`, 별칭·메서드 결과로 얻은 타입(규칙 d는 선언 타입과 초기화식으로 컬렉션을 판단),
  이름에 `log`가 없는 변수에 든 로거(규칙 f).
- 규칙 n과 SQL 규칙은 코드에 문자열로 적힌 SQL을 읽습니다. 실행 시점에 값으로 조립한 SQL(메서드 결과로 정한 표 이름, 도우미가 돌려주는 조각)은 리터럴 부분만 읽고, 전부 실행 시점에 만든 문은 보이지 않습니다.
- 규칙 k는 가리키는 키와 열 이름이 다른 참조(`session_ref`를 가리키는 `sender_ref`·`target_ref`)와 이름이 `_id`·`_ref`로 끝나지 않는 키에 대한 참조를 놓칩니다. 같은 이름의 키가 여러 표에 있으면 엉뚱한 대상을 적을 수 있습니다.
- 규칙 h는 import된 BOM의 그룹을 관리 대상으로 봅니다. 그 그룹에 속하지만 BOM이 싣지 않은 라이브러리는 잘못 보고됩니다.
- 규칙 g-string-equals는 바깥 형식의 문자열(HTTP 메서드, MCP 메서드 이름)도 단어형 리터럴이면 셉니다. 재작성에서 이런 문자열은 경계에만 둡니다.

## 검사 자체의 사례

`RuleFixturesTest`는 규칙마다 위반하는 소스와 지키는 소스를 둡니다. 규칙이 건드리지 말아야 하는 경계 사례(로그를 남기거나 다시 던지는 catch, enum switch, 불변 상수, SQL의 `max`·`min`·`coalesce`,
SQL 주석·리터럴 안의 단어)를 포함합니다. 규칙 a는 서로 참조하는 두 패키지와 그렇지 않은 두 패키지를 실제로 컴파일해 검사합니다.
