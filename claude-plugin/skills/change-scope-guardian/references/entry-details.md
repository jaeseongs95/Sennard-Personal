## 절차

1. 대상 run(`workflowId`)과 작업 영역, 적용 지침을 확인한다. run에 변경 기록이 없으면 먼저 `submit_change_plan`으로 계획을 낸다. 계획 단계(기준선 전)의 run은 비교할 수 없다.
2. 첫 변경 전이면 `run_skill_check`의 `change-scope-guardian`/`capture-baseline` 명령으로 `WorkspaceBaseline.v1`을 받고 `manifestSha256`을 baseline artifact의 신뢰 경계에 동결한다. baseline은 run의 기록에서 읽을 뿐이며, 작업 중 범위를 갱신해 task contract를 새로 만들 때도 기록은 작업 시작 전 상태 그대로다. 원래 baseline과 동결 digest를 capture 요청의 `baseline`·`baselineArtifactDigest`로 넘겨 새 task envelope에 다시 결속한다.
3. 검증 시에는 `workflowId`, `workspaceRoot`, task envelope, baseline과 동결한 `baselineArtifactDigest`를 `compare` 명령의 `input`으로 전달한다.
4. `excluded`, `unplanned`, `ownership-unknown`과 `unreadable:<경로>`를 근거 경로와 함께 보고한다.
5. `PASS`, `NEEDS_REVIEW`, `BLOCKED`, `INCONCLUSIVE` 중 하나만 반환한다.

경로 판정 규칙은 [references/path-policy.md](path-policy.md), verdict 조건은 [references/verdict-rules.md](verdict-rules.md)를 읽는다.
