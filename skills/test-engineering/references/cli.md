# Engineering Practices CLI

현재 허용된 작업과 직접 지정한 로컬 파일에만 사용한다. MCP 도구 `run_skill_check`의 `skill`은 `test-engineering` 또는 `code-review`이며 두 이름은 같은 명령을 제공한다.

## 명령

```text
run_skill_check {"skill":"test-engineering","command":"self-check"}
run_skill_check {"skill":"test-engineering","command":"catalog","arguments":{"module":"test-design"}}
run_skill_check {"skill":"test-engineering","command":"check-plan","arguments":{"root":"/absolute/work","plan":"plan.json"}}
run_skill_check {"skill":"test-engineering","command":"snapshot","arguments":{"root":"/absolute/work","files":["src/module.ext","tests/module.test.ext"]}}
run_skill_check {"skill":"test-engineering","command":"run","arguments":{"root":"/absolute/work","snapshot":"snapshot.json","timeout-ms":30000,"argv":["<실행 파일>","<인자>"]}}
run_skill_check {"skill":"test-engineering","command":"check-proof","arguments":{"root":"/absolute/work","plan":"plan.json","proof":"proof.json"}}
run_skill_check {"skill":"code-review","command":"check-review","arguments":{"root":"/absolute/work","request":"request.json","report":"review.json"}}
run_skill_check {"skill":"test-engineering","command":"check-stage-bundle","arguments":{"root":"/absolute/work","input":"stage-bundle.json","bundle-digest":"sha256:<raw-manifest-hash>","task-digest":"sha256:<signed-task-digest>","capability":"test-sensitivity-review"}}
run_skill_check {"skill":"test-engineering","command":"provider-result","arguments":{"root":"/absolute/work","capability":"test-sensitivity-review","result":"test-result.json","attachmentId":"<업로드한 결과 첨부의 UUID>"}}
```

테스트 구현·실행 전에 계획을 작성·저장하고 `check-plan`을 확인한다. 위 명령 목록은 이미 허용된 작업에 필요한 명령만 선택하는 안내이며 모든 명령을 실행하라는 절차가 아니다.

`root`는 절대경로이고 파일 참조는 그 root 아래 상대경로다. 예를 들어 `/absolute/work/.ev/plan.json`은 `"root":"/absolute/work","plan":".ev/plan.json"`으로 지정한다. `snapshot`·`proof`·`request`·`report`·`input`·`result`에도 같은 기준을 적용한다. 파일 참조의 절대경로·상위경로·Windows 예약경로·symlink는 거부한다.

`files`는 `["src/module.ext", "tests/module.test.ext"]`와 같은 명시적 상대 경로 배열이다. 자동 전체 Git diff 수집기는 아니다. base/head에 포함하지 않은 파일이나 외부 의존성·생성물·환경은 별도 조사로 범위에 넣는다. 모든 관련 선언 파일을 포함하되 민감한 credential 파일은 넣지 않는다.

출력 JSON은 도구 결과의 `value`로 받는다. 기존 호스트 파일 도구로 저장한다. 출력 파일은 snapshot 대상에서 제외한다. raw JSON 파일의 hash는 SHA-256 byte digest다. planDigest/requestDigest 및 snapshot 자체 digest는 코드의 정렬된 canonical JSON 방식으로 계산한다. 두 해시 종류를 혼용하지 않는다. 예제 생성기가 올바른 참조 파일을 보여준다.

artifact root와 산출물 저장 위치는 현재 허용된 쓰기 범위 안에서 정한다. 산출물의 복사·이동·정리도 같은 범위를 따르며, snapshot 제외나 CLI 검사 성공은 그 범위를 넓히지 않는다.

