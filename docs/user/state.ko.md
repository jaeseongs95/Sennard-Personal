[English](state.md)

# 상태 저장과 정리

Sennard가 이 컴퓨터에 남기는 상태의 위치, 위치를 바꾸는 설정, 오래된 상태를 지우는 방법과 복구 절차를 설명합니다.

## 저장 위치

Sennard 설정은 로컬 H2 데이터베이스에, 업무 상태는 별도로 연결한 데이터베이스에 저장합니다. 같은 PC의 모든 호스트는 같은 서버와 그 서버에 연결된 저장소를 씁니다.

상태 폴더의 기본값은 `~/.sennard`이며 `~`는 JVM의 `user.home`입니다(모든 운영체제에서 같은 질문이며 운영체제별 위치는 없습니다). 바꾸려면 환경 변수 `SENNARD_STATE_DIR`를 지정하거나, `sennard install --state-dir <경로>`로 설치하거나, 설치된 `sennard.yaml`에 `sennard.state-dir`를 UTF-8로 적습니다(위치는 아래에 있습니다). 설치할 때의 상대 경로는 명령 실행 폴더 기준의 절대 경로로 기록합니다. 시스템의 파일 이름 인코딩(`native.encoding`)으로 쓸 수 없는 경로는 `STATE_DIR_NOT_REPRESENTABLE`로 거절합니다. `SENNARD_STATE_DIR`나 `sennard.yaml`의 `sennard.state-dir`를 그 인코딩으로 쓸 수 있는 폴더로 지정합니다. 서버는 이 폴더의 `server.properties`에 주소를 알립니다([문제 해결](troubleshooting.ko.md#상주-서버) 참고). 상태 폴더 하나에는 서버가 하나만 실행됩니다.

| 데이터 | 위치(상태 폴더 기준) |
| --- | --- |
| 로컬 설정 데이터베이스(패스키, 업무 데이터베이스의 연결) | `settings/sennard-settings`(H2 파일 데이터베이스) |
| 세션과 JEV 키 재료 | `service/session-pepper.json`, `service/session-pepper.key`, `service/jev-seal.key` |

업무 데이터베이스는 상태 폴더 밖에 있습니다. 설치 폴더(기본값 `~/.sennard/install`, `SENNARD_INSTALL_ROOT`로 바꿉니다)에는 프로그램과 설정이 들어 있습니다. 시스템의 파일 이름 인코딩으로 쓸 수 없으면 `sennard install`이 `INSTALL_ROOT_NOT_REPRESENTABLE`로 끝납니다.

## 업무 데이터베이스

Sennard는 데이터베이스 드라이버를 함께 배포하지 않으며 특정 데이터베이스 제품을 정하지 않습니다. 처음 설치할 때는 Sennard 전용 빈 업무 데이터베이스를 새로 만들고 JDBC 드라이버를 준비합니다. 데이터베이스는 `sennard.yaml`이 아니라 관리 페이지의 **업무 데이터베이스** 화면에서 연결합니다([관리 페이지와 컨텍스트](admin-page.ko.md#업무-데이터베이스) 참고). 화면이 묻는 항목은 다음과 같습니다.

- **JDBC 드라이버**: 데이터베이스 제조사에서 받은 JDBC 드라이버입니다. jar 파일 하나 또는 jar가 든 폴더의 절대 경로를 적습니다. Sennard는 이 경로에서만 드라이버를 읽으며 다른 곳은 찾지 않습니다.
- **JDBC URL**: 데이터베이스의 URL이며 `jdbc:<하위 프로토콜>://호스트[:포트]/데이터베이스[?옵션]` 형태입니다. 예: `jdbc:mariadb://127.0.0.1:3306/sennard`. 비밀번호는 넣지 않으며, 비밀번호가 든 URL은 거절합니다.
- **계정**: Sennard가 쓰는 하나의 계정입니다. 이 계정이 `sennard` 스키마를 소유하며, Sennard가 그 스키마를 직접 만들고 마이그레이션합니다.
- **비밀번호 파일**: 첫 줄에 그 계정의 비밀번호가 든 파일의 절대 경로입니다. 경로만 저장하며 비밀번호는 저장하지 않습니다. 일반 파일이고 비어 있지 않을 때만 Sennard가 읽습니다(그런 파일로 가는 링크는 따라갑니다). 본인만 읽을 수 있게 두십시오. Sennard는 다른 누가 읽을 수 있는지 검사하지 않습니다(아래 「비공개 저장소」 참고).
- **보호 방식**: 평문(루프백 주소만) 또는 서버 검증(TLS)입니다. 아래 TLS 규칙을 따릅니다.

연결을 저장하려면 패스키가 필요합니다. 연결은 상태 폴더의 로컬 설정 데이터베이스(`settings/sennard-settings`)에 저장되며, 업무 데이터베이스를 지정하는 곳은 여기 하나입니다. 서버와 `sennard admin-reset`, `sennard attachments`가 모두 여기서 읽습니다. 설치 폴더가 아니라 상태 폴더에 있으므로 `sennard uninstall` 뒤에도 남고, 나중의 설치나 `sennard upgrade`가 그대로 다시 찾습니다.

연결은 같은 화면에서 언제든 바꿀 수 있으며, 데이터베이스가 준비된 상태에서도 바꿀 수 있습니다. 새 연결을 저장하기 전에 Sennard가 그 연결이 가리키는 데이터베이스에 접속해 보고 그곳에 테이블을 준비합니다. 닿지 않거나 이 점검을 통과하지 못하는 데이터베이스는 저장하지 않으며 이전 연결이 그대로 남습니다. 저장소가 준비된 동안 저장한 연결은 Sennard 서버를 다시 시작한 뒤부터 쓰입니다(`sennard stop` 다음 `sennard start`). 그때까지 화면이 그 사실을 알려 줍니다. 이전 데이터베이스의 기록은 새 데이터베이스로 옮겨지지 않습니다.

`sennard attachments check`, `rekey`, `keygen`, `purge-orphans`는 연결을 설정 데이터베이스에서 읽는데, 실행 중인 서버가 그 파일을 잡고 있습니다. 먼저 `sennard stop`을 실행합니다(그러지 않으면 명령이 그렇게 알려 줍니다). `sennard admin-reset`은 서버를 스스로 멈춥니다.

지원하는 데이터베이스 제품은 다음과 같습니다. 이 저장소의 시험이 검증하는 것은 PostgreSQL 하나입니다. MariaDB는 Sennard의 SQL 방언(`SqlBuilder`)과 아래 TLS 규칙으로 지원하지만 그 시험으로 검증하지는 않습니다.

| 제품 | URL 예 | 루프백 IP 주소가 아닌 호스트에 Sennard가 요구하는 TLS 옵션 |
| --- | --- | --- |
| PostgreSQL | `jdbc:postgresql://127.0.0.1:5432/sennard` | `sslmode=verify-full`과 `sslrootcert=<파일>` |
| MariaDB(현행 LTS) | `jdbc:mariadb://127.0.0.1:3306/sennard` | `sslMode=verify-full` |

표에 없는 제품도 확인하지 않았습니다. MySQL URL은 받아들이며(원격 호스트는 `sslMode=VERIFY_IDENTITY`), Spring Boot가 URL로 알아보는 그 밖의 제품은 루프백 IP 주소에서만 받아들입니다. 둘 다 시험하지 않았습니다.

참고 사항은 다음과 같습니다.

- TLS 옵션이 없는 URL은 호스트가 루프백 IP 주소 리터럴(`127.0.0.1` 또는 `[::1]`)일 때만 받아들입니다. 이름 `localhost`는 해당하지 않습니다.
- H2는 업무 데이터베이스가 될 수 없습니다. 로컬 설정만 담는 데이터베이스이기 때문입니다. H2를 가리키는 URL은 거부합니다.
- MariaDB와 MySQL은 `CHARACTER SET utf8mb4 COLLATE utf8mb4_bin`으로 데이터베이스를 만드십시오. Sennard는 식별자와 상태를 글자 그대로 비교하는데, 대소문자를 구분하지 않는 정렬 규칙은 그렇게 비교하지 않습니다. 계정에는 `sennard` 스키마(데이터베이스)를 만들 권한과 그 스키마에 대한 모든 권한이 필요합니다.
- 각 판은 시작할 때 자기 스키마 스크립트를 적용합니다. 업그레이드 전에 데이터베이스를 백업하십시오.

`sennard.yaml`은 설치 폴더 바로 아래에 있지 않습니다. 설치된 판의 폴더, 곧 설치 폴더 아래 `versions/<판>` 폴더에서 애플리케이션 jar 옆에 있으며 모든 운영체제에서 같습니다. 경로는 `<설치 폴더>/versions/<판>/sennard.yaml`입니다. 예를 들어 기본 설치 폴더의 개인판 1.0.0은 `~/.sennard/install/versions/1.0.0/sennard.yaml`입니다. `sennard upgrade`는 옛 판 폴더의 `sennard.yaml`을 새 판 폴더로 복사합니다.

Sennard는 상태 폴더를 만들 때 소유자 전용으로 만듭니다. 설치 프로그램, 세션 시작, 서버가 모두 같은 곳을 거쳐, 그 안에 무엇이든 쓰기 전에 만듭니다(아래 비공개 저장소 참조). 이미 있는 상태 폴더는 그대로 사용하며, 개인판은 PC 전체를 신뢰합니다.

## 비공개 저장소

상태 폴더, 그 안의 키·페퍼 파일, 설정 데이터베이스 폴더, 업무 데이터베이스의 비밀번호 파일은 Sennard 안의 한 곳을 거쳐 만들고 읽습니다. 개인판의 규칙은 다음과 같습니다.

- **Sennard가 만드는 것은 처음부터 소유자 전용입니다.** Linux와 macOS에서는 폴더 `rwx------`, 파일 `rw-------`입니다. Windows에서는 부모의 권한을 상속하지 않는 접근 제어 목록을 겁니다. 소유자에게 전체 제어를 주고, 폴더에는 시스템 계정에도 줍니다.
- **Windows에서 그 목록의 소유자는 프로세스를 실행한 사용자이며, 이름 설정이 아니라 프로세스의 접근 토큰에서 가져옵니다.** Sennard는 만들려는 폴더 안에 빈 확인용 파일을 만들어 Windows가 주는 소유자를 읽고(SID로 만든 주체이므로 이름이 같은 로컬·도메인 계정이나 Azure AD 계정을 혼동하지 않습니다) 그 파일을 지웁니다. 관리자 권한으로 시작한 프로세스는 Administrators 그룹이 새 항목의 소유자가 될 수 있습니다. 그때만 계정을 이름으로 조회하고, 찾은 계정이 토큰의 계정과 같은지 확인합니다. 어느 계정인지 시스템이 가리지 못하면 다른 계정에 권한을 주지 않고 `PRIVATE_STORAGE_USER_UNKNOWN`으로 멈추며 아무것도 만들지 않습니다. 관리자 권한 없이 Sennard를 시작하거나 폴더를 직접 만들어 두면 Sennard가 그대로 사용합니다.
- **이미 있는 것은 있는 그대로 읽습니다.** 그룹·기타 사용자·다른 계정이 쓸 수 있어도, 다른 사용자가 소유해도, 심볼릭 링크여도(링크는 따라갑니다) Sennard는 거절하지 않으며, 이미 있는 경로의 권한을 바꾸지도 않습니다.
- **남는 검사는 데이터의 정합성 검사입니다.** 파일 자리에 폴더가 있거나, 폴더 자리에 파일이 있거나, 비밀 파일이 비어 있으면 거절하고 메시지(`services.privateStorage.*`)에 경로를 알립니다.

이유: 개인판은 한 PC의 한 사용자를 위한 제품이며 PC 전체를 신뢰합니다. 같은 PC의 다른 OS 계정을 막는 장치를 두지 않으므로, 다른 계정이 닿을 수 있다는 이유로 멈추지 않습니다. PC에 다른 사람의 계정이 있고 비밀을 그들에게서 지켜야 한다면 상태 폴더와 파일의 권한을 직접 설정하십시오. 예를 들어 Linux와 macOS에서는 `chmod 700`과 `chmod 600`, Windows에서는 `icacls <경로> /inheritance:r /grant:r "%USERNAME%:F"`입니다. Sennard는 그 권한으로 동작합니다.
## 무엇이 저장되나

- workflow 저장소에는 전체 `WorkflowReceipt`가 평문 JSON으로 들어갑니다. 각 단계 결과의 provider output, 근거 메모, findings, blockers, error가 포함됩니다.
- `list_session_workflows`를 인자 없이 호출하면 현재 세션에 보관된 실행과 활성 root를 조회할 수 있습니다. `runs`에는 종료된 실행도 포함되며, `openRoots`에는 검토나 사용자 결정을 기다리는 root가 포함됩니다. 각 항목은 ID, revision, 상태를 반환합니다. 자세한 내용은 ID로 `get_workflow_status` 또는 `get_convergence_status`를 호출해 읽으십시오. 빈 배열은 조회에 성공했으나 해당 기록이 없다는 뜻입니다. 호출자 결속이나 저장소 읽기 중 문제가 생기면 오류를 반환합니다. 두 호스트가 같은 세션 ID를 보고하면 저장된 이력의 소유 호스트를 구분할 수 없어 `BINDING_INVALID`를 반환합니다.
- 작업 기록 저장소에는 상황 컨텍스트 본문과 그 이전 내용이 평문 JSON으로 남습니다. 에이전트 API는 이전 내용을 보여 주지 않지만, 같은 OS 사용자의 DB 자격 증명과 파일 직접 접근은 막지 않습니다.
- 단계 결과의 근거 항목은 결과를 기록하는 세션이 업로드 클라이언트로 등록한 첨부를 `attachmentId`와 첨부 안의 파일 번호 `seq`로 가리킵니다. 첨부는 파일 1~64개의 묶음이며, 파일을 하나씩 검증하고 ID 하나로 함께 보관합니다. 서버는 호출자 컴퓨터의 경로를 읽지 않습니다. 첨부가 곧 근거이므로, 업로드한 뒤 원본 파일을 바꾸거나 지워도 `finalize_workflow`에는 영향이 없습니다. 첨부는 설정한 첨부 폴더에 암호화해 저장하고 키는 `secrets/attachment-keys`에 두며, DB에는 메타데이터만 둡니다. `sennard.attachments.max-file-bytes`(기본 64MiB)보다 큰 파일은 업로드에서 거절합니다. 현재와 이전의 첨부 폴더, 키 폴더, DB를 함께 백업하십시오. [첨부와 업로드](../contributing/attachments.ko.md)를 참조하십시오.
- 파일 하나는 `sennard attachments upload --session-ref <sessionRef> <file>`로 올립니다. 현재 세션 참조는 `open_session`에서 받습니다. 묶음을 올릴 때는 파일마다 `sennard attachments prepare --name <상대 경로> <file>`을 실행한 뒤 모든 파일의 메타데이터를 `artifact_begin`에 한 번 전달합니다. 각 업로드 ID로 `sennard attachments send --upload-id <id> <file>`을 실행하면 multipart로 파일을 보내고 전송 상태를 출력합니다. 모든 파일을 보낸 뒤 `artifact_finish`를 호출합니다. 결과의 `evidence`, `artifacts`, `outputFile`에는 필요한 `attachmentId`, 파일 번호 `seq`, 파일의 `sha256`을 넣습니다. `priorFailure.evidenceRefs`에는 첨부 ID를 넣습니다. 전송이 중단되면 같은 ID로 `send`를 다시 실행합니다. 클라이언트는 파일을 다시 보내고 서버는 이미 저장한 바이트를 건너뜁니다. 서버 포트를 지정하려면 `--port`를 사용합니다.
- 그 밖의 저장 데이터는 애플리케이션 수준 암호화를 하지 않습니다(페이지에서 등록한 key와 첨부 파일만 암호화합니다).
- 그러므로 비밀값, 개인정보, 원시 로그·코드를 작업 기록이나 단계 결과에 넣지 마십시오.

## 오래된 상태 정리

상태 정리는 자동으로 실행되지 않습니다. 보존 기간이 지났다는 이유만으로 지워지지도 않습니다.

1. `prepare_state_cleanup`이 지울 후보를 읽기만 하고 HMAC 서명 token을 돌려줍니다.
2. 사용자가 미리보기를 확인합니다.
3. 같은 token을 `execute_state_cleanup`에 넘겨야 삭제가 시작됩니다.

token에는 유효 시간이 없습니다. 확인을 기다리는 동안 후보가 바뀌면 digest 불일치로 거부됩니다. token은 계획 ID, 정책, cutoff, 후보 digest에 결속되고 한 번만 쓸 수 있습니다.

고정 보존 정책은 다음과 같습니다.

- workflow의 terminal run과 `completed`/`abandoned` convergence root: 180일
- `active`가 아닌 continuity snapshot payload: 30일 뒤 hash tombstone으로 치환
- `active`가 아닌 continuity task와 request/tombstone/observation metadata: 180일 뒤 전체 삭제
- active workflow root, 그 root에 연결된 run, active continuity snapshot/task: 삭제하지 않음
- 세션이 결속한 root 가운데 하나라도 active이면 그 continuity task와 snapshot: 삭제하지 않음. 한 세션은 root를 여러 개 결속하고 `root_id`는 가장 최근 root만 가리키므로, 대표 root가 끝나도 다른 active root의 소유 기록(`workflow-root:<rootId>` 관측 행)은 지우지 않습니다.
- workflow metadata, run sequence, plugin update state, cleanup claim과 signing key: 삭제하지 않음

terminal convergence root와 연결된 run은 하나의 단위로 계산하고 삭제합니다. 실행 직전에 후보를 다시 계산하며 하나라도 바뀌면 stale token으로 거절합니다. workflow와 continuity의 삭제는 두 저장소의 모듈 잠금을 쥔 한 transaction이라 함께 확정되거나 함께 되돌려집니다. 중간 상태(`partial`)는 없습니다. 영수증의 상태는 `completed`이거나 지울 후보가 없었을 때의 `no-op`이고, 저장소별 `databases.*.status`는 `completed` 또는 `skipped`입니다.

### 백업

지운 행의 사본은 같은 transaction 안에서 모듈별 백업 표(`continuity_cleanup_backup`, `workflow_cleanup_backup`)에 계획 ID와 함께 남습니다. 파일 백업은 만들지 않으므로 영수증의 `backupPath`는 `null`입니다. 이 표의 행은 자동으로 지우지 않습니다. `os-managed-unverified`는 OS 계정 권한과 디스크 암호화 같은 OS 보호를 써야 하지만 서버가 그 활성 상태를 확인하지 않았다는 뜻입니다.

### 복구

Sennard에는 백업 표에서 자동으로 되살리는 명령이 없습니다. 지운 뒤 필요해진 행은 표에 남아 있는 사본을 기준으로 조사합니다.

업무 데이터베이스는 상태 폴더에 없으므로 상태 폴더 복사만으로 전체 데이터베이스가 백업되지는 않습니다. 로컬 상태 폴더와 연결한 업무 데이터베이스를 각각 보관합니다.

1. `sennard stop`으로 서버를 멈춥니다.
2. 상태 폴더를 별도 위치에 복사하고 업무 데이터베이스는 해당 관리자의 백업 절차로 보관합니다.
3. 서버를 멈춘 상태에서 서로 대응하는 상태 폴더와 데이터베이스 백업을 복구한 뒤 `sennard start`를 실행합니다.
4. `sennard status`와 workflow·continuity 조회로 복구를 확인합니다.

### 미리보기의 보호 집계

미리보기의 선택적 `rootProtection`은 workflow의 같은 조회 transaction에서 계산한 보호 집계입니다. `activeRoots`는 기존 `counts.protectedActiveRoots`와 같은 전체 활성 root 수입니다. `linkedNonTerminalRoots`, `linkedRecentRoots`, `linkedMissingReceiptRoots`는 180일이 지난 terminal root 중 연결된 run 때문에 제외된 고유 root 수입니다. 한 root가 여러 이유에 들어갈 수 있으며 `totalProtectedRoots`는 이유별 합계가 아니라 활성 root와 제외된 terminal root의 중복 없는 총수입니다. terminal root 자체가 아직 보존 기간 안에 있는 경우와 continuity task는 이 집계에 섞지 않습니다.

이 객체의 여섯 필드는 함께 있습니다. 새 미리보기의 관측된 0은 0이고, 구형 응답이나 token에 객체가 없으면 미관측(UNKNOWN)입니다. 부분 객체나 잘못된 숫자는 거절합니다. `previewCreatedAt`은 plan의 생성 시각이며, HMAC으로 결속된 같은 집계를 receipt에 복사합니다. receipt의 집계는 실행 시점의 재관측이나 삭제 성공을 뜻하지 않습니다. 실제 삭제 건수와 실패 여부는 `databases`의 status·deleted 필드로 확인합니다. 진단만 변해도 기존 후보와 연결 메타데이터가 같으면 기존 token의 실행 조건은 바뀌지 않습니다.

3.0의 타입과 plan/receipt output schema는 이 선택적 필드를 함께 지원합니다. 과거의 `additionalProperties: false` validator는 새 응답을 거부할 수 있으므로 모든 구형 소비자와의 호환을 보장하지 않습니다.
