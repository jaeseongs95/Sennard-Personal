---
name: independent-audit-gate
description: "Before and after executing or releasing a change that mutation-risk-preflight classifies as high-risk, an auditor separate from the author checks the final change and verification evidence directly and judges whether it can be completed. Not for simple research, low-risk fixes, general code review or design talk without implementation."
license: AGPL-3.0-or-later
metadata:
  version: "1.0.0"
---

# Independent Audit Gate

고위험 변경을 완료로 선언하기 전에 독립 감사와 증거 확인을 강제한다. 변경을 만든 주체와 다른 감사자가 최종 대상을 직접 검사하고 `PASS`, `FAIL`, `BLOCKED` 중 하나로 판정하게 한다.

감사는 배타적 적대적 검수로 한다. 감사자는 작성자의 보고와 판단에 기대지 않고, 대상이 맞다는 생각을 버린 채 결함을 찾는다.

## 적용 범위

다음 작업을 실행, 병합, 릴리스 또는 완료하려 할 때 사용한다.

- `mutation-risk-preflight`의 고위험 분류에 해당하는 변경. 분류와 승인 조건은 그 스킬의 목록에 따르며 이 스킬은 목록을 두지 않는다.
- 사용자가 독립 감사나 완료 게이트를 명시적으로 요청한 변경

단순 조사, 저위험 문서 수정, 같은 디스크 안 이동처럼 되돌릴 수 있는 운영 작업, 일반 코드 리뷰, 구현 대상이 없는 설계 토론에는 이 스킬을 사용하지 않는다. 복잡한 선택지를 여러 관점에서 숙고하는 일은 별도의 deliberation workflow에 맡기고, 선택한 방안을 실제 고위험 변경으로 실행할 때 이 게이트를 적용한다.

<!-- optimization-navigation:start condition="the skill is activated for the request" reference="references/entry-details.md" do-not-load-otherwise="true" -->
- If the skill is activated for the request, read [entry details](references/entry-details.md) before producing any result or taking any action; otherwise do not read it.
<!-- optimization-navigation:end -->
## 출력

사용자에게 다음 순서로 필요한 사실만 제시한다.

1. `Audit Target` — 단계와 최종 대상 식별자
2. `Independence` — 작업자와 감사자, fresh context, 재위임 여부
3. `Evidence Checked` — 직접 확인한 파일, 변경분, 명령, 시험, 로그와 배포 상태
4. `Findings` — ID, `blocking | non_blocking`, 증거, 영향, 상태
5. `Remediation/Re-audit` — 수정과 재감사 범위, stale 여부
6. `Gate` — `PASS | FAIL | BLOCKED`와 판정 근거
7. `Limitations` — 미관측 항목과 남은 불확실성

내부 프롬프트, 숨은 chain-of-thought 또는 확인하지 않은 사실을 출력하지 않는다.
