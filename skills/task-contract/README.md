# task-contract

사용자 요청과 적용 지침을 `TaskEnvelope.v1`으로 정리하는 독립 스킬입니다. 목표, 포함·제외 범위, 수용 기준, coarse work unit, 위험도와 권한의 출처를 함께 남깁니다. 계약을 작성하거나 검토할 뿐 구현, 전문 스킬 선택, 실행 순서와 최종 완료 판정은 맡지 않습니다.

## 요구 환경

- Sennard 서버의 MCP 도구 `run_skill_check`

## 직접 사용

Codex에서는 `$task-contract`를 호출하고 원 요청과 적용 지침을 제공합니다. 스킬은 `TaskContractReport.v1`과 `AcceptanceEvidencePlan.v1`을 작성한 뒤 로컬 validator로 구조와 불변조건을 확인합니다.

작성된 `{ schemaVersion, request, report }` JSON은 `input` 인자에 JSON 값 또는 `{"file":"<절대 경로>"}`로 넘겨 직접 검증할 수 있습니다.

```text
run_skill_check {"skill":"task-contract","command":"validate","arguments":{"input":{"file":"/absolute/task-contract.json"}}}
```

validator는 자연어 요청을 자동 해석하지 않습니다. `TaskEnvelope.v1` schema, work unit dependency, Windows·POSIX 경로 범위 충돌, repo-relative POSIX write target, 수용 기준과 증거 계획의 일대일 대응, 핵심 provenance를 검사합니다. 각 권한 action은 입력의 authority evidence와 정확히 결속되어야 하며, 프로젝트 지침만으로 허용 권한을 늘리면 거부합니다. suite 패키지나 MCP를 import하지 않습니다.

## 검증

suite checkout의 `java/`에서 실행합니다.

```bash
./gradlew :sennard-personal:test :sennard-repo-tools:test
python C:/path/to/skill-creator/scripts/quick_validate.py .
```

테스트는 읽기 전용 요청, 고위험 승인 경계, dependency cycle, 범위·권한 충돌, 증거 누락과 Windows 경로 입력을 검사합니다. CLI 실행 전후 fixture가 바뀌지 않는지도 확인합니다.

## Sennard 편입

`integration/skill-descriptor.json`은 `task-contract-definition` capability를 bootstrap provider로 선언합니다. 일반적인 계약 작성은 `plan_workflow` 전에 끝나야 합니다. 이미 유효한 envelope의 검토를 사용자가 명시한 경우만 workflow stage로 다룰 수 있습니다.

독립 실행은 MCP나 suite 설치를 요구하지 않습니다. suite에는 검증을 마친 clean tag를 import해야 합니다.

## 라이선스

[AGPL-3.0-or-later](../../LICENSE)를 적용합니다.
