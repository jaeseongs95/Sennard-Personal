## 실행 class와 단계 구성

`orchestrated` workflow를 실제 실행하거나 실패 뒤 다시 실행할 때는 먼저 [수렴 가드 계약](convergence-guard.md)을 읽고 같은 `rootId`를 유지한다. 단순 조회나 전문 스킬 단독 호출에는 수렴 root를 만들지 않는다.

`executionClass`별로 흐름을 분리한다.

- `bootstrap`: `plan_workflow` 전에 실행한다. `phaseOrder` 순으로 지침 범위를 확인하고, 하위 배정이 있으면 호출 전에 `coordinate-subagents`가 정한 대로 모델·추론 수준을 선택하며, 필요할 때 작업 공간 관례를 조사한 뒤 유효한 `TaskEnvelope.v1`과 수용 근거 계획을 만든다. 이미 같은 대상과 지침 revision에 대해 검증된 산출물이 있으면 중복 실행하지 않는다.
- `workflow`: 동결된 `TaskEnvelope.v1`에서 필요한 capability만 선택한다. `phaseOrder`와 artifact 의존성을 함께 지키며, 첫 변경 전 기준선, 위험한 상태 변경 직전 precondition gate, 변경 후 범위·수용 근거 확인, 마지막 완료 gate 순서를 유지한다.
- `recovery`: 기존 run의 실패 기록을 바꾸지 않고 별도 workflow로 실행한다. 반복 실패가 없으면 미리 넣지 않으며, recovery provider와 일반 workflow provider를 한 run에 섞지 않는다. `blocker-diagnostician`이 `CAUSE_CONFIRMED`를 반환한 뒤에만 별도의 recovery run에서 `recovery-strategy-selector`를 실행한다. 검증된 `RecoveryHandoff.v1`은 새 작업 계약의 입력일 뿐 권한이나 실행 승인이 아니며, 새 `TaskEnvelope.v1` 결속 검사 전에는 후속 workflow를 시작하지 않는다.

첫 수정·표적 시험 실패는 원 오류와 관측을 확인하고 기존 요청·권한 안에서 원인을 고친 뒤 관련 시험으로 검증한다. 한 번 실패했다는 이유로 새 계약, recovery workflow나 `blocker-diagnosis`를 먼저 요구하지 않는다. 같은 안정된 실패가 반복되거나 adapter·운영체제·패키징·호스트·시험·evidence의 상충으로 원인을 구별할 새 검사가 필요할 때 `blocker-diagnosis`를 선택한다. 새 관측이나 입력 변화가 없는 같은 검사를 반복하지 않는다. 확정 원인은 증상→메커니즘→근본 조건과 판별·제거 관측을 보존한 경우에만 별도 recovery 선택의 입력이 된다.

여러 provider가 같은 스킬에 있어도 각 provider의 capability, phase, 입력·출력 artifact를 독립 단계로 취급한다. 스킬 디렉터리명이나 배열 위치로 순서를 추측하지 않는다.