run은 자식 프로세스를 시작하고 기다리므로 공통 봉투의 `data`를 `{jobRef, state}`로 먼저 응답한다. 그 job의 결과는 `get_background_job`이 돌려주는 `data`의 `result`로 읽으며, 같은 호출을 기다렸다면 받았을 응답 봉투 전체와 같다. 실행됐다면 `result.data`가 `{exitCode, value}`이고 `value`가 receipt다. argv·env·snapshot 같은 입력 검사도 job 안에서 하므로, 거부된 입력은 job이 `SUCCEEDED`로 끝나고 `result`가 `ok`가 false인 봉투이며 `result.error`(`code`, `message`, `messageKey`, `params`, `details`)가 거부 본문이다. 이 봉투에는 `exitCode` 필드가 없다. 예상하지 못한 `RuntimeException`이 나면 job은 `FAILED`로 끝나고, `errorCode`는 `JOB_FAILED`(저장소 예외이면 그 예외의 코드)이며 `result`는 null이다. `snapshot`은 `snapshot` 명령의 출력 JSON을 그대로 주거나, root 아래 상대경로로 저장한 그 파일을 가리킨다. root 밖의 파일(`{"file": ...}` 형태 포함)은 거부한다. 선택 인자 `environment-note`는 receipt의 `environmentNote`에 적을 실행 환경 설명이고, 선택 인자 `env`는 자식의 환경에 더할 변수(이름→문자열 값 객체)다(아래 「실행과 신뢰 경계」).

run의 의도적인 red 테스트는 `exitCode` 1과 FAIL receipt를 반환한다. 예상 실패의 `value`와 `exitCode`를 모두 보존하고 무조건 성공 취급하지 않는다. run이 아닌 명령에서 도구 자체의 invalid input 오류나 IO 오류는 `exitCode` 2에 해당하며 `ok`가 false이고 `error.code`를 담은 봉투가 MCP 오류 결과(`isError`)로 온다. run의 거부는 MCP 오류 결과가 아니라 위 job의 `result` 봉투(`error`)로 온다. check-proof/check-review의 완료 미충족·차단 결과는 `exitCode` 1이다. provider-result는 포장 명령의 성공 코드만 반환하므로 내부 error와 output.verdict를 읽는다.

## 검증되는 것

case.argv는 1~128개의 비어 있지 않은 문자열(각 최대 8192자)로 실행 명령을 정확히 고정한다. shell 문자열을 추측해 파싱하지 않는다. plan.runner와 plan.environment는 설명이며 환경 보장이 아니다. red와 green은 모두 frozen case.argv와 일치해야 하고 receipt.executable은 argv[0]과 같아야 한다. 지정 argv가 의도한 테스트를 실제 실행하는지와 unsigned receipt의 진본성·인과성은 검토자 책임이다.

plan은 요구 ID↔case, testFiles↔scopeFiles, 중복/누락/형식과 oracle·예외 사유를 검사한다. 신뢰할 만한 oracle인지는 에이전트가 실제 요구와 대조한다.

proof는 frozen plan digest, 현재 후보의 실제 바이트, case별 coverage, receipt의 실제 파일 hash, 동일한 명령·기재된 환경, red→green 시간 순서, FAIL/PASS 상태, 동일한 테스트 바이트, 명시한 제품 mutation 경로, red assertion witness를 확인한다. 요구 필수 항목의 NOT_RUN은 INCOMPLETE다. EXEMPT는 plan에서 미리 not-applicable로 정한 경우만 허용한다. manual-review는 기계적 red/green 검증을 대신하는 자동 통과 모드가 아니다. 필요한 수동 검증은 기존 acceptance-evidence-validator로 전달한다.

review는 request/head 결속, 선언한 base/head의 파일 변화 전체, 현재 head 바이트, 삭제 상태, 변경 파일별 REVIEWED/NOT_REVIEWED, finding 위치와 줄 범위, 결함·권고·가설의 구분을 검사한다. 권고와 가설은 blocking이 될 수 없다. 근거가 충분한 결함은 supported로 분류할 수 있으며 실측 재현이 없다는 이유만으로 단순 가설로 낮추지 않는다. 차단 결함이면 CHANGES_REQUESTED, 필수 미검토 파일이면 INCOMPLETE다. 모두 해소됐다는 기록만 NO_BLOCKING_FINDINGS다.

## 선택된 workflow 단계의 원파일 결속

