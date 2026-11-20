# Repository Guidelines

## 구조와 책임

이 저장소는 열 개 전문 스킬, 한국어 산문 워크플로, 오케스트레이터와 로컬 MCP 서버를 함께 배포하는 플러그인 모노레포다.

```text
.codex-plugin/plugin.json        # 플러그인 매니페스트
.claude-plugin/marketplace.json  # Claude Code 마켓플레이스 배포 정보
.gemini-plugin/plugin.json       # Gemini / Antigravity 플러그인 매니페스트
mcp_config.json                  # Gemini / Antigravity MCP 서버 설정
GEMINI.md                        # Gemini / Antigravity 프로젝트 지침
claude-overlay/                  # Claude 전용 파일과 스킬별 Claude 보정(adaptations/)
claude-plugin/                   # 생성된 Claude Code 플러그인(직접 수정 금지)
.agents/plugins/marketplace.json # 마켓플레이스 배포 정보
contracts/                       # 공유 JSON Schema 계약
java/                            # 상주 서버·설치기·저장소 도구와 시험(Gradle)
runtime/                         # 배포하는 공용 참고 자료
skills/orchestrator/             # 요청 분류와 실행 흐름 연결
skills/<skill-name>/             # 독립된 전문 스킬
tests/                           # 스킬별 fixture, 사례, 파이썬 검사기
```

전문 스킬은 한 가지 역할과 독립 호출 가치를 유지한다. 오케스트레이터는 요청 분류, 실행 순서, 공통 입출력, 중간 검증과 결과 통합만 맡는다. 필수 순서와 불변조건은 지침만으로 두지 말고 스크립트, schema 또는 MCP 실행 계층에서 강제한다.

공용 스킬, 계약, MCP 도구와 런타임은 특정 AI 제품이나 호스트 API를 전제로 하지 않는다. 호스트 이름은 확장 가능한 식별자로 다루고, Codex·Claude Code·Gemini·Grok·Spark 등 제품별 훅, 신원 관측, 깨우기와 배포 형식은 adapter 또는 overlay 경계에만 둔다. 새 호스트를 지원할 때 공용 계약을 복제하거나 제품명을 박아 넣지 말고 같은 계약에 얇은 adapter를 추가한다. 제품 자체의 로그처럼 본질적으로 호스트 전용인 기능은 이름과 적용 범위에 그 제약을 명시한다.

이 원칙은 코드 배치뿐 아니라 동작과 검증에도 적용한다. 어떤 모델·벤더·실행 주체(메인 세션·서브에이전트)가 실행해도 같은 코드로 같은 판정을 내린다. 공용 코드에 호스트·모델·메인/서브 분기를 두지 않으며, 모델 목록이나 등급은 실행 허가 조건이 아니다. 결함 하나를 고치는 작은 수정을 포함한 모든 수정은 Claude Code·Codex·Antigravity 세 호스트에서 조사하고 실제로 시험한다. 시험하지 못한 호스트는 미검증으로 보고한다.

## 기준 상태

정확한 버전, 명령, 필수 파일은 현재 `release/version.json`, `java/`의 Gradle 빌드, `.codex-plugin/plugin.json`, `skills/registry.json`, 소스와 테스트에서 확인한다. 문서나 과거 채팅이 다르면 현재 구현을 우선한다.

작업 폴더 소스, Git commit/tag, 공개 릴리스와 Codex 설치 캐시는 서로 다른 상태다. 설치·실행 여부를 확인할 때는 버전 문자열만 보지 말고 설치 캐시의 런타임, MCP 호출과 대표 흐름을 직접 검사해 각각 보고한다.

## 개발과 검증

JDK 25와 저장소의 Gradle Wrapper를 사용한다. 주요 작업은 `java/`에서 실행한다.

