# acceptance-evidence-validator

고정된 구현 대상과 테스트·검사 결과를 수용 기준별로 연결해 `PASS`, `FAIL`, `BLOCKED`, `NEEDS_INPUT`을 판정하는 스킬입니다. 구현을 수정하거나 고위험 릴리스의 독립 감사를 대신하지 않습니다.

## 요구 사항

- Sennard 서버의 MCP 도구 `run_skill_check`

## 직접 실행

JSON 파일 경로나 JSON 값을 사용할 수 있습니다.

```text
run_skill_check {"skill":"acceptance-evidence-validator","command":"analyze","arguments":{"input":{"file":"/absolute/passing.json"}}}
```

출력은 `AcceptanceEvidenceReport.v1` JSON입니다. 수용 기준의 ID(`[AC-001]` 같은 라벨)는 `task-contract`만 정하며, 이 스킬은 근거를 기준 원문에 결속하는 키로 `AC-`와 원문 UTF-8 SHA-256 앞 12 hex를 씁니다. 현재 `target.digest`와 일치하는 검증된 evidence만 판정에 사용됩니다. verification command에는 고유 ID와 영향을 받는 기준 ID를 함께 기록하며, stale 결과와 0이 아닌 종료 코드를 각각 증거 부족과 반증으로 반영합니다.

기존 v1 위치 ID(`AC-001`) 입력도 읽지만, 문장 결속 없는 근거를 현재 배열에 자동으로 붙이지 않고 `BLOCKED`로 남깁니다. 동결된 원보고서의 정확한 ID↔문장 표를 optional `criterionBindings`로 옮기면 기존 근거를 원문 다이제스트 키로 이관할 수 있습니다. 보고서도 재생성하고 새 요청 artifact digest를 동결해야 합니다. [이관 규칙](references/evidence-protocol.md)을 따릅니다.

`not-applicable`은 임의 문자열로 선언할 수 없습니다. 현재 대상에서 검증된 authority evidence가 해당 기준과 `TaskEnvelope`의 실제 제외 범위 또는 금지 행동을 함께 가리켜야 합니다. authority evidence digest는 locator의 값뿐 아니라 evidence ID, 기준 ID, 대상 digest, 방향과 관측 결과도 함께 고정합니다.

기존 보고서를 다시 검사할 때는 다음 명령을 사용합니다.

```text
run_skill_check {"skill":"acceptance-evidence-validator","command":"validate-report","arguments":{"request":{"file":"/absolute/passing.json"},"requestArtifact":{"file":"/absolute/request-artifact.json"},"report":{"file":"/absolute/report.json"}}}
```

`request-artifact.json`에는 외부에서 동결한 요청 artifact의 ID, 입력 schema ID, locator와 canonical SHA-256을 기록합니다. report만 제출하거나 request와 digest가 일치하지 않으면 검증이 실패합니다.

## 검증

suite checkout의 `java/`에서 실행합니다.

```bash
./gradlew :sennard-personal:test :sennard-repo-tools:test
```

## 통합

`integration/skill-descriptor.json`은 선택 어댑터입니다. 이 파일을 제거해도 직접 호출 기능은 그대로 동작합니다. Sennard는 clean tag를 편입할 때 descriptor와 schema checksum을 검증해야 합니다.
