# Independent Audit Gate

`independent-audit-gate` is a skill that checks whether a high-risk change has received an independent, evidence-based audit before it is marked complete.

`mutation-risk-preflight`가 고위험으로 분류한 변경을 완료하기 전에 사용합니다. 구현에 참여하지 않은 감사자가 최종 변경과 검증 자료를 직접 확인하고 `PASS`, `FAIL`, `BLOCKED` 중 하나로 판정합니다. 실제 상태를 바꾼 뒤에는 실행·배포 식별자, 영향 범위, 부분 실패와 복구 필요성도 확인합니다.

## 언제 사용하나요

`mutation-risk-preflight`의 고위험 분류에 해당하는 변경을 실행하거나 릴리스하려 할 때 맞습니다. 분류 목록은 그 스킬에만 있습니다.

단순 조사, 저위험 수정, 일반 코드 리뷰, 구현할 대상이 없는 설계 토론에는 사용하지 않습니다. 복잡한 판단을 여러 관점에서 논쟁하고 합의안을 만드는 `independent-deliberation-panel`과도 역할이 다릅니다. 이 스킬은 패널이나 Judge를 구성하지 않고, 최종 변경에 독립 감사와 완료 게이트가 제대로 적용됐는지만 다룹니다.

## 설치와 경로

`independent-audit-gate`는 Sennard 플러그인에 포함되어 있으므로 별도 clone이나 스킬 설치가 필요하지 않습니다. Codex에서는 플러그인 marketplace를 추가한 뒤 suite를 설치합니다.

```bash
codex plugin marketplace add jaeseongs95/sennard --ref v2.4.0
codex plugin install sennard@sennard
```

Claude Code에서는 같은 suite를 marketplace에서 설치합니다.

```text
/plugin marketplace add jaeseongs95/sennard
/plugin install sennard@sennard
```

소스 checkout에서 이 스킬은 `skills/independent-audit-gate/`에 있습니다. Claude 배포물은 Gradle 작업 `:sennard-repo-tools:claudePlugin`이 `claude-plugin/skills/independent-audit-gate/`에 생성하며, 생성 경로는 직접 수정하지 않습니다.

## 사용

직접 호출하려면 요청에 `$independent-audit-gate`를 넣습니다.

```text
$independent-audit-gate를 사용해 이 권한 변경의 최종 diff와 테스트 결과를 독립 감사하고 완료 가능 여부를 판정해 줘.
```

```text
$independent-audit-gate로 이 마이그레이션의 rollback 검증과 배포 후 상태를 확인해 줘.
```

자동 호출도 켜져 있습니다. 고위험 변경을 실행·배포·완료하려는 요청과 일치하면 Codex가 이 스킬을 선택할 수 있습니다.

## 판정

- `PASS`: 독립성, 최종 대상 일치, 필수 증거, 발견사항 처리와 재감사를 모두 확인했습니다.
- `FAIL`: 확인된 열린 `blocking` 발견사항이 있습니다.
- `BLOCKED`: 감사자, 최종 대상 또는 필수 증거를 확보하지 못해 판정을 마칠 수 없습니다.

테스트가 성공했다는 이유만으로 `PASS`하지 않습니다. 감사 뒤 동작이나 검증 근거가 바뀌면 이전 판정은 효력을 잃으며, 변경된 범위를 다시 감사해야 합니다.

## 개발 검증

suite checkout의 `java/`에서 `./gradlew :sennard-repo-tools:test`를 실행해 스킬 경로, 메타데이터와 로컬 링크를 검사합니다. 이 검사는 감사 내용의 사실성이나 실제 에이전트 독립성을 자동으로 증명하지는 않습니다.

이 스킬은 `independent-deliberation-panel`을 개발 검토에만 사용하며 런타임 의존성으로 두지 않습니다.

## 라이선스

[AGPL-3.0-or-later](../../LICENSE)
