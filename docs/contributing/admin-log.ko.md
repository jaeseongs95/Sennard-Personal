[English](admin-log.md)

# 관리자 서명 기록과 설정 읽기

관리자 페이지의 사용법은 [관리자 페이지와 컨텍스트](../user/admin-page.ko.md)에 있다. 이 문서는 페이지가 저장하는 서명 기록의 형식, 검증, 읽는 쪽의 동작, JEV 구성과 오류 코드를 정리한다.

## 페이지 서버

- 주소는 `http://localhost:<port>/`이고 포트는 고정이 아니다. 상주 서버의 내장 Tomcat에 추가 커넥터(`AdminPageAddress`)를 port 0으로 열어 서버가 시작할 때마다 운영체제가 빈 포트를 주며, 설정은 없다. 주소를 말하는 곳(훅·도구 응답의 `adminUrl`, 승인 안내, `sennard status`의 `adminPage`)과 요청의 출처·포트 검사는 모두 이 빈 하나에서 읽는다. 패스키는 포트가 아니라 호스트 이름 `localhost`에 묶이므로 포트가 바뀌어도 쓸 수 있고, 서명 기록의 `origin`은 서명한 시작의 포트를 담으며 로그를 읽을 때는 어느 포트든 받아들인다.
- 커넥터는 `127.0.0.1`에만 묶인다. Host·Origin 검사는 `GuardFilter`가 포트별로 한다.

## 정본: 패스키 서명 기록

- 사람이 바꾸는 항목의 정본은 서명 기록(로그) 하나다. 기록은 추가만 한다. 로그는 시간 순서로 이어진 두 곳에 있다. 업무 데이터베이스를 연결하기 전에 서명한 기록은 설정 데이터베이스(`pre_link_admin_records`, 「업무 데이터베이스를 연결하기 전」 참조)에 있고, 업무 데이터베이스가 준비된 때부터는 저장소의 `admin_log`에 있다. 읽는 쪽은 둘을 하나의 로그로 접는다(`AdminLogSource`). 연결 전 구간을 먼저, 그 뒤에 업무 기록을 읽는다. 업무 기록의 seq는 앞 구간의 마지막 seq만큼 밀어 하나의 순서로 만들며, 한 곳의 기록을 다른 곳으로 복사하지 않는다.
- 기록 하나는 패스키가 서명한 canonical JSON 문자열(intent) 하나다. 내용은 `{v, domain:"sennard.admin", rpId:"localhost", target, prevTargetHash, change, issuedAt}`이고 challenge는 SHA-256(intent)이다. 읽을 때 `canonicalJson(JSON.parse(s)) === s`를 강제한다.
- 대상(target)은 `keys`, `global`, `roles`, `roleBody:<roleId>`(역할 본문), `binding:<host>:<sessionId>`, `rootApproval:<approvalId>`(root 승인), `rootRelease:<rootId>`(root 해제), 그리고 설정의 `jev`(켜고 끄기·예산·프리셋), `jevKeys`, `editExempt`, `jvmPreset`(서버 JVM 프리셋, `JVM_PRESET_SET {preset}`: `JvmPreset` 상수 이름만 받는다), `database`(업무 데이터베이스의 연결, `DATABASE_LINK {driverPath, jdbcUrl, accountName, passwordRef, tls}`: 연결 전 구간만 받고 비밀번호는 담지 않는다. 아래 참조), `convergenceLimits`(수렴 상한, `CONVERGENCE_LIMITS_SET {limits: {maxAttemptsPerEpoch, maxEpochs}}`: 둘 다 1 이상의 정수이고 상한은 없다. 서명한 값이 없으면 기본 3·2이며, 서명 뒤 새로 여는 root의 frame부터 들어가고 이미 열린 root는 frame의 값을 유지한다. `ConvergenceLimits`의 `@Min(1)`이 규칙이고 0·음수·소수·문자열은 `ADMIN_CHANGE_INVALID`로 거절한다. 읽는 쪽은 `ConvergenceLimitSource`(구현 `SignedConvergenceLimits`)이다)이다. 대상마다 이전 기록의 hash를 이어 거는 사슬이라 서로 무관한 대상끼리는 경합하지 않는다.
- 업무 DB 준비 후 서명 대상 `resourceLimits`에 `RESOURCE_LIMITS_SET {limits: {csArtifactMaxBytes, maxOpenWorkflowsPerSession}}`를 저장한다. 두 값은 정수다. 파일 크기는 1,048,576~67,108,864바이트이고 기본값은 8,388,608바이트(8 MiB)다. 세션당 열린 워크플로는 1~2,147,483,647개이고 기본값은 5개다. 새 검사와 개설부터 저장 값을 적용한다. 보류·검토 대기는 개수에 포함하고 완료·취소는 제외한다. 상한을 낮춰도 기존 작업은 유지한다. 개설 응답은 현재 수와 남은 수를 알려 준다. 관련 추가 지시는 기존 워크플로에서 이어 가며 `submit_change_plan`으로 파일 계획을 수정한다. 기본값 복원은 관리자가 8,388,608과 5를 패스키로 다시 저장한다. DB 스키마 이전 없이 검증된 서명 기록으로 복원한다. 이전 바이너리는 새 서명 동작을 인식하지 못하므로 저장 이후에는 상위 버전으로 갱신한다.
- 검증 항목: client data type(`webauthn.get`), challenge, origin(`http://localhost[:port]`), rpIdHash(`localhost`), 사용자 존재·사용자 확인(UP·UV) 플래그, 서명(ES256·RS256·EdDSA, JDK 암호 API로 검증, `AdminWebAuthn`). signCount는 기록만 하고 판정에 쓰지 않는다. 페이지 서버는 저장할 때 origin이 자기 주소(`http://localhost:<이번 시작의 포트>`)와 같아야 받는다. 다른 localhost 포트에 띄운 비슷한 페이지가 받아 낸 서명으로는 저장할 수 없다. 이미 저장된 기록을 읽을 때는 포트가 시작마다 바뀌므로 localhost의 어느 포트든 받는다.
- 첫 키는 자기 서명으로 등록한다(TOFU). 그 뒤 키 추가·폐기는 유효한 키의 서명이 있어야 한다. 마지막 키는 폐기할 수 없다. 폐기된 키가 그 뒤에 서명한 기록은 무효다.
- 패스키의 공개키는 업무 데이터베이스에 두지 않는다. 서명하는 `KEY_ADD`는 `{credentialId, fingerprint, alg, label}`이고, 지문은 `sha256:`와 키 바이트(브라우저가 내는 SPKI)의 SHA-256 소문자 hex로, JEV 키의 지문과 같은 형태다. 키 자체는 서명한 commit과 함께 온다(commit 요청의 `publicKey`, JEV 키 값이 `secret`으로 오는 것과 같다). 서버는 무엇이든 쓰기 전에 그 키가 서명한 지문이 가리키는 키인지, 서명한 알고리즘으로 가져와지는지 확인한다. 키는 설정 데이터베이스의 패스키 등록부에 둔다(「패스키 등록부」 참조).
- 저장은 검사와 추가를 한 트랜잭션으로 한다(자체 잠금은 없다). 서명 둘이 같은 순간에 저장되면 나중 기록은 로그를 다음에 읽을 때 사슬 머리 검사에 실패해 실패한 기록으로 보이고, 사용자는 다시 서명한다. 같은 요청 ID로 다시 보내면 이미 저장된 기록을 돌려준다. 응답을 받지 못한 페이지는 다시 보내지 않고 `GET /api/request/:id`로 결과만 확인한다.

