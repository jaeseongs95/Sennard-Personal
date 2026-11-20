[English](testing-and-ci.md)

# CI와 테스트 서버의 역할

## 원칙

CI가 실패하면 코드 결함이어야 한다. 러너가 바빠서 떨어지는 시험은 CI에 두지 않는다. 실제 호스트와 모델을 쓰는 실측은 Linux·Windows 테스트 서버에서 한다.

## 시험 구성

모든 자동 시험은 Gradle과 JUnit으로 돈다.

- `./gradlew test`: 모듈별 단위·통합 시험. 저장소 계약 검사, 메시지 카탈로그 검사, 도메인 중립 문구 검사, 스킬 검사와 업무 데이터베이스가 필요 없는 저장소 시험이 여기에 있다. `business-db` 태그가 붙은 시험은 `test`에서 빠지며, 사용자가 지정한 데이터베이스에 대해 `businessDbTest`로 따로 돈다([기여 안내](../../CONTRIBUTING.ko.md)의 시험 실행 절 참조). `sennard-personal`의 `test`는 실제 Tomcat·JVM으로 도는 서버 시험과, 빌드가 만든 릴리스 jar(`releaseJar`와 그 풀린 형태)를 설치하고 실행하는 프로세스 시험을 포함한다. `./gradlew :sennard-personal:jarRun`은 그 jar를 사용자가 실행하듯 ASCII 밖의 이름을 가진 폴더에서 실행하며 `test`에 포함되지 않는다.

시험 JVM은 Gradle을 실행한 세션의 호스트 변수 없이, 모듈의 `build/isolated/<작업>/` 아래 홈·임시 폴더로 시작한다(루트 `java/build.gradle.kts`의 `isolateFromHost`). 그래서 개발 PC에서 셸 래퍼 없이 `./gradlew`만 쓰고, 서로 다른 작업 사본의 시험을 동시에 돌릴 수 있다. 시험 서버는 모델 실측에만 쓴다. 시작 조건과 JDK 25 선택은 [기여 안내](../../CONTRIBUTING.ko.md)의 「시험 실행」 절에 있다.

데이터 계약 벡터와 fixture는 `tests/<skill>/fixtures`, `skills/cs-engineering/assets/examples`에 파일로 두고 JUnit 매개변수화 시험이 읽는다.

## 실행 위치

| 위치 | 명령 | 범위 |
|---|---|---|
| GitHub Actions(ubuntu·windows, JDK 25 Temurin) | `./gradlew --no-daemon --console=plain test --fail-fast`(업무 DB 시험은 `test`에 들지 않고 `businessDbTest`로 따로 돈다. `--continue` 없음: 실패한 시험 작업은 첫 실패에서 멈추고 새 작업은 시작하지 않지만, 이미 병렬로 돌던 다른 모듈의 시험 작업은 끝까지 돈다. 한 OS 작업이 실패하면 다른 OS 작업은 취소된다) | `test`의 전체 자동 시험(`businessDbTest` 제외) |
| Linux·Windows 테스트 서버 | 실호스트 시험 | Claude·Codex·agy에 설치한 패키지의 실제 동작 |

발행 후보는 CI 전체 시험과 Claude·Codex·agy 실호스트 시험을 모두 통과해야 한다. 실측 시험은 CI에 넣지 않는다.

개인판 1.0.0은 GitHub Actions의 Ubuntu·Windows 자동 시험을 통과했습니다. 실제 호스트에서 남은 검증은 [1.0.0 릴리스 노트](../release-notes/release-notes-v1.0.0.md#알려진-한계)를 참고합니다.
