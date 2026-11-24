[English](development.md)

# 개발 참고

개발 환경, 검증 명령, 생성물, 스킬 추가 절차를 정리합니다. 기여 원칙은 [기여 안내](../../CONTRIBUTING.ko.md)에 있습니다.

## 개발 환경과 검증 명령

JDK 25와 저장소의 Gradle Wrapper를 씁니다. Wrapper를 시작할 JDK 17 이상(`JAVA_HOME`이나 `PATH`)만 있으면 되고, JDK 25는 Gradle 툴체인이 찾거나 내려받습니다([시험 실행](../../CONTRIBUTING.ko.md#시험-실행)). 명령은 `java/`에서 실행하며 `java/sennard-repo-tools/build.gradle.kts`가 저장소 도구 작업의 정본입니다.

| 명령 | 하는 일 |
| --- | --- |
| `./gradlew test` | JUnit 시험. 저장소 계약, 메시지 카탈로그, 도메인 중립 문구, 스킬 검사와 지정된 업무 데이터베이스 시험을 포함합니다 |
| `./gradlew :sennard-personal:releaseJar` | 릴리스 jar 하나(`sennard-<판>.jar`, 재현 가능한 Spring Boot jar)와 그 `.sha256`을 `build/release`에 생성 |
| `./gradlew :sennard-repo-tools:hostHookFiles` | 호스트 어댑터 출력으로 훅 정의 파일 생성 |
| `./gradlew :sennard-repo-tools:claudePlugin`, `:sennard-repo-tools:checkClaudePlugin` | Claude Code 배포물 생성·검사 |
| `./gradlew :sennard-repo-tools:antigravityPackage -Ptool="--ref <ref> --out <zip>"` | Antigravity 설치 묶음 생성 |
| `./gradlew :sennard-personal:jarRun` | 이 빌드의 릴리스 jar를 사용자가 실행하듯, ASCII 밖의 이름을 가진 폴더에서 파일 이름으로 실행(install, status, start, stop, upgrade, uninstall). `test`에 포함되지 않습니다 |
| `./gradlew :sennard-repo-tools:checkWhitespace -Ptool="--base <sha>"` | `git diff --check`가 하는, 추가된 줄의 공백 검사(기준이 없으면 `HEAD`를 그 부모와 비교) |
| `./gradlew :sennard-repo-tools:checkReleaseArchive`, `:sennard-repo-tools:checkReleaseChecksums` | 릴리스 워크플로의 jar 이름(태그와 대조)·체크섬·설치 실행 파일(`release/*.template`에서 만든 `setup.cmd`는 CRLF, `setup.sh`는 LF) 검사(인자는 작업 설명 참조). 릴리스 첨부는 Gradle 작업이 아닙니다. 릴리스에 쓸 수 있는 작업은 하나뿐이며, 저장소를 내려받지 않고 검사를 마친 파일을 `gh release upload`로 올립니다 |
| `./gradlew :sennard-repo-tools:syncReleaseMetadata`, `:sennard-repo-tools:checkReleaseMetadata` | 버전 표면 동기화·검사 |
| `./gradlew :sennard-repo-tools:updateSourceLock` | `skills/source-lock.json`의 통합 checksum 갱신 |
| `./gradlew :sennard-repo-tools:updateClassificationDigests` | 스킬 `classification.json` span의 digest를 인용한 파일에서 다시 계산(줄·바이트 범위는 사람이 정하며 옮기지 않고, 파일을 벗어나면 실패) |
| `./gradlew :sennard-repo-tools:acceptTranslation` | 원문 메시지 카탈로그가 바뀐 뒤 번역 잠금 갱신 |
| `./gradlew :sennard-repo-tools:publicExport -Ptool="check --ref HEAD"` | 공개 내보내기 분류·검사(공개 트리에는 이 작업이 없음) |

전체 검증은 `./gradlew test --continue`, 저장소 루트의 `git diff --check`입니다. 어떤 시험을 어디서 돌리는지는 [시험과 CI](testing-and-ci.ko.md)에 있습니다.

## 생성물

`claude-plugin/`과 훅 정의 파일(`hooks/hooks.json`, `hooks.json`, `claude-overlay/hooks/hooks.json`, `gemini-extension/hooks/hooks.json`)은 생성물이며 직접 고치지 않습니다. 생성물이 포함하는 원본(`contracts/`, `skills/` 등)을 바꾸면 다음으로 다시 만들어 함께 커밋합니다.

```bash
cd java
./gradlew :sennard-repo-tools:hostHookFiles :sennard-repo-tools:updateSourceLock :sennard-repo-tools:updateClassificationDigests :sennard-repo-tools:claudePlugin
```

공용 원본과 호스트별 부분의 경계는 [AGENTS.md](../../AGENTS.md)의 「Claude Code 배포물」 절을 따릅니다.

## 스킬 추가

모든 스킬은 이 저장소의 `skills/<skill-name>/`에서 직접 관리합니다. 새 전문 스킬은 다음을 같은 변경에 둡니다.

- `SKILL.md`와 참조 파일, 필요하면 `integration/skill-descriptor.json`
- `skills/registry.json`의 provider 항목
- 결정적 검사가 필요하면 `java/sennard-server`의 `SkillCheck` 빈, `java/sennard-personal`의 JUnit 시험, `tests/<skill-name>/fixtures`의 정상·경계·실패 사례
- `:sennard-repo-tools:updateSourceLock`으로 갱신한 `skills/source-lock.json`

저장소 계약 시험(`RepositoryContractTest`)이 스킬 메타데이터, 레지스트리, source lock의 일치를 검사합니다.

## 계약과 버전

공개 계약은 `contracts/`에 JSON Schema 2020-12로 정의되어 있습니다. 기존 버전과 호환되는 스킬 추가는 `minor`, 동작 수정은 `patch`, 계약·권한·식별자의 호환성을 깨는 변경은 `major` 버전으로 관리합니다.