## 패스키 등록부

시간 순서는 로그가 정한다. 키를 언제 추가·폐기했는지와 대상별 사슬이다. 공개키 바이트와 키의 상태는 패스키 등록부(`PasskeyRegistry`, 설정 데이터베이스: 상태 폴더 아래의 H2 파일이며 소유자만 읽는다)가 정한다. 업무 데이터베이스에만 쓸 수 있는 사람은 서명을 위조할 수 없다. 서명을 검증하는 키가 거기에 없기 때문이다.

- 서명은 등록부가 그 credential에 대해 가진 키로 검증하며, 키의 상태는 따지지 않는다. 키를 폐기하기 전에 서명한 기록도 접을 때마다 다시 검증하기 때문이다. 등록부는 접을 때 한 번 읽는다.
- 키는 로그가 가리키는 키여야 한다. `KEY_ADD` 기록이 가리키는 키(기록의 서명자와 기록이 등록하는 키)를 등록부가 갖고 있지 않으면 그 기록은 `ADMIN_RECORD_ALTERED`(`details.reason: KEY_NOT_IN_REGISTRY`)로 실패하고, 지문이나 알고리즘이 서명한 기록과 다른 키도 같은 코드(`details.reason: KEY_FINGERPRINT_MISMATCH`)로 실패한다. 설정 데이터베이스를 바꾸거나 잃어도 어느 키가 서명하는지는 바뀌지 않는다.
- 접은 로그에 이런 실패가 있는 동안에는 새 서명도 첫 키도 받지 않는다(`ADMIN_RECORD_ALTERED`, `details.reason: LOG_INTEGRITY_FAILED`, `AdminLog.requireIntact`). 첫 키는 실패한 기록이 하나라도 있으면 막고(실패한 기록은 있는데 키가 없는 로그는 새 로그가 아니다), 나열된 키의 서명은 무결성 실패가 있을 때 막는다(그 밖의 실패한 기록, 곧 낡은 사슬 머리는 사용자가 다시 서명하면 된다). 이것이 없으면 첫 키 기록이 실패한 로그가 「키 없음」으로 접혀 첫 키가 스스로 등록되는 상태가 된다. 풀 길은 `sennard admin-reset`뿐이다.
- 새 서명은 로그가 나열하고 등록부가 활성으로 가진 키(`requireActive`)여야 하며, 페이지도 그런 키만 서명에 내놓는다. 등록부가 보증하지 못하는 키만 나열한 로그(설정 데이터베이스를 잃은 경우)에는 서명할 수 있는 키가 없고, `sennard admin-reset`으로 다시 시작한다.
- 등록부의 쓰기(`register`, `revoke`, `clear`)는 설정 데이터베이스의 서비스 메서드이고, 각각 `@Transactional(settingsTransactionManager)` 하나를 가지며 멱등이다. `register`는 INSERT를 하고 유일 키를 어기면 그 행을 읽어 같은 키(같은 공개키와 알고리즘)면 그것을 돌려주고 다른 키면 `PASSKEY_EXISTS`로 거절한다. `revoke`는 조건부 UPDATE(`state = ACTIVE`) 하나이며, 이미 폐기된 키는 폐기된 것으로 돌려준다(그런 키가 아예 없을 때만 `PASSKEY_NOT_FOUND`). 한 키의 폐기 둘은 둘 다 성공하고 한 번만 쓴다(두 번째 UPDATE가 첫 번째의 행을 기다린다). H2 2.4.240은 같은 유일 키의 두 번째 INSERT가 첫 번째의 커밋 안 된 행을 기다리게 하지 않는다. 인덱스의 유일 검사가 `CONCURRENT_UPDATE_1`(SQLState 40001)을 바로 던지고(`MVSecondaryIndex.checkUnique`), 커밋된 행만 중복 키다. 그래서 한 키의 등록 둘은 첫 번째가 두 번째의 INSERT 전에 커밋했을 때 둘 다 성공하고, 커밋 안 된 등록과 겹친 등록은 다른 쓰기와의 경합이다. H2는 이것을 바로(`CONCURRENT_UPDATE_1`, 40001) 거절하거나 잠금 대기가 시간 초과된 뒤(`LOCK_TIMEOUT_1`, 50200) 거절한다. 설정 `JdbcClient`에 H2용 Spring 오류 코드 번역기를 지정했으므로(Spring 7의 기본 번역기는 뒤쪽을 문장 시간 초과인 `QueryTimeoutException`으로 번역한다) 둘 다 `ConcurrencyFailureException`이다. 등록부는 그 예외를 그대로 올려 보내고(기다림·재시도·우리 쪽 잠금도, 설정 저장소 자체의 코드도 없다), 진입점이 데이터 접근 예외를 하나의 번역(`PersistenceErrors.ofDataAccess`. 요청은 `SennardExceptionHandler`, 도구는 `PersistenceErrors.translate`)으로 재시도 가능한 `409 CONFLICT`(`reasonCode: CONCURRENT_UPDATE`, 모든 경합의 답인 「다시 호출하라」)로 말한다. 이 답에는 따로 정한 `details.reason`이 없다. 다른 쪽이 끝난 뒤 같은 호출을 다시 하면 이미 가진 키로 답한다. 설정 저장소는 응용 계층 아래라서 그 `SettingsException`은 같은 진입점에서 하나의 번역(`PersistenceErrors.ofSettings`, `default`가 없어 새 코드는 답을 정해야 컴파일된다)으로 말한다. `PASSKEY_EXISTS`와 `REQUEST_ALREADY_RECORDED`는 `CONFLICT` `DUPLICATE`, `PRE_LINK_CLOSED`는 `ADMIN_CHANGE_INVALID`(`DATABASE_LINK_CLOSED`), `INVALID_SETTING`과 `VALUE_OUTSIDE_SET`은 `INVALID_INPUT`(`VALIDATION_FAILED`), `STORED_VALUE_UNKNOWN`과 `PASSKEY_NOT_FOUND`는 `INTEGRITY_FAILED`, 나머지는 `STORE_UNAVAILABLE`이다. 어느 것도 예기치 않은 500으로 나가지 않는다. 설정 데이터베이스는 H2이며, H2는 제약을 어긴 문장만 되돌리고 트랜잭션은 이어 가므로 위반 뒤의 읽기는 같은 트랜잭션이고, 그 뒤에 쓰기는 없다.
- 두 데이터베이스는 한 트랜잭션이 아니므로 쓰기마다 순서를 「아무것도 서명할 수 없는」 쪽으로 기울게 정했고, 등록부의 쓰기는 업무 쪽이 자기 트랜잭션에 묶이지 않고(다른 DataSource) 부르는 설정 데이터베이스 자신의 유스케이스다. `KEY_ADD`는 키를 먼저 등록하고 그다음 기록을 추가한다. 기록이 실패하면 어떤 로그도 나열하지 않는 키가 남고, 그 키로는 서명할 수 없다. 등록은 `ContextLayerService.commit` 안에 두며 신선도·사슬·서명을 검사한 뒤에 한다. 서명된 요청만 credential id 아래에 키를 심을 수 있어야 하기 때문이다. `KEY_REVOKE`는 기록을 먼저 추가하고 트랜잭션이 커밋된 뒤 등록부를 폐기한다(`PasskeyRevoked`, `PasskeyRevocations`). 이것이 실패하면 로그에 남기고(경고), 관리 상태를 보여 주는 진입점(`AdminPageData`)이 로그 읽기가 끝난 뒤 로그가 폐기했는데 등록부는 아직 활성으로 가진 키를 모두 폐기한다(`PasskeyRevocations.reconcile`, 읽기 자체는 읽기 전용 트랜잭션이라 아무것도 쓰지 않는다). 그동안에도 로그가 더는 그 키를 나열하지 않으므로 그 키는 서명할 수 없다. 연결 전에는 등록과 폐기가 기록을 추가하는 설정 데이터베이스의 한 트랜잭션에 들어간다.

