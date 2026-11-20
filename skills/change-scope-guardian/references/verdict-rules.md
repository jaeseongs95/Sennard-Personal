# 판정 규칙

각 현재 변경은 다음 중 하나로 분류한다.

- `in-scope`: baseline 이후 생겼고 그 run의 계획 파일이거나 허용 경로 안에 있다.
- `excluded`: 명시적 제외 경로와 일치한다.
- `unplanned`: 제외되지는 않았지만 계획 파일과 포함 범위, write target 밖이다. 변경 기록에 남은 경로 중 작업 영역 밖의 것(절대 경로로 준 write target)도 계획 파일이 아니면 여기에 속한다. 변경 기록은 run의 write target 아래만 보므로 그 밖의 경로는 기록되지 않고 판정 대상이 아니다.
- `ownership-unknown`: baseline이 없어 변경을 이 작업의 것으로 확인할 수 없다.

보고서의 `currentDigest`는 보고서 `changes` 목록의 canonical digest다. 변경 기록에는 작업 영역 전체의 지문이 없으므로 현재 상태는 보고한 변경 목록으로 정한다.

전체 verdict는 우선순위대로 계산한다.

1. baseline 부재로 소유권을 판단할 수 없거나, 기록이 읽지 못한 경로(`unreadable:<경로>`)가 있으면 `INCONCLUSIVE`. 읽지 못한 것을 바뀌지 않았다고 하지 않는다.
2. `excluded`가 있으면 `BLOCKED`.
3. `unplanned`가 있으면 `NEEDS_REVIEW`. 확인된 범위 이탈이며 증거 부족으로 바꾸지 않는다.
4. 나머지는 `PASS`.

`NEEDS_REVIEW`는 정확한 경로를 검토해야 한다는 뜻이며 사용자 승인 요청을 뜻하지 않는다. 원 요청·goal·적용 지침에서 이미 허용한 필요한 변경이면 그 근거로 범위를 조정하고 task contract와 baseline을 새로 만든다. 새 baseline은 작업 시작 전 상태를 담은 run의 기록에서 다시 읽고, 원래 baseline과 동결 digest를 capture 요청의 `baseline`·`baselineArtifactDigest`로 넘겨 원래 기준 상태를 새 task contract에 다시 결속한다. 소유권을 확인할 수 없으면 추측해 덮어쓰지 않는다. 사용자 승인이 실제 필요한 고위험 분류(`mutation-risk-preflight`)의 변경이나 원 권한 밖 작업만 별도로 승인을 받는다. provider는 검토가 해결되기 전까지 `blocked`로 소비하며 `needs-approval`로 승격하지 않는다.

`PASS`는 run의 write target 아래에서 기록된 변경의 범위만 통과했다는 뜻이다. write target 밖의 변경은 기록되지 않아 이 검사가 보지 않으며, 그 변경이 없다는 근거로 쓰지 않는다. 에이전트가 write target 밖을 직접 쓰는 것은 편집 게이트가 막는 몫이고(게이트가 모든 쓰기를 잡지는 않는다), 빌드나 외부 프로그램이 write target 밖에서 바꾼 것은 어느 쪽에서도 확인되지 않는다. 요구사항 충족, 코드 품질, 보안과 릴리스 가능성을 보장하지 않으며 새 작업 권한을 부여하지 않는다.
