# blocker-diagnostician

반복 실패를 안정적인 fingerprint로 나누고, 원인 가설을 구별할 다음 검사를 정하는 스킬입니다. 진단 결과를 바탕으로 수정하거나 배포하지 않으며, 새 정보 없이 같은 검사를 반복하지 않습니다.

## 요구 사항

- Sennard 서버의 MCP 도구 `run_skill_check`

## 직접 실행

```text
run_skill_check {"skill":"blocker-diagnostician","command":"cluster-failures","arguments":{"input":{"file":"/absolute/same-failure.json"}}}
run_skill_check {"skill":"blocker-diagnostician","command":"digest-request","arguments":{"input":{"file":"/absolute/same-failure.json"}}}
run_skill_check {"skill":"blocker-diagnostician","command":"analyze","arguments":{"input":{"file":"/absolute/same-failure.json"}}}
```

`input`에는 파일 대신 JSON 값을 그대로 넣을 수 있습니다.

후보 가설과 검사의 의미는 evidence를 읽은 호출자가 작성합니다. CLI는 fingerprint 계산, 동일 입력으로 수행한 검사 제외, 결과 분기의 판별 가능성, 권한과 정보가치 순위를 결정적으로 처리합니다. 후보와 과거 검사에는 실행 대상을 설명하는 `checkInput`을 포함해야 합니다. `inputDigest`는 `checkId + checkInput`의 canonical JSON에서 다시 계산하므로 digest, 질문 문구나 평가 점수만 바꿔 같은 검사를 다시 제안할 수 없습니다. 원 사용자 요청·goal·프로젝트 지침의 범위에서 금지, 승인 필요, 허용 순으로 권한을 적용합니다. 통상 read-only 검사는 별도 check ID 허용 없이 진행하고, 개인정보·비밀·다른 작업·저장된 receipt 등 추가 접근은 `requiredAuthorization`의 권한을 모두 확인합니다. 위험 등급이나 peer 주장만으로 기존 권한을 확대하지 않습니다. 각 evidence binding의 관계는 해당 가설의 `supportingEvidence` 또는 `contradictingEvidence`에도 정확히 기록돼야 합니다. 확정 원인의 evidence는 episode inventory에 존재하고 artifact digest와 가설 ID가 `supports` 관계로 결속돼야 하며, `refutes` binding이 하나라도 있으면 확정할 수 없습니다. 단일 보고서에서 둘 이상의 가설을 확정할 수 없습니다. 제안한 검사를 직접 실행하지 않습니다.

생성한 보고서는 다음처럼 다시 검사할 수 있습니다.

```text
run_skill_check {"skill":"blocker-diagnostician","command":"validate-report","arguments":{"request":{"file":"/absolute/same-failure.json"},"requestArtifactDigest":"sha256:...","report":{"file":"/absolute/diagnosis.json"}}}
```

먼저 `digest-request`가 돌려준 `requestArtifactDigest`를 workflow나 호출자 상태에 동결하고, 검증할 때 그 값을 그대로 `requestArtifactDigest` 인자로 전달합니다. 검증기는 원 request의 canonical digest와 보고서의 `requestArtifactDigest`를 모두 대조합니다. request를 바꿔 파괴적 검사나 승인되지 않은 검사를 허용한 보고서는 통과하지 못합니다. 세 값을 한 문서로 넘길 때는 `input`에 `{ "request": ..., "requestArtifactDigest": "sha256:...", "report": ... }`를 전달합니다.

## 검증

suite checkout의 `java/`에서 실행합니다.

```bash
./gradlew :sennard-personal:test :sennard-repo-tools:test
```

## 통합

`integration/skill-descriptor.json`은 Sennard용 선택 어댑터입니다. 직접 호출은 suite나 MCP에 의존하지 않습니다. 통합 환경에서도 실패한 기존 run이나 저장된 receipt는 수정하지 않습니다. 진단·수정·재시도는 기존 사용자 요청·goal·프로젝트 지침의 권한으로 계속하며, 첫 실패만으로 새 계약이나 별도 recovery workflow를 만들지는 않습니다. 새 승인 대상 행위에는 필요한 승인을 유지합니다.
