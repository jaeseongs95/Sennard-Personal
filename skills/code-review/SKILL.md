---
name: code-review
description: "Find defects in a fixed change set (diff, patch, PR) or in existing code at a fixed revision, including a full inspection of named files: confirm review coverage and real failure paths, and separate evidenced defects, recommendations and questions. Read-only by default; does not replace architecture audit, fixes, deep security audit, final acceptance, independent audit or release approval."
license: AGPL-3.0-or-later
metadata:
  version: "0.1.0"
---

# Code Review

## 적용과 입력
변경분 리뷰나 고정한 시점의 기존 코드에서 결함을 찾는 검토(지정 파일·전수조사)가 요청되면 사용한다. 구현이나 전체 저장소 구조 감사만 요청된 작업에는 임의로 이 절차를 추가하지 않는다. 적용 지침, 요구·ADR, base/head 또는 snapshot, 변경 파일과 제외 범위를 읽는다.
기존 코드 검토는 commit이나 파일 digest로 시점을, 파일 목록으로 대상을 고정하고 [code-review](references/code-review.md)의 변경분 없는 대상 경계를 따른다. 아래 changedFiles·CLI 절차는 변경분이 있을 때만 쓴다.

## 실행
1. `engineering-review-request`로 후보와 changedFiles를 고정한다. [code-review](references/code-review.md)를 읽는다. diff가 잘리면 관련 파일을 추가로 읽거나 미검토로 남긴다.
2. 중요한 변경 경로를 호출자부터 부작용·오류 처리까지 추적한다. 제거된 guard는 이력 또는 현재 계약으로 필요성을 확인한다.
3. 결함마다 위치·위반 계약·도달 경로·영향·수정 방향을 적는다. 충분히 뒷받침된 결함과 미확인 가설을 구분한다. 여러 리뷰어의 반복 지적을 심각도 상승 근거로 삼지 않는다.
4. 모든 변경 파일을 REVIEWED 또는 NOT_REVIEWED로 기록한다. 스타일 권고는 프로젝트 기준을 우선하고 non-blocking으로 둔다. 요청 밖의 구조 변경을 요구하지 않는다.
5. 보안·공급망은 `software-security-auditor`, CS 불변조건은 설치된 `cs-engineering`, 책임 분리·교체 가능성·의존 방향·벤더 중립 같은 설계 경계는 `solid-design`, 테스트 보강은 `test-engineering`, 수정은 `minimal-implementation`에 인계한다. 스스로 세션을 생성하거나 중복 위임하지 않는다.
6. CLI로 report/request 결속·변경 범위·line 범위·후보를 검사한다. 최종 수용·독립성·릴리스 결정은 기존 담당에게 넘긴다.

## 결함 판단과 종결

- 허용된 읽기 범위 안에서 diff 밖의 관련 호출자·설정·공통 방어도 확인한다. 변경 줄만으로 결함이나 보호 효과를 판정하지 않는다. 추가로 읽은 파일과 변경 파일의 coverage를 혼동하지 않는다.
- 설계 계약도 결함 판단에 넣는다. 변경이 닿은 경계에서 독립된 변경 이유를 한 모듈에 묶었는지, 같은 계약의 구현을 바꿨을 때 소비자가 기대한 의미가 깨지는지, 정책 코드가 특정 벤더·호스트·DB·OS·SDK 세부에 직접 묶였는지, 객체가 지킬 불변식과 종류별 동작이 호출자 쪽 분기·정적 도우미로 흩어졌는지를 호출 경로로 판단한다. 사용자·프로젝트 지침이 객체지향·SOLID·벤더 중립을 요구하면 위반은 위치·계약·도달 경로·영향을 갖춘 결함으로 올리고, 그런 지침이 없으면 도달 경로가 입증된 것만 권고로 둔다. 점검표를 채우려고 변경 밖 코드를 훑거나 구조 개편을 요구하지 않으며, 깊은 설계 판단은 `solid-design`에 인계한다. 시험 통과는 이 판단을 대신하지 않는다.
- 수정 커밋·구현자 설명·새 테스트 통과만으로 기존 finding을 닫지 않는다. 같은 finding ID에 원래 실패 조건과 현재 후보의 처리 경로를 대조한 근거를 연결한다. 다른 원인의 실패는 별도 finding으로 유지한다.
- 요구를 어긴 동작에 맞춰 기대값·snapshot·검증 guard를 바꾼 변경인지 확인한다. 테스트 통과 여부와 요구 충족 여부를 분리하고, 정당한 요구 변경이라면 그 근거와 영향 범위를 확인한다.

## 출력과 실패
`engineering-review-report`에 coverage, findings, openQuestions, handoffs를 남긴다. CLI는 `engineering-review-result`를 계산하며 NO_BLOCKING_FINDINGS는 해당 검토 기록에서 차단 결함이 없다는 뜻이지 승인·무결함·독립 감사 PASS가 아니다. 파일이 없거나 후보가 달라지면 해당 근거로 결론을 내리지 않는다.

## CLI
```text
run_skill_check {"skill":"code-review","command":"check-review","arguments":{"root":"/absolute/artifacts","request":"request.json","report":"review.json"}}
```
원시 파일의 상대 경로는 artifact root 안에서만 사용한다. [공통 CLI](../test-engineering/references/cli.md)를 참조한다.
