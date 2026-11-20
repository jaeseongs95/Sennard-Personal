---
name: change-scope-guardian
description: "Compare the run's recorded pre-work baseline with the changes recorded since to flag changes under the run's write targets that are outside the plan or in excluded paths; a change outside the write targets is not recorded, so it is not seen. Call only when work overlaps other work or before a default-branch merge or publication. Not for requirement fulfilment or code quality review."
---

# Change Scope Guardian

현재 변경이 합의된 작업 범위를 침범했는지, run의 변경 기록을 근거로 읽기 전용으로 확인한다.

- 검사를 통과시키려고 포함 경로를 작업 영역 전체로 넓히거나 변경 후 상태를 새 원본 baseline으로 캡처하지 않는다. 범위 조정은 원 요청의 근거와 작업 시작 전 상태를 보존한다.
- 경로가 허용됐다는 이유로 파일 전체 덮어쓰기·일괄 stage·다른 작업의 변경 포함을 정당화하지 않는다.
- `PASS`는 실제로 비교한 변경 기록의 범위 판정이다. 변경 기록이 읽지 못한 경로, 기록 범위 밖의 외부 자원, 따라가지 않은 symlink 대상까지 확인했다고 보고하지 않는다.

## 적용할 때

- 첫 변경 전에, 계획을 낸 run의 기준선(baseline)을 작업 시작 전 상태로 남겨야 할 때
- 구현 뒤 기록에 남은 변경이 `TaskEnvelope.v1`의 포함·제외 범위와 write target을 지키는지 확인할 때
- 같은 작업 영역을 여러 작업이 쓰고 있어 이번 run의 write target 아래에서 계획 밖 파일을 건드렸는지 판정할 때

단순 코드 품질 검토, 요구사항 충족 판정, 파일 복구에는 적용하지 않는다. baseline이 없으면 변경 소유자를 추측하지 않는다.

<!-- optimization-navigation:start condition="the skill is activated for the request" reference="references/entry-details.md" do-not-load-otherwise="true" -->
- If the skill is activated for the request, read [entry details](references/entry-details.md) before producing any result or taking any action; otherwise do not read it.
<!-- optimization-navigation:end -->
## 불변조건

- 파일을 되돌리거나 지우거나 쓰지 않고, 변경 기록도 바꾸지 않는다. 비교 전의 재검사(rescan)가 남기는 기록만 예외다.
- 원문 코드와 전체 diff를 baseline에 저장하지 않는다. baseline은 경로·크기·시각과 계획 파일의 checksum만 담는다.
- 삭제는 삭제 전 경로로 검사한다.
- symlink 대상을 따라가지 않는다. 다만 변경 기록의 root(write target) 자체가 링크이면 그것이 가리키는 실제 폴더를 읽고, root 아래의 링크만 따라가지 않는다.
- 변경 기록은 run의 write target 아래만 본다. 그 밖의 파일 변경은 기록되지 않으므로 `PASS`는 그런 변경이 없다는 뜻이 아니다. 에이전트가 write target 밖을 직접 쓰는 것은 편집 게이트가 막는 몫이고(게이트가 모든 쓰기를 잡지는 않는다), 빌드나 외부 프로그램이 write target 밖에서 바꾼 것은 이 검사가 보지 않는다.
- baseline과 요청의 workflow가 다르거나 baseline이 그 run의 기록과 다르면 비교하지 않는다.
- 변경 기록이 읽지 못한 경로가 있으면 바뀌지 않았다고 하지 않고 `unreadable:<경로>` finding과 `INCONCLUSIVE`로 보고한다.
- 입력 artifact 자체의 checksum만 다시 계산해 신뢰하지 않는다. baseline과 report는 오케스트레이터나 호출자가 별도로 동결한 artifact digest와 일치해야 한다.
- 범위 확대가 필요하면 기존 결과를 덮어쓰지 말고 task contract와 baseline을 새로 만든다.

직접 호출은 MCP를 요구하지 않는다. 오케스트레이션에서는 capture와 verify가 서로 다른 provider stage로 등록된다.
