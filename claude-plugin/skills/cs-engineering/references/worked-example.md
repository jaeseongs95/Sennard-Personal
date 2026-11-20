# 작업 큐의 실행 가능한 예시

`assets/examples/claim-queue/`에는 기준 출처, 요청, 제약, task, 정책, binding,
후보 manifest, 리뷰와 실제 로컬 참조 테스트 결과가 포함되어 있다.
예제 후보는 이 패키지가 새로 만든 Java reference queue(`candidate/ClaimQueue.java`,
JDBC와 ANSI SQL)다. Sennard 제품 큐의 복사나 현재 Sennard에서 발견된 결함이 아니다.
목적은 제약을 코드와 근거에 결속하는 흐름을 보여주는 것이다.

```text
run_skill_check {"skill":"cs-engineering","command":"check-bundle","arguments":{"root":"<SKILL_DIR>/assets/examples/claim-queue","binding":"binding.json","task":"task.json","policy":"policy.json","review":"review.json","candidate":"candidate.json"}}
```

예제는 두 조건을 동결한다. 첫째, 같은 소유권 세대의 원자 claim 성공자는 최대 하나다.
둘째, 재할당되거나 만료된 token은 DB의 결과를 확정하지 못한다. 연결이 각각 독립인
동시 claim 경합과 오래된 worker의 완료 쓰기로 검증한다. 근거 파일은 실행한 테스트의
요약이며 코드 검토와 원시 로그의 의미 해석을 대체하지 않는다.

후보는 조회 뒤 조건부 UPDATE(소유 조건·generation·lease 비교)와 갱신 행 수 검사를 사용한다.
비교할 시간을 호출자가 주입한다. 실제 운영에서는 시간의 신뢰·단위·프로세스 간
비교 가능성을 별도로 설계해야 한다. 이 예제는 외부 API 부작용의 exactly-once,
멀티호스트 공유 파일시스템, 네트워크 파티션, 운영 처리량의 보장을 주장하지 않는다.

## 시험 재실행

핵심 규칙 카드 20개의 `validationScope`는 Gradle 시험의 클래스와 메서드를 가리킨다.
DB가 필요 없는 사례는 `sennard-server`의 `CsReferenceMechanismsTest`, DB가 필요한 사례는
`sennard-store-pg`의 `CsQueueReferenceTest`다. 후자는 시험용 PostgreSQL 데이터베이스가
필요하고 `business-db` 태그를 쓴다. 그 데이터베이스는 `-Psennard.test.businessDb.url`, `-Psennard.test.businessDb.user`,
`-Psennard.test.businessDb.passwordFile`로 지정한다.

```text
cd java
./gradlew :sennard-server:test --tests '*CsReferenceMechanismsTest'
./gradlew :sennard-store-pg:test --tests '*CsQueueReferenceTest'
```

기본 실행은 예제 근거를 덮어쓰지 않는다. 근거를 새로 수집하고 예제를 다시 동결하는
유지보수 작업에서는 근거 파일 경로를 `-Psennard.cs.evidence.out`으로 준다.

```text
./gradlew :sennard-store-pg:test --tests '*CsQueueReferenceTest' -Psennard.cs.evidence.out=<저장소>/skills/cs-engineering/assets/examples/claim-queue/evidence/queue-tests.json
./gradlew :sennard-repo-tools:updateCsEngineeringLock
./gradlew :sennard-repo-tools:updateClassificationDigests :sennard-repo-tools:updateSourceLock :sennard-repo-tools:claudePlugin
```

`updateCsEngineeringLock`은 규칙 카드·지식 잠금·예제의 digest(원시 파일 digest와
canonical JSON digest)를 파일에서 다시 계산한다. 그 뒤 패키지 digest가 바뀌므로 source-lock과
claude-plugin 사본을 다시 만들어 검증한다. 기존 운영 작업의 binding을 조용히 갱신하지 않는다.
