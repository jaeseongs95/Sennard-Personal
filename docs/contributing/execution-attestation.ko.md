[English](execution-attestation.md)

# 실행 관측과 호스트 연결

실행 관측은 서버가 검증한 호출자(`VerifiedCaller`)의 `execution`에서 온다. 호스트별 caller ingress가 그 값을 호스트가 쓴 사실에서 채우고, 공통 `HostAttestationProvider`가 신뢰된 adapter로 자격을 판정하며, `WorkflowService`가 task/run/stage/revision 결속과 한 번 소비를 집행한다. 관측은 시간이 지나도 만료되지 않는다. 재사용은 한 번 소비 기록이 영구히 막고, 미래 시각의 관측만 거부한다. 도구 인자의 `executionContext` JSON이나 설정 희망값은 관측을 대신하지 않는다. 서명 token(`_hostAttestation`, `aghs1`·`aghs2`)은 없다.

## 관측과 실행 자격

- `caller.execution`은 `{model, effort, turnId, provenance}` 또는 `{unavailable: 사유}`다. 관측을 기록하는 도구(`plan_workflow`, `record_stage_result`, `classify_skills`, `record_skill_selection`)만 소비한다. 6.0.0에는 실행 하한이 없다.
- `provenance`라는 이름의 필드가 둘 있으며 서로 다른 것을 답합니다. `caller.execution.provenance`는 모델과 추론 강도가 어디서 왔는지(호스트가 보고했는지)를 말합니다. stage 결과의 `recordedBy.provenance`(`ObservedCaller`, `WorkflowCall.of`)는 서버가 *누가* 기록했는지를 어떻게 알게 되었는지를 말합니다. `HOST_OBSERVED`는 호스트가 호출에 사슬 전체를 보여 준 하위 에이전트일 때만이고, 메인(root) 세션은 호스트가 보여 주지 않은 에이전트를 대신해 기록할 수 있으므로 설계상 `UNAVAILABLE`입니다. 그래서 모델과 강도가 관측된 메인 세션도 `recordedBy.provenance: UNAVAILABLE`로 기록하며, 관측된 실행은 결과의 `execution`에 있습니다.
- 6.0.0의 `ExecutionAssurance.observe()`는 호스트가 보고한 모델과 추론 수준을 보고한 그대로 기록한다. 추론 수준은 비어 있지 않은 아무 문자열이며(보고가 없거나 공백이면 `null`과 참고 `HOST_EFFORT_UNREPORTED`), 모델 등급은 서버가 모르므로 `null`과 참고 `MODEL_NOT_IN_CATALOG`를 붙인다. 모델·추론 수준의 목록, 지원 effort, 최소 effort 판정은 없다.
- `observationId`는 (host, sessionId, actor, callId)의 hash다. 같은 호스트 호출을 다시 보내도 두 번째 소비는 workflow 원장에서 거부된다. 호출에 ID를 주지 않는 호스트(agy)는 PreToolUse 훅이 남긴 행에서 호출 ID를 얻으므로(아래 표) 그 행을 찾았을 때만 관측이 생기며, `HOST_CALL_ID_UNAVAILABLE`은 ID도 그런 행도 없는 호출의 진단이다.

## 배포물의 연결

