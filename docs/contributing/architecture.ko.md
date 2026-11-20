[English](architecture.md)

# 아키텍처

이 저장소는 하나의 설치 가능한 플러그인 안에서 여러 전문 스킬을 배포합니다. 소스가 한 저장소에 있어도 전문 스킬의 실행 책임은 분리합니다.

```text
plugin manifest
├── skills/orchestrator/       분류·순서·입출력·결과 통합
├── skills/<specialist>/       전문 판단과 해당 결과 형식
├── contracts/                 여러 스킬이 공유하는 명시적 계약
└── java/                      상주 서버(MCP 도구·훅·관리자 페이지)와 설치 도구
```

오케스트레이터는 스킬 이름을 하드코딩하지 않습니다. 설치 시 노출된 스킬 설명에서 capability 후보를 정하고, MCP 도구 `run_skill_check`의 orchestrator `query-registry` 명령으로 활성 provider의 `skillId`, capability, execution class, phase/order, priority, 선택 조건, precondition과 gate만 조회합니다. 후보를 특정하지 못할 때만 `--all` compact catalog를 사용합니다. 조회기는 매번 원본 `skills/registry.json`에서 결과를 만들고 정렬하므로 별도 catalog 복사본을 두지 않습니다. provider는 `bootstrap`, `workflow`, `recovery`로 나뉩니다. 지침 범위, 저장소 관례와 작업 계약을 만드는 bootstrap provider는 `plan_workflow` 전에 직접 실행합니다. 일반 workflow provider는 artifact 의존성을 먼저 만족시키고 `phaseOrder`를 안정적인 보조 정렬 기준으로 사용합니다. recovery provider는 일반 workflow와 섞지 않고 별도 실행으로 계획합니다. 반복 실패는 `blocker-diagnostician`의 원인 확정 후 `recovery-strategy-selector`가 `RecoveryHandoff.v1`을 만들며, 기존 run은 불변으로 남기고 handoff에 결속된 새 `TaskEnvelope.v1`과 새 workflow에서만 복구를 실행합니다.

같은 capability를 여러 provider가 제공하면 숫자가 큰 priority를 우선합니다. `selectionCriteria`는 사람이 검토할 선택 조건과 이유이며 MCP가 자연어를 해석하지는 않습니다. 조건별 자동 분기가 필요하면 서로 다른 구체적 capability로 등록합니다. 같은 capability에 priority가 겹치거나 선택 조건이 비어 있으면 저장소 검증이 실패합니다.

공용 스킬, JSON Schema, MCP 도구와 런타임은 호스트 중립 계약을 사용합니다. `host`는 Codex나 Claude Code로 닫힌 enum이 아니라 확장 가능한 식별자이고, 제품별 훅 이벤트·inbox·wake transport·실행 관측은 adapter 또는 overlay가 공용 계약으로 변환합니다. 따라서 Grok, Spark나 다른 로컬 AI 런타임도 공용 계층을 고치지 않고 adapter를 추가해 같은 기능을 사용할 수 있습니다. 특정 제품의 로그나 설치 형식처럼 본질적으로 전용인 기능만 예외로 두며, 그 제약은 기능 이름과 문서에 명시합니다.

공개 계약은 `TaskEnvelope.v1`, `SkillDescriptor.v2`, `ProviderResult.v1`, `WorkflowPlan.v1`, `StageResult.v1`, `WorkflowReceipt.v1`, `ApiResult.v1`와 전송 전용 `ResponseModeV1`, `WorkflowStatusSummaryV1`, `ConvergenceRootHandleV1`, `ConvergenceStatusSummaryV1`로 나뉩니다. `SkillDescriptor.v2`는 한 스킬의 여러 provider, 입출력 artifact, 결과 schema, 상태 매핑과 gate를 선언합니다. `plan_workflow`가 레지스트리를 읽어 schema checksum이 포함된 계획을 HMAC으로 서명하고, `start_guarded_workflow`가 같은 MCP 저장소의 서명과 일회용 lease를 확인한 뒤 계획을 동결합니다. 공개 `start_workflow`는 같은 검사로 계획·root·lease·run을 한 transaction에서 만드는 최소 시작입니다.

`record_stage_result`는 revision과 실행 순서를 확인한 뒤 provider envelope와 내부 output을 각각 선언된 schema로 검증합니다. 계획에 생산자가 있는 입력 artifact는 해당 선행 단계가 검증된 artifact를 남긴 경우에만 소비할 수 있습니다. bootstrap·task 입력처럼 계획 밖에서 들어오는 artifact의 내용과 출처 확인은 실행한 전문 스킬이 책임지고, MCP는 제출된 첨부 id·digest의 구조를 확인하고, digest를 첨부의 sha256과 비교해(파일은 결과를 기록하는 세션이 업로드한 것이며, 서버는 호출자 컴퓨터의 경로를 읽지 않습니다) 결과를 `artifactChecks`에 `SHA256_MATCHED` 또는 `NOT_CHECKED`로 기록합니다. `verified`는 제공자 선언이며 관문 판정에 쓰지 않습니다. root만 Sennard를 호출하므로 감사자·검토자 독립성, fresh context, 사용자 승인은 관측되지 않고 `unverifiedClaims`에 남습니다. descriptor의 선택적 `receiptPolicy`는 계획 stage로 복사되어 HMAC에 결속됩니다. `reference-only` mode는 닫힌 output schema와 opaque reference만 허용하고, 선언된 actor pointer에는 canonical UUID와 run 단위 고유성을 적용합니다. `finalize_workflow`는 모든 필수 단계가 통과하고 미해결 항목이 없으며 각 stage의 receipt policy가 다시 확인된 경우에만 완료 결과를 만듭니다.

### 의미 기반 실행 보증

