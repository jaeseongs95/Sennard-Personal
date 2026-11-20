# Evaluation Validity Auditor

동결된 스킬·모델 평가의 입력, 판정 방법과 결과 집계가 공정하고 재현 가능한지 읽기 전용으로 감사하는 독립 스킬입니다. 특정 평가 도메인이나 실행기에 연결되지 않으며 JSON/JSONL과 SHA-256 기반의 정규화된 증거만 처리합니다.

## 빠른 시작

Sennard 서버의 MCP 도구 `run_skill_check`를 사용합니다.

```text
run_skill_check {"skill":"evaluation-validity-auditor","command":"digest-request","arguments":{"input":{"file":"/absolute/request.json"}}}
run_skill_check {"skill":"evaluation-validity-auditor","command":"analyze","arguments":{"input":{"file":"/absolute/request.json"},"artifact-root":"/absolute/evidence"}}
run_skill_check {"skill":"evaluation-validity-auditor","command":"validate-report","arguments":{"request":{"file":"/absolute/request.json"},"request-artifact":{"file":"/absolute/request-artifact.json"},"report":{"file":"/absolute/report.json"},"artifact-root":"/absolute/evidence"}}
```

검사는 공통 봉투(`schemaVersion`, `ok`, `data`, `error`)의 `data`에 `{exitCode, value}`를 돌려주고 입력 artifact를 수정하거나 평가를 실행하지 않습니다. 정상적으로 만들어진 `FAIL`·`BLOCKED` 보고서는 `exitCode` 0, 보고서 검증 불일치는 1입니다. request·경로·인자 오류는 `exitCode` 없이 `ok`가 false인 `error`로 옵니다.

## 계약

- `EvaluationValidityRequest.v1`: 단계, 동결 대상, 역할, artifact와 예상 case/run/criterion을 결속합니다.
- `EvaluationCaseRecord.v1`: fixture manifest의 case inventory를 정의합니다.
- `EvaluationResultRecord.v1`: case/run/criterion별 결과와 독립 판정 근거를 정의합니다.
- `EvaluationAggregateClaim.v1`: 공개 집계 주장을 정의합니다.
- `EvaluationValidityReport.v1`: 검사 결과와 재계산 metric을 고정 코드와 digest로 기록합니다.
- `EvaluationValidityValidation.v1`: 동결 요청과 보고서를 다시 결속해 remapping을 탐지합니다.

`pre-execution PASS`는 평가 설계만 확인합니다. 품질 gate나 릴리스 근거로 재사용할 수 있는 결과는 `post-execution PASS`뿐입니다.