## 업무 데이터베이스를 연결하기 전

서버는 업무 데이터베이스를 지정했든 아니든 시작하며, 없으면 저장소는 기다린다(`NOT_CONFIGURED`). 이때 페이지는 설정 데이터베이스의 것이다(`PreLinkAdminService`). 패스키와 업무 데이터베이스의 연결만 보여 주고 다른 것은 보여 주지 않는다.

- 첫 패스키는 여기서 자기 서명으로 등록하고(TOFU), 사용자는 패스키로 `DATABASE_LINK`에 서명해 업무 데이터베이스를 연결한다. 담는 것은 드라이버(jar 또는 jar 폴더), JDBC URL, 계정, 계정의 비밀번호를 담은 파일, 연결의 보호 방식(`LOOPBACK_PLAINTEXT` 또는 `VERIFY_FULL`)이다. 비밀번호는 기록·연결 설정·페이지 상태 어디에도 없고 그것을 담은 파일만 있다. 비밀번호를 담은 URL은 거절한다. 연결은 저장소가 접속할 때 하는 검사대로 미리 검사하므로(H2는 업무 데이터베이스가 아니고, 제품은 Sennard가 SQL을 가진 것이어야 하며, TLS 규칙을 지킨다) 접속할 수 없는 연결은 패스키를 묻기 전에 거절한다.
- 기록은 설정 데이터베이스의 `pre_link_admin_records`에 업무 로그와 같은 기록 형식·사슬 규칙·검사로 추가한다. 설정 데이터베이스가 담을 수 있는 변경(키의 등록·폐기와 연결)만 받고, 다른 변경은 `ADMIN_CHANGE_INVALID`(`details.reason: BUSINESS_DATABASE_NOT_READY`)로 거절한다.
- 연결을 커밋하면 기록·키와 같은 트랜잭션에서 연결 설정(`database_link`)을 쓴다. 커밋 뒤 저장소가 그 설정으로 준비한다(접속, 마이그레이션, 준비 완료). 저장소가 아직 준비된 적 없을 때 연결에 다시 서명하면 앞의 연결을 대체한다.
- 설정 데이터베이스의 연결이 업무 데이터베이스의 유일한 출처다. 서버, `sennard admin-reset`, `sennard attachments`가 모두 거기서 읽는다. `sennard.yaml`의 `sennard.store.database.*` 키는 읽지 않으므로 데이터베이스를 지정하지도, 페이지를 잠그지도 않는다. 페이지는 저장소의 `phase`, 저장된 연결, `link.restartRequired`를 보여 준다(마지막 항목 참고).
- 이 구간은 업무 데이터베이스가 처음 준비될 때까지 열려 있고, 그때 닫힌다(`pre_link_section.closed`). 그 뒤에는 기록을 더 받지 않고(`SettingsCode.PRE_LINK_CLOSED`) 페이지는 업무 데이터베이스의 것이 된다. 이 구간에는 상태 행이 하나 있고(`pre_link_section`: 마지막 순번과 닫힘 여부), 기록 추가와 구간 닫기는 둘 다 그 행의 조건부 UPDATE다(`H2PreLinkAdminRecords`: `SET last_seq = last_seq + 1 WHERE closed = FALSE`, `SET closed = TRUE WHERE closed = FALSE`. 각각 설정 데이터베이스의 서비스 메서드 하나와 트랜잭션 하나). 그래서 어느 쪽이 먼저인지는 데이터베이스가 정한다. 기록은 닫기 전에 들어가거나 `PRE_LINK_CLOSED`로 거절되고, 닫힌 구간에는 기록이 들어가지 않으며, 잠금용 행이나 `FOR UPDATE`는 쓰지 않는다. 기록의 순번은 그 추가가 가져간 번호이며 admin reset 뒤에도 이어서 센다. 사슬마다 업무 로그의 첫 기록은 연결 전 구간의 그 사슬 마지막 기록을 `prevTargetHash`로 가리킨다.
- 업무 데이터베이스가 준비된 뒤의 변경: 이때도 페이지는 `DATABASE_LINK`를 받는다(`ContextLayerService`). 모양 검사는 같고, 서명은 업무 로그의 기록이다(연결 전 구간은 닫혀 있다). 새 연결이 가리키는 데이터베이스는 서명을 커밋할 때 시도한다(`LinkedDatabaseProbe`: 사용자의 드라이버와 계정, 풀의 시작 점검, URL이 가리키는 제품, 테이블의 마이그레이션). 실패는 저장소 자체의 오류(`StoreException`)이고 기록이 되돌려져 아무것도 남지 않는다. 그 뒤 연결 설정(`database_link`)을 쓴다. 설정 데이터베이스는 업무 트랜잭션이 아니므로 이 쓰기가 마지막 단계이며, 그 뒤 업무 커밋이 실패하면 같은 요청에서 서명을 검증한 연결이 기록 없이 남는다. 저장소는 프로세스당 한 번 준비하므로 저장한 연결은 다음 시작부터 쓰인다. 페이지가 그 사실을 알리고(`link.restartRequired`: 저장소가 `READY`이고 연결이 저장소가 시작된 뒤에 저장됨) 다음 행동(`sennard stop`, `sennard start`)을 보여 준다. 이전 데이터베이스의 기록은 새 데이터베이스로 옮기지 않으며, 새 데이터베이스의 로그는 연결 전 구간 뒤에서 비어 있는 채로 시작한다.