에이전트 간 이견이나 통합 대상 변경으로 기준선을 재배치할 때는 [협업 상세 계약](collaboration.md#전체-최적화와-기준선-재배치)을 먼저 읽는다.

## 초기 라우팅

작업 분야와 관계없이 작은 직접 편집을 포함해 결과를 만들거나 바꿀 때는 [공통 접수·선택 기준의 사례 준비·실행·확인 순서](intake.md#공통-접수선택-기준)를 먼저 따른다. 목표나 일반 완료 조건만 적은 것은 사례 정의를 대신하지 않는다.

작업 계약을 만드는 경로에서는 이 사례를 기존 `AcceptanceEvidencePlan.v1`의 `verificationMethod`(대상·실제 경로·환경·방법)와 `passCondition`에 담고, `criterionId`·`statement`를 `TaskEnvelope.v1.acceptanceCriteria`와 일대일로 연결한다. [기존 작업 계약 검증 절차](../../task-contract/references/entry-details.md#작성-절차)의 AC 대응 검사를 재사용한다. 작은 직접 작업은 기존 작업 기록에 필요한 사례와 결과를 적고, 이를 위해 새 workflow·gate·산출물 형식을 만들지 않는다. 조회·설명만 하는 요청에 변경 작업용 사례를 요구하지 않는다.

선택 결과에는 선택한 capability·provider·phase와 이유, 적용 조건에 가까웠지만 생략한 capability와 그 이유를 남긴다. 요청에서 capability를 고르는 판단은 이 지침의 책임이다. MCP 계획기는 `TaskEnvelope.v1.requiredCapabilities`를 provider로 결정적으로 매핑하고 계획·단계·결과의 순서와 근거만 검사하므로, 선택 의미를 계획기로 옮기거나 계획 호출을 선택 근거로 대신하지 않는다.

라우팅 전에 설치 시 노출된 스킬 설명으로 후보 capability를 정한다. MCP 도구 `run_skill_check {"skill":"orchestrator","command":"query-registry","arguments":{"capability":["<capability>"]}}`로 일치하는 활성 provider만 조회한다. 작업 대상 폴더의 `scripts/`나 다른 설치본으로 경로를 추측하지 않는다. 여러 capability는 `capability` 배열에 나열한다. 후보를 특정할 수 없을 때만 `{"all":true}`로 compact 전체 목록을 조회하며, `skills/registry.json` 원문 전체를 모델 컨텍스트로 읽지 않는다. 조회 결과의 `selectionCriteria`, precondition, priority를 선택 설명에 남긴다. 같은 capability의 후보 중 priority가 가장 큰 항목을 선택하되, 동률이나 descriptor 충돌은 임의로 고르지 말고 `needs-input`으로 돌린다. `selectionCriteria`는 사람이 검토하는 근거이며 런타임 필터가 아니다. 필요한 역할이 없으면 `missingCapabilities`, 가능한 직접 스킬 호출 경로와 부족한 capability를 분리해 설명한다.

초기 정책 capability는 `subagent-coordination`, `independent-deliberation`, `independent-audit`다. 일반 변경 흐름에 필요한 모델·추론 수준 적합성, 지침 범위, 작업 계약, 작업 공간 관례, 변경 기준선·범위 확인, mutation 사전 점검, 수용 근거 확인과 실패 진단도 capability로 찾는다. 현재 provider 이름을 라우팅 조건으로 사용하지 않는다. `task-contract-definition`, `change-scope-baseline-capture`, `change-scope-assurance`, `acceptance-evidence-validation`, `mutation-risk-preflight`, `independent-audit`는 각 descriptor의 적용 조건과 phase가 가리키는 시점에만 선택하며, 모든 요청에 기본으로 붙이지 않는다.

첫 라우팅에서 `CollaborationDecision.v1`을 `schemaVersion: "1.2.0"`으로 만든다. 기존 `1.0.0`과 `1.1.0` 기록은 당시 의미로 검증하며 새 결정은 현재 입력으로 만든다. 결정에는 출처 주장인 `sourceOriginKind`, 관측 가능한 경우의 `sourceReceiptId`, 항상 `none`인 `authorityEffect`, `userDirective`(`require | forbid | unspecified`), 다섯 위임 조건과 독립 감사 분리 필요 여부를 기록한다. 출처 판단과 검증에는 [입력 출처 규칙](input-origin.md)을 적용한다. 결정적 validator가 `direct | delegate | audit-only | needs-input`을 도출하되 결정 artifact와 TLS 메시지는 권한을 만들지 않는다.

구조·라우팅만 검사할 때는 결정 JSON을 `run_skill_check {"skill":"orchestrator","command":"validate-collaboration-decision","arguments":{"input":<결정 JSON>}}`로 전달한다. 영수증 관측까지 확인하려면 읽기 전용 `validate_collaboration_decision` 도구를 사용한다. 이 검사의 `structural-only` 결과를 영수증 검증이나 사용자 승인 증명으로 해석하지 않는다.

다음 순서로 분류한다.

1. 사용자가 특정 전문 스킬을 명시했으면 해당 스킬의 적용 조건을 확인하고 그 스킬을 호출한다. 요청과 무관한 capability를 덧붙이지 않되, 그 스킬이나 요청된 흐름에 필요한 승인·검증·독립성 gate는 면제하지 않는다.
2. 그 밖의 전문 기능은 요청의 목표와 수용 기준에서 capability를 추출하고 레지스트리 descriptor로 찾는다. 선택한 capability, provider, phase와 선택 이유를 계획에 남긴다.
   `evaluation-validity-audit`를 선택할 때는 공유 `TaskEnvelope.v1`을 변경하지 않는다. `plan_workflow`에 `{ schemaVersion, taskEnvelope, evaluationAuditPurpose }` 구조를 넘기고, 실행 전 설계 감사면 `evaluationAuditPurpose`를 `design-readiness`로, 평가 결과를 품질·릴리스 근거로 제출하는 감사면 `quality-or-release`로 고정한다. 후자는 `post-execution PASS`와 `qualifiesAsQualityOrReleaseEvidence: true`가 모두 확인되지 않으면 완료하지 않는다.
3. 서브에이전트 위임이 결정된 뒤 호출 전에 `subagent-model-selection` 단계에서 `coordinate-subagents`를 호출한다. 담당 작업에 맞는 모델·추론 수준을 어떻게 고르는지는 그 스킬이 정하며 이 문서는 기준을 두지 않는다. 단, MCP `orchestrated` workflow를 계획할 때는 별도 규칙을 적용한다. caller는 `plan_workflow` 인자에 `executionContext`를 넣지 않는다. 현재 bootstrap 실행 설정과 task 결속은 호스트가 서버 측 `TrustedExecutionContextProvider`를 통해 authoritative observation으로 제공해야 한다. bootstrap은 신뢰할 수 있는 관측·결속을 검사하며 root 세션의 사용자 선택 설정을 바꾸거나 거부하지 않는다. 관측 제공자 부재나 잘못된 결속으로 MCP가 `BINDING_REQUIRED` 또는 `BINDING_INVALID`를 반환하면 값을 임의로 보정·추정하지 않고 해당 workflow를 시작하지 않는다.
4. `CollaborationDecision.v1.route`가 `delegate`인 경우에만 `subagent-coordination`을 `TaskEnvelope.v1.requiredCapabilities`에 명시한다. `auditSeparationRequired`는 route와 독립된 의무다. `delegate`와 함께 참이면 작업 위임과 별도 감사자를 모두 계획한다. `audit-only`는 작업 위임 없이 감사 의무만 남은 경우다. 감사자의 독립성과 시점은 `independent-audit-gate`가 정한다. 작업 단위가 둘 이상이거나 `orchestration.requested: true`라는 사실만으로 추가하지 않는다.
5. 아직 정하지 않은 결정에 `independent-deliberation-panel`의 적용 조건이 맞을 때만 `independent-deliberation`을 `TaskEnvelope.v1.requiredCapabilities`에 명시한다. `decision.complexity: complex`만으로 추가하지 않으며, 이 단계는 실제 작업이나 완료 게이트를 대체하지 않는다.
6. 이미 만든 변경·결과의 완료 가능 여부를 판정해야 하고 `independent-audit-gate`의 적용 조건이 맞을 때 `independent-audit`을 추가한다. 적용 조건은 두 스킬이 정하며 이 문서는 기준을 두지 않는다.
7. [공통 접수·선택 기준](intake.md#공통-접수선택-기준)에서 결과물(코드·문서·자료·설정·절차)을 만들거나 바꾸는 단계가 선택됐으면 `minimal-implementation`을 `TaskEnvelope.v1.requiredCapabilities`에 명시한다. 이 단계는 변경 전 기준선 뒤, 위험한 상태 변경의 사전 점검과 범위·수용 근거 확인 전에 실행된다. 이 단계에서는 이력으로 복구할 수 있는 파일(예: Git이 추적하는 파일)의 편집·삭제, 새 파일 생성, 확인용 검사(예: 테스트·빌드) 실행만 한다. 추적되지 않는 기존 파일이나 작업 폴더(예: 저장소) 밖 대상의 삭제·덮어쓰기, 마이그레이션·데이터 변경의 실제 실행, 배포·태그처럼 별도 승인 경계에 있는 작업은 필요한 사전 점검 뒤에 실행한다. 시험 실행, 되돌릴 수 있는 작업 사본 저장과 자동 검사 실행의 PRE·승인 불요 범위는 `mutation-risk-preflight`의 적용 조건을 따른다. MCP 계획에 실제 상태 변경에 필요한 stage가 없으면 그 stage를 넣은 새 계획으로 진행하고, 승인이 없으면 대상·영향·실행할 명령을 담아 사용자에게 바로 묻는다. 요청받은 변경을 남은 작업으로 돌려 놓은 채 완료로 보고하지 않는다. MCP 없이 직접 진행할 때도 이 단계에서 이 capability의 provider를 호출한다. 동결된 `TaskEnvelope.v1`의 범위와 수용 기준은 명시적 요청으로 보고 줄이지 않으며, 줄일 후보는 최종 결과에 제안으로만 남긴다. 검사용 파일(예: 테스트)을 포함한 새 파일은 `scope.included`·`workUnits[].writeTargets` 안에서 작업 공간의 기존 검사 관례와 위치를 따라 만든다. 작업 계약이 없으면 사용자 요청이 정한 범위를 같은 기준으로 삼는다. 이 capability를 적용할 요청과 제외할 요청은 `minimal-implementation`이 정한다. 대체물(모의·고정값·자리 채움·조용한 대체 경로·삼킨 오류)이 낸 통과를 완료 근거로 인정하는지는 `acceptance-evidence-validator`가 판정한다. 실제 실행이 실패하면 `FAILED`로 기록한다.
8. 단독 전문 작업과 스킬 생략은 [공통 접수·선택 기준](intake.md#공통-접수선택-기준)을 따르고 수렴 root나 workflow run을 만들지 않는다.

이 스킬을 사용했다는 사실은 MCP `executionMode`나 `CollaborationDecision.v1.route`를 정하지 않는다. 실행 방식은 선택한 capability와 `riskLevel`·`orchestration`을 담은 `TaskEnvelope.v1`로 기존 계획기가 정하고, 위임 여부는 위임 판단 규칙이 따로 정한다. `orchestration.requested: true`는 실패 영향이 크고 순서·gate 연결이 필요한 전문 단계가 둘 이상이며 MCP를 사용할 수 있을 때 적는다. 계획기는 이 값으로 `orchestrated`와 `direct`를 나누므로 `false`이면 direct 계획이 돌아온다.

통합 워크플로에서는 bootstrap을 마친 뒤 작업 단위 조정, 독립 숙고, 변경 전 기준선, 최소 변경 작업(`minimal-implementation`), 위험한 상태 변경 직전의 사전 점검, 요청된 전문 작업, 범위·수용 근거 확인, 최종 고위험 감사 순으로 연결한다. 구체적인 순서는 provider의 `phaseOrder`와 artifact 의존성으로 정하며 MCP stage 순서와 같아야 한다. 각 전문 스킬이 이미 내부적으로 worker를 조정하는 경우에는 같은 단위를 다시 배정하지 않는다.

위임 여부와 자동·명시적 위임의 조건은 [협업 상세 계약의 위임 판단](collaboration.md#위임-판단)이 부르는 `coordinate-subagents`가 정한다. 이 스킬은 그 결과를 `CollaborationDecision.v1`에 기록한다.

## 실행 순서와 입출력 연결

- 각 단계마다 결과를 기록하는 provider 계약, 입력 출처, 기대 산출물, 다음 단계와 중단 조건을 기록한다. provider는 그 단계에서 쓸 스킬을 제한하지 않는다. 단계 안의 판단·행동마다 설치된 스킬 전체와 다시 대조해 맞는 스킬을 모두 호출하며, 다른 스킬을 쓰려고 계획을 고치지 않는다.
- provider가 선언한 `inputBindings`의 `select`, `collect`, `combine`, `require-external`만 사용한다. 누락된 입력을 새로 만들거나 다른 artifact로 조용히 대체하지 않는다.
- 전문 스킬 결과는 선언된 `ProviderResult.v1` envelope로 연결한다. `outputSchema`, `resultSchema`, `stateMapping`을 통과하지 못한 결과를 다음 단계의 입력으로 사용하지 않는다.
- MCP 계획 stage에 `executionRequirement`가 있어도 caller는 `StageResult.v1`이나 `record_stage_result` 인자에 `executionContext`를 넣지 않는다. 실제 실행자의 model class·추론 수준과 정확한 run·stage·revision 결속은 호스트가 서버 측 `TrustedExecutionContextProvider`를 통해 관측한다. 현재 세션 값, 다른 worker 값이나 모델 이름에서 추측한 class를 caller 입력으로 대신하지 않는다. stage의 `PASSED`에 필요한 신뢰 관측이 없거나 결속이 잘못되면 MCP의 `BINDING_REQUIRED` 또는 `BINDING_INVALID`를 그대로 처리하고 `PASSED`로 진행하지 않는다. `korean-prose-finalization`처럼 계획이 `executionRequirement`를 싣지 않은 stage에는 이 관측값을 요구하지 않는다.
- 실제 실행자의 model class·추론 수준은 호스트가 관측해 결속할 뿐, 그 값을 이유로 모델 변경·재실행을 요구하거나 `PASSED` 기록을 거부하지 않는다. 호스트가 root로 확인한 세션은 사용자가 고른 설정을 유지한다. `MODEL_NOT_IN_CATALOG`와 `modelClass: null`은 안내이며 그 자체로 거부 사유가 아니다. root/subagent 구분은 호스트가 검증한 caller 계보를 따르고, 계보나 관측값을 바꾸지 않는다. 실패·차단 결과를 `PASSED`로 바꾸지 않으며, 신뢰 관측·결속이나 artifact·gate 검사는 모든 실행자에게 그대로 적용한다.
- 한 스킬의 결과를 다음 스킬에 전달할 때는 확인한 사실, 대상 식별자, 원시 증거 위치, 열린 제한사항을 보존한다. 요약으로 원시 결과나 불확실성을 대체하지 않는다.
- 하위 실행에 어떤 컨텍스트를 넘기는지는 `coordinate-subagents`가 정한다.
- 큰 로그와 전문 결과는 artifact 참조와 digest로 전달하고, 상위 컨텍스트에는 결론, 근거 위치와 미해결 사항만 유지한다.
- 이전 단계의 필수 입력이 없거나 결과가 실패·차단 상태이면 그 의존 단계는 실행하지 않는다. 이미 확인된 결과와 누락된 입력을 구분해 보고한다.
- 독립 감사가 필요한 흐름에서는 작업자와 감사자를 분리하고, 감사 후 의미 있는 변경이 생기면 감사 대상과 판정을 다시 연결한다.
- 같은 명령, 입력·candidate digest, 실패 원인과 판별 가설이 모두 그대로라면 다시 실행하지 않는다. 다른 원인 가설을 가르는 검사가 없으면 해당 단계만 중단하고 실패 근거와 필요한 새 입력을 보고한다.

`independent-deliberation` 또는 `independent-audit` provider를 호출하기 전에 [전문 단계 handoff 계약](specialist-handoffs.md)에서 해당 절을 읽고 입력, 결과 투영과 진행 조건을 적용한다.

## 결과 통합

사용자 정정이나 새 실패 근거가 들어오면 영향받는 선택·입력·판정·위임만 다시 결속한다. 앞 단계의 `PASS` 개수나 다수 찬성으로 남은 실패를 덮지 않는다. 역할별 산출물 완료와 사용자 수용 기준 충족을 대조하고, 이미 끝난 독립 단계를 이유 없이 재실행하지 않는다.

전문 스킬의 분석·계획·보고서 생성 완료는 해당 단계의 결과이며 사용자 요청 전체의 완료와 구분한다. 분석·계획만 요청받았다면 후속 변경을 실행하지 않는다. 사용자 요청에 구현·실행·검증이 포함되어 있고 이미 허용됐다면 전문 결과를 기존 담당 실행자·도구의 후속 작업 입력으로 연결하고, 기존 역할 범위와 필수 gate 안에서 그 작업과 필요한 검증까지 계속 수행한다. 후보·보고서 반환만으로 전체 완료를 보고하거나 실행 가능한 후속 확인을 사용자에게 넘기지 않는다.

호출·재선택 시점과 실행 가능한 일의 구분은 [공통 접수·선택 기준](intake.md#공통-접수선택-기준)을 따른다. 필수 입력이나 gate 실패로 막힌 의존 단계는 실행하지 않으며, 실제 실행·검증 근거와 남은 제약으로 요청 전체의 상태를 보고한다. 이 연결을 위해 단독 전문 작업에 workflow나 새 보고서를 추가하지 않는다.

보안 전문 분석을 요청했거나 수용 기준에서 요구하면 `software-security-audit`를 선택한다. 보안 관련 파일의 존재만으로 추가하지 않는다. phase 65의 `security-audit-request`에는 실제 파일 내용을 고정한 대상과 허용 범위를 전달한다. 보고서는 로컬 검사 스크립트로 대상·근거를 검증하고 원자료를 확인한 뒤 기록한다. 이 provider의 `PASSED`는 조사 산출물 생성 성공이며 안전 판정이 아니다. `partial`의 미검사 항목과 limitations를 생략하지 않는다.

수용 근거 검증도 선택됐다면 `security-audit-report` artifact의 attachmentId·digest·targetDigest와 원시 근거를 기존 `workflow:verification-evidence`에 추가하고 해당 보안 수용 기준에 연결한다. 기준 불충족과 검사 공백은 기존 수용 검증·독립 감사 게이트에서 판단한다. 취약점 심각도만으로 전역 차단 정책을 새로 만들지 않는다. 감사 후 대상 변경이 있으면 영향받은 분석과 근거 결속을 다시 확인한다.

## MCP 도구 사용 계약

MCP로 계획·실행·결과 기록을 하기 전에 [MCP 실행 계약](mcp-execution.md)을 읽는다. 연결과 도구 스키마를 확인한 뒤 계약의 호출 순서, revision, compact 응답과 실패 처리를 따른다.

MCP를 사용할 수 없어도 설치된 전문 스킬로 독립 처리 가능한 부분은 진행한다. 필요한 통합 실행 자체가 불가능할 때만 통합 결과를 `BLOCKED`로 보고하며, 직접 실행 결과를 guarded 완료 근거로 표현하지 않는다.

## CS Engineering 연결 (Sennard 2.8.0 후보)

정확성에 관련 CS 조건이 필요한 동작 변경이면 `cs-constraint-derivation`을 bootstrap 25에서
선택하고, 핵심 5분야20규칙과 추가 draft5분야를 구분한다. 실제 관련 분야만 로딩한다.
[2.x 인계·리뷰 계약](../../cs-engineering/references/sennard-2x-integration.md)을 읽고
READY 조건을 task-contract에 전달해 사용자 요구·수용 기준·검증 계획의 출처로 기록한다.
TaskEnvelope.v1을 바꾸지 않고 최종 task digest로 binding을 만든다.

`handoff`로 동결 입력을 재검사하고 같은 reference/digest와 required obligation ID를
`minimal-implementation`의 작업 지시로 넘긴다. 수신자는 원자료 접근과 조건 수신을 확인한다. 기존
`minimal-implementation` inputBindings는 유지되며 모든 결과물 작업의 자동 인계 증명으로 표현하지 않는다.

리뷰 capability만 workflow에 요청한다. 선택된 phase67의 MCP stage는
`cs-review-bundle`과 `cs-review-report` artifact를 받아 검사 스크립트로 task·후보·조건·원시
파일을 기록 시와 finalize 시 재검사한다. FAIL·BLOCKED를 PASS로 바꾸지 않는다.
번들 검토 stage(`cs-implementation-review`, `test-sensitivity-review`, `change-code-review`)는
첨부로 판정한다. 서버는 호출자 컴퓨터의 경로를 읽지 않는다. 번들의 manifest와 보고서는
`output.artifacts[]`의 산출물(`cs-review-bundle`·`cs-review-report`, 또는 `engineering-stage-bundle`·
`engineering-test-result`·`engineering-review-result`)이고, manifest나 검사가 읽는 모든 원파일(task,
plan 또는 request, proof 또는 report, result, 후보 파일)은 `evidence[]` 항목이다. 모두 결과를 기록하는
같은 세션이 먼저 업로드한 첨부이며 선언 digest를 갖는다. 첨부를 올릴 때의 파일 이름이 번들 폴더 아래의
경로다(`/`로 구분한 이식 가능한 상대 경로, `..`·드라이브·역슬래시·예약 이름 없음, 대소문자 무시 중복
없음, 합계 64 MiB 이하). 큰 파일은 `sennard attachments prepare --name <상대 경로> <file>` → `artifact_begin`
(같은 이름) → `sennard attachments send --upload-id <id> <file>` 순서로 올리고, 작은 내용은 도구로 직접 올린다. 서버는 첨부를 비공개 임시 폴더에 두고 바뀌지 않은 `check-stage-bundle`을 그 안에서
실행한 뒤 폴더를 지우며, 보고서 산출물은 검사가 읽은 그 첨부(같은 경로와 digest)여야 한다. 쓸 수 없는
파일은 `INVALID_INPUT` `workflow.bundle.fileNotUsable`(`details.reason`: `PATH_NOT_PORTABLE`,
`DUPLICATE_PATH`, `TOO_LARGE`, `ATTACHMENT_NOT_FOUND`)이다.
같은 의무 ID와 실제 원시 근거를 기존 수용 근거 검증에 연결하고 보안·독립 감사 책임을 유지한다.

계획·lease·start·resume 전체 binding과 전역 적용성·acceptance 강제 정책은 아직 지원하지 않는다.
unknown field를 삭제해 약한 경로로 재시도하지 않는다. 직접 실행한 스크립트 결과와 선택된
MCP stage 검증·실호스트 자동 선택·독립 감사를 각각 구분한다.

## Sennard Engineering Practices v0.1.0

테스트 작성·보강 요청이면 `test-engineering`을 선택한다. 같은 테스트가 실제 결함에서 실패하는지 확인할 때는 `test-engineering`의 test-sensitivity-review를, prepared test plan의 계약 검사에는 test-plan-validation을 선택한다. 고정된 diff·patch·PR 리뷰에는 `code-review`/change-code-review를 선택한다. 단순 문구 수정·설명이나 순수 결과물 제작에 두 검증 단계를 일률적으로 추가하지 않는다. 입력 자료는 새 스킬의 SKILL.md와 integration descriptor로 확인한다.

실천 규칙 모듈은 각 담당 스킬의 references가 들고 있고 담당 스킬이 스스로 읽는다. 실패 판별은 `blocker-diagnostician`, 구현 단순화는 `minimal-implementation`, 의존성·에이전트 확장 변경은 `software-security-auditor`, 완료 근거 대조는 `acceptance-evidence-validator`, 스킬 작성은 `prompt-engineering`으로 연결한다. 오케스트레이터는 모듈 내용을 읽어 전달하지 않는다.

기존 cs-engineering 보고서가 있으면 불변조건·검증 의무 ID를 참조하고 새 규칙으로 교체하지 않는다. 새 권한·PEER·세션 배분·감사 정책은 만들지 않는다. prepared artifacts를 검사 스크립트로 검증한 결과와 원시 근거를 기존 수용/감사 단계에 전달한다. 선택됨·참조 읽음·실행됨·검증됨을 별도로 기록한다. registry 등록과 로컬 CONSISTENT만으로 실제 호스트 활성화나 독립 감사 PASS를 주장하지 않는다.
