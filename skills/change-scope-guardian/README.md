# change-scope-guardian

run의 변경 기록(계획서, 기준선, 훅과 재검사가 남긴 변경)을 읽어 run의 write target 아래에서 계획 밖이거나 제외된 경로의 수정을 찾는 스킬입니다. 변경 기록은 write target 아래만 보므로 그 밖의 수정은 찾지 못합니다. 에이전트가 write target 밖을 직접 쓰는 것은 편집 게이트가 막는 몫이고, 빌드나 외부 프로그램이 write target 밖에서 바꾼 것은 이 검사가 보지 않습니다. 파일을 되돌리거나 수정하지 않으며 경로, 상태와 checksum만 다룹니다. 버전 관리 도구는 필요하지 않습니다.

## 요구 사항

- Sennard 서버의 MCP 도구 `run_skill_check`
- 변경 계획을 낸 run: `submit_change_plan`으로 계획을 내고 승인되어 기준선이 잡힌 workflow (`workflowId`)

## 직접 실행

기준선은 계획이 승인될 때 run의 변경 기록에 남습니다. `capture-baseline`은 그 기록을 읽어 `WorkspaceBaseline.v1`로 돌려줄 뿐 기록을 바꾸지 않습니다. 요청에는 `workflowId`와 task envelope를 담습니다.

```text
run_skill_check {"skill":"change-scope-guardian","command":"capture-baseline","arguments":{"input":{"file":"/absolute/request.json"}}}
```

구현 뒤에는 `workflowId`, 작업 영역의 절대 경로 `workspaceRoot`(task envelope의 범위 규칙이 이 폴더 기준이다), task envelope, baseline과 외부에서 동결해 둔 `baselineArtifactDigest`를 담은 요청을 검증합니다. `compare`는 먼저 쓰기 범위를 다시 검사해 기록을 최신으로 만든 뒤 보고서를 만듭니다. 이 digest가 없거나 baseline의 값과 다르면, 또는 baseline이 그 run의 기록과 다르면 비교하지 않습니다.

```text
run_skill_check {"skill":"change-scope-guardian","command":"compare","arguments":{"input":{"file":"/absolute/verify-request.json"}}}
```

저장된 baseline이나 report의 checksum과 판정 일관성은 다음 명령으로 확인합니다. 입력 JSON은 `artifact`와 신뢰 경계 밖에서 동결한 `expectedArtifactDigest`를 담아야 합니다. artifact 안의 checksum을 읽어서 같은 입력에 복사하는 방식은 변조를 막지 못합니다.

```text
run_skill_check {"skill":"change-scope-guardian","command":"validate-report","arguments":{"input":{"file":"/absolute/validation-request.json"}}}
```

`input`에는 파일 대신 JSON 값을 그대로 넣을 수 있고, 결과는 공통 봉투(`schemaVersion`, `ok`, `data`, `error`)의 `data`에 `{exitCode, value}`로 받고, 거절된 입력은 `ok`가 false인 `error`로 받습니다. 작업 영역의 파일은 바꾸지 않습니다.

## 개발

suite checkout의 `java/`에서 실행합니다.

```powershell
./gradlew :sennard-personal:test :sennard-repo-tools:test
```

`integration/skill-descriptor.json`은 추후 Sennard 편입에만 사용합니다. 이 디렉터리를 제거해도 직접 실행과 테스트는 동작합니다.
