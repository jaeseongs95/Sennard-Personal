[English](release.md)

# 발행

판 번호, 릴리스 노트, 발행 전 점검, 공급망 메타데이터를 정리합니다. 발행(main 병합, tag, GitHub Release, 설치)은 사용자의 명시적 승인이 있을 때만 합니다.

발행은 gitflow 순서를 따릅니다. `develop`에서 `release/<판>` 브랜치를 만들어 판 번호, 릴리스 노트, 발행 전 수정만 넣습니다. 발행 전 점검을 통과하면 `release/<판>`을 `main`에 병합하고 그 커밋에 `v<판>` 태그를 단 뒤, `release/<판>`을 `develop`에 다시 병합합니다. 발행된 판의 긴급 수정은 `main`에서 만든 `hotfix/<판>`에서 하고 `main`과 `develop`에 병합합니다. 브랜치 규칙 전체는 [AGENTS.md](../../AGENTS.md)에 있습니다.

## 판 번호

판 번호는 SemVer를 따릅니다. 기존 계약·스킬 이름·권한 범위·사용자 설정을 깨는 변경은 major, 호환되는 기능 추가는 minor, 수정만 있으면 patch입니다. 전문 스킬 자체의 버전과 플러그인 버전은 따로 관리합니다.

플러그인 현재 버전의 단일 소스는 `release/version.json`입니다. `:sennard-repo-tools:syncReleaseMetadata`가 현재 버전 표면(manifest·marketplace·README 두 개의 `release-version`·`release-install`·`release-agy` 표식·[로드맵](roadmap.ko.md)의 `release-version` 표식)을 생성하고, `:sennard-repo-tools:checkReleaseMetadata`는 누락·중복 표식과 drift를 실패시킵니다. 이 명령들은 tag, GitHub Release, marketplace 설치나 배포를 만들지 않습니다.

