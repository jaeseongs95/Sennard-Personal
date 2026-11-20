---
name: mutation-risk-preflight
description: Use right before running a hard-to-reverse rm, permanent delete or overwrite, deploy or publish, production DB migration, permission, payment or global setting change, or another irreversible command. Checks exact target, approval, impact and recovery conditions read-only. Judge heaviness by scale, reversibility, real data, shared resources and public-state impact, not the action's name. Same-disk moves and renames, reversible local edits and checks, and test or work-branch pushes are just done, without this skill; not for performing the change or post-change audit.
license: AGPL-3.0-or-later
metadata:
  version: "1.0.3"
---

# Mutation Risk Preflight

실제 상태 변경 직전에 계획된 행동과 대상을 고정하고, 현재 권한·승인·복구 근거로 시작 가능 여부를 판정한다. 이 스킬의 `READY`는 새 권한이나 승인을 만들지 않으며 실제 변경을 수행하지 않는다.

- 같은 대상·행동·환경에 대한 유효한 사용자 승인이 이미 있으면 그 근거를 보존한다. 형식적인 승인 문구를 다시 요구하거나 요청한 에이전트의 동의를 사용자 승인으로 바꾸지 않는다.
- 백업 존재, 복원 시험, dry-run과 실제 변경 결과를 구분한다. 미검증 복구 계획을 문구나 추가 승인만으로 검증된 계획으로 바꾸지 않는다.
- `READY` 뒤에도 실행 직전의 실제 대상과 효과를 다시 대조한다. 다른 ref·계정·환경에 대한 receipt를 재사용하지 않는다.
- 사용자의 현재 상태(클립보드, 열린 문서, 설정 값 등)를 덮어써야 하는데 백업할 수 없으면, 백업 도구나 우회 시험을 만들지 말고 무엇을 잃는지 적어 덮어써도 되는지 사용자에게 바로 묻는다. 사용자가 그 손실을 받아들이면 복구 불가 수용(`irreversibilityAccepted`)으로 진행한다.

기존 승인 부족과 복구 설계·근거 부족을 구분한다. 이미 승인된 공개의 일반적인 복제본 회수 한계를 새 승인 조건으로 추가하지 않는다. 신규 비공개 외부 준비는 검토된 효과 제한과 중단·보존 계약으로 점검할 수 있으며, 실제 공개·기존 자원 변경까지 그 경로로 허용하지 않는다. `scoped-resources-only`는 엄격한 기존 의미를 유지한다. 공급자 표준 이벤트가 기존 통합에 전달될 수 있으면 별도 `scoped-resources-plus-platform-events` 계약을 적용하고, 수신자 전체 목록 미관측은 `not-observable`로 기록한다. 통합·수신자·권한 변경, 별도 dispatch, 알려진 고위험 후속 효과, 승인 조건 충돌과 필수 대상 조회 실패를 허용하지 않는다. 구체 조건과 근거의 검증 범위는 아래 복구 요건을 따른다.

## 고위험 분류

「이 변경이 고위험인가」는 이 목록으로만 판정한다. 다른 스킬은 목록을 복사하지 않고 이 분류를 따른다. 행동 이름이 아니라 실패 영향, 규모, 되돌릴 수 있는지, 실데이터·공유 자원·외부 공개에 닿는지, 실제 권한 경계로 판단한다.

- 백업·휴지통 없는 영구 삭제나 덮어쓰기, 운영 데이터 대량 변경, 개인정보·백업·복원을 포함한 운영 데이터 정합성 영향
- 운영 데이터나 schema·제약 변경, 데이터 변환, migration(rollback 불명확·호환성 단절 포함)
- 배포, 공개 게시, tag/release, 공유 정본 반영(예: 기본 브랜치 병합), 운영 인프라·가용성 변경
- 인증·인가, 권한 확대, 비밀정보, 암호화, 격리, 외부 노출
- 결제, 과금, 환불, 가격, 지급, 거래 실행
- 설치·전역 설정, 보안 정책, 다른 사용자·서비스에 영향을 주는 공용 런타임·네트워크 변경
- 그 밖에 복구가 어렵거나 다른 사용자·외부 서비스에 영향을 주는 변경

이름(예: CI/CD, 프로젝트 전체 설정, 비프로덕션)만으로 고위험으로 분류하지 않는다. 비프로덕션에서도 민감 데이터 노출, 권한 확대, 금전·운영 영향이나 복구하기 어려운 데이터 변경이 있으면 그 영향 범위는 고위험이다. 로컬 여부나 외부 연결 유무만으로 높이거나 낮추지 않고, 불확실하다는 이유만으로 올리지 않는다.

## 적용 조건

위 분류의 변경 중 되돌리기 어려운 것을 실행하기 직전에 적용한다. 사용자가 명시적으로 사전 위험 점검을 요청한 경우에도 적용한다.

읽기 전용 조사, 같은 디스크 안 이동·이름 변경, 원본이나 백업이 남는 복사, git이나 백업으로 되돌릴 수 있는 로컬 편집, 변경 완료 후 감사, 구체적인 대상이 없는 일반 위험 설명에는 적용하지 않는다. 이런 일은 점검 없이 바로 하고 결과를 직접 확인한다.

통상 읽기 전용 진단, 시험, 되돌릴 수 있는 작업 사본 저장과 자동 검사 실행(예: 작업 브랜치 commit/push, CI)은 기존 사용자 권한 안에서 진행하며 별도 PRE나 추가 사용자 승인을 요구하지 않는다. 자동 실행(예: CI)이 실제 배포·설치·권한 변경을 수행하면 그 변경에만 이 분류와 승인·독립 감사 조건을 적용한다. 공유 정본 반영(예: 기본 브랜치 병합), tag/release, 설치·배포, 영구 삭제, 권한 확대와 운영 데이터 변경의 사용자 승인 조건은 유지한다.

<!-- optimization-navigation:start condition="the skill is activated for the request" reference="references/entry-details.md" do-not-load-otherwise="true" -->
- If the skill is activated for the request, read [entry details](references/entry-details.md) before producing any result or taking any action; otherwise do not read it.
<!-- optimization-navigation:end -->
## 판정

- `READY`: 이 스킬이 확인할 사전조건을 충족했다. 실행 권한을 새로 부여하지 않는다.
- `NEEDS_INPUT`: 대상 상태나 필수 근거를 더 확인해야 한다.
- `NEEDS_APPROVAL`: 별도 승인이 필요하거나 현재 승인 범위가 맞지 않는다. 우회 작업을 만들지 말고 대상·영향·실행할 명령·복구 방법을 담아 사용자에게 바로 묻는다.
- `NEEDS_REDESIGN`: 현재 복구 계획이나 영향 범위로는 진행할 수 없다.
- `BLOCKED`: 대상이 안전하게 특정되지 않았거나 입력 자체가 유효하지 않다.

변경 후 완료·병합·릴리스 여부는 고정된 최종 결과를 대상으로 독립 감사자가 별도로 판정한다.

## 직접 호출

직접 호출에는 `MutationIntent.v1` 작성이나 평가·receipt 스크립트 실행을 요구하지 않는다. 대상·승인·복구 근거를 대조한 판정과 그 근거를 응답에 적으면 된다. 기계용 intent·보고서·receipt는 Sennard workflow 단계로 연결됐거나 호출자가 receipt를 요구할 때만 만든다. 이 스킬은 호스트 훅의 실행 조건이 아니며, 형식 산출물을 맞추느라 승인된 작업을 미루지 않는다.