`test-sensitivity-review`와 `change-code-review`는 record/finalize에서 같은 공통 CLI의 `check-stage-bundle`을 실행한다. 기존 direct `check-proof`/`check-review`와 provider-result 포장은 유지한다. 포장·verified 자기 선언은 원파일 검증을 대체하지 않는다.

이 CLI 자체는 `root` 아래의 파일을 읽는다. 그러나 단계 결과를 MCP 서버에 기록할 때 번들은 첨부로 전달한다. 서버는 호출자 컴퓨터의 경로를 읽지 않고, 첨부를 비공개 임시 폴더에 풀어 같은 검사를 그 안에서 실행한다(아래 「단계 결과가 번들을 싣는 방식」). manifest를 번들 폴더 안에 둔다. test 단계는 아래 필드를 사용하고 code review는 `plan`/`proof` 대신 `request`/`report` reference를 사용한다. 각 digest는 원파일 bytes SHA256이며 manifest 자체 digest는 stage artifact에 고정한다.

```json
{
  "schemaVersion": "1.0.0",
  "kind": "engineering-stage-bundle",
  "capability": "test-sensitivity-review",
  "task": { "path": "task.json", "digest": "sha256:<raw-task-hash>" },
  "plan": { "path": "plan.json", "digest": "sha256:<raw-plan-hash>" },
  "proof": { "path": "proof.json", "digest": "sha256:<raw-proof-hash>" },
  "result": { "path": "result.json", "digest": "sha256:<raw-result-hash>" }
}
```

task는 해당 workflow의 동결 TaskEnvelope다. prepared plan/request의 taskId·contractDigest는 그 task와 canonical digest에 결속하고, requirements는 acceptanceCriteria와 같은 집합이어야 한다. 선택된 test 단계의 각 frozen criterion에는 required red-green/mutation case가 있어야 한다. 필수 의무를 optional·exempt로 낮춰 통과시키지 않는다. 여러 업무 중 일부만 검사하려면 실제 요구와 권한을 보존한 별도 작업 계약으로 범위를 먼저 고정한다.

provider output은 현재 원파일로 다시 계산한 result와 같아야 한다. 기존 `engineering-test-result` 또는 `engineering-review-result` artifact와 추가 `engineering-stage-bundle` artifact를 같은 result bytes/targetDigest로 연결한다. artifact verified는 true여야 하며 result-file reference는 manifest의 result와 같아야 한다. NOT_RUN·CHANGES_REQUESTED·INCOMPLETE와 adapter 오류는 비통과 상태를 유지한다.

### 단계 결과가 번들을 싣는 방식

- manifest와 보고서는 단계 결과의 산출물이다. `output.artifacts[]`에 `engineering-stage-bundle`(manifest), `engineering-test-result` 또는 `engineering-review-result`(보고서)를 `attachmentId`와 `digest`로 적는다. manifest의 선언 digest는 manifest 원본 바이트의 sha256이며 검사의 `bundle-digest`다.
- manifest나 검사가 읽는 모든 원파일(task, plan 또는 request, proof 또는 report, result, 후보 파일)은 `evidence[]` 항목이며 각각 `attachmentId`와 선언 digest를 갖는다.
- 모든 첨부는 결과를 기록하는 같은 세션이 먼저 올린다. 파일마다 `sennard attachments upload --session-ref <sessionRef> --name <상대 경로> <file>`로 올린다. 첨부를 올릴 때의 파일 이름(`fileName`)이 번들 폴더 아래의 경로다. 이식 가능한 상대 경로이고 `/`로 구분하며(manifest의 `path`와 같은 형태), `..`·드라이브·역슬래시·예약 이름이 없고, 대소문자를 구분하지 않고 비교해도 겹치지 않으며, 합계는 64 MiB 이하다.
- 서버는 첨부를 비공개 임시 폴더에 그 경로로 두고 바뀌지 않은 `check-stage-bundle`(서명된 task 결속, 원파일 digest, 후보 바이트)을 그 안에서 실행한 뒤 폴더를 지운다. 보고서 산출물은 검사가 읽은 그 첨부(같은 경로와 digest)여야 한다.
- 쓸 수 없는 파일은 `INVALID_INPUT` `workflow.bundle.fileNotUsable`이며 `details.reason`은 `PATH_NOT_PORTABLE`, `DUPLICATE_PATH`, `TOO_LARGE`, `ATTACHMENT_NOT_FOUND` 중 하나다.

