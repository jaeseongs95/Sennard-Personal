[English](CONTRIBUTING.md)

# 기여 안내

Sennard 자체를 고치는 사람을 위한 안내입니다. 저장소 작업 규칙(구조와 책임, 벤더 독립 원칙, 브랜치, 배포물 계약)은 [AGENTS.md](AGENTS.md)가 정본이며, 이 문서는 변경을 준비하고 제출하는 순서를 정리합니다.

**외부 기여는 받지 않습니다.** Sennard는 AGPL-3.0-or-later로 제공하고 저작권자가 상용 라이선스도 별도로 줍니다. 그래서 프로젝트 밖에서 보내는 pull request와 코드 기여는 받지 않으며 기여자 라이선스 계약(CLA)도 두지 않습니다. 이슈와 비공개 보안 신고는 환영합니다. 이 안내는 관리자와 관리자가 지시하는 에이전트의 작업 규칙입니다.



- **벤더 독립:** 공용 스킬, 계약, MCP 도구, 런타임은 특정 AI 제품이나 호스트 API를 전제로 하지 않습니다. 호스트별 hook, 신원 관측, 깨우기, 배포 형식은 어댑터나 overlay 경계에만 둡니다. 어떤 호스트·모델·실행 주체(메인 세션·서브에이전트)가 실행해도 같은 코드로 같은 판정을 내려야 합니다.
- **세 호스트 시험:** 결함 하나를 고치는 작은 수정을 포함한 모든 수정은 Claude Code·Codex·Antigravity 세 호스트에서 조사하고 실제로 시험합니다. 시험하지 못한 호스트는 미검증으로 보고합니다.
- **작게 나누기:** 변경은 한 스킬 또는 하나의 공통 계약 경계를 기준으로 작게 나눕니다. 전문 스킬의 책임을 넓히거나 다른 스킬과 겹치게 만들기 전에는 오케스트레이터 라우팅, 계약, 기존 단독 호출에 미치는 영향을 설명합니다. 구현과 무관한 형식 정리는 별도 변경으로 분리합니다.

## 변경 순서