## 읽는 쪽(hook·MCP·페이지)

- 읽을 때마다 기록 전체를 순서대로 검증한다. 검증에 실패한 기록은 적용하지 않고, 그 대상은 서명이 유효한 마지막 판(rN)으로 동작한다. 유효한 판이 없으면 기본값(전역 컨텍스트 없음, 역할 없음, 결속 없음)을 쓴다. 권한이 줄어드는 쪽이다.
- hook은 실패한 대상마다 한 줄을 넣는다: 「검증 실패 · rN 적용 중 · 사용자에게 알릴 것 · 에이전트가 고칠 수 없음」.
- 전역 컨텍스트와 담당 컨텍스트는 따로 읽는다. 하나가 실패해도 다른 하나는 주입한다.
- 페이지는 실패한 대상마다 빨간 띠와 [서명해서 채택]·[버리기]를 보여 준다. [서명해서 채택]은 실패한 기록이 요청하던 변경에 다시 서명하는 것이고, [버리기]는 현재 유효한 상태를 그대로 두겠다는 확인(`ACK`)에 서명하는 것이다.
- 상주하는 MCP 서버는 대상마다 본 적 있는 가장 큰 seq를 메모리에 둔다. 기록이 그보다 줄면 `ADMIN_LOG_REGRESSED`로 알린다.