- `./gradlew test`: JUnit 전체 시험(저장소 계약, 스킬 검사, 내장 PostgreSQL 포함)
- `./gradlew :sennard-repo-tools:hostHookFiles`: 호스트 어댑터 출력으로 훅 정의 파일 생성
- `./gradlew :sennard-repo-tools:claudePlugin`, `:sennard-repo-tools:checkClaudePlugin`: Claude Code 배포물 생성·검사
- `./gradlew :sennard-repo-tools:syncReleaseMetadata`, `:sennard-repo-tools:checkReleaseMetadata`: 버전 표면 동기화·검사
- `./gradlew :sennard-repo-tools:updateSourceLock`: `skills/source-lock.json`의 통합 checksum 갱신
- `./gradlew :sennard-repo-tools:updateClassificationDigests`: 스킬 `classification.json` span의 digest 갱신(줄·바이트 범위는 옮기지 않음)
- `./gradlew :sennard-repo-tools:publicExport -Ptool="check --ref HEAD"`: 공개 내보내기 분류·검사(공개 트리에는 이 작업이 없음)

전체 검증은 `./gradlew test --continue`와 저장소 루트의 `git diff --check`다. CI는 Ubuntu와 Windows의 JDK 25(Temurin)를 기준으로 한다.

## 배포물 계약

플러그인 설치 과정은 호스트에 런타임 의존성을 설치하지 않는다. 플러그인에는 훅 정의, 스킬 문서와 참조 파일, MCP 연결 정보만 두고, 제품 코드는 상주 서버 하나에 둔다. 스킬의 결정적 검사는 MCP 도구 `run_skill_check`로 실행하며 스킬 폴더에 실행 스크립트를 넣지 않는다.

스킬이나 계약을 바꾸면 같은 변경에서 `:sennard-repo-tools:updateSourceLock`, `:sennard-repo-tools:updateClassificationDigests`와 `:sennard-repo-tools:claudePlugin`을 실행해 커밋 산출물이 같은 소스 상태를 가리키게 한다. `./gradlew test`의 저장소 계약 시험이 어긋남을 실패로 보고한다.

업데이트 확인 기능은 설치 파일이나 마켓플레이스 설정을 바꾸지 않으며 `automaticInstall: false`를 유지한다. 저장 실패나 손상된 업데이트 상태가 기존 workflow를 막아서는 안 된다. 저장소(PostgreSQL, Flyway) 변경에는 기존 데이터 보존, 두 연결의 경합, 성공·실패 경합, 알림 버전의 단조 증가와 버전당 한 번 claim을 검증하는 테스트를 둔다.

## Claude Code 배포물

Claude Code 배포물은 Codex 플러그인과 서로 영향을 주지 않아야 한다. 공용 원본(`skills/`, `runtime/`, `contracts/`, `release/version.json`)은 두 배포물이 함께 쓰고, 호스트별 부분은 따로 둔다. Codex 전용 부분은 Codex가 저장소 루트를 그대로 설치하므로 `.codex-plugin/`, `.agents/`, `hooks/`, `.mcp.json`, 스킬의 `agents/openai.yaml`에 있다. Claude 전용 파일은 `claude-overlay/`에, 스킬별 Claude 문구(description과 호스트 중립 표현이 없는 문장)는 `claude-overlay/adaptations/<스킬명>.json`에 둔다. Claude 작업을 위해 Codex 전용 부분과 루트 `skills/`의 Codex 동작을 바꾸지 않는다.

`claude-plugin/`은 `:sennard-repo-tools:claudePlugin`으로만 생성하고 직접 고치지 않는다. 공용 원본을 바꾸면 같은 변경에서 다시 생성한다. 저장소 계약 시험이 생성물과 원본의 차이를 실패로 보고한다. 치환할 원문이 사라지거나 생성물에 Codex 전용 표현이 남아 생성이 실패하면 오류에 표시된 adaptation 파일을 고친다. 상태는 서버의 PostgreSQL에만 두고, 현황판에는 요청 원문·비밀을 저장하지 않으며, 세션 메시지에는 제한된 본문만 저장하고 인증 비밀은 넣지 않는다.

## 스킬과 라우팅 규칙