엄격한 MCP 보증은 도구 인자로 전달된 `executionContext`를 신뢰하지 않습니다. `TrustedExecutionContextProvider`가 해당 task·run·stage·revision에 결속된 새 관측을 제공해야 하며, 관측 ID는 워크플로 저장소에서 원자적으로 확보되고 서버 재시작과 동시 연결을 넘어 한 번만 쓸 수 있습니다. 만료·불일치·재사용된 관측은 fail-closed로 거부됩니다. 서버는 호스트 증명을 만들어 내지 않습니다. 각 배포물은 정적 `Sennard-Host-Label` 헤더로 호스트를 선언하며(`.mcp.json`은 `codex`, Claude 플러그인 manifest는 `claude`, `mcp_config.json`은 `agy`), 서버는 그에 맞는 호스트 어댑터를 고릅니다. 라벨이 없거나 알 수 없으면 알 수 없는 호스트로 취급하여 신뢰 수준은 `unavailable`이 되고, 세션은 `open_session`이 준 `sessionRef`로 식별합니다.

실행 관측 계약은 모든 호스트에 공통입니다. 각 호스트에는 서버가 `Sennard-Host-Label` 헤더로 고르는 caller ingress가 하나씩 있습니다. Codex와 Antigravity는 호스트가 기록한 `tools/call`의 `_meta`를 읽고, Claude Code는 PreToolUse 거버넌스 훅이 기록한 admission 행을 `claudecode/toolUseId`로 확보합니다. 핵심 호출자 검사는 검증된 호출자(`host`, `sessionId`, `callId`, root 또는 child인 `actor`, `parentChain`, `execution`)를 돌려주거나 거부하며, 관측되지 않은 호출자를 root로 간주하지 않습니다. 서비스는 이 호출자를 별도 매개변수로 받고, `_sessionBinding`, `_continuityBinding`, `_hostAttestation`, `_sessionProof`가 든 도구 인자는 거부합니다. `plan_workflow`, `record_stage_result`, `classify_skills`, `record_skill_selection`에서는 `HostAttestationProvider`가 현재의 신뢰된 어댑터로 `caller.execution`(원본 모델, 추론 수준)의 자격을 판정합니다. 관측 ID는 호스트·세션·actor·호출 ID에서 유도하며, 기존 `WorkflowService`의 task/run/stage/revision 결속, 신선도, 원자적 일회 확보 검사는 그대로 유지됩니다. 6.0.0에는 단계 하한이 없으므로 관측한 모델과 추론 수준은 기록만 하고 단계를 거부하는 데 쓰지 않습니다. 관측이 없으면 `BINDING_REQUIRED`, 모순·자격·재사용 오류는 `BINDING_INVALID`입니다. 서명 token은 없으며, 보장하는 것은 호스트 관측이지 같은 OS 사용자로 실행되는 프로세스와의 격리가 아닙니다. 자세한 내용은 [execution-attestation.md](execution-attestation.ko.md)를 참고하십시오.