## 설정을 읽는 곳

설정 항목도 다른 서명 항목과 같은 검증 경로(`AdminLog.fold`)로 읽는다. 읽을 때마다 기록 전체를 검증하고, 검증에 실패한 기록은 적용하지 않는다. 그 항목은 마지막 유효 판, 그것도 없으면 기본값으로 동작한다.

| 항목 | 읽는 곳 | 바뀐 값이 적용되는 때 |
| --- | --- | --- |
| 첨부 폴더·자원 상한 | `SignedAttachmentSettings`: 검증된 현재 값과 이전 폴더 이력. 오프라인 유지보수도 같은 설정 사용 | 다음 요청부터. 기존 파일의 위치는 유지 |
| 편집 관문 예외 경로 | 편집 관문 판정(`ProductEditTargets`) | 다음 편집부터 |
| JEV 켜고 끄기·모델·정보 전송 허용·프리셋 | 서버의 분류 런타임 읽기(`classify_skills`·선택 기록·사용자 프롬프트 훅이 시작하는 자동 분류 때마다) | 다음 호출부터 |
| JEV 예산 | 서버의 예산 예약(예약할 때마다) | 다음 예약부터 |
| JEV key | JEV 호출과 모델 목록 갱신 때 key 읽기(이름 `JEV_API_KEY`) | 다음 호출부터 |
| 서버 JVM 프리셋 | 커밋 뒤 `JvmPresetSigned` 이벤트를 받는 `PersonalJvmInstallation`이 `sennard-jvm.yaml`에 씀. 저장소가 준비되면 서명한 값과 파일을 맞춤 | 서버를 다시 시작할 때 |

- 예외 경로 기록을 읽지 못하면 예외를 하나도 더하지 않는다. 읽기 실패가 관문을 열지 않는다. 예외 경로는 최대 50개다(`EDIT_EXEMPT_MAX_PATHS`).
- JEV 설정을 읽지 못하면 JEV는 꺼진다. 예산을 읽지 못하면 JEV 예약을 하지 않는다.
- 비용: hook 한 번에 서명 기록을 한두 번 검증한다(기록 수만큼 서명 검증). 기록은 사람이 저장한 것만 쌓이므로 작다. 수천 개로 늘면 검증된 앞부분을 저장해 두는 방식으로 바꾼다(`ceiling:`).