스킬 디렉터리는 `kebab-case`로 짓고 `SKILL.md` frontmatter의 `name`과 일치시킨다. YAML은 2칸 들여쓰기를 사용한다. 지침에는 적용 조건, 제외 조건, 입력, 출력과 실패 처리를 명령형으로 적는다. 공통 규칙은 복사하지 말고 오케스트레이터 계약이나 공유 참고 자료로 관리한다.

스킬을 추가·삭제하거나 이름, 버전, 역할을 바꾸면 같은 변경에서 `README.ko.md`의 `포함된 스킬` 표와 `README.md`의 `Included skills` 표도 갱신한다. 두 표의 스킬 수, 이름, 버전과 역할 설명은 `skills/registry.json`과 `skills/source-lock.json`에 맞추고, 스킬 링크는 저장소에 내장된 `skills/<name>/` 경로를 쓴다.
서브에이전트의 모델과 추론 수준은 위임하는 에이전트가 그 일마다 스스로 판단한 추상 등급·깊이를 호스트가 제시한 목록에서 고른다. 제품은 모델 이름, 호스트 고유 추론 수준 라벨, 역할별 고정 표, 위험에 따른 바닥을 두지 않는다. 위임 여부, 에이전트 수, 배치, 권한과 감사 요구에는 영향을 주지 않는다. 사용자 지정값과 호스트의 실제 지원 범위를 우선하며, 모델 등급·추론 수준을 이유로 위임·도구 접근·결과 기록·종결을 막지 않는다.

## 테스트 규칙

각 스킬에는 정상, 경계와 예상 실패 사례를 둔다. 오케스트레이터는 스킬 선택, 실행 순서, 입출력 계약과 중간 실패 전파를 검증한다. 네트워크, 시간과 사용자 환경 값은 fixture로 격리한다. 배포 경로나 런타임 의존성이 바뀌면 개발 저장소 테스트만으로 끝내지 말고 실제 설치 트리와 같은 clean-room 검사를 추가한다.

## 변경과 릴리스

브랜치는 gitflow를 따른다. `main`은 발행된 판만 담고, `develop`은 다음 판의 통합 브랜치다. 새 기능과 수정은 `develop`에서 `feature/<작업명>` 브랜치를 만들어 그 브랜치나 연결된 worktree에서 작업하고 `develop`에 병합한다. 발행은 `develop`에서 `release/<판>` 브랜치를 만들어 판 번호, 릴리스 노트, 발행 전 수정만 넣고, `main`에 병합해 태그를 단 뒤 `develop`에 다시 병합한다. 발행된 판의 긴급 수정은 `main`에서 `hotfix/<판>` 브랜치를 만들어 `main`과 `develop`에 병합한다. 사용자가 브랜치 이름을 지정하면 그 이름을 따른다. 이 규칙 전에 만든 `codex/`, `claude/`, `gemini/` 작업 브랜치는 병합될 때까지 그대로 둔다.

작업 전 `git status`, 관련 worktree와 적용 지침을 확인한다. 미추적 파일, 다른 작업의 커밋과 변경은 사용자 소유로 보고 보존한다. 파일과 외부 상태는 한 작업자만 쓰게 하고, 별도 worktree의 결과는 commit과 검증 근거로 통합한다.

커밋은 `feat: add acceptance evidence validator`, `docs: define skill boundaries`처럼 명령형 Conventional Commit을 사용한다. PR에는 변경 목적, 영향받는 스킬, 계약·매니페스트 변경과 실제 검증 결과를 적는다.

배포는 명시적 요청이 있을 때만 수행한다. 버전과 후보 commit을 고정하고 전체 검증, 독립 사전 감사, mutation preflight, `origin/main`과 annotated tag·GitHub Release 반영, CI 확인, 로컬 marketplace 갱신·설치, 설치 캐시와 MCP 동작 확인, 독립 사후 감사 순서로 진행한다. 감사 후 후보가 바뀌면 영향 범위를 다시 감사한다.