개인판은 [Sennard-Personal](https://github.com/jaeseongs95/Sennard-Personal)에서 `1.0.0`부터 시작합니다. 개인판의 태그, 릴리스 노트와 산출물은 이 저장소에서 발행합니다. 개인판 첫 릴리스는 신규 설치와 빈 업무 데이터베이스로 검증합니다. `syncReleaseMetadata`는 저장소 URL을 생성하지 않으므로 marketplace, 소스 링크, 업데이트 태그 목록 API, annotated tag URL prefix와 업데이트 알림 링크를 개인판 저장소에 맞춥니다.

## 릴리스 노트

- 위치: `docs/release-notes/release-notes-v<버전>.md`. 개인판 첫 발행은 `docs/release-notes/release-notes-v1.0.0.md`입니다. 한 파일에 영문을 먼저, 한글을 이어서 적습니다. 이전 릴리스 노트는 역사 기록으로 보존합니다.
- GitHub Release 본문은 이 파일로 만듭니다.

  ```bash
  gh release create v<버전> --notes-file docs/release-notes/release-notes-v<버전>.md
  ```

- 이미 발행한 판의 노트는 내용을 고치지 않습니다. 문서 경로가 바뀌어 상대 링크가 깨지면 링크 대상만 고칩니다.
- 노트의 언어, 절 구성과 순서는 [문서 포맷](document-format.ko.md#릴리스-노트)을 따릅니다.
- Antigravity 설치 ZIP은 release가 발행되면 `.github/workflows/antigravity-package.yml`이 만들어 그 release에 올립니다.
- 릴리스를 발행하면 `.github/workflows/server-package.yml`이 러너 하나에서 JAR와 체크섬을 만듭니다(`:sennard-personal:releaseJar`). `checkReleaseArchive`는 태그를 대조하고 정확한 JAR 파일명으로 `setup.cmd`와 `setup.sh`를 생성합니다. 워크플로는 체크섬과 두 실행 파일을 검증한 뒤 서버 자산 네 개를 첨부합니다. 태그 없이 수동으로 실행하면 실행 산출물로 보관합니다. 지원 운영체제는 동일한 JAR를 사용합니다.

## 발행 전 점검

릴리스 후보는 플러그인 manifest, 포함된 스킬, 릴리스 jar가 같은 소스 상태를 가리켜야 합니다. 변경된 계약이나 스킬이 있으면 해당 변경을 포함한 검증 결과를 남깁니다.

GitHub Release에는 `sennard-<판>.jar`, `sennard-<판>.jar.sha256`, `setup.cmd`, `setup.sh`를 첨부합니다. 사용자는 자신의 JDK 25 이상으로 실행합니다. jar는 재현 가능하게(파일 시각 없음, 항목 순서 고정) 만들어, 워크플로가 만든 jar와 관리자의 컴퓨터에서 만든 jar의 SHA-256이 같게 합니다. release를 발행하기 전에 두 SHA-256을 대조하며 다르면 발행을 멈춥니다.

릴리스 전에는 다음을 확인합니다.

- `java/`의 `./gradlew test --continue`가 JDK 25에서 통과한다. 저장소 계약 시험이 스킬 메타데이터, 레지스트리, 통합 checksum, 생성물의 일치를 확인한다.
- `:sennard-repo-tools:checkReleaseMetadata`가 `release/version.json`과 모든 현재 버전 표면의 일치를 확인한다.
- `:sennard-repo-tools:publicExport -Ptool="check --ref HEAD"`가 내보낼 파일의 분류와 비공개 이름 검사를 통과한다.
- `git diff --check`에 공백 오류가 없다.
- `sennard-personal`의 시험이 지정된 업무 데이터베이스에서 서버 재시작 뒤에도 기존 run, `revision`, run ID sequence와 계획 서명 키가 유지되는지 확인한다.
- 업데이트 확인의 24시간 캐시, 실패 후 1시간 재시도, 버전당 한 번 안내와 비차단 실패 경로를 fixture로 검증한다.
- 배포 후보의 현재 버전과 공개 저장소의 최신 안정 tag를 비교하고, 업데이트 알림이 설치 파일이나 마켓플레이스 설정을 변경하지 않는지 확인한다.
- 포함 스킬의 품질 평가 상태와 runtime 활성 상태를 공개 문서에 분리해 기록한다. 품질 게이트를 통과하지 못한 스킬은 기본적으로 비활성화하되, 사용자가 현재 후보와 남은 제한을 확인하고 활성 배포를 명시적으로 승인한 경우에는 registry, 직접 descriptor와 implicit invocation을 같은 릴리스에서 일관되게 활성화한다.
- orchestrated semantic workflow의 `bootstrapExecution`, stage별 `executionRequirement`와 `StageResult.executionContext`가 계획 HMAC·저장 receipt에 결속되고, missing/under-provisioned 실행이 각각 `BINDING_REQUIRED`/`BINDING_INVALID`로 차단되는지 확인한다. observation ID가 서버 재시작과 동시 DB 연결 뒤에도 한 번만 소비되는지, HMAC token의 same-byte 비정규 base64url 표기가 schema와 내부 검사 모두에서 거부되는지도 확인한다. strict MCP claim·guarded start·semantic stage 경계에서는 assurance가 없는 plan을 진행시키지 않는다.
- 호출자 관측이 없으면 `BINDING_REQUIRED`, 모순·재사용 오류면 `BINDING_INVALID`로 거절하는지 시험으로 확인한다. 릴리스 전에 빌드한 플러그인으로 transcript를 남기는 새 Claude Code 세션을 띄워 `plan_workflow`부터 `open_convergence_root`, `claim_workflow_attempt`, `start_guarded_workflow`, `record_stage_result`, `finalize_workflow`까지 한 흐름이 관측값으로 통과하는지, 서브에이전트가 부른 `plan_workflow`는 실제 호출 actor를 관측하고 메인 세션에 결속되는지 확인한다.
- `record_stage_result`의 `outputFile`이 digest 불일치·JSON 아닌 첨부·16 MiB 초과·없는 첨부와 다른 세션의 첨부를 거절하고, 인라인 출력과 같은 검사를 거쳐 receipt에 참조만 남기는지 테스트로 확인한다. 저장소 크기에 비례하는 stage 출력이 있는 실제 요청으로 새 Claude Code 세션을 띄워 orchestrated run이 finalize까지 가는지 확인한다.
- 비활성 스킬은 직접 호출 시 provider와 스크립트를 실행하지 않는 fail-closed 지침을 유지한다. 활성 스킬은 registry, 직접 descriptor, `agents/openai.yaml`과 `SKILL.md`의 상태가 모두 일치하는지 확인한다.
- 저장소가 전체 `WorkflowReceipt`·`StageResult`와 direct continuity snapshot의 `core`·`evidenceRefs`를 평문으로 저장한다는 사실, 확인 기반 보존·삭제 정책, 백업 표(`continuity_cleanup_backup`, `workflow_cleanup_backup`)의 처리와 OS 접근 권한 정책을 사용자 문서에서 설명한다.

공개 전에는 버전, 변경 요약, 호환성 영향, 알려진 제한을 확인합니다. 데이터베이스 스키마(마이그레이션 스크립트)가 바뀌면 이전 판의 상태 폴더 사본에 대해 릴리스 후보를 시작해 호환성을 확인하고, 복구 절차도 변경 요약에 남깁니다. 고위험 변경이 포함되면 독립 감사의 대상 식별자와 판정이 현재 릴리스 후보와 일치하는지도 확인합니다.

공개 GitHub 저장소를 만들거나 처음 push하기 직전에는 저장소 주소와 공개 범위를 다시 확인합니다. 릴리스 후에는 태그의 `.codex-plugin/plugin.json`, `.agents/plugins/marketplace.json`이 같은 버전을 가리키는지 확인합니다. 깨끗한 Codex 환경에서 marketplace를 추가한 뒤 registry의 전문 스킬 전부(4.0.0 기준 23개)와 오케스트레이터가 발견되고 연속성 MCP 도구(`checkpoint_context` 등)가 `tools/list`에 나오는지, 비활성 스킬이 MCP 라우팅에서 제외되는지 확인합니다. MCP `tools/list`, 대표 계획·실행·중단 흐름과 explicit continuity 도구도 함께 점검합니다. 설치 확인은 Claude Code·Codex·Antigravity 세 호스트에서 각각 하고, 하지 못한 호스트는 미검증으로 노트에 적습니다.

스키마를 올린 뒤에는 플러그인만 다시 설치해서는 롤백되지 않습니다. PostgreSQL 스키마를 바꾸는 판은 릴리스 노트에 되돌리는 방법을 적습니다.

## 스킬 버전과 checksum

`skills/source-lock.json`은 각 스킬의 버전과 통합본 checksum을 고정한다. 모든 스킬은 이 모노레포에서 직접 관리한다. 저장소 계약 시험이 네트워크 없이 registry, 직접 descriptor, `SKILL.md` metadata, 로컬 checksum을 교차 검사하고, 스킬을 바꾸면 `:sennard-repo-tools:updateSourceLock`으로 checksum을 갱신한다.