### 환경 변수

환경 변수는 아래 설정에 쓰지 않는다.

- **표시 언어:** 언어를 고르는 설정이 없다. 사용자 문서([admin-page.ko.md](../user/admin-page.ko.md#언어))에 그 사실을 적는다.
- **편집 관문 예외 경로:** 서명한 목록만 쓴다. 환경 변수와 서버 설정은 예외 경로를 더하지 않는다.
- **JEV:** 서버가 전부 관리한다. 설치 설정 파일과 환경 변수는 JEV에 쓰지 않는다. 아래 「JEV 구성」을 본다.
- **벤더 경로 분류:** 설치 설정을 읽지 않으므로 동작하지 않는다.

## JEV 구성

JEV의 역할(조언 계층이며 관문이 아님), 쓰는 조건과 전송 항목은 [사용자 문서의 JEV 절](../user/admin-page.ko.md#jev-스킬-선택-조언)에 있다. 구현 쪽 사실은 다음과 같다.

- **공급자는 Sennard 소스에 내장한다**(`JevProvider`). 이 판의 공급자는 TypeSafe 하나다. 호출 주소 `https://api.typesafe.ai/v1/systemone`, 모델 목록 주소 `https://api.typesafe.ai/v1/models`, wire 형식 `jev-skill-selection-choice-r01`, key 이름 `JEV_API_KEY`. 공급자를 더할 때는 이 파일의 목록에 정의 하나를 더한다.
- **쓰는 조건의 모델 검사:** Sennard가 아는 공급자·모델 이름 형식이어야 한다.
- **모델 목록:** 서버 메모리에 두고 24시간마다(실패하면 1시간 뒤) 다시 받으며, 동시에 들어온 갱신 요청은 하나로 합친다.
- **qualification 없음:** JEV에는 사전 qualification 관문을 두지 않는다. Sennard가 만드는 JEV 프로필은 qualification을 `NOT_RUN`으로 적고, `classify_skills` 응답의 `adviceQualification`도 JEV 조언이면 `NOT_RUN`이다. 벤더 경로의 qualification 규칙(PASS·inventory 결속·만료)은 그대로다.
- **정보 전송 허용:** 서명한 허용이 JEV에서 설치 설정의 `externalClassificationAllowed`를 대신한다(벤더 경로에는 설치 설정을 그대로 쓴다). 페이지의 「전송 정보 자세히 보기」는 wire 형식이 실제로 만드는 항목을 보여 준다.
- **예산:** 서명한 한도만 쓴다. 호출마다 최대 비용(입력 상한 256 KiB를 1바이트=1토큰, 입력 USD 0.042/M로 계산한 약 0.0110 USD)을 미리 잡아 두고, 응답의 토큰 수로 계산한 추정액으로 정산한다. 사용량을 모르는 호출은 예약을 그대로 남긴다.
- **평가 시도:** JEV는 1회만 시도하고, 조언하지 못하면 에이전트가 직접 고른다.
- **쓰는 중 표시:** 페이지와 workflow 변경 안내는 JEV를 실제로 부를 수 있을 때만 「쓰는 중」으로 말하고, 아니면 빠진 것을 적는다.

### JEV key 저장

- JEV key(`JEV_API_KEY`)는 이 페이지에 등록한 값만 쓴다. 같은 이름의 환경 변수는 JEV에 쓰지 않는다.
- 페이지는 key 값의 SHA-256 지문(`sha256:<hex>`)을 만든 뒤, 그 지문을 담은 변경(`JEV_KEY_SET {name, fingerprint}`)에 패스키로 서명한다. 서명 기록에는 지문만 남는다.
- 값은 서명한 commit 요청에만 함께 보낸다(`secret`). 서버는 값의 지문이 서명한 지문과 같은지 확인한 뒤, 봉인기(`SecretSealer`)로 암호화해 서명 기록을 추가하는 트랜잭션 안에서 `jev_keys`에 저장한다. 인증 데이터는 이름과 지문이므로, 저장된 값을 다른 이름으로 옮기거나 다른 값으로 바꾸면 풀리지 않는다.
- 데이터 키는 로컬 봉인기(`LocalFileSealer`)가 상태 폴더에 둔다(소유자만 읽기 권한, 플랫폼이 지원할 때). 처음 등록할 때 만든다.
- 읽는 쪽은 마지막 유효한 서명 기록의 지문과 저장된 값이 맞을 때만 값을 쓴다. 나중 기록이 검증에 실패하면 저장된 값이 그 기록의 것이므로 지문이 맞지 않고, key를 쓰지 않는다(key 없음으로 동작).
- 응답·페이지 상태·진단에는 값을 넣지 않는다. 재설정(`admin-reset`)은 저장된 key도 지운다.
- 지문은 솔트 없는 SHA-256이다. 무작위로 만든 API key라면 지문에서 값을 되찾을 수 없지만, 짧거나 추측하기 쉬운 값은 지문으로 맞혀 볼 수 있다.
- 운영체제 키 저장소(Windows DPAPI 등)는 이 판에서는 쓰지 않는다. 암호화가 막는 것은 저장소 내용만 따로 복사되거나 읽힌 경우(백업, 지원용 묶음)다.

### 사용액

JEV 사용액은 이미 집계된 값만 보여 준다. `sennard.jev_usage_daily`의 추정 누계(호출 수, USD 추정액)와 상주 서버 메모리의 예약 중인 금액이다(`JevBudget`). 응답에 청구액이 없어 실제 지출로 표시하지 않는다. 추정 누계는 서버를 다시 시작해도 이어지고, 예약은 재시작하면 사라진다. 페이지는 그 값을 보여 주기만 한다.

## 에이전트 요청

- `checkpoint_global_context`와 `bind_session_role`은 서명 없는 요청(proposal)만 저장하고 `pending`·`proposalId`·`adminUrl`을 돌려준다. 같은 세션이 같은 대상에 새 요청을 내면 이전 요청을 대신한다.
- 결속은 역할 등록표에 있는 역할에만 요청할 수 있다. 없으면 `ROLE_NOT_REGISTERED`다.
- 결속은 root 세션 단위다. 서브에이전트는 root 세션의 결속을 이어받는다. 결속은 마지막 사용 뒤 30일이 지나면 없어진다.
- root 승인(`replace-root`, `release-root`)은 이 서명 기록의 항목(`ROOT_APPROVE`)이다. 승인 전 도구는 `USER_APPROVAL_REQUIRED`, `adminPage`, `scopeDigest`를 돌려주고, 사용자가 페이지에서 패스키로 승인한 뒤 같은 호출을 다시 하면 그 승인을 재사용한다(`kind: admin-page`, `RootApprovalService`).
- root 해제(`ROOT_RELEASE`)는 소유 세션이 없어도 사용자가 열린 root를 해제하는 서명 항목이다. 대상 체인은 `rootRelease:<rootId>`이고 내용은 `rootId`·`expectedRevision`·`reason`이다. `/api/intent`와 커밋 둘 다에서 root가 없으면(`ADMIN_ROOT_NOT_FOUND`, 404), 더는 열려 있지 않으면(`ADMIN_ROOT_NOT_ACTIVE`, 409), 판이 바뀌었으면(`ADMIN_ROOT_STALE`, 409) 거절한다. 커밋에서 거절되면 서명 기록도 남지 않는다. 해제는 서명 기록과 한 트랜잭션이며(`RootReleaseService`), 해제 기록의 `releasedBy`는 `admin-page`이고 `userApprovalRefs`는 서명 기록의 해시를 담은 승인 기록(`kind: admin-page`)을 가리킨다. 세션이 `release_convergence_root`로 하는 해제와 닫는 코드는 하나다(`WorkflowService.closeRoot`).

## 무인증 보기

`GET /`와 `GET /api/state`는 패스키 없이 열린다. 다른 웹 페이지가 읽거나 쓰지 못하도록 Host 헤더(`localhost:<port>`·`127.0.0.1:<port>`만)와 POST의 Origin·`Sec-Fetch-Site`·`Content-Type: application/json`·본문 64KiB 상한을 검사한다(DNS rebinding·cross-site 요청 차단). 페이지는 nonce CSP로 외부 스크립트·스타일을 막는다.

## 재설정

`sennard admin-reset`(`AdminResetCommand`, `AdminReset`)은 패스키 등록부를 비우고, 연결 전 구간의 기록을 설정 데이터베이스의 `pre_link_admin_records_archive`로 옮기고, 업무 로그를 `admin_log_archive`로 옮기며, `admin_resets`에 `resetOf`(이전 마지막 기록 hash)를 남긴다. 순서는 이대로다. 두 데이터베이스는 한 트랜잭션이 아니므로 중간에 멈춘 실행은 서명할 키가 없는 쪽이 되고, 다시 실행해도 같은 결과다. 연결 설정은 서명 기록이 아니므로 남긴다. 초기화 뒤 첫 키는 다시 자기 서명으로 등록한다. 어느 초기화가 도는지는 상태 하나로 정한다. 연결 전 구간이 아직 열려 있는가(`PreLinkAdminRecords.closed()`)이며, 판의 문제가 아니라 진입점 `AdminResetConfiguration`이 읽는다. 열려 있는 동안은 업무 데이터베이스가 한 번도 준비된 적이 없어 로그가 설정 데이터베이스에만 있으므로, 초기화(`AdminResetBeforeLink`, 설정 데이터베이스의 트랜잭션 하나)는 업무 데이터베이스에 닿지 않고 등록부를 비우고 구간의 기록을 보관한다. 업무 데이터베이스가 아예 지정되지 않았어도 된다(권한은 소유자의 설정 파일이며, 첫 등록 뒤나 실패한 연결 뒤에 패스키를 잃었을 때의 길이다). 닫힌 뒤에는 위의 초기화가 돌고 업무 데이터베이스가 필요하며, 닿지 않으면 `ADMIN_RESET_DATABASE_UNREACHABLE`로 끝난다. 명령의 컨텍스트는 느리게 시작하므로 업무 데이터베이스의 풀은 뒤의 경우에만 생긴다. 이 명령은 웹 서버 없는 자기 Spring Boot 컨텍스트를 띄우고, 서버와 같은 설정(설치의 설정, 없으면 페이지가 저장한 연결)·드라이버·풀·격리 수준으로 업무 데이터베이스에 닿으며 등록부를 위해 설정 데이터베이스에 닿는다. 설정 데이터베이스는 한 번에 한 프로세스만 여는 H2 파일이므로 이 명령은 설치본의 서버가 떠 있으면 먼저 그 서버를 멈춘다(`AdminResetCommand`, `ServerControl.shutdown()`, 컨텍스트가 파일을 열기 전에, 미리보기와 `--yes` 모두). 다시 시작된 서버가 파일을 또 쥐고 있으면 `ADMIN_RESET_SETTINGS_LOCKED`로 끝난다(`AdminResetContext` 한 곳에서 변환, 재시도 없음). 초기화는 `AdminReset` 서비스의 `@Transactional` 메서드 하나이다. 대기 중인 요청은 거절 처리한다. 첫 등록 증명에는 MCP 확인 창(elicitation)을 쓰지 않는다. 같은 OS 사용자가 가짜 MCP 클라이언트로 자동 수락할 수 있기 때문이다. 막지 못하는 공격 목록은 [사용자 문서](../user/admin-page.ko.md#막지-못하는-것)에 있다.

## 오류 코드

`ADMIN_KEY_NOT_REGISTERED`, `ADMIN_ALREADY_BOOTSTRAPPED`, `ADMIN_KEY_UNKNOWN`, `ADMIN_KEY_REVOKED`, `ADMIN_LAST_KEY_REVOKE_REFUSED`, `ADMIN_ALGORITHM_UNSUPPORTED`, `WEBAUTHN_PUBLIC_KEY_UNAVAILABLE`, `ADMIN_SIGNATURE_INVALID`, `ADMIN_CHALLENGE_MISMATCH`, `ADMIN_ORIGIN_REJECTED`, `ADMIN_RPID_MISMATCH`, `ADMIN_USER_VERIFICATION_MISSING`, `ADMIN_INTENT_NONCANONICAL`, `ADMIN_INTENT_EXPIRED`(intent 발급 뒤 10분), `ADMIN_INTENT_BEFORE_START`(이 서버 프로세스가 시작되기 전에 발급된 intent, 페이지 포트는 기동마다 바뀜), `ADMIN_INTENT_NOT_ISSUED`(commit은 이 서버 프로세스가 준비했고 아직 어떤 기록도 쓰지 않은 intent만 받는다. 서명은 사용자가 무엇에 서명했는지를 증명할 뿐 이 서버가 그것을 요청했음을 증명하지 않는다), `ADMIN_TARGET_STALE`, `ADMIN_LOG_REGRESSED`, `ADMIN_PROPOSAL_NOT_FOUND`, `ADMIN_ROOT_NOT_FOUND`, `ADMIN_ROOT_NOT_ACTIVE`, `ADMIN_ROOT_STALE`, `ADMIN_CHANGE_INVALID`(details.reason: `ROLE_NAME_TAKEN` 등, 설정은 `JEV_PRESET_UNKNOWN`·`JEV_KEY_NOT_FOUND`·`JEV_KEY_FINGERPRINT_MISMATCH`·`SECRET_UNEXPECTED`·`JEV_KEY_VAULT_UNAVAILABLE`, 키는 `KEY_FINGERPRINT_MISMATCH`·`PUBLIC_KEY_UNEXPECTED`, 연결은 `DATABASE_LINK_INVALID`·`DATABASE_LINK_CLOSED`·`BUSINESS_DATABASE_NOT_READY`), `ADMIN_RECORD_ALTERED`(파일에서 고쳐진 기록, 또는 패스키 등록부의 키가 없거나 기록이 가리키는 키가 아닌 경우: `details.reason: KEY_NOT_IN_REGISTRY` 또는 `KEY_FINGERPRINT_MISMATCH`. 로그에 이런 실패가 있는 동안 서명이나 첫 키를 거절하는 것은 `LOG_INTEGRITY_FAILED`). HTTP 경계: `HOST_REJECTED`, `CONTENT_TYPE_REJECTED`, `REQUEST_TOO_LARGE`, `REQUEST_INVALID`. 저장소가 답하지 못하면 `MCP_UNAVAILABLE`에 `reasonCode: STORE_UNAVAILABLE`이며, 페이지는 이것을 자기 문장으로 보여 준다.