각 호스트의 관측이 어디서 오는지(Claude Code PreToolUse 거버넌스 훅과 트랜스크립트, Codex `tools/call`의 `_meta`, Antigravity 훅 러너의 PreToolUse 행)와 호스트별 한계는 [execution-attestation.md](execution-attestation.ko.md#배포물의-연결)의 표에 있습니다. 2.x의 서명 token 경로(도구 입력을 고쳐 쓰는 PreToolUse 훅이 발급하던 `_hostAttestation`)는 더 이상 없습니다.

Antigravity 패키지는 `mcp_config.json`(서버 키 `sennard`)에 `Sennard-Host-Label: agy` 헤더와 함께 MCP 서버를 등록합니다. 그 관측을 수집하는 방식은 [execution-attestation.md](execution-attestation.ko.md)에 있습니다.

v1.19.0부터 stage 결과는 provider 출력을 첨부 참조(결과를 기록하는 세션이 업로드한 첨부의 `attachmentId`와 SHA-256을 가진 `StageResult.outputFile`, `output.output`은 null)로 실을 수 있습니다. 서버는 첨부를 읽고(한도 16 MiB) 다이제스트를 확인하며, 파싱한 JSON을 인라인 출력과 똑같이 검증하고 참조만 저장합니다. 엄격한 MCP claim, guarded start, 의미 단계 경계는 실행 보증이 없는 계획을 진행시키지 않습니다.


MCP의 `plan_workflow` 경계는 execution assurance를 fail-closed로 적용합니다. ExecutionContext가 결속된 새 plan의 stage-level 검사는 그대로 유지됩니다.


v1.13부터 orchestrated workflow는 의미 판단 단계의 실행 능력을 계획에 결속합니다. `plan_workflow`는 현재 호출에 결속된 호스트 관측을 검증하며 모델 등급·추론 수준의 하한을 두지 않습니다. 서명되는 초기 접수 요건은 `policyId`, `kind`(`semantic`), `observationRequired`(`true`)를 가집니다. 계획을 시작할 수 있다는 사실은 개별 stage의 실행 자격을 뜻하지 않습니다. 새 계획의 각 stage에는 `ExecutionRequirement.v1`이 들어갑니다. 의미 판단 stage의 요건은 `semantic`이고 관측을 요구합니다. stage가 `semantic`이고 `PASSED`가 되려면 현재의 신뢰된 관측을 검증해야 합니다. 관측 정보가 없으면 `BINDING_REQUIRED`로 거절하지만, 검증된 관측의 모델 등급·추론 수준이 무엇이든 미관측이어도 거절하지 않고 그대로 기록합니다. 신원·서명·결속 오류는 `BINDING_INVALID`로 구분합니다. `korean-prose-finalization`처럼 스킬의 검사만으로 결정되는 단계는 `deterministic`(`observationRequired: false`)으로 표시해 관측을 요구하지 않습니다. 계약은 `kind`와 `observationRequired`를 묶어 `semantic`이면 `true`, `deterministic`이면 `false`만 허용합니다.

공통 계약은 특정 제품 모델 이름 목록을 고정하지 않지만, 새 ID를 기본으로 신뢰하지도 않습니다. 모델 이름의 해석과 capability descriptor는 신뢰된 host adapter가 제공하며, 기본 카탈로그 밖의 ID는 descriptor가 없으면 class를 모르는 채로 실행되고 관측에 정책 메모가 남습니다. `lightweight < general < deep < frontier` model class와 추상 추론 깊이 순서(`runtime/model-routing-presets.json`의 `depth_order`)는 실행 설정의 추천 순서입니다. effort 라벨은 호스트가 자기 모델에 대해 제시하는 목록의 순서를 따릅니다. 같은 class나 effort가 서로 다른 벤더 모델의 실력 등가를 실측했다는 뜻은 아닙니다. model class는 신뢰된 adapter의 capability 정책에서 얻고, 실제 model ID와 effort는 host runtime 또는 worker spawn 결과에서 관측해야 합니다. caller가 도구 인자로 넣은 `executionContext` JSON은 trusted observation이 아니며 그 자체로는 인증되지 않습니다. MCP 경계는 caller `executionContext`를 관측으로 쓰지 않고, 서버가 검증한 호출자의 호스트 관측(`caller.execution`)만 씁니다. 다만 이 관측은 같은 OS 사용자 권한으로 실행되는 프로세스 사이의 격리를 보장하지 않습니다. 따라서 이 계층은 모델 성능을 새로 만들어 내거나 서로 다른 세션의 산출물 품질을 같게 만드는 장치가 아니라, 어떤 실행 설정으로 semantic stage를 통과했는지 관측해 남기는 실행 거버넌스입니다. 실제 품질 보장은 동결된 corpus·rubric·threshold와 독립 평가 증거를 결속하는 별도 평가 계층이 담당합니다.

[v1.13.0 릴리스 노트](../release-notes/release-notes-v1.13.0.md)는 해당 계약을 도입한 당시의 기록입니다. 현재 adapter 구성과 관측 경계는 이 절의 설명을 따릅니다.

MCP 서버는 설치가 호스트의 플러그인 사본(Claude Code는 플러그인 루트의 `.mcp.json`)에 등록하며, `server.properties`의 주소에 있는 서버의 `/mcp`로 각 메시지를 전달하는 stdio MCP 중계(`sennard-hook-client`의 `McpRelayMain`)입니다. MCP 응답은 외부 상태를 관측하는 근거일 수 있지만, 호출 수락만으로 성공을 뜻하지 않습니다. MCP가 없을 때도 단독 전문 스킬로 처리할 수 있는 요청은 계속할 수 있습니다.

mutating workflow 도구의 `responseMode`와 status 도구의 `detail`은 서버 경계의 전송 옵션입니다. 생략 시 `full`이며 기존 domain validator와 상태 전이를 그대로 사용합니다. `compact`일 때만 검증·저장이 끝난 domain 결과를 요약 계약으로 투영합니다. 이 옵션은 서버 경계에서 제거되므로 `StageResultV1`, `WorkflowReceiptV1` 같은 저장 계약에는 들어가지 않습니다. MCP 결과는 JSON text content block 하나만 사용하고 같은 데이터를 `structuredContent`에 복제하지 않습니다.

`claim_workflow_attempt`가 task envelope와 frame을 함께 생략하면 서버는 `rootId`의 저장 값을 복원한 뒤 기존 digest와 revision 검사를 수행하고 정규화된 전체 proposal을 저장합니다. `start_guarded_workflow`가 plan을 생략하면 `leaseId`에 결속된 proposal plan을 복원한 뒤 integrity token, root revision, 만료와 일회성 소비를 검사합니다. 호출자가 사본을 전달하면 저장된 root·proposal과의 기존 완전 일치 검사를 유지합니다. lease가 안전한 plan reference이므로 별도 plan ID나 정리 정책은 추가하지 않습니다.

run, revision, run ID sequence, 계획 서명 키, 업데이트 상태, 정리 claim과 trusted execution observation claim은 저장소의 `sennard` 스키마에 저장합니다. 서버를 다시 시작해도 이전 run과 observation의 일회성 소비 상태를 복구하고, optimistic revision 검증과 원자적 observation claim으로 충돌·재사용을 거부합니다. observation claim은 만료 뒤에도 opaque ID tombstone으로 유지해 같은 ID의 재사용을 허용하지 않습니다. compact 전송도 저장 단위를 바꾸지 않으며 전체 `WorkflowReceipt`의 평문 JSON을 유지합니다. 따라서 `StageResult`의 provider output, evidence note, findings, blockers와 error에 원문 코드, 로그, 비밀값이나 개인정보가 들어 있으면 그 내용도 DB에 남습니다. MCP 서버는 필드 내용을 걸러 내거나 자동으로 만료·삭제하지 않으므로 호출자는 민감한 원문을 제출하지 않고 상태 폴더의 접근 권한과 보존 기간을 관리해야 합니다.

## 스킬 분류 조언 계층(JEV)

`providerKind: "jev"`는 특정 제품이 아니라 선택을 돕는 조언 계층의 자리입니다. 결정은 AGENT가 하며, TypeSafe는 이 계층의 구현 하나입니다. `noul_*` 점수 종류와 `JEV_*` 이유 코드는 TypeSafe adapter가 내는 값입니다. profile 설정 규칙(reasoning effort, `judgmentPolicy`)은 각 wire adapter의 `validateConfiguration`이 정하고, 인증 헤더는 adapter의 transport 계약이 정합니다(기본값 `authorization: Bearer`). profile·route JSON·요청은 인증 헤더를 정하지 못합니다. `SkillClassificationService`가 외부 전송 허용, 예산 예약·정산(`JevBudget`), usage 검증, 실패 응답, 응답 검증을 맡습니다. 4.0.0부터 JEV 공급자 정의는 소스(`JevProvider`)에 내장하고, 켜고 끄기·모델·정보 전송 허용·예산·프리셋·key는 관리자 서명 설정에서 읽습니다. 서버 쪽 예산 상한은 없고, 추정 사용액 누계는 `sennard.jev_usage_daily`에 저장됩니다. 자세한 구성은 [관리자 서명 기록과 설정 읽기](admin-log.ko.md#jev-구성)에 있습니다.

## 로컬 task continuity

Continuity는 상주 서버 안의 별도 서비스이며 workflow 표와 분리된 자체 표(`continuity_*`)를 사용합니다. Direct-task current·history, session epoch, compact marker, hash-only 관측과 보존 기간 조회 index를 여기에 저장합니다. Orchestrated 상태의 원장은 계속 workflow 표의 `TaskEnvelope`, `WorkflowReceipt`와 convergence root이고 continuity 표에는 root 결속과 marker만 둡니다.

Direct 메모리는 `continuity_current_memory`와 `continuity_memory_history`로 나눕니다. 현재 본문 `JSON(status/core/evidenceRefs)` 전체에는 UTF-8 2000 byte의 hard 상한이 있으며 채울 목표 분량이 아닙니다. 갱신은 이전 현재 내용을 history에 보관하고 current를 교체하는 같은 transaction으로 처리합니다. 에이전트 API는 history를 노출하지 않지만 같은 OS 사용자의 상태 폴더 직접 접근을 차단하는 별도 권한 격리는 없습니다. 본문은 평문 JSON이므로 비밀값, 개인정보, 원시 로그·코드와 chain-of-thought를 제외하고 상태 폴더의 접근 권한과 보존 기간을 관리합니다. `load_context`는 `schemaVersion`만으로 자기 current와 `stateToken`을 읽으며, 쓰기·삭제는 반환된 토큰을 `expectedStateToken`으로 대조합니다. 명시적 `purge_direct_context`는 current만 지우고 hash tombstone을 남기며 비공개 history, workflow receipt와 convergence root는 삭제하지 않습니다.

호스트 lifecycle hook은 상주 서버가 받습니다. 호스트가 Sennard Java 클라이언트(`sennard-hook-client`, `java -cp`로 시작)를 command hook으로 실행하고, 클라이언트가 서버가 `server.properties`에 알린 주소의 `/hooks/v1/<host>/<event>`로 입력을 보냅니다(`ContinuityHookHandler`, `hook` 패키지의 호스트 어댑터). Claude Code, Gemini CLI, Antigravity의 모든 hook과, `agent_id`가 선택인 모든 이벤트(SessionStart, SessionEnd, UserPromptSubmit, PreCompact, PostCompact, PreToolUse, PostToolUse, PreModelRequest, PostModelRequest)의 Codex hook이 이 경로입니다. Codex의 나머지 hook(SubagentStart, SubagentStop, Stop)은 `mcp_tool` hook이며 MCP 중계를 거쳐 서버에 닿습니다. HTTP hook은 없습니다. 호스트의 HTTP hook은 고정된 URL을 받는데 서버의 포트는 고정되지 않기 때문입니다. 호스트별 표와 근거는 [호스트 훅 수신](hooks.ko.md)에 있습니다.

`SessionStart(resume)`과 direct-task `SessionStart(compact)`는 기존 hook으로 현재 메모리 본문을 주입합니다. 최신성은 원자적 읽기 시점 L의 current를 뜻하며, 호스트 수락 ACK 없이 crash 이후까지 exactly-once 전달이나 모델 첫 행동까지의 최신성을 보장하지 않습니다. Codex·Claude Code의 일반 입력은 본문 없는 짧은 갱신 cue만 받습니다. AGY turn0는 native ephemeral current+cue 경로를 사용해 주입문을 durable 대화 이력에 누적하지 않습니다. Workflow의 revision·digest 검증과 marker에 결속된 bounded 구조 카드 복원은 유지합니다. Startup은 복원하지 않고 clear는 epoch를 회전합니다. 서버에 닿지 못하면 `SessionStart` hook은 `sennard status`로 확인하라는 안내를 출력합니다.

## 세션 현황판

세션 현황판은 상주 서버의 서비스(`SessionBoardService`)와 `board_sessions` 표가 맡고, MCP 도구 `update_session_status`, `list_session_status`가 인터페이스입니다. 행마다 세션과 한 줄 요약을 두며 현황판에는 요청 원문을 두지 않습니다(원문은 아래 「사용자 원문 보관소」가 맡습니다). 저장소는 모든 호스트(Claude Code·Codex·Antigravity)가 함께 쓰므로 목록은 모든 호스트의 행을 `host`와 함께 보여 줍니다. 현황판은 세션이 목록을 다시 읽어야 변경을 알 수 있는 pull 방식이며, 실행 중인 다른 세션에 메시지를 push하거나 그 작업을 중단시키는 실시간 통신 채널은 아닙니다.

## 사용자 원문 보관소

사용자가 주 세션에 한 말은 프롬프트 표(V15)에 원문 그대로 둡니다. 보관소의 목적은 세 가지입니다. 사용자가 한 말을 원문으로 확인하고, 에이전트가 그 말을 듣고 한 일을 요약으로 참조하고, 중요한 것은 공유 컨텍스트로 옮기도록 유도합니다. 에이전트의 답변 원문은 저장하지 않습니다.

- 표: `user_prompt`(원문, 크기·SHA-256·잘림 표시·트랜스크립트 경로), `turn_recap`(원문당 한 줄 요약, `version`으로 교체), `recap_nudge`(원문당 한 번의 되돌림 기록), `transcript_anchor`(원문당 트랜스크립트 안의 위치 하나: 호스트가 정한 항목 식별자와 시각, INSERT만).
- 쓰기는 훅 수집 경로 하나입니다. `PromptCollector`(`@Order(7)`)가 `PromptArchiveWriter`로 INSERT하고, MCP 도구는 읽기와 리캡 기록만 합니다. 빌드 규칙 `r-prompt-archive`가 이 경계를 지킵니다.
- 저장 확인은 `TurnCheckService`가 맡습니다. 모든 호스트의 사용자 프롬프트(`UserPromptSubmitted`)마다 연속성 저장소의 관측(`turn-check:start`)으로 턴 시작을 기록하고, `checkpoint_context`와 `checkpoint_role_context`(`RoleContextSaved` 이벤트)가 성공하면 `turn-check:saved`를, `mark_turn_unchanged`가 `turn-check:unchanged`를 같은 호출자(actor) 이름으로 남깁니다. 관측은 epoch를 가로질러 읽습니다. `ContinuityHookHandler`가 턴 끝(`TurnEnded`)에 시작 뒤 기록이 없으면 단계·증거 점검과 한 문장으로 묶어 한 번 이어 가고(`turn-check:asked`), 이미 이어 갔으면 `turn-check:closed`를 실패로 남기며, 다음 프롬프트가 지난 턴의 기록 없음을 알립니다. 이어 갈 수 없는 턴 끝(`continuable` 거짓)은 턴을 닫지 않습니다. 서브에이전트, 억제된 세션, 닫힌 턴 뒤의 턴 끝(깨운 턴)은 확인하지 않습니다.
- 턴 끝 확인은 `RecapNudgeHook`(`@Order(30)`, 가장 뒤)입니다. 원문에 리캡이 없고 되돌림 기록도 없으면 턴을 한 번 이어 가며 `record_turn_recap`을 부르게 합니다. 세션 목록은 사람이 각 세션이 지금 무엇을 하는지 알아보려는 화면이므로, 현황판의 요약(`BoardService.readEntry`)은 `SummaryState`로 가립니다. 없음, 낡음(현황판의 `summaryStale()`, 마지막 요청보다 오래됨), 최신입니다. 요약이 없거나 낡은 세션은 같은 이어 가기에서 `update_session_status`로 요약을 쓰라는 요청도 받고(`RecapNudgeService.remindNow`), 리캡이 있으면 요약 요청만 받습니다. 요약 요청은 기록을 남기지 않고 최신이 될 때까지 매번 합니다. 같은 hook은 호스트가 맥락을 받는 다른 지점에서도 요청합니다. `UserPromptSubmitted`, `ModelCallFinished`, `stepBoundary`인 `ToolFinished`입니다(Antigravity의 도구 종료는 맥락을 싣지 못해 제외). 호스트가 이미 이어 간 턴 끝(`alreadyContinued`)은 요약 때문에 다시 이어 가지 않아, 응답하지 않는 세션이 반복에 빠지지 않습니다. 리캡 질문은 원문의 키(`recap_nudge`)에 묶여 있어 호스트의 `alreadyContinued` 값과 무관합니다. Antigravity가 취소·실패한 Stop을 이어 갈 수 없는 것으로 알리면(`alreadyContinued`) 어댑터가 이어 가기를 내보내지 않습니다.
- 도구는 `record_turn_recap`(리캡 길이는 가중치 합 500 이하: 한글·한자·가나 5, 그 밖 2)과 `recall_user_prompts`(`scope` SESSION 또는 ROLE)입니다. ROLE은 서명된 역할 결속(`ContextLayerService.signedRoles`)이 같은 세션의 원문만 읽습니다. 검색은 `PromptSearch` 포트이고 기본 구현은 표준 SQL `LOWER(...) LIKE LOWER(?) ESCAPE '\'`입니다. 세 번째 도구 `read_prompt_transcript`는 아래 트랜스크립트 경계를 거칩니다.
- 트랜스크립트 경계: 원문 뒤에 에이전트가 한 일은 `read_prompt_transcript`가 호스트의 트랜스크립트에서 읽으며, 읽는 길은 `interface Transcript`(`sennard-domain`의 `domain.transcript`) 하나입니다. 호스트마다 파일과 형식이 다르므로 구현은 호스트별 `@Component`이고(6.0.0은 `ClaudeTranscript`뿐), `TranscriptHosts`가 `List<Transcript>`에서 호스트 이름으로 고릅니다. 구현이 없는 호스트(Codex·Gemini CLI·Antigravity)는 `Unsupported`로 답하며, 그 형식은 시험 서버에서 실측한 뒤 별도 과제로 구현합니다. `locate`와 `readAfter`는 sealed 결과(`TranscriptLookup`, `TranscriptRead`)로 답하고, 미지원·미확인을 `Optional`이나 null로 뭉치지 않습니다. 읽기 결과의 항목마다 호스트의 식별자(`key`, Claude는 줄의 `uuid`이고 한 줄에 항목이 여럿이면 `uuid/자리`)가 따르고, `continueAfter`는 그 키의 항목 다음부터 같은 턴을 이어 읽으며, 키가 그 메시지의 턴에 없으면 `NotInTurn`으로 답합니다.
- 트랜스크립트는 같은 세션만 읽습니다. `TranscriptAccess`가 호출 세션과 원문 행의 (host, 세션 ID)를 비교하고, 읽는 파일은 원문 행에 훅이 저장한 `transcript_path`뿐이어서 호출자는 경로를 줄 수 없습니다. 처음 항목을 찾으면 그 위치(`entry_key`, Claude는 줄의 `uuid`)만 `transcript_anchor`에 INSERT하고, 다음 호출은 그 위치에서 읽습니다. 두 호출이 동시에 처음 찾으면 기본 키 충돌 뒤 다시 읽어 저장된 값을 씁니다(`TranscriptAnchorService`). 트랜스크립트 본문은 저장하지 않습니다. Claude 구현은 `ClaudeTranscriptFile`·`ClaudeEntry`가 줄의 형식을 한 곳에서 읽고, 호출의 모델을 찾는 `ClaudeTranscriptExecutionReader`도 같은 읽기를 씁니다.
- 도구를 부른 세션의 키는 훅이 저장한 (host, 호스트 세션 ID)입니다. 세션 참조만 가진 호출(Gemini CLI)은 `SessionRegistry.identityOf`로 호스트 세션 ID를 얻습니다(`ArchiveSessionKeys`).

## 세션 메시지 저장소 (broker 없음)

세션 메시지는 저장소의 메시징 표에 저장하고, 연산은 상주 서버(서버 어댑터 `SessionMessageService`와 `sennard-app`의 `MessagingService`) 안에서 실행합니다. 세션별 relay 프로세스, 별도 broker 프로세스, 잠금 디렉터리는 없습니다. 연산 하나는 데이터베이스 transaction 하나이고, 한도를 판정하는 `prepare`·`send`는 직렬화 가능 격리 수준에서 실행합니다. `host`는 열린 문자열 식별자이며 저장소는 제품별 transport나 운영체제를 알지 못합니다.
메시지 ID는 prepare에서 서버가 발급하며 불변 sender·target·body·TTL을 `prepared_messages`에 보존합니다. prepare는 전달 효과가 없고, send는 발급 ID만 받아 큐 삽입과 최초 영수증을 같은 transaction으로 확정합니다. 같은 ID는 보존된 최초 결과를 반환하고 미발급·다른 sender·만료·정리된 ID와 변경 내용을 거절합니다. 응답 유실은 같은 알려진 ID로 조정하며 unknown을 무조건 새 prepare로 재전송하지 않습니다. 저장 상한과 복구 제한은 [메시지 ID·재시도 계약](session-messaging.ko.md)을 따릅니다. 수신 훅의 claim은 짧은 lease를 설정하고, ACK가 없으면 TTL까지 지수 backoff로 재전달합니다. 모델이 본문을 처리한 뒤 MCP ACK를 호출한 시점만 `ACKNOWLEDGED`이며, Codex queue 또는 Claude inbox write 성공은 전달 증거로 쓰지 않습니다. 메시지는 발신·수신 host/session, 본문, 생성·만료·claim·ACK 시각과 전달 횟수·최초 전달 시각을 저장하고 개인 키, broker token, inbox token과 socket 경로는 넣지 않습니다. 본문 4096 UTF-8 byte, TTL 30초~24시간, spool 1000개·4 MiB 제한을 적용합니다. claim은 최대 10개를 고르되 JSON escape와 metadata를 포함한 실제 UTF-8 응답이 32 KiB를 넘기 전에 batch를 자릅니다. 2.x의 broker와 client는 요청·응답 한 줄을 받은 byte 그대로 모아 줄바꿈을 포함한 크기가 32 KiB 이하일 때만 받고, 완성된 줄을 한 번에 UTF-8로 풀어 TLS 조각 경계에 걸린 문자도 깨지지 않게 했습니다(3.0부터 broker·TLS 연결은 없습니다). 호출자는 대상 host의 주입 한도에 맞춰 `maxMessages`와 UTF-16 code unit 기준 `maxBodyChars`를 더 낮출 수 있으며, 번들 hook은 한 번에 한 메시지만 claim합니다. 최종 envelope는 8192 UTF-8 byte 이내로 검사하며, JSON 이스케이프로 넘치는 본문은 명시적인 base64-utf8 인코딩으로 무손실 전달합니다. 원문 digest와 BEGIN/END 경계·ACK 안내는 유지합니다. 일반 사용자 입력, wake와 도구 경계의 구분은 [입력 출처와 작업 경계](input-boundaries.md)를 따릅니다.

쉬는 세션 깨우기는 hook이 요청을 붙들고 기다릴 수 있는 호스트(Claude Code의 `asyncRewake`)에만 있습니다. 서버는 그 요청을 DB 연결 없이 잡아 두었다가 메시지가 커밋되면 claim한 봉투를 응답 본문으로 돌려주고 hook이 출력합니다(`WakeController`, `WakeWaiters`). 그 밖의 호스트는 다음 hook 경계에서 받습니다.

같은 OS 사용자 권한의 악성 프로세스는 상태 폴더를 읽거나 바꿀 수 있으므로 막지 못합니다. peer 메시지는 승인·권한·외부 변경 의사를 대신하지 않습니다.

## 첨부

단계 결과의 근거 파일은 첨부로 보관하며, 첨부 하나는 파일 하나 이상의 묶음입니다. 표(`attachment`, `attachment_file`, `attachment_storage`, `upload_staging`)에는 메타데이터만 두고, 본문은 서버 자신의 폴더에 암호화한 세그먼트로 저장하며 `artifact_*` 도구로 업로드합니다. [첨부와 업로드](attachments.ko.md)를 참조하십시오.

## 플러그인 업데이트 알림

MCP 서버는 고정된 공개 저장소에서 `vMAJOR.MINOR.PATCH` 형식의 안정 tag만 확인합니다. 성공한 결과는 `sennard.plugin_update_state`에 24시간 동안 보관하고, 실패하면 마지막 성공 결과를 유지한 채 1시간 뒤 다시 시도합니다. 업데이트 확인 오류는 workflow 상태나 도구 결과를 바꾸지 않습니다.

설치된 버전보다 높은 tag가 있고 아직 안내하지 않은 버전이면 기존 MCP 도구 결과 뒤에 `plugin-update-notice` content block을 붙입니다. 같은 버전의 안내는 DB 전체에서 한 번만 claim합니다. `check_for_updates`에 `force: true`를 전달하면 TTL을 무시하고 다시 확인할 수 있습니다. 서버는 설치 파일이나 마켓플레이스 설정을 수정하지 않으며 `automaticInstall`은 항상 `false`입니다.

고위험 변경의 완료 판정은 오케스트레이터가 내리지 않습니다. 최종 대상과 증거를 확인한 독립 감사 스킬의 결과를 그대로 통합합니다. `mutation-risk-preflight`의 통과는 실행 승인이나 감사 통과를 대신하지 않습니다.

MCP는 적대적인 호출자를 인증하는 보안 경계가 아닙니다. `verified`, 첨부의 선언 digest, auditor ID는 전문 스킬이 직접 확인한 뒤 제출하는 신뢰 입력이며, MCP는 그 선언의 구조와 단계 불변조건을 검사합니다. 실제 신원 인증이나 원자료 무결성이 필요한 배포에서는 인증된 외부 신원·증거 서비스를 추가해야 합니다.

기존에 서명된 미시작 계획은 초기 접수 요건이 달라 새 정규 계획과의 비교에서 `LEASE_CONFLICT`가 날 수 있습니다. 원계획·실행 기록·상태 원장은 보존하고 현재 호출의 새 관측으로 계획을 다시 만듭니다. 이미 시작한 실행의 단계 요건은 저장된 계획을 따르며, 이 변경은 운영 데이터나 복구 기록을 다시 쓰지 않습니다.

## 검사 범위와 한계

MCP 서버는 `SkillDescriptor.v2`의 `capability`, 실행 단계, `artifact` 의존성을 읽어 계획을 만듭니다. 계획에는 스키마 체크섬과 HMAC 서명이 포함되며, 서버는 다음 항목을 검사합니다.

- 계획을 시작한 뒤 내용이 바뀌지 않았는지
- 각 단계가 정해진 순서와 `revision`에 맞게 제출됐는지
- 각 provider의 결과가 선언된 스키마와 상태 매핑을 따르는지
- 다음 단계에 필요한 산출물과 검증 근거가 준비됐는지
- 독립 숙고와 필수 감사의 대상이 현재 결과와 일치하고 정해진 조건을 충족하는지
- 해결되지 않은 차단 사유가 남아 있지 않은지
- 새 orchestrated 실행이 root에 결속된 일회용 lease를 사용하고 root의 frame에 고정된 epoch당 시도 예산(기본 3회, 관리자 페이지에서 바꾼 값은 새로 여는 root부터)과 frame 불변조건을 지켰는지

Convergence root, epoch, attempt, lease, review와 workflow 연결도 workflow 표에 append-only 이력으로 저장되므로 서버가 다시 시작돼도 예산과 활성 attempt를 유지합니다. 서버는 OS에 등록된 사용자 서비스로 상주하고, 호스트는 등록된 HTTP MCP 주소(Codex `.mcp.json`, Claude Code plugin manifest, Antigravity `mcp_config.json`)로 연결합니다. 관리자 페이지 포트는 [관리자 서명 기록](admin-log.ko.md#페이지-서버)을 봅니다.

오케스트레이터는 설치 시 노출된 스킬 설명에서 필요한 capability 후보를 고른 뒤 MCP 도구 `run_skill_check`의 orchestrator `query-registry` 명령으로 활성 provider의 실행 메타데이터만 조회합니다. 후보를 정하지 못한 경우에만 `--all` compact catalog를 사용하며, 전체 `skills/registry.json`을 모델 입력으로 전달하지 않습니다.

공개 MCP 도구의 응답 옵션을 생략하면 기존과 같은 전체 결과와 convergence 이력을 반환합니다. 오케스트레이터의 정상 경로는 `responseMode: "compact"`와 `detail: "compact"`를 사용해 plan, 누적 `stageResults`, provider output, task/frame 원문과 전체 이력을 제외한 고정 크기 요약만 받습니다. 오류 원인, 과거 결과 또는 감사 자료가 필요할 때만 해당 상태를 `full`로 다시 조회합니다.

실행 중인 run, 현재 `revision`, run ID sequence, 계획 서명 키와 플러그인 업데이트 확인 상태는 저장소에 있으므로 서버를 다시 시작해도 이어서 처리할 수 있습니다. 업데이트 상태에는 버전·tag·commit, ETag, 확인 시각, 다음 확인 시각, 마지막 안내 버전과 오류 코드만 들어갑니다. descriptor에 `receiptPolicy.mode: reference-only`를 선언한 provider는 저장 전에 닫힌 output schema, digest·artifact reference·고정 토큰만 허용하며 note, locator, findings, blockers와 error의 자유 텍스트도 거부합니다. `actorIdPointer`와 `uniqueness: run`을 함께 선언하면 canonical lowercase UUID actor ID의 run 내 재사용도 서버 재시작 후까지 거부합니다. 이 정책은 원문 비저장을 위한 구조적 저장 경계일 뿐 신원을 인증하지 않습니다. 인증된 신원, 암호화된 장기 보존이나 적대적 환경에서도 보장되는 증거 무결성이 필요하다면 별도의 신원·증거 저장소를 연결해야 합니다.

## 공통 엔지니어링 지식

`test-engineering`과 `code-review`는 2.8.1에서 소스 패키지 `sennard-engineering-practices` 0.1.0으로 편입했습니다. 공통 지식은 8개 모듈이며, 각 모듈은 담당 스킬의 `references/`에 있고 담당 스킬이 현재 작업에 필요한 모듈만 읽습니다. 규칙 카드 원본인 `catalog.json`만 `skills/orchestrator/references/engineering-practices/`에 남아 서버가 읽습니다.

| 모듈 | 담당 |
| --- | --- |
| `test-design`, `test-proof`, `cli` | `test-engineering` |
| `code-review` | `code-review` |
| `debugging` | `blocker-diagnostician` |
| `implementation` | `minimal-implementation` |
| `dependency` | `software-security-auditor` |
| `verification` | `acceptance-evidence-validator` |
| `agent-instructions` | `prompt-engineering` |

닫힌 `engineering-*` JSON Schema 9개(`engineering-test-plan`, `engineering-test-proof`, `engineering-plan-result`, `engineering-test-result`, `engineering-review-request`, `engineering-review-report`, `engineering-review-result`, `engineering-snapshot`는 1.0.0판, `engineering-run-receipt`는 2.0.0판)가 입력과 결과를 표현합니다. 서버의 `EngineeringPractices`는 스냅샷·계획·증명·리뷰의 일관성을 Java로 검사합니다. 영수증을 만드는 `run` 명령은 personal 서버만 제공하며 호출자가 준 시험 명령을 JDK `ProcessBuilder`로 실행합니다(외부 프로그램 실행 금지의 예외, 사용자 결정 2026-11-12). 실행의 한도와 환경은 `skills/test-engineering/references/cli.md`에 있습니다. 결과가 `CONSISTENT`·`NO_BLOCKING_FINDINGS`라는 사실만으로 요구사항 충족, 실제 동작, 근거 진본성, 독립 감사 또는 릴리스 승인을 확정하지 않으며, `NOT_RUN`·누락·무결성 실패를 성공으로 바꾸지 않습니다.

## 스킬 분류

스킬은 두 묶음으로 나뉩니다. 묶음은 스킬 폴더의 `classification.json`에 있는 최상위 `category`(`agent-correction` 또는 `specialist`)이고, 서버의 `SkillCategory` enum과 JSON Schema(`contracts/skill-classification-file.v1.schema.json`)가 같은 두 값만 받습니다. 모든 스킬 폴더에 이 값이 있어야 하며 없으면 인벤토리 읽기와 저장소 시험이 실패합니다.

| 묶음 | 값 | 스킬 |
| --- | --- | --- |
| 에이전트 교정(15) | `agent-correction` | `acceptance-evidence-validator`, `blocker-diagnostician`, `change-scope-guardian`, `coordinate-subagents`, `evaluation-validity-auditor`, `independent-audit-gate`, `independent-deliberation-panel`, `instruction-scope-resolver`, `iteration-frame-auditor`, `minimal-implementation`, `mutation-risk-preflight`, `orchestrator`, `recovery-strategy-selector`, `session-board`, `task-contract` |
| 전문분야(8) | `specialist` | `cs-engineering`, `code-review`, `test-engineering`, `solid-design`, `ui-ux-design`, `korean-prose-editor`, `software-security-auditor`, `prompt-engineering` |

분류의 효과는 표시뿐입니다. README(영·한)와 관리자 페이지의 스킬 목록이 두 묶음으로 나누어 보여 주고, 스킬을 고르는 방식(`classify_skills`, `query-registry`), 검사, hook은 바꾸지 않습니다.

## 저장소 구조

```text
.codex-plugin/plugin.json        Codex 플러그인 매니페스트
.agents/plugins/marketplace.json Codex 마켓플레이스 배포 정보
.claude-plugin/marketplace.json  Claude Code 마켓플레이스 배포 정보
.gemini-plugin/plugin.json       Antigravity 플러그인 매니페스트
.mcp.json                        Codex MCP 서버 등록(생성물, 패키지에서는 비어 있음. 설치가 stdio 중계를 씀)
mcp_config.json                  Antigravity MCP 서버 등록(생성물, 패키지에서는 비어 있음. 서버 키 sennard)
hooks/hooks.json                 Codex hook 등록(생성물)
gemini-extension/                Gemini CLI 확장(gemini-extension.json, hooks/hooks.json 생성물)
hooks.json                       Antigravity hook 등록(생성물)
antigravity-connector/           Antigravity용 스킬 문구 보정(adaptations/)
claude-overlay/                  Claude Code 전용 파일과 스킬별 문구 보정(adaptations/)
claude-plugin/                   생성된 Claude Code 배포물(직접 수정 금지)
skills/                          오케스트레이터와 전문 스킬, registry, source lock
contracts/                       스킬·도구가 공유하는 JSON Schema
locales/                         메시지 카탈로그
java/                            상주 서버, 저장소, 설치 도구, 저장소 도구(sennard-repo-tools)
runtime/                         엔지니어링 계약 잠금, 용어집, 모델 프리셋 자료
release/version.json             플러그인 현재 버전의 단일 소스
tests/                           스킬별 시험 사례
docs/                            사용자·기여자 문서, 보관 기록, 릴리스 노트
```

`skills/orchestrator/`는 요청 분류, 실행 순서 결정, 입출력 전달, 결과 통합만 담당합니다. 전문 판단과 감사 결론은 각 전문 스킬이 맡습니다. MCP 서버는 스킬 이름으로 분기하지 않고 `skills/registry.json`에 선언된 provider의 `capability`와 계약을 사용합니다.

## 상주 서비스

사용자·PC당 상주 Spring Boot 서버 하나만 상태 저장소를 엽니다. 호스트는 stdio MCP 중계를 거쳐 서버의 `/mcp`로 연결하고, 훅은 Sennard Java 클라이언트와 Codex의 MCP 도구 훅으로 요청하며, 플러그인에는 실행 코드를 두지 않습니다. 서버의 포트는 시작할 때마다 운영체제가 정하며 상태 폴더의 `server.properties`에 알립니다. 상태 폴더 하나에는 서버가 하나만 실행됩니다(`ServerInstanceLock`).

설정은 로컬 H2에 저장하고 업무 저장소는 별도로 연결합니다. MCP·hook 포트와 관리자 페이지 포트는 같은 Tomcat의 두 커넥터이며 `127.0.0.1`에만 묶입니다. 설치·시작·업그레이드는 `sennard` 명령(`java/sennard-personal`의 `SennardCli`, `Installer`)이 맡으며 OS 서비스에 등록하지 않습니다. 절차는 [업데이트와 이전](../user/update.ko.md)에 있습니다.
