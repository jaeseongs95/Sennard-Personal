## 입력

`AcceptanceEvidenceInput.v1`을 받는다. 최소 입력은 `TaskEnvelope.v1`, 고정 대상 식별자와 digest, 기준에 연결된 evidence다. evidence에는 locator, 검증 여부, 대상 digest, `supports | refutes` 방향이 있어야 한다.

판정 전에 [references/evidence-protocol.md](evidence-protocol.md)를 읽는다. 기계적인 일대일 대응과 stale 검사는 다음 명령으로 재현할 수 있다.

```text
run_skill_check {"skill":"acceptance-evidence-validator","command":"analyze","arguments":{"input":{"file":"/absolute/request.json"}}}
run_skill_check {"skill":"acceptance-evidence-validator","command":"validate-report","arguments":{"request":{"file":"/absolute/request.json"},"requestArtifact":{"file":"/absolute/request-artifact.json"},"report":{"file":"/absolute/report.json"}}}
```

문서 인자는 JSON 값을 그대로 넣거나 `{"file":"<절대 경로>"}`로 지정하며, 결과는 공통 봉투(`schemaVersion`, `ok`, `data`, `error`)의 `data`에 `{exitCode, value}`로 받고, 거절된 입력은 `ok`가 false인 `error`로 받는다. 시험을 대신 실행하거나 파일을 수정하지 않는다.

## 절차

1. 규칙 카드는 [verification](verification.md)에서 필요한 것만 읽는다. 결과물(예: commit, diff, artifact) 또는 관측 상태의 식별자와 digest를 고정한다.
2. 기준의 ID(`[AC-001]` 같은 라벨)는 `task-contract`만 정하며 이 스킬은 ID를 새로 매기지 않는다. 각 `acceptanceCriteria` 원문(라벨 포함) UTF-8의 SHA-256 앞 12 hex에 `AC-`를 붙인 원문 다이제스트 키로 근거를 기준에 결속한다. 정렬·trim으로 원문을 바꾸지 않는다. 이전 위치 라벨만 있는 근거는 동결된 원문↔라벨의 `criterionBindings`로 이관하며, 결속표가 없으면 충족으로 사용하지 않고 `BLOCKED` 한계를 남긴다. 상세 이관 규칙은 evidence protocol을 따른다.
3. 각 evidence가 한 개 이상의 유효한 기준 ID와 현재 대상 digest를 가리키는지 확인한다.
4. 검증되고 현재 대상에 맞는 반증이 있으면 `unsatisfied`, 지지 근거가 있으면 `satisfied`로 둔다.
5. 근거가 없거나 stale하면 `insufficient-evidence`로 둔다. `not-applicable`은 현재 대상에서 검증된 authority evidence가 해당 기준과 `TaskEnvelope.scope.excluded` 또는 `authorization.prohibitedActions`의 실제 항목을 함께 가리키고, 기준 ID와 대상 digest를 포함한 canonical evidence digest가 일치할 때만 허용한다.
6. 모든 기준이 `satisfied` 또는 정당한 `not-applicable`일 때만 `PASS`한다. 반증이 하나라도 있으면 `FAIL`, 남은 근거 부족이 있으면 `BLOCKED`다.
7. 기존 보고서는 원 요청과 외부에서 동결한 request artifact digest를 함께 받아 다시 검증한다. report만 전달하거나 request digest가 일치하지 않으면 통과시키지 않는다.

## 권한과 경계

- 요청된 검증만 실행하고 새 외부 작업, 배포, 승인 또는 권한을 만들지 않는다.
- 파일 존재나 시험 이름만으로 동작 충족을 추정하지 않는다.
- 다른 commit·artifact에서 얻은 결과를 현재 근거로 사용하지 않는다.
- 선언된 verification command가 현재 대상과 다르면 해당 기준을 완료하지 않고, 현재 대상에서 종료 코드가 0이 아니면 반증으로 처리한다.
- 수용 기준 밖의 보안·복구·운영 실패 모드를 독립 감사처럼 확장하지 않는다.