이 검사는 선택된 두 workflow capability에만 적용한다. bootstrap 계획 검사, 자연어 의미 선택, CS 의무의 전역 적용성, plan/lease/start/resume 전체 binding, native host attestation·실행 진실성·reviewer 독립성·배포 승인은 발급하지 않는다. 기존 CS 단계와 수용/독립 감사 책임을 유지한다. 다른 후보·변경된 raw proof/candidate는 첨부의 바이트와 digest 대조로 거부하며 임시 폴더의 OS 수준 공격 격리는 별도다.

## 실행과 신뢰 경계

run은 `SkillProcessRunner` 빈이 있는 서버, 곧 personal 서버만 제공한다. 호출자의 `argv`를 JDK `ProcessBuilder`에 그대로 넘기고(아래 배치 파일 예외 외에는 셸을 거치지 않는다), 작업 디렉터리는 snapshot을 검증한 `root`의 실제 경로(real path)다. 표준 입력은 즉시 끝(EOF)이다. 프로그램(`argv[0]`)은 다음과 같이 찾는다. 경로 구분자가 들어 있는 상대 경로(`./gradlew`, Windows에서는 `.\gradlew.bat`도)는 `root`(snapshot을 검증한 실제 경로)에서 시작해 풀어 실행한다. 구분자가 없는 이름(`gradlew.bat`, `java`)은 `root`에서 찾지 않고 운영체제의 프로그램 검색(서버의 `PATH`)에 맡기므로, `root`에만 있는 파일은 찾지 못해 `SPAWN_ERROR`다. 절대 경로는 그대로 쓴다. receipt의 `argv`와 `executable`에는 호출자가 쓴 그대로 적는다. 기본 제한 30초, 최대 300초(`timeout-ms` 1~300000)이며 `argv`는 1~128개, 각 1~8192자, NUL 불가다. 그 빈은 personal 서버의 `LocalProcessRunner`뿐이고 프로파일·조건 없이 컴포넌트 스캔으로 등록된다. 이 빈이 없는 구성은 `skillCheck.processesNotAvailable`로 거부한다. 서버가 있는 위치(로컬·원격)나 운영체제로 가르지 않는다.

이 명령은 서버를 실행한 사용자의 권한으로 임의 프로그램을 실행한다. 호출자 신원 확인은 이 명령이 하지 않으며 별도 작업(설치 토큰)에서 다룬다. 이 명령이 스스로 거는 한도는 다음과 같다.

- 동시 실행: 실행 중인 command는 최대 `sennard.run.max-concurrent`개(기본 2)다. 한도를 넘어 요청하면 큐에 넣지 않고 즉시 `practices.runner.tooManyRuns`로 거부하며 아무것도 시작하지 않는다.
- 출력: stdout·stderr는 command의 파이프에서 읽으며(스트림마다 가상 스레드 하나, 디스크에는 쓰지 않는다) 각각 앞 1MiB만 UTF-8로 보관해 receipt에 싣는다. 1MiB를 넘는 바이트는 읽는 즉시 버린다. 실행 중에 어느 쪽이든 1MiB를 넘게 쓴 것이 확인되면(`sennard.run.output-check-interval`마다 본다. 기본 25ms, 1ms 이상) 프로세스와 그 자손을 종료하고 `SPAWN_ERROR`(`exitCode` null, 앞 1MiB 유지)다. 확인하기 전에 스스로 끝났으면 앞 1MiB만 남기고 `SPAWN_ERROR`이며 그 프로그램의 종료 코드가 기록된다(어느 쪽인지는 확인 시점에 달려 있다).
- 배치 파일: argv[0]이 `.bat` 또는 `.cmd`(대소문자 무시, 뒤의 점·공백 무시)이면 Windows에서 JDK는 `cmd.exe`를 거쳐 실행하고, 이 해석기는 인자를 다시 명령으로 읽는다. JDK 25.0.5(Windows 11)로 실측했다. 인자 `a&echo X`·`a|echo X`는 인자 하나로 전달됐지만, `a"&echo X`는 `echo X`가 실행됐고 `%PATHEXT%`는 확장됐으며 `a>out.txt`는 `out.txt`를 만들었다. 그래서 argv[0]이 배치 파일이고 어느 항목이든 `"` `&` `|` `<` `>` `^` `%` `!` 또는 줄바꿈을 가지면 `practices.runner.batchFileArgumentsUnsafe`로 거부하고 아무것도 시작하지 않는다. 이 판정은 문자열만 보므로 모든 OS에서 같다. `gradlew.bat`의 일반 인자(`build`, `--no-daemon`, `-Pname=value`, `:module:task`)는 통과한다. 해석기는 `=`·`,`·`;`에서도 인자를 나누므로 배치 파일이 `%1`로 읽으면 `a=b`는 둘로 보이고, `%*`로 그대로 넘기는 `gradlew.bat` 같은 래퍼는 쓴 그대로 받는다(주입은 아니므로 거부하지 않는다). 호출자가 `cmd.exe /c ...`처럼 해석기를 직접 지정한 argv는 호출자의 선택이므로 막지 않는다.