| 호스트 | 관측 출처 | 한계 |
| --- | --- | --- |
| Codex | `.mcp.json`의 정적 머리글 `Sennard-Host-Label: codex`. 서버가 `tools/call` `_meta`의 `x-codex-turn-metadata`(`model`, `reasoning_effort`, `turn_id`)와 `callId`를 읽는다. hook이 없다 | model이 없으면 `CODEX_EXECUTION_META_MISSING`. effort가 없으면 `reasoningEffort: null`로 관측한다. child도 자기 thread의 값을 쓴다 |
| Claude Code | manifest의 정적 머리글 `Sennard-Host-Label: claude`. PreToolUse governance hook이 effort(`effort.level`)와 model(발신 transcript 항목, 없으면 `SessionStart`·`PostModelSwitch`가 기록한 세션 model)을 admission 행에 기록하고(발신 항목은 0.3초까지 기다리고, 대신할 세션 model이 없으면 5초까지 기다린다), 서버가 `claudecode/toolUseId`로 그 행을 한 번 회수한다 | model을 관측하지 못하면 `CLAUDE_MODEL_UNOBSERVED` 등. effort가 없으면 `reasoningEffort: null`로 관측한다. transcript-latest 추정은 하지 않는다 |
| Antigravity | `mcp_config.json`의 `Sennard-Host-Label: agy` 머리글로 어댑터를 선택합니다. MCP 호스트는 `_meta`에 대화, 루트, 하위 대화인 경우 부모를 전달합니다. 서버는 최근 30초 이내의 PreToolUse 관측 중 원래 대화, 도구, 정규화한 인자가 같은 관측을 가져옵니다. 훅은 호스트가 제공한 모델과 추론 수준도 기록합니다. | 도구 관측이 없거나 서로 충돌하면 `HOOK_OBSERVATION_UNMATCHED`로 결속 없이 응답합니다. 서로 다른 대화에서 동일한 도구 입력이 겹치면 관측이 만료될 때까지 어느 호출인지 확정할 수 없습니다. 한 대화가 같은 호출을 재시도하면 한 호출만 그 관측을 가져갑니다. |

호스트 이름표가 없거나 어댑터가 모르는 값이면 처음 보는 호스트로 처리한다. 신뢰 등급은 `unavailable`이며 `sessionRef`(`open_session`으로 발급)로 세션을 지정한다.

서버는 모델 등급을 정하지 않는다. `modelClass`는 `null`로 기록하고, 모델 이름 목록이나 등급 mapping을 읽지 않는다. 보고된 추론 수준은 무엇이든 그대로 기록하고, 모델의 지원 목록이나 minimum으로 실행 자격을 거부하지 않는다. 추론 수준이 없으면 `reasoningEffort: null`로 기록하고 다른 모델·턴의 값으로 보충하지 않는다.

## 실패와 검증

호출자를 관측하지 못하면 `BINDING_REQUIRED`(`CALLER_PROVENANCE_UNAVAILABLE`), 호스트 사실이 모순되거나 자격·재사용 오류면 `BINDING_INVALID`다. 실행만 관측하지 못한 호출은 신원을 유지한 채 그대로 받아들여진다. 6.0.0에는 실행 하한이 없기 때문이다(아래 참조). 같은 OS 사용자 권한으로 DB에 쓰거나 서버의 MCP 경로를 직접 호출하는 프로세스와의 격리는 보장하지 않는다.

단위 fixture의 synthetic `_meta`와 실제 설치 호스트의 관측은 서로 다른 검증이다. 릴리즈·설치 담당자는 실호스트(설계 6.2)에서 관측과 guarded workflow 완료를 따로 확인해야 한다.

## 단계 추천의 근거와 범위

계획에는 모델 등급·추론 수준의 하한 필드가 없다(모델 선택은 모델이 판단할 일이므로 하한 판정을 두지 않는다). 어떤 에이전트·모델이 와도 실행하며, 루트·하위 에이전트 어느 쪽이 기록하든 관측한 모델 등급·추론 수준(미관측 포함) 때문에 `record_stage_result`·`finalize_workflow`가 거부되지 않는다. 단계 요구는 `policyId`, `kind`, `observationRequired`만 가지며(스킬의 검사만으로 결정되는 `deterministic` 단계는 관측을 요구하지 않고, 그 밖의 `semantic` 단계는 요구한다), 관측값은 기록만 한다. 관측 자체(호출 결속·신선도·형식)는 계속 검사하고, caller가 입력한 희망값을 실제 관측으로 바꾸지 않는다. 감사 의무·독립성·산출물 검사는 그대로 집행한다.

TaskEnvelope의 `constraints`는 문자열 배열이고 provider의 `gate`는 감사 조건이다. 별도의 명시 모델 하한 입력 계약은 없다. 생성된 단계 요구는 `taskDigest`와 전체 계획 서명에 결속되고 claim 시 작업에서 다시 계산해 대조된다. caller가 서명된 값을 바꾸면 무결성 검사에서 거부한다.