1. gitflow에 따라 작업 브랜치를 만듭니다. `main`은 발행된 판만 담고, `develop`은 다음 판의 통합 브랜치입니다. 새 기능과 수정은 `develop`에서 `feature/<작업명>`을 만들어 작업하고 `develop`에 병합합니다. 발행은 `develop`에서 `release/<판>`을 만들어 판 번호, 릴리스 노트, 발행 전 수정만 넣고, `main`에 병합해 태그를 단 뒤 `develop`에 다시 병합합니다. 발행된 판의 긴급 수정은 `main`에서 `hotfix/<판>`을 만들어 `main`과 `develop`에 병합합니다. 사용자가 브랜치 이름을 지정하면 그 이름을 따릅니다. 이 규칙 전에 만든 `codex/`, `claude/`, `gemini/` 작업 브랜치는 병합될 때까지 그대로 둡니다.
2. 저장소 구조와 관례는 [아키텍처](docs/contributing/architecture.ko.md)와 [개발 참고](docs/contributing/development.ko.md)에서 확인합니다.
3. 변경과 함께 시험을 둡니다. 각 스킬에는 정상, 경계, 예상 실패 사례를 둡니다. 어떤 시험이 CI에서, 어떤 시험이 시험 서버에서 도는지는 [시험과 CI](docs/contributing/testing-and-ci.ko.md)에 있습니다.
4. 생성물(`claude-plugin/`, 훅 정의 파일)이 원본을 담고 있으면 다시 만들어 함께 커밋합니다. [생성물](docs/contributing/development.ko.md#생성물)
5. 모든 Java 소스 파일과 SQL 마이그레이션 스크립트는 SPDX 라이선스 머리(주석 세 줄, [ADDITIONAL-TERMS.ko.md](ADDITIONAL-TERMS.ko.md) 참고)로 시작합니다. `java/`에서 `./gradlew spotlessApply`를 실행하면 머리가 들어가며, `spotlessCheck`는 `check`에서 실행되어 머리가 없는 파일이 있으면 실패합니다.
6. 아래 검증 명령을 실행합니다.
7. 커밋은 `feat: add acceptance evidence validator`, `docs: define skill boundaries`처럼 명령형 Conventional Commit으로 씁니다.

```bash
cd java
./gradlew test --continue
cd ..
git diff --check
```

전체 검증 순서와 각 명령의 역할은 [개발 참고](docs/contributing/development.ko.md#개발-환경과-검증-명령)에 있습니다.

## 시험 실행

전체 JUnit 시험은 개발 기기에서 `java/`의 `./gradlew`만으로 돌립니다. 셸 래퍼 스크립트는 필요 없습니다.

- **Gradle을 띄우는 JVM:** `./gradlew`는 시작하려면 JDK 17 이상이 필요하고, `JAVA_HOME`이나 `PATH`에서 찾습니다. 빌드의 컴파일과 시험은 JDK 25로 하며 Gradle 툴체인이 고릅니다. 설치된 JDK 25는 자동으로 찾고(`JAVA_HOME`, Gradle을 실행한 JVM, 일반 설치 폴더), 없는 기기에서는 `java/settings.gradle.kts`에 선언한 foojay 툴체인 리졸버로 내려받습니다. 내려받기에는 네트워크가 필요합니다. 플러그인은 `plugins.gradle.org`에서 한 번, JDK를 내려받을 때는 foojay API와 JDK 배포처에서 받고, 의존성은 Maven Central에서 옵니다. 표준 위치가 아닌 폴더의 JDK를 쓰려면 이 저장소가 아니라 사용자의 `gradle.properties`(`<GRADLE_USER_HOME>/gradle.properties`)에 `org.gradle.java.installations.paths=<JDK 폴더>`를 적습니다.
- **격리:** 모든 시험 JVM(모든 `Test` 작업)은 Gradle을 실행한 에이전트 세션의 변수(`SENNARD*`, `CLAUDE*`, `CODEX*`, `GEMINI*`, `ANTHROPIC*`, `OPENAI*`) 없이 시작하므로, 시험이 그 세션의 MCP 서버나 relay에 닿을 수 없습니다. 홈·임시 폴더(`user.home`, `java.io.tmpdir`, `HOME`, `USERPROFILE`, `TEMP`, `TMP`, `TMPDIR`)와 제품 자신의 위치(`SENNARD_INSTALL_ROOT`, `SENNARD_STATE_DIR`. 시험이 띄운 자식 JVM이 상속하며 `user.home`은 상속하지 않습니다)는 모듈의 `build/isolated/<작업>/` 아래 폴더이므로 어떤 시험도 실제 프로필에 닿지 않습니다. `sennard-protocol`의 `TestIsolationTest`가 이를 검사합니다.
- **메모리와 병렬 실행:** 빌드는 힙, 프로세서 수, 워커 수를 스스로 정하지 않습니다. 빌드를 돌리는 JVM, 동시에 도는 작업의 수(`org.gradle.workers.max`), 시험 JVM의 힙은 Gradle의 기본값이고 시험 JVM은 모든 프로세서를 봅니다. `org.gradle.parallel=true`로 모듈이 함께 돕니다. 다른 값이 필요한 컴퓨터는 그 컴퓨터의 `<GRADLE_USER_HOME>/gradle.properties`나 명령줄에서 직접 정합니다. `org.gradle.jvmargs`, `org.gradle.workers.max`가 있고, 시험 JVM에는 `-Psennard.test.heap=<크기>`, `-Psennard.test.cpus=<n>`, `-Psennard.test.childHeap=<크기>`(시험이 띄우는 JVM의 힙이며, 주지 않으면 시험 JVM의 힙을 씁니다)가 있습니다. 한 컴퓨터에서 여러 빌드가 동시에 돌면서 하나의 메모리 한도 안에 머물러야 한다면 `sennard.heap.ledger=true`와 `sennard.build.heapLimitMiB=<MiB>`로 힙 장부를 켤 수 있습니다(기본값과 상한이 없습니다). 그러면 빌드는 데몬의 힙을, 실행 중인 `Test`와 `JavaExec` 작업은 각자의 힙을 빌드들이 함께 쓰는 장부 폴더(`sennard.heap.ledgerDir`, 주지 않으면 Gradle 사용자 홈의 `sennard-heap-ledger`)에 예약하고, 예약의 합이 한도를 넘으면 작업은 기다립니다(`sennard.heap.maxWaitMinutes`, 60). 장부를 켜면 한 빌드의 `Test`, `JavaExec`, `Exec` 작업은 한 번에 하나씩 돕니다. 이들은 사용자를 하나만 허용하는 Gradle 빌드 서비스(`maxParallelUsages = 1`)를 함께 쓰므로 Gradle 스케줄러가 형제 작업을 시작 전에 붙들어 두고, 작업이 워커 자리를 쥔 채 형제를 기다리는 일이 없습니다(그 대기가 예약을 쥔 작업에 자리를 남기지 않아 빌드가 멈춘 적이 있습니다). 장부가 직접 기다리는 것은 다른 빌드의 예약뿐입니다. `java/build-logic`의 시험도 같은 이유로 `sennard-repo-tools`에서 돕니다. 장부의 연결은 `java/build-logic`의 Java 클래스 `SennardBuildLogicPlugin`에 있고(스크립트 플러그인은 클래스 로더가 루트 스크립트의 플러그인과 격리되어 쓰지 않습니다), `sennard-repo-tools`의 `HeapLedgerBuildTest`가 그 플러그인을 쓰는 작은 빌드를 Gradle TestKit으로 돌립니다. 장부를 켜고 `--max-workers=2`로 돌릴 때, 예약의 합이 한도를 넘는 `Test` 작업 셋이 끝나야 합니다. 성능 측정 `perfBaseline`은 JVM 하나로 둡니다. 여기에는 실제 설치본에서 세션 시작을 다섯 번 재는 측정(`HookStartsServerProcessTest`)이 들어 있고 `test`는 이를 돌리지 않습니다. 대신 `test`는 그런 시작을 한 번 돌려 띄운 서버가 응답하는지 단언하는 시험을 그대로 돌립니다. 측정은 `./gradlew :sennard-personal:perfBaseline`으로 돌리며 아래의 시험 DB를 지정해야 합니다.
- **Gradle 내장 기능:** 데몬은 켜 둡니다(CI 러너는 한 번 쓰고 버리므로 CI는 `--no-daemon`을 씁니다). 빌드 캐시(`org.gradle.caching`)는 입력이 같은 앞선 빌드의 컴파일·리소스·jar 결과를 가져오고, 설정 캐시(`org.gradle.configuration-cache`)는 다음 빌드를 위해 설정 결과를 보관합니다. `Test` 작업은 빌드 캐시에서 가져오지 않습니다. 시험은 매번 실제로 돕니다. `--rerun-tasks`는 컴파일까지 모두 다시 하므로 쓰지 않습니다. 한 모듈의 시험만 다시 돌리려면 `./gradlew :<모듈>:test --rerun`, 모듈과 그 모듈에 기대는 모듈을 함께 확인하려면 `./gradlew :<모듈>:buildDependents`를 씁니다.
- **동시 실행:** 위 폴더는 작업 사본 하나에 속하므로, 두 작업 사본(예: `git worktree` 폴더 둘)의 시험을 동시에 돌릴 수 있습니다. Gradle 캐시는 파일 잠금을 쓰며 여러 빌드가 함께 써도 되므로 Gradle 사용자 홈은 나눌 필요가 없습니다. 컴퓨터의 프로세서와 메모리가 허용하는 수보다 많은 전체 실행을 한꺼번에 돌리지 않습니다. 저장소의 연결 대기 시간이 1초라서, 빌드가 더 많이 프로세서를 다투면 데이터베이스 연결을 열거나 서버를 띄우는 시험이 결함이 아니라 시간 때문에 실패합니다. 실행을 시작하기 전에 가용 메모리를 확인합니다.
- **시험 DB:** 업무 스키마가 필요한 시험(`sennard-store-pg`, `sennard-server`, `sennard-personal`에서 `business-db` 태그가 붙은 클래스와 메서드)은 `test`에 들어 있지 않습니다. 각 모듈의 `businessDbTest` 작업에 들어 있으며, 지정한 PostgreSQL 데이터베이스 하나에서 돕니다(시험은 PostgreSQL에서만 돕니다). 제품에서와 같이 시험에서도 JDBC 드라이버는 사용자의 것입니다. 빌드가 PostgreSQL 드라이버(구성 `businessDbDrivers`)를 `build/business-db-drivers`에 복사하며 그 폴더가 시험의 드라이버 경로입니다. 다른 jar나 폴더는 `-Psennard.test.businessDb.driverPath=<jar 또는 폴더>`로 지정합니다. 돌리는 명령은 `./gradlew businessDbTest -Psennard.test.businessDb.url=<jdbc url> -Psennard.test.businessDb.user=<계정> -Psennard.test.businessDb.passwordFile=<파일>`입니다. 빌드에는 기본 데이터베이스가 없습니다. 이 작업을 요청했는데 셋 중 하나라도 주지 않으면 아무것도 시작하기 전에 실패하며, 시험은 건너뛰지 않습니다. 계정은 `sennard` 스키마를 소유하는 계정이고, 제품도 한 계정으로 접속하므로 시험도 한 계정을 씁니다. 시험 코드가 비밀번호 파일의 첫 줄에서 비밀번호를 읽어 JDBC 속성으로만 드라이버에 넘기며, 출력·로그·명령줄에 나오지 않습니다. 시험 클래스는 시작할 때마다 `sennard` 스키마를 지우고 마이그레이션을 다시 적용하며, 데이터베이스의 그 밖의 것은 건드리지 않습니다. 이 데이터베이스는 자원 하나이므로 빌드가 한 번에 한 작업만 쓰게 하고(Gradle 공유 빌드 서비스) 시험 JVM도 하나만 띄웁니다. 한 데이터베이스에는 빌드를 한 번에 하나만 돌리십시오. **CI는 이 시험을 돌리지 않습니다.** 워크플로에는 데이터베이스가 없고 `businessDbTest`를 요청하지 않으며, `test`와 `build`는 태그가 붙은 시험을 뺍니다.
- **결과:** `java/<모듈>/build/test-results/test/*.xml`과 `java/<모듈>/build/reports/tests/test/`의 HTML 보고서.

## 제3자 라이선스

Sennard는 [ADDITIONAL-TERMS.ko.md](ADDITIONAL-TERMS.ko.md)의 추가 조건을 붙인 AGPL-3.0-or-later로 배포합니다. 발행 jar에는 `sennard-personal`의 클래스패스(`runtimeClasspath`: `sennard-hook-client`를 포함한 프로젝트 모듈과 그 모듈이 쓰는 라이브러리)가 들어갑니다. 여기에 들어가는 것을 두 검사가 지키며, 둘 다 `java/`에서 `check`에 묶여 있어 `build`에서도 실행됩니다.

- `./gradlew :sennard-personal:checkLicense`는 그 클래스패스의 모든 라이브러리 라이선스를 POM(부모 POM 포함)에서 읽어 이름을 정규화하고, 라이브러리의 라이선스 중 [`java/config/license/allowed-licenses.json`](java/config/license/allowed-licenses.json)에 있는 것이 하나도 없으면 실패합니다. 라이선스가 없는 라이브러리도 허용되지 않습니다. 실패 메시지는 라이브러리마다 발견된 라이선스 문자열을 나열합니다. AGPL-3.0-or-later와 함께 쓸 수 있는 라이선스를 허용하려면 정규화된 이름을 허용목록에 더하고, 라이브러리 하나만 허용하려면 `moduleName`(과 `moduleVersion`)을 넣은 항목을 더합니다. 플러그인이 알아보지 못하는 표기는 [`java/config/license/license-normalizer-bundle.json`](java/config/license/license-normalizer-bundle.json)의 규칙(`transformationRules`, 형식은 플러그인의 [기본 묶음](https://github.com/jk1/Gradle-License-Report/blob/master/src/main/resources/default-license-normalizer-bundle.json)과 같습니다)으로 알려진 라이선스에 연결합니다. AGPL-3.0-or-later와 맞지 않는 라이선스(예: 단독 GPL-2.0-only, LGPL, EPL-1.0)는 목록에 없으며, 결정 없이 더하지 않습니다.
- `./gradlew :sennard-personal:generateThirdPartyNotices`는 같은 보고서로 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 다시 쓰고, `./gradlew :sennard-personal:checkThirdPartyNotices`는 커밋된 파일이 최신이 아니면 실패합니다. 의존성이 바뀌면 생성 작업을 돌려 변경과 함께 파일을 커밋합니다. 발행 jar는 이 파일을 `META-INF/` 아래에 담습니다.

보고서는 [Gradle-License-Report](https://github.com/jk1/Gradle-License-Report) 플러그인(Apache-2.0, 버전은 `java/settings.gradle.kts`)이 만듭니다. 이 플러그인의 작업은 설정 캐시와 호환되지 않으므로, 이 작업을 돌리는 빌드는 그 캐시 없이 돕니다.

## pull request

프로젝트 밖에서 보내는 pull request는 받지 않습니다. 관리자 자신의 pull request에는 변경 목적, 영향을 받는 스킬과 MCP 경로, 계약 또는 manifest 변경 여부, 실행한 검증 명령과 결과, 시험한 호스트와 시험하지 못한 호스트를 적습니다.

## 스킬 추가와 provider

- 새 전문 스킬은 저장소 안에서 직접 만들고 `:sennard-repo-tools:updateSourceLock`으로 잠금을 갱신합니다. [스킬 추가](docs/contributing/development.ko.md#스킬-추가)
- 스킬 디렉터리는 `kebab-case`로 짓고 frontmatter `name`과 맞춥니다. 적용 조건·제외 조건·입력·출력·실패 처리를 명시합니다. 오케스트레이터에는 전문 판단을 넣지 말고 필요한 연결 규칙만 추가합니다.
- 추가한 스킬은 `skills/registry.json`에 capability, phase, 선행조건, 필수·생성 산출물과 risk gate를 등록합니다.
- 기존 capability와 겹치는 provider를 추가할 때는 서로 다른 priority와 비어 있지 않은 `selectionCriteria`를 기록합니다. MCP는 priority가 큰 provider를 우선하며 `selectionCriteria` 자연어를 실행 중에 해석하지 않습니다. 요청 조건에 따라 자동으로 다른 provider를 골라야 한다면 각각 구체적인 capability를 선언합니다.
- 스킬을 추가·삭제하거나 이름, 버전, 역할을 바꾸면 같은 변경에서 `README.md`와 `README.ko.md`의 스킬 표도 고칩니다.

## 판 번호와 발행

호환 가능한 스킬 추가는 플러그인 minor, 기존 동작의 수정은 patch, 계약·스킬 이름·권한 범위를 깨는 변경은 major로 분류합니다. 전문 스킬 자체의 버전과 플러그인 버전은 따로 유지합니다. 릴리스 노트 위치와 발행 절차는 [발행](docs/contributing/release.ko.md)에 있습니다.

## 보안

취약점은 공개 이슈가 아니라 [보안 정책](SECURITY.ko.md)의 비공개 신고 경로로 알립니다.