환경 변수: 자식의 환경은 비운 뒤, 서버 환경에서 `PATH`, `Path`, `PATHEXT`, `JAVA_HOME`, `TEMP`, `TMP`, `TMPDIR`, `HOME`, `USERPROFILE`, `SystemRoot`, `SystemDrive`, `windir`, `ComSpec`, `LANG`, `LC_ALL`, `LC_CTYPE`만(서버에 있을 때) 복사하고, 그 뒤에 선택 인자 `env`(변수 이름→문자열 값 객체)의 호출자 변수를 더한다. 같은 이름이면 호출자 값이 이긴다. 서버의 나머지 변수(다른 세션의 것 포함)는 자식에게 가지 않는다. 목록 밖에서 필요한 변수(예: `GRADLE_USER_HOME`)는 `env`로 명시한다. `env`는 변수 32개 이하, 이름 1~128자(`=`·제어 문자 불가), 값 8192자 이하(NUL 불가)이며 값이 문자열이 아니면 거부한다. 값은 receipt에 싣지 않는다. `environmentNote`는 `environment-note`를 주지 않으면 서버가 허용 목록과 호출자가 준 변수의 이름(값 아님)을 적은 문장이고(「Child environment: emptied, then the server's variables PATH, ... when the server has them, then the variables set by the caller (names only; values are not recorded): NAME1, NAME2. Undeclared environmental inputs are not captured.」), `environment-note`를 주면 그 문장을 그대로 쓴다. `check-proof`는 red와 green receipt의 `argv`(계획 사례의 `argv`와도 같아야 한다)·`environmentNote`·`recorderRuntime`·`platform`·`executable`이 같은지 비교한다. `env`의 값은 receipt에 담기지 않으므로 `check-proof`가 직접 비교하지 않는다. `environment-note`를 주지 않으면 기본 `environmentNote`에 호출자 변수의 이름이 들어가므로, red와 green에서 `env` 변수 이름이 다르면 `environmentNote`가 달라져 `check-proof`가 거부한다(`practices.core.declaredExecutionEnvironmentsDiffer`). 같은 이름의 값이 다른 경우와, 직접 준 `environment-note` 아래의 환경 차이는 `check-proof`가 알 수 없다. `recorderRuntime`에는 receipt를 기록한 서버 JVM의 판 전체(`Runtime.version().toString()`)를, `platform`에는 서버 JVM이 보고하는 운영체제 이름·판·아키텍처를 적는다. receipt 계약은 2.0.0이다. 5.x의 1.0.0에 있던 `nodeVersion`과 `signal`은 없다(JDK는 종료시킨 신호를 알려 주지 않는다). `check-proof`는 1.0.0 receipt를 `practices.core.runReceiptVersion1IsNotAccepted`로 거부하고 변환하지 않으므로, 새 서버로 명령을 다시 실행해 2.0.0 receipt를 받는다.

status는 snapshot 대상 파일을 실행 전후로 비교해 정한다. 실행 뒤 대상 파일의 digest가 달라졌거나 읽을 수 없으면 종료 코드와 상관없이 `INPUT_CHANGED`(종료 코드는 그대로 기록)이고, 그렇지 않으면 종료 코드 0은 `PASS`, 그 밖은 `FAIL`이다. 제한 시간에 끝나지 않으면 `TIMED_OUT`(`exitCode` null)이다. 프로세스를 시작하지 못했거나 출력이 1MiB를 넘었거나 서버가 정지했으면 `SPAWN_ERROR`다. 시간초과·실행 실패·snapshot 대상 변경은 정상 PASS가 아니다. 실행 전에 snapshot이 현재 파일과 같은지 확인하고, 다르면 실행하지 않고 거부한다.

프로세스 종료: 서버는 실행 중인 command를 모두 추적하고, 자손은 `ProcessHandle.descendants()`로 시작 직후와 실행 중 200ms마다 찾아 핸들을 보관한다(자식과, 이미 찾은 살아 있는 프로세스 각각에서 찾는다). 시간 초과·출력 한도 초과·서버 정지 때는 자식과 보관한 자손, 그 시점의 자손을 강제 종료하고 모두 끝날 때까지 최대 10초 기다린다. 서버 정지(SmartLifecycle)는 실행 중인 command를 모두 이렇게 종료하고 이후 run은 `practices.runner.serverIsStopping`으로 거부하며, 정지로 끝난 run은 `SPAWN_ERROR`다. 자식이 스스로 끝났어도 그것이 시작해 아직 살아 있는 자손(찾아 둔 것)은 종료한다. 자식이 끝나면 찾아 둔 자손을 종료한 뒤 파이프에 들어 있는 출력을 모두 읽고, 부모에서 떨어져 나간 프로세스가 계속 쓰고 있어 파이프가 비지 않으면 최대 2초 뒤 읽기를 끝내고 파이프를 닫는다. 파이프를 쥐고 쓰지 않는 프로세스가 있어도 run이 답하는 시점에는 읽는 스레드와 파이프가 남지 않는다. 그 프로세스가 이후 쓰면 파이프 끊김 오류를 받고, 그 뒤에 쓴 바이트는 결과에 남지 않으며 디스크에도 쓰이지 않는다. 첫 찾기 전에 부모에서 떨어져 나간 프로세스(예: `sh -c 'sleep 300 &'`처럼 셸이 곧바로 끝나는 경우, Windows에서 `start /b`를 두 번 거는 경우)와 두 번의 찾기 사이에 시작해 부모에서 떨어져 나간 프로세스는 찾지 못해 종료되지 않으며, run이 끝난 뒤에도 계속 실행된다(출력에서만 끊기고, 파이프 끊김 신호를 무시하고 계속 도는 프로세스는 계속 돈다). 프로세스 그룹이나 Job Object 없이는 이런 프로세스를 잡을 수 없다. `run`은 임의 프로그램을 실행할 수 있으므로 허용된 작업·격리된 디렉터리에서만 호출한다. 네트워크 격리와 자격증명 보호는 하지 않는다.

receipt는 서명 없는 로컬 기록이다. 로그에 assertion 문자열이 있다는 사실만으로 의도한 결함이 그 assertion을 발생시켰다고 증명하지 않는다. 에이전트/검토자는 원시 실패 내용과 실제 테스트 경로를 읽어 원인을 대조해야 한다. 종료 코드 0은 호출한 프로세스의 성공이지 테스트 개수·의미·외부 통합 성공의 증명이 아니다. 파일에 없던 환경 입력과 명령 실행 파일 자체의 진본성도 별도 확인 사항이다.

파일당 snapshot 최대 8MiB, 총 64MiB, 최대 1000파일이다. JSON/receipt에도 별도 크기 제한이 있다. 읽는 동안 파일 자체 변화는 탐지하지만, 공격적인 동시 프로세스의 상위 디렉터리 교체·hardlink로부터 격리하는 sandbox는 아니다. artifact root는 신뢰할 수 있는 private directory로 관리한다.
