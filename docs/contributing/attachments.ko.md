[English](attachments.md)

# 첨부와 업로드

이 문서는 Sennard 6.0.0이 첨부(단계 결과의 근거 파일)를 저장하고 업로드하는 방식을 설명합니다. 설계는 장부·SSH 제품과 공통이며(AGS3-D-0264), 세 제품이 같은 방식으로 구현합니다. 코드는 `io.github.jaeseongs95.sennard.app.attachment`(`sennard-app`)에 있습니다. 표는 `V21__attachment.sql`(`sennard-store-pg`)이 아래의 묶음 형태로 만듭니다. 도구 선언은 `ArtifactTools`(`sennard-server`)에 있습니다.

## 목적과 근거

- **첨부는 파일의 묶음입니다.** 한 번의 업로드에 파일을 여러 개(최대 64개) 실을 수 있고, 이 파일들은 ID 하나의 첨부로 함께 보관됩니다. 호출자가 파일을 하나로 첨부하려고 일부러 압축할 필요가 없습니다. 기록은 첨부(ID)를 가리키거나, 첨부의 파일 하나(ID와 파일 번호 `seq`)를 가리킵니다. 첨부의 파일은 하나씩 검증하고(크기, sha256), 모두 통과했을 때만 첨부를 만듭니다.
- **첨부에 관한 모든 것을 감사를 위해 보관합니다.** 보관된 첨부의 세 표(`attachment`, `attachment_file`, `attachment_storage`)는 `INSERT`만 받습니다. `UPDATE`도 `DELETE`도 없고, 「사용함」이나 「삭제함」 표시도 없습니다. 시간에 따라 달라지는 것(다른 키로 다시 쓴 파일)은 바뀐 행이 아니라 새 행입니다.
- **파일 본문은 DB에 두지 않습니다.** 서버 자신의 폴더에 있는 파일에 암호화한 세그먼트로 저장합니다. 표에는 메타데이터와 파일 이름만 둡니다. 본문을 담는 BLOB·BYTEA·CLOB·긴 텍스트 열은 없습니다. 이렇게 하면 DB의 테이블스페이스가 커지지 않고, DB 복사본만으로는 파일 내용을 알 수 없습니다.
- **키 없이는 파일을 읽을 수 없습니다.** 폴더에 접근한 사람이 파일이 무엇인지 알 수 없도록 이름은 무작위이고 내용은 AES-256-GCM 암호문입니다. 키는 별도 폴더에 둡니다.
- **FK를 두지 않습니다.** 첨부를 가리키는 기록은 첨부의 기본 키 값만 담습니다. 기록을 쓰는 유스케이스가 첨부가 있는지, 기록 작성자가 만든 첨부인지 검사합니다(아래 「단계 결과와의 연결」).
- **프로토콜은 하나입니다.** 첨부가 파일 하나이든 여럿이든 모델은 같은 도구 다섯 개를 부릅니다. 서버는 이 도구로 호출자 PC의 경로를 읽지 않으며, 바이트는 청크로 도착합니다.
- **구현은 하나입니다.** 파일의 쓰기·읽기·닫기·이동·삭제·점검은 업로드만 맡는 `AttachmentFileStore`가 합니다.

## 표

네 표는 모두 `sennard` 스키마에 있습니다. 시각 열은 모두 주입된 `Clock`이 쓴 UTC 값을 담는 `TIMESTAMP`입니다. 어느 표에도 FK가 없습니다.

### `upload_staging`

진행 중인 업로드입니다. 업로드하는 첨부의 파일마다 한 행입니다. 네 표 가운데 바뀌는 표는 이것 하나입니다.

| 열 | 형 | 뜻 |
| --- | --- | --- |
| `id` | `CHAR(36)` | 기본 키. 파일 하나의 업로드 ID |
| `attachment_id` | `CHAR(36)` | finish가 만드는 첨부의 ID. 함께 begin한 파일은 이 값을 공유하므로 첨부 ID는 begin부터 알려져 있음 |
| `seq` | `INTEGER` | 첨부 안의 파일 번호. `artifact_begin`의 `files` 목록에서의 위치이며 0부터 시작. `CHECK (seq >= 0)` |
| `owner` | `VARCHAR(255)` | 작성자. 호출한 세션의 actor ID |
| `operation_key` | `VARCHAR(128)` | `artifact_begin`의 `operationKey` |
| `file_name` | `VARCHAR(512)` | 선언한 파일 이름 |
| `media_type` | `VARCHAR(255)` | 선언한 미디어 형식 |
| `declared_size` | `BIGINT` | 선언한 파일 전체 크기(바이트) |
| `declared_sha256` | `CHAR(64)` | 선언한 파일 전체 sha256(16진수 64자) |
| `received_bytes` | `BIGINT` | 받은 평문 바이트 수 |
| `stored_bytes` | `BIGINT` | 행이 반영한 파일 크기 |
| `next_sequence` | `BIGINT` | 다음 청크 번호 |
| `last_chunk_sha256` | `CHAR(64)` | 마지막으로 반영한 청크의 sha256. 첫 청크 전에는 null |
| `storage_key` | `CHAR(32)` | 파일의 무작위 이름 |
| `key_id` | `VARCHAR(64)` | 파일을 쓰는 키 |
| `state` | `VARCHAR(16)` | `STAGING` 또는 `COMPLETED` |
| `expires_at`, `created_at`, `updated_at` | `TIMESTAMP` | 만료 시각과 기록 시각 |

제약: `UNIQUE (owner, operation_key, seq)`(파일이 여럿인 begin 하나는 같은 operation key 아래에 파일마다 한 행을 만듦), `UNIQUE (attachment_id, seq)`, `UNIQUE (storage_key)`, `CHECK (state IN ('STAGING', 'COMPLETED'))`, 네 카운터가 음수가 아니라는 `CHECK`. `(state, expires_at)` 인덱스는 정리기가 씁니다. 한 첨부의 파일들은 만료 시각이 항상 같습니다. 어느 파일의 청크든 모든 파일의 만료를 다시 세므로, 첨부는 통째로 살고 통째로 죽습니다.

### `attachment`

보관된 첨부, 곧 묶음입니다. 기록이므로 행은 넣기만 하고 바꾸지 않습니다. 본문 열은 없습니다. 첨부를 가리키는 기록은 이 키만 담습니다.

| 열 | 형 | 뜻 |
| --- | --- | --- |
| `id` | `CHAR(36)` | 기본 키. begin부터 업로드 행의 `attachment_id`였던 값이므로, `upload_staging`에서 `attachment`를 가리키는 열도, 그 반대도 없음. 업로드 행이 지워진 뒤에 다시 보낸 `artifact_finish`도 같은 ID로 첨부를 찾음 |
| `created_by` | `VARCHAR(255)` | 작성자 |
| `created_at` | `TIMESTAMP` | |

### `attachment_file`

첨부의 파일 하나이며, 보낸 그대로의 파일 사실을 담습니다. 기록이므로 행은 넣기만 하고 바꾸지 않습니다.

| 열 | 형 | 뜻 |
| --- | --- | --- |
| `attachment_id` | `CHAR(36)` | 첨부(FK 없음) |
| `seq` | `INTEGER` | 첨부 안의 파일 번호. 0부터 시작하며 `artifact_begin`의 `files` 목록 순서. `CHECK (seq >= 0)` |
| `file_name`, `media_type` | `VARCHAR` | 선언한 값(검토 번들 파일의 파일 이름은 번들 폴더 아래의 경로) |
| `size_bytes` | `BIGINT` | 평문 바이트 수. `CHECK (size_bytes >= 0)` |
| `content_sha256` | `CHAR(64)` | 평문의 sha256 |

기본 키는 `(attachment_id, seq)` 쌍이며 대리 키는 없습니다. `artifact_finish`는 첨부의 모든 행을 `attachment` 행과 함께 트랜잭션 하나로 넣습니다.

### `attachment_storage`

파일 하나의 바이트가 있는 곳입니다. 파일이 저장된 사본마다 한 행이며, 기록이므로 행은 넣기만 하고 바꾸지 않습니다.

| 열 | 형 | 뜻 |
| --- | --- | --- |
| `attachment_id` | `CHAR(36)` | 첨부(FK 없음) |
| `seq` | `INTEGER` | 파일 |
| `rev` | `INTEGER` | `artifact_finish`가 닫은 사본은 0, 다시 암호화가 쓴 사본마다 하나씩 증가. `CHECK (rev >= 0)` |
| `storage_key` | `CHAR(32)` | 폴더에 있는 파일의 무작위 이름. `UNIQUE` |
| `key_id` | `VARCHAR(64)` | 사본을 쓰는 키 |
| `created_at` | `TIMESTAMP` | |

기본 키는 `(attachment_id, seq, rev)` 삼중이며 대리 키는 없습니다. 파일의 현재 사본은 `rev`가 가장 큰 행입니다(두 행의 시각이 같을 수 있어 `created_at`으로는 순서가 정해지지 않습니다). `artifact_finish`가 모든 파일의 `rev` 0을 넣습니다. 다시 암호화는 자기 트랜잭션에서 읽은 그 파일의 최대 `rev`에 1을 더해 넣습니다. 두 번의 다시 암호화가 같은 최대값을 읽으면 두 번째는 번역된 유일 키 충돌로 끝나며 다시 실행하지 않습니다.

아키텍처 검사 `w-sql-write-verb`는 `attachment`, `attachment_file`, `attachment_storage`에 `INSERT`만 허용합니다([아키텍처 검사](../sennard60-architecture-checks.ko.md)).

## 저장 형식

### 위치와 이름

- 본문은 `sennard.attachments.directory`에 있습니다. 키 파일은 `sennard.attachments.key-directory`에 있으며, 이 폴더는 앞의 폴더 밖이어야 합니다.
- 파일 이름(`storage_key`)은 `SecureRandom`의 128비트를 소문자 16진수(32자)로 쓴 것입니다. 하위 폴더는 이름의 앞 두 글자입니다. 이름에는 확장자·날짜·원본 파일 이름·ID·프로젝트가 들어가지 않습니다.
- 폴더와 파일은 만들어지는 순간부터 소유자만 접근하도록 만듭니다(POSIX 모드, 접근 제어 목록이 있는 파일 시스템에서는 소유자 항목 하나). 개인판은 PC 전체를 신뢰하므로 이미 있는 폴더와 파일은 그대로 씁니다. 소유자, 접근 권한, 링크는 거절 사유가 아닙니다. 거절은 설계와 다른 저장 경로(폴더 자리의 파일, 키 파일 자리의 폴더)와, 소유자 전용으로 만들 수 없는 파일 시스템(권한 비트도 접근 제어 목록도 없는 FAT 등, 또는 시스템이 모르는 실행 계정)입니다. 이때 `ARTIFACT_STORAGE_UNSAFE`를 냅니다. 비었거나 256비트가 아닌 키 파일은 쓸 수 있는 키가 아니므로 `ARTIFACT_KEY_MISSING`을 냅니다(`details.reason`은 `empty` 또는 `wrong-length`).
- 닫힌 파일은 읽기 전용입니다. 파일이 닫혔는지는 권한 비트가 아니라 행 상태(`COMPLETED`)와 끝 표시로 판정합니다. root로 실행하는 프로세스는 권한 비트와 상관없이 쓰기 때문입니다.

### 세그먼트

청크 하나가 세그먼트 하나입니다. 암호는 JDK의 `AES/GCM/NoPadding`, 키는 256비트이며 제조사 라이브러리는 쓰지 않습니다. 디스크의 세그먼트는 다음과 같습니다.

| 부분 | 크기 | 내용 |
| --- | --- | --- |
| 길이 | 4바이트 | 뒤따르는 바이트 수(IV, 암호문, 태그). big-endian |
| IV | 12바이트 | 세그먼트마다 새로 만듦 |
| 암호문 | n바이트 | 암호화한 청크 |
| 태그 | 16바이트 | GCM 태그 |

길이 덕분에 청크 크기가 달라도 경계를 찾을 수 있습니다. 길이를 바꾸면 경계가 어긋나 태그 검증이 실패합니다.

추가 인증 데이터(AAD)는 `storage_key`, 세그먼트 번호, 마지막 세그먼트인지를 나타내는 표시입니다. 그래서 다른 파일로 옮긴 세그먼트, 순서를 바꾼 세그먼트, 뒤가 잘린 파일은 복호화에 실패합니다.

`artifact_finish` 전에는 어느 청크가 마지막인지 모르므로 청크는 모두 「마지막 아님」으로 씁니다. 끝 표시는 빈 평문의 세그먼트 하나이며 「마지막」으로 표시합니다. `artifact_finish`가 파일 전체를 검증한 뒤에 덧붙입니다. 끝 표시가 없는 파일을 읽으면 잘린 파일로 보고합니다. 디스크에서 알 수 있는 것은 파일 크기뿐입니다.

## 덧붙이기와 파일 잠금

진입점(`AttachmentUploads`, `AttachmentFileStore`)이 트랜잭션 밖에서 덧붙이고, 서비스가 그 결과를 트랜잭션 하나로 행에 반영합니다.

1. 읽기 전용 유스케이스가 행의 `next_sequence`, `stored_bytes`, `last_chunk_sha256`을 읽습니다. 청크 번호가 `next_sequence - 1`이고 청크 해시가 `last_chunk_sha256`과 같으면 이미 반영된 청크이므로 덧붙이지 않고 현재 상태를 답합니다. `next_sequence`가 아닌 번호는 `ARTIFACT_CHUNK_CONFLICT`입니다.
2. 파일을 열고 파일 끝 너머의 1바이트를 잠급니다: `FileChannel.tryLock(Long.MAX_VALUE - 1, 1, false)`. 덧붙이는 쪽끼리만 서로 막습니다. read와 status는 잠금이 닿지 않는 데이터 구간을 읽으므로 막히지 않습니다(Windows는 잠긴 구간의 읽기를 막으므로 데이터 구간에는 잠금을 걸지 않습니다). 잠금은 한 번만 시도합니다. 다른 프로세스가 잡고 있거나 같은 JVM의 다른 요청이 잡고 있으면(`OverlappingFileLockException`) `ARTIFACT_BUSY`를 답합니다. 기다리거나 다시 시도하지 않습니다.
3. 잠금 안에서 파일 크기를 `stored_bytes`와 비교합니다.
   - 같음: 세그먼트를 덧붙이고 `force(true)`를 호출합니다.
   - 큼: 꼬리 `[stored_bytes, 크기)`는 앞선 요청이 덧붙였지만 행이 아직 반영하지 못한 세그먼트입니다(트랜잭션이 졌음). 이 청크의 번호를 AAD로 복호화해 평문 해시를 이 청크의 해시와 비교합니다. 같으면 덧붙이지 않습니다(같은 청크의 재전송이거나 경합에서 진 쪽의 재시도). 다르면 `ARTIFACT_CHUNK_CONFLICT`입니다. 꼬리는 자르지 않습니다. 진행 중인 다른 요청의 세그먼트일 수 있기 때문입니다.
   - 작음: 손상이므로 `ARTIFACT_CORRUPTED`입니다.
4. 서비스가 조건부 `UPDATE upload_staging SET stored_bytes = ?, received_bytes = ?, next_sequence = ?, last_chunk_sha256 = ?, expires_at = ?, updated_at = ? WHERE id = ? AND next_sequence = ? AND state = 'STAGING'`를 실행합니다. 바뀐 행이 없으면 행을 다시 읽습니다. 이미 반영된 같은 청크는 전과 같이 답하고, 그 밖에는 `ARTIFACT_CHUNK_CONFLICT`입니다. 다시 시도하지 않습니다. 행이 청크를 반영했으면 같은 트랜잭션에서 첨부의 모든 파일의 만료를 다시 셉니다(`UPDATE upload_staging SET expires_at = ?, updated_at = ? WHERE attachment_id = ? AND state = 'STAGING'`).

바이트 순서는 파일 잠금이 지키고, 행이 어느 청크를 반영했는지는 조건부 `UPDATE`가 정합니다. 청크 크기는 설정이 아니라 계약 상수 `AttachmentFileStore.MAX_CHUNK_BYTES = 262144`바이트(256KiB)입니다. `artifact_begin` 응답의 `maxChunkBytes`가 이 값입니다. 덧붙이기와 끝 표시를 쓰기 전에는 항상 디스크 여유분(`disk-margin-bytes`)을 확인합니다.

## finish

`artifact_finish`는 첨부의 ID를 받아 그 첨부의 모든 파일을 마무리합니다. 진입점이 파일을 번호 순서로 하나씩 처리하며, 파일마다 전체를 먼저 검증하고 통과했을 때만 닫습니다(1~3단계). 모든 파일이 통과했을 때만 서비스가 첨부를 만듭니다(4단계). 파일 하나가 실패하면 행을 하나도 쓰기 전에 finish가 끝납니다. 첨부도, 파일 행도, 사본 행도 만들어지지 않고, 업로드 행은 `STAGING`으로 남으며, 업로드는 만료될 때까지 이어 갈 수 있습니다. 실패한 파일보다 앞서 닫힌 파일은 닫힌 채로 남습니다. 다음 finish가 그 파일을 다시 검증하고, 끝 표시를 찾아 두 번째로는 아무것도 덧붙이지 않습니다.

1. 진입점이 같은 잠금 아래에서 파일을 읽습니다. 파일이 `stored_bytes`보다 길면 꼬리가 정확히 끝 표시(빈 평문, 마지막 표시, AAD 통과)여야 합니다. 앞선 finish가 끝 표시를 덧붙인 뒤 트랜잭션에서 진 경우이므로, 끝 표시 앞까지 검증을 이어 가고 다시 덧붙이지 않습니다. 그 밖의 꼬리는 `ARTIFACT_INCOMPLETE`입니다.
2. 끝 표시 앞의 모든 세그먼트를 복호화해 태그를 검증하고, 평문의 sha256과 크기를 계산합니다. 선언보다 바이트가 적으면 `ARTIFACT_INCOMPLETE`, 많거나 sha256이 다르면 `ARTIFACT_HASH_MISMATCH`입니다. 어느 경우에도 파일을 닫지 않고 행은 `STAGING`으로 남으므로 만료될 때까지 같은 업로드를 이어 갈 수 있습니다.
3. 끝 표시 세그먼트를 덧붙이고 `force(true)`를 호출한 뒤 파일을 읽기 전용으로 바꿉니다. 이미 있는 이름은 덮어쓰지 않습니다.
4. 서비스(트랜잭션 하나)가 모든 파일의 검증한 크기·sha256을 선언값과 대조하고, 모든 업로드 행을 `UPDATE ... WHERE id = ? AND state = 'STAGING' AND expires_at > ?`로 `COMPLETED`로 바꾸고, `attachment` 행, 파일마다 `attachment_file` 행, 파일마다 `rev` 0의 `attachment_storage` 행을 넣습니다. 전부 들어가거나 하나도 들어가지 않습니다. 더는 `STAGING`이 아닌 행(묶음이 만료돼 정리되었거나 다른 finish가 먼저 완료함)이 있으면 트랜잭션이 끝나며, 이긴 다른 finish의 첨부는 그대로 답합니다. `artifact_finish`를 다시 부르면 정리기가 업로드 행을 지운 뒤에도 같은 첨부를 답합니다.

## 도구

공개 MCP 도구는 `artifact_begin`, `artifact_finish`, `artifact_status`, `artifact_read`입니다. 각 입력에는 `schemaVersion`(`"1.0.0"`)이 필요하며 `contracts/artifact-*-request.v1.schema.json`을 따릅니다. 첨부의 작성자는 `artifact_begin`을 호출한 세션입니다. 업로드 클라이언트는 `POST /attachments/upload` 요청의 `file` 파트로 파일을 보내고, `X-Sennard-Upload-Id` 헤더에 업로드 ID를 넣습니다. `artifact_status`는 업로드 ID로 조회할 수 있고 `artifact_finish`는 첨부 ID로 호출할 수 있습니다. 완료된 첨부의 상태 조회, `artifact_read`, `artifact_begin`에는 세션이 필요합니다.

`sha256` 값은 `sha256:<소문자 16진수 64자>`로 씁니다.

| 도구 | 입력 | 응답 |
| --- | --- | --- |
| `artifact_begin` | `operationKey`, `files`(1~64개 항목. 항목은 `fileName`(1~512자), `mediaType`(1~255자), `size`(바이트, 0 이상), `sha256`(파일 전체)), `purpose`(선택) | `attachmentId`(finish가 만드는 ID), `files`(목록 순서대로 파일마다 `seq`와 `uploadId`), `maxChunkBytes`(262144), `expiresAt` |
| `artifact_finish` | `attachmentId` | `attachmentId`, `files`(파일마다 `seq`, `fileName`, `mediaType`, `size`, `sha256`) |
| `artifact_status` | `uploadId`와 `attachmentId` 중 정확히 하나 | `uploadId`로 조회하면 `kind` `upload`, `uploadId`, `attachmentId`, `seq`, `state`, `fileName`, `mediaType`, `size`, `sha256`, `received`, `expiresAt`, `sequence`. `attachmentId`로 조회하면 `kind` `attachment`, `attachmentId`, `state`, `files`(finish와 같음). 경로나 저장 키는 답하지 않음 |
| `artifact_read` | `attachmentId`, `seq`(파일. 기본 0), `offsetBytes`(기본 0), `maxBytes`(1~262144, 기본 262144) | `attachmentId`, `seq`, `offsetBytes`, `bytes`, `size`(파일 전체), `end`(페이지가 끝에 닿음), `data`(base64), `text`(미디어 형식이 `text/`로 시작하고 페이지가 온전한 UTF-8일 때만) |

규칙:

- **`operationKey`**는 `artifact_begin`에 전달합니다. 영문자, 숫자, `_ . : -`로 구성된 16~128자입니다. 같은 작성자가 같은 키로 파일 이름, 크기, 해시를 같은 순서로 보내면 기존 첨부 ID와 업로드 ID를 돌려줍니다. 목록이 다르면 `ARTIFACT_OPERATION_CONFLICT`를 반환합니다. 시작할 때 발급한 무작위 ID로 파일을 전송하고 첨부를 완료할 수 있습니다. 작성자는 시작한 세션이며 같은 첨부 ID로 완료를 다시 요청하면 기존 첨부를 돌려줍니다.
- **첨부의 파일.** 파일은 `artifact_begin`의 `files` 목록 순서대로 0, 1, 2 ... 번호를 받습니다. 이 번호(`seq`)로 단계 결과가 파일 하나를 가리킵니다. 첨부 하나는 최대 64개 파일입니다(`ARTIFACT_LIMIT`, `details.limit`는 `file-count`, `details.requested`와 `details.allowed`는 파일 개수). 파일이 하나인 첨부가 일반적인 경우입니다. `files`에 항목이 하나이고 파일은 `seq` 0(또는 `seq` 없음)으로 가리킵니다.
- **`purpose`**는 `upload`, `staged`, `evidence` 중 하나입니다. Sennard는 `evidence`만 지원하며 `purpose`를 생략하면 `evidence`입니다. 나머지 둘은 `ARTIFACT_UNSUPPORTED_PURPOSE`로 거부합니다.
- **한도**(파일 개수, 파일 크기, 예산, 작성자당 열린 업로드 수)는 `artifact_begin`에서 검사합니다. 한도를 넘는 요청은 잘라 내지 않고 통째로 거부합니다. 크기 한도는 파일마다 적용하고, 예산은 파일 크기의 합으로 셉니다. 작성자의 열린 업로드는 첨부 단위로 세므로 파일이 여럿인 첨부도 하나로 셉니다. 업로드를 시작하기 전에 모든 파일의 선언 크기가 디스크에 들어가는지 한 번 확인합니다.
- **같은 순간의 두 begin.** 같은 작성자가 같은 `operationKey`로 `artifact_begin`을 두 번 불렀는데 둘 다 업로드가 없는 것을 보고 같은 순간에 INSERT하면, 유일 키 `(owner, operation_key)`가 하나만 통과시킵니다. 진 쪽은 저장소의 유일 키 위반 그대로가 아니라 `ARTIFACT_BUSY`(`details.reason`은 `begin-in-progress`, `retryable` true)로 답합니다. 이 답은 유스케이스의 트랜잭션이 끝난 뒤 진입점이 만듭니다. 유스케이스는 위반을 잡아서 이어 가지 않고, 서버는 begin을 다시 보내지도 않습니다. 같은 `artifact_begin`을 다시 보내면 이긴 쪽의 `uploadId`로 답합니다(내용이 다르면 `ARTIFACT_OPERATION_CONFLICT`).
- **동시 begin의 받아들인 한계.** 한도는 격리 수준 `READ COMMITTED`의 트랜잭션에서 행을 읽어 검사하며, 두 begin 사이에는 잠금이 없습니다. 그래서 `operationKey`가 다른 begin이 같은 순간에 돌면, 그 순간에 선언한 만큼 열린 업로드 수(`max-open-per-owner`)와 예산(`staging-budget-bytes`, `attachment-budget-bytes`)을 함께 넘을 수 있습니다. 이 한계는 받아들입니다. 한도는 회계가 아니라 디스크를 지키는 장치이고, 이미 시작한 업로드는 잘라 내지 않으며, 다음 `artifact_begin`은 그 업로드를 모두 보고 끝나거나 만료될 때까지 거부합니다.
- **청크.** 마지막 청크를 같은 내용으로 다시 보내면 전과 같이 답합니다. 같은 번호에 다른 내용, 순서가 어긋난 번호, `COMPLETED`인 업로드에 보낸 청크는 `ARTIFACT_CHUNK_CONFLICT`입니다. 청크 해시가 맞지 않으면 `CHUNK_HASH_MISMATCH`입니다. 선언 크기를 넘게 되는 청크는 덧붙이기 전에 `ARTIFACT_HASH_MISMATCH`로 거부합니다. 청크를 반영할 때마다 첨부의 모든 파일의 만료 시각을 그 시점부터 다시 셉니다(`upload-ttl`). 그래서 첨부는 통째로 살고 통째로 죽으며, 일찍 보낸 파일이 다른 파일을 보내는 동안 정리되는 일이 없습니다. 만료된 업로드의 청크는 `ARTIFACT_EXPIRED`이고, 알 수 없는 `uploadId`는 `ARTIFACT_NOT_FOUND`이고, 올바른 `uploadId`의 청크는 어느 세션이나 클라이언트가 보내도 받습니다.
- **read.** 첨부의 파일 하나를 읽습니다(`seq`. 첨부에 파일이 없는 번호는 `ARTIFACT_NOT_FOUND`). 요청한 페이지의 세그먼트를 복호화해 태그를 검증합니다. 페이지가 파일 끝에 닿으면 끝 표시와 평문 크기도 확인합니다. 전체 sha256은 페이지마다가 아니라 `artifact_finish`와 `sennard attachments check`가 확인합니다.
- 도구는 멱등이며 파괴적이지 않습니다. `artifact_status`와 `artifact_read`는 읽기 전용입니다.

## 설정

모든 키는 `AttachmentProperties`(`@ConfigurationProperties("sennard.attachments")`) 한 record에 있습니다. 크기는 Spring의 `DataSize`입니다. `64MB`는 64메비바이트(각 1,048,576바이트)입니다.

| 키 | 기본값 | 뜻 |
| --- | --- | --- |
| `sennard.attachments.directory` | 코드에는 없음. `application.yaml`이 `${sennard.state-dir}/attachments`를 줌 | 본문을 쓰는 위치 |
| `sennard.attachments.key-directory` | 코드에는 없음. `application.yaml`이 `${sennard.state-dir}/secrets/attachment-keys`를 줌 | 키 파일 위치. `directory` 밖이어야 하며, 안이면 설정 바인딩이 실패해 서버가 시작하지 않음(설정 실패이며 도구 오류 코드가 아님) |
| `sennard.attachments.active-key-id` | `primary` | 새 업로드를 쓰는 키 |
| `sennard.attachments.max-file-bytes` | `1GB` | 첨부의 파일 하나의 최대 크기 |
| `sennard.attachments.staging-budget-bytes` | `4GB` | 열린 업로드가 선언한 크기의 합 한도 |
| `sennard.attachments.attachment-budget-bytes` | `10GB` | 보관 첨부 합계, 열린 업로드의 선언 크기 합계, 새 업로드의 합 한도. 넘는 새 `artifact_begin`은 거절하고 보관 첨부는 지우지 않음 |
| `sennard.attachments.max-open-per-owner` | `4` | 작성자당 열린 업로드 수(1 이상) |
| `sennard.attachments.disk-margin-bytes` | `64MB` | 파일을 쓴 뒤에도 디스크에 남겨야 하는 공간. 모자라면 쓰기를 `ARTIFACT_STORAGE_FULL`로 거부. 비교식은 `usable - size < margin`. 기본값은 파일 상한과 별개로 64 MiB를 남김. 관리자가 조정하는 자원 정책이며 측정값은 아님 |
| `sennard.attachments.upload-ttl` | `10m` | 업로드 만료 시간. 마지막 청크부터 다시 셈 |
| `sennard.attachments.orphan-grace` | `24h` | 행 없는 파일과 대체된 파일을 `orphans/`로 옮기기 전의 유예 |
| `sennard.attachments.reaper-interval` | `1m` | 정리기 주기(한 바퀴가 끝난 뒤의 고정 지연). 정리기는 만료된 업로드 행과 완료된 업로드 행을 DB에서 찾아 지우기만 함 |
| `sennard.attachments.reconcile-interval` | `24h` | 폴더와 행을 대조하는 주기(대조가 끝난 뒤의 고정 지연). 행 없는 파일, 대체된 파일, `orphans/`의 파일을 다룸. 시작 뒤 저장소가 준비됐을 때 한 번, 그리고 `--yes` 없는 `sennard attachments purge-orphans`에서도 돎. 기본값은 `orphan-grace`의 기본값과 같음. 파일은 `orphan-grace`가 지나야 옮기므로 그보다 자주 대조하면 같은 파일을 다시 볼 뿐이고, 유예마다 한 번이면 파일을 많아야 주기 하나만큼 늦게 옮김. 측정이 아니라 판단임. 대조는 폴더의 모든 파일을 파일마다 한 번에서 세 번 조회하므로 정리기처럼 1분마다 돌리지 않음 |

청크 크기와 첨부의 파일 개수를 정하는 키는 없습니다. 상수 262144바이트와 64입니다(`AttachmentFileStore.MAX_CHUNK_BYTES`, `AttachmentService.MAX_FILES`). 양수가 아닌 값(`max-file-bytes`, `upload-ttl`, `reaper-interval`, `reconcile-interval`), 음수인 `orphan-grace`, 올바르지 않은 `active-key-id`는 시작을 막습니다.

## 오류 코드

도구 실패는 `error.code`와 `details.retryable`을 싣습니다. 같은 요청을 나중에 다시 보내 성공할 수 있으면 true입니다. 제품이 스스로 다시 보내지는 않습니다(재시도는 통신 오류만). 모든 코드는 `ArtifactErrors`에서 만들므로 `retryable` 없이 나가는 코드는 없습니다. 이 코드들의 `error.code`는 코드 이름 자체이고 `reasonCode`는 없습니다. 범주는 예외 클래스입니다.

| 코드 | 범주 | 뜻 | `retryable` |
| --- | --- | --- | --- |
| `ARTIFACT_OPERATION_CONFLICT` | Conflict | 같은 작성자의 같은 `operationKey`로 다른 업로드를 시작하려 함 | false |
| `ARTIFACT_CHUNK_CONFLICT` | Conflict | 순서가 어긋난 청크, 또는 이미 받은 번호에 다른 내용이 옴(`details.reason`) | false |
| `CHUNK_HASH_MISMATCH` | Integrity | 청크의 sha256이 선언과 다름 | false |
| `ARTIFACT_BUSY` | Conflict | 같은 업로드에 대한 다른 요청이 진행 중. 덧붙이기(같은 청크를 나중에 다시 보냄)이거나, 같은 `operationKey`의 같은 begin(`details.reason`은 `begin-in-progress`, 같은 begin을 다시 보내 이긴 쪽의 `uploadId`를 받음) | true |
| `ARTIFACT_INCOMPLETE` | Conflict | 행이 반영하지 않은 꼬리가 있거나 선언보다 바이트가 적음. 빠진 청크를 보내야 하며 같은 `artifact_finish`를 다시 보내서는 통과하지 않음. 파일 하나라도 덜 왔으면 첨부는 만들어지지 않음 | false |
| `ARTIFACT_HASH_MISMATCH` | Integrity | 선언한 sha256이나 크기가 바이트와 다름(`details.reason`은 `size` 또는 `sha256`) | false |
| `ARTIFACT_CORRUPTED` | Integrity | 태그·해시·크기·끝 표시가 맞지 않거나 파일이 행보다 짧음(`details.reason`) | false |
| `ARTIFACT_LIMIT` | Unavailable | 한도 초과. `details.limit`이 어느 한도인지 알림(아래 표) | limit별 |
| `ARTIFACT_STORAGE_UNSAFE` | Unavailable | 저장 경로를 이 용도로 쓸 수 없음. 정규 파일이나 폴더가 아니거나(폴더 자리에 파일이 있는 경우 등), 그 파일 시스템에서 소유자 전용으로 만들 수 없음(FAT 등, 시스템이 모르는 실행 계정). `details.reason`이 어느 쪽인지 알려 줌. 개인판은 기존 경로의 소유자나 접근 권한으로 이 코드를 내지 않음 | false(소유자가 경로를 고쳐야 함) |
| `ARTIFACT_STORAGE_IO_ERROR` | Unavailable | 디스크를 읽거나 쓰지 못함 | false(소유자가 디스크를 고쳐야 함) |
| `ARTIFACT_KEY_MISSING` | Unavailable | 행이 가리키는 `key_id`에 쓸 수 있는 키가 없음. 키 파일이 없거나, 비었거나, 256비트가 아님(`details.keyId`, 뒤의 두 경우는 `details.reason`이 `empty` 또는 `wrong-length`) | false(소유자가 키 파일을 복구해야 함) |
| `ARTIFACT_STORAGE_FULL` | Unavailable | 남은 공간에서 파일을 빼면 `disk-margin-bytes`보다 작아짐(`details.requestedBytes`, `usableBytes`, `marginBytes`). 디스크를 읽지 못한 경우는 이 코드가 아니라 I/O 오류 | false(소유자가 디스크를 비워야 함) |
| `ARTIFACT_UNSUPPORTED_PURPOSE` | Invalid input | 제품이 보관하지 않는 `purpose` | false |
| `ARTIFACT_EXPIRED` | Conflict | 업로드가 만료됨 | false |
| `ARTIFACT_NOT_FOUND` | Not found | 해당 ID의 업로드나 첨부가 없음 | false |

`ARTIFACT_LIMIT`의 `details.limit` 값입니다(`details`에는 `requested`와 `allowed`도 있습니다).

| `limit` | 발생 조건 | `retryable` |
| --- | --- | --- |
| `file-size` | 파일이 `max-file-bytes`보다 큼 | false |
| `file-count` | 첨부의 파일이 64개를 넘음(`requested`와 `allowed`는 파일 개수) | false |
| `attachment-budget` | 보관 첨부, 열린 업로드의 선언 크기, 새 파일들의 크기의 합이 `attachment-budget-bytes`를 넘음. 소유자가 첨부를 정리해야 함 | false |
| `staging-budget` | 열린 업로드의 선언 크기와 새 파일들의 크기의 합이 `staging-budget-bytes`를 넘음. 업로드가 끝나거나 정리기가 치우면 풀림 | true |
| `open-uploads` | 작성자가 업로드가 열린 첨부를 `max-open-per-owner`개 가짐. 업로드가 끝나거나 정리기가 치우면 풀림 | true |

## 키

- 키 파일은 무작위 256비트이며 그 밖의 내용은 없습니다. 파일 이름이 키 ID입니다(영문자·숫자·`_`·`-`, 1~64자. 경로를 가리킬 수 없게 함). 만들어지는 순간부터 소유자만 읽을 수 있습니다. 키는 로그·행·메시지에 남기지 않습니다.
- 시작할 때(`ServerStartTasks`의 `attachmentStorage`, 요청을 받기 전) 폴더가 없으면 소유자 전용으로 만들고, 키 폴더에 키가 없으면 첫 키(`primary`)를 만들고, 현재 행이 가리키는 모든 키의 파일을 확인합니다. 진행 중인 각 업로드의 키와 첨부의 각 파일의 현재 사본(`rev` 최대)의 키입니다. `active-key-id`는 쓸 수 있는 키여야 합니다. 점검은 키 파일을 실제로 읽어 요청 때와 같은 기준으로 판정합니다. 그래서 첫 요청에서 실패할 키가 시작을 통과하는 일은 없습니다. 키 자리에 폴더가 있으면 시작이 `ARTIFACT_STORAGE_UNSAFE`(`details.reason`은 `key-not-a-file`)로 멈추고, 키 파일이 없거나 비었거나 256비트가 아니면 첫 업로드가 아니라 시작이 `ARTIFACT_KEY_MISSING`(뒤의 두 경우 `details.reason`은 `empty` 또는 `wrong-length`)으로 멈춥니다.
- **키 교체**: 새 키를 만들고(`sennard attachments keygen --id <keyId>`) `sennard.attachments.active-key-id`를 그 키로 바꿉니다. 새 업로드는 새 키를 쓰고, 옛 키로 쓴 파일은 행의 `key_id`가 고르는 옛 키로 계속 읽습니다.
- **다시 암호화**(`sennard attachments rekey --from <keyId>`): 그 키로 쓴 파일의 현재 사본마다 진입점이 복호화하며 태그·끝 표시·크기·sha256을 검증하고, 활성 키로 새 `storage_key`의 새 파일을 쓰고 디스크에 force한 뒤, 서비스가 그 파일의 다음 `rev`의 `attachment_storage` 행을 더합니다(트랜잭션 하나). 옛 사본은 대체된 파일이 됩니다(「정리」 참조). 행을 더하기 전에 실행이 죽으면 새 파일은 행 없는 파일이 되고 옛 행이 그대로 유효합니다. 실패로 멈춘 실행은 그 앞에서 한 일을 남기며 다시 시작할 수 있습니다. `--from`은 활성 키일 수 없습니다.
- 옛 키는 그 키를 가리키는 현재 행이 없어진 뒤에만, 소유자가 지웁니다. Sennard는 키를 지우지 않습니다.

## 정리

두 일이 각자의 주기로 따로 돕니다. 드는 비용이 다르기 때문입니다(`AttachmentReaperRounds`가 둘을 등록합니다).

- **정리기**(`AttachmentMaintenance.reap`)는 시작 `reaper-interval` 뒤에 첫 바퀴를 돌고, 이후 한 바퀴가 끝난 뒤 `reaper-interval`마다 다시 돕니다. 만료된 업로드 행과 완료된 업로드 행을 DB에서 찾아 지우기만 합니다(아래 첫 두 규칙). 폴더를 나열하지 않고 파일마다 묻지도 않습니다.
- **폴더와 행의 대조**(`AttachmentMaintenance.reconcileFolder`, 아래에서 파일을 옮기는 두 규칙)는 폴더의 모든 파일을 파일마다 한 번에서 세 번 조회합니다. 세 경우에 돕니다. 시작 뒤 저장소가 준비됐을 때 한 번(`ServerStartTasks`, 요청을 받은 뒤에 도는 작업이라 요청이 기다리지 않음), 그 뒤 `reconcile-interval`마다(첫 번째는 시작 `reconcile-interval` 뒤), 그리고 `--yes` 없이 `sennard attachments purge-orphans`를 실행할 때마다입니다.

실패한 바퀴나 대조는 로그에 남기고 다음 차례에 다시 합니다.

- **만료된 업로드.** 후보마다 `DELETE FROM upload_staging WHERE id = ? AND state = 'STAGING' AND expires_at <= ?`를 실행합니다. 조건 전체를 현재 행으로 다시 평가하므로 그사이 완료됐거나 만료 시각이 늘어난 업로드는 건너뜁니다. 한 행이 지워졌을 때만 파일을 지웁니다. 둘 사이에 프로세스가 죽으면 행 없는 파일이 남고, 다음 규칙이 처리합니다.
- **완료된 업로드.** `DELETE ... WHERE id = ? AND state = 'COMPLETED'`로 행을 지웁니다. 파일은 첨부의 것이므로 지우지 않습니다.
- **대체된 파일**(대조). `attachment_storage` 행은 있으나 그 파일의 현재 행이 아닌 파일(다시 암호화 뒤의 옛 파일)은 대체한 행을 쓴 뒤 `orphan-grace`가 지나면 `orphans/`로 옮깁니다. 옛 행으로 읽기를 시작한 요청이 사라진 파일을 만나지 않게 하기 위해서입니다.
- **행 없는 파일**(대조). `upload_staging`과 `attachment_storage`의 어느 행도 이름을 가리키지 않는 파일은 마지막으로 쓰인 뒤 `orphan-grace`가 지나면 `directory` 아래 `orphans/`로 옮깁니다. 지우지 않습니다. 옛 백업으로 DB를 되돌리면 정상 파일이 이렇게 보일 수 있기 때문입니다. `orphans/`의 파일 이름을 가리키는 행이 다시 생기면 대조가 제자리로 돌려놓습니다.
- **`orphans/` 비우기**는 명시 명령으로만 합니다: `sennard attachments purge-orphans --yes`. 지운 파일 이름은 로그(`ATTACHMENT_QUARANTINE_PURGED`)에 남습니다. 확인한 실행은 `orphans/`에 있는 파일을 지우며 그 전에 폴더를 대조하지 않습니다. 그래서 `--yes` 없는 실행이 나열한 파일을 그대로 지우고, 지금 대조가 옮길 파일을 보지 못한 채 지우는 일은 없습니다.
- **손상은 보고만 하고 고치지 않습니다.** 현재 파일이 없거나 태그·해시·크기·끝 표시가 맞지 않는 첨부는 `sennard attachments check`가 보고하며 지우지 않습니다. 어떤 기록도 가리키지 않는 첨부는 보존합니다.

## 백업과 복원

첨부 저장소 폴더, 키 폴더, DB를 함께 백업하고 복원합니다. 복원한 뒤 `sennard attachments check`가 발견 0건이어야 합니다. 옛 백업으로 되돌린 DB는 행 없는 정상 파일을 남길 수 있는데, 이 파일은 `orphan-grace` 뒤 `orphans/`로 옮겨지고 행이 다시 가리키면 제자리로 돌아옵니다. 키는 키 폴더에만 있으므로 키 폴더가 없는 백업은 읽을 수 없습니다.

## `sennard attachments` 명령

아래의 관리 동작은 서버 없이 실행하며(업로드 동작 `prepare`와 `send`는 "업로드 클라이언트"에서 설명합니다), 설치의 설정을 읽고 서버와 같은 방식으로 저장소를 여는 자체 Spring 컨텍스트에서 돕니다. 결과는 JSON으로 출력합니다. `check`가 무엇인가 발견하면 종료 코드는 1입니다.

| 명령 | 동작 |
| --- | --- |
| `sennard attachments check` | 행과 폴더를 대조합니다. 모든 첨부의 모든 파일의 현재 사본을 열어 검증하고(태그·끝 표시·크기·sha256), 열린 업로드의 파일을 확인하고, 어느 행도 가리키지 않는 파일을 나열합니다. 발견 종류는 `attachment-without-file`, `file-missing`, `damaged`, `file-without-row`이며, 파일에 관한 발견은 첨부를 `id`에, 그 안의 파일 번호를 `seq`에 따로 적습니다. 아무것도 바꾸지 않음 |
| `sennard attachments rekey --from <keyId>` | 그 키로 쓴 파일의 현재 사본을 활성 키로 다시 쓰고 건수를 출력 |
| `sennard attachments keygen --id <keyId>` | 새 키 파일을 만듦. 키 값은 출력하지 않음. 이미 있는 키는 바꾸지 않음 |
| `sennard attachments purge-orphans [--yes]` | `--yes` 없이는 먼저 폴더를 행과 대조하고(`orphan-grace`가 지난 행 없는 파일과 대체된 파일을 `orphans/`로 옮기고, 행이 다시 생긴 파일을 되돌림), `orphans/`의 파일을 나열만 하고 지우지 않음. `--yes`가 있으면 `orphans/`의 파일만 지우고 이름을 로그에 남김 |

명령을 잘못 쓴 경우는 입력 오류입니다. `INVALID_INPUT`이고 사유는 `VALIDATION_FAILED`이며, 문제는 `details.violations`에 담깁니다(예: `--from: ...`). `details.retryable`은 false입니다. 잘못 쓴 경우는 `rekey --from`이 활성 키를 가리키는 것, `keygen --id`가 키 ID(글자, 숫자, `_`, `-`, 1~64자)가 아닌 것, 그리고 `keygen --id`의 경로가 키 폴더에 이미 있는 것입니다. 이 경우에 `ARTIFACT_STORAGE_UNSAFE`를 내지 않습니다. 이 코드는 저장 경로를 쓸 수 없다는 뜻뿐이고, 소유자가 명령줄이 아니라 저장 위치를 고쳐야 할 때 씁니다.

## 관리자 저장 설정

로컬 관리자 페이지의 **일반 설정 → 첨부 저장**에서 저장 폴더의 절대 경로, 파일당 최대 크기와 전체 첨부 용량을 입력한 뒤 등록된 패스키로 저장하십시오. 용량 단위는 MiB입니다. 기존 전송 용량에 따라 설정할 수 있는 파일당 최대 크기와 전체 첨부 용량의 범위가 정해집니다. 서명한 설정은 이후 요청에 적용되며 서버를 다시 시작해도 유지됩니다. 상한을 낮추면 기존 첨부는 보존되며 새 업로드나 현재 파일 상한을 넘는 전송이 거절될 수 있습니다.

폴더를 바꿔도 파일은 이동하지 않습니다. 새 파일은 선택한 폴더에 쓰고, 완료된 첨부와 일부 기록된 파일은 이전 폴더를 계속 사용합니다. 조회와 유지보수는 검증된 관리자 로그의 위치 이력을 확인합니다. 이전 폴더와 별도의 암호화 키 폴더를 함께 백업하고, 다른 폴더를 선택했다는 이유로 이전 폴더를 지우거나 옮기지 않습니다. 저장 위치는 키 폴더와 겹칠 수 없습니다. 저장에 실패해도 입력은 보존됩니다. 다른 설정이 먼저 저장되었다면 필요한 값을 복사한 뒤 변경 취소로 최신 판을 불러와 다시 편집하고 서명합니다.

## 업로드 클라이언트

로컬 파일은 `sennard attachments upload --session-ref <sessionRef> [--name <fileName>] [--operation-key <key>] [--port <port>] <file>` 명령으로 한 번에 첨부합니다. 공용 클라이언트가 측정·준비·전송·완료를 수행하고 첨부 ID와 파일 메타데이터를 JSON 한 줄로 출력합니다. 파일 바이트는 모델에 전달되지 않습니다. 현재 세션 참조는 `open_session`에서 받습니다. 호스트가 관측된 호출자는 기존 세션을 돌려받고, 관측되지 않은 호출자는 발급받은 참조를 이후 호출에도 사용합니다. 세션 참조는 작성자 결속에 쓰며 호스트 관측을 증명하지 않습니다.

| 명령 | 동작 |
| --- | --- |
| `sennard attachments upload --session-ref <sessionRef> <file>` | 파일 하나를 자동으로 측정·준비·전송·완료합니다. 기본 작업 키는 정규화된 절대 경로·내용 해시·선언한 이름으로 정하며, 같은 명령을 다시 실행하면 같은 첨부를 반환합니다 |
| `sennard attachments prepare [--name <fileName>] <file>` | 연결 없이 로컬에서 측정한 `fileName`, `size`, `sha256`, `mediaType`을 출력합니다 |
| `sennard attachments send --upload-id <id> [--port <port>] <file>` | 따로 준비한 첨부의 파일 하나를 전송하고 `uploadId`, `attachmentId`, `seq`, `received`를 출력합니다. 모든 파일을 보낸 뒤 호출자가 묶음을 한 번 마무리합니다 |

파일 바이트는 `X-Sennard-Upload-Id`를 담은 표준 `multipart/form-data` POST 한 번으로 `/attachments/upload`에 전송합니다. 메타데이터는 `/mcp`의 MCP로 전달합니다. JDK가 로컬 파일을 스트리밍하고, Spring의 `StandardServletMultipartResolver`가 Servlet multipart Part(Servlet 3.0에서 도입한 방식)를 처리합니다. 컨테이너는 서버 상태 폴더 아래의 소유자 전용 `attachment-transfer` 폴더에 본문을 임시 저장하고, 메모리 임계값은 0으로 둡니다. 요청이 끝나면 임시 Part를 정리합니다. 서버는 선언한 길이와 파일 전체 해시를 검증한 뒤 최대 262144바이트 버퍼로 암호화 세그먼트를 기록합니다. 이어 보내기도 multipart 파일 전체를 전송하며, 서버가 이미 반영한 바이트를 건너뜁니다. 완료된 업로드는 기존 결과를 반환합니다.

파싱 전 필터는 Content-Length를 요구하고, 현재 서명된 파일 상한·선언한 크기(multipart 부가정보 최대 65536바이트 추가)·업로드 ID·임시 디스크 여유를 검사합니다. 기존 Origin/Host 검사가 먼저 적용됩니다. 해시가 다르면 업로드 진행량은 늘지 않으며, 같은 업로드의 동시 요청은 busy로 거절합니다. 이 상한은 디스크와 처리 자원을 위한 것이며 토큰용 1 MiB 제한은 없습니다.

클라이언트는 설치의 주소 파일로 서버를 찾고, `--port`로 포트를 직접 지정할 수 있습니다. 거절은 stderr에 `CODE: message`를 쓰고 종료 코드 1로 끝납니다. 전송 응답이 불명확하면 상태를 한 번 조회하고, 미완료이면 오류로 알립니다. 준비나 완료 응답이 불명확하면 `UPLOAD_RESULT_UNKNOWN`으로 알립니다. 같은 작업 키로 같은 업로드 명령을 다시 실행해 결과를 복구합니다. 변경 요청을 자동 재시도하지 않습니다. 만료된 업로드에는 새 작업 키가 필요합니다. 여러 파일을 묶을 때는 prepare → artifact_begin 한 번 → 파일마다 send → artifact_finish 한 번을 사용하며, 모델은 메타데이터만 전달합니다.

## 단계 결과와의 연결

단계 결과는 모든 파일을 첨부 id와 파일 번호로 가리킵니다(계약은 `contracts/stage-result.v1.schema.json`). `evidence[]`, `output.artifacts[]`, `outputFile`은 각각 `attachmentId`, `seq`(첨부 안의 파일 번호. 생략하면 0이며, 파일이 하나인 첨부의 유일한 파일), `digest`(그 파일의 sha256)를 담고, `claim_workflow_attempt`의 `priorFailure.evidenceRefs`는 첨부를 id로 가리킵니다. 서버는 호출자 컴퓨터의 경로를 읽지 않습니다. 결과를 기록하는 세션이 먼저 업로드 클라이언트로 파일을 첨부합니다. 그다음 돌려받은 `attachmentId`와 파일마다의 `seq`, `sha256`을 이 필드에 넣습니다. 여러 항목이 한 첨부의 서로 다른 파일을 가리킬 수 있습니다. 계약이 `attachmentId`와 함께 `digest`를 요구하므로, `digest`가 없으면 판정 전에 `INVALID_INPUT`으로 거절합니다.

- **포인터는 기록 트랜잭션 안에서 행으로 판정합니다.** `EvidenceAttachments`가 결과에 적힌 모든 첨부를 조회해 두 가지를 검사합니다. 첨부가 있는지, 만든 사람이 결과를 기록하는 세션과 같은지입니다. 하나라도 아니면 기록이 실패하고 아무것도 기록되지 않습니다. 오류는 `BINDING_INVALID`이며 `details.reason`은 `ATTACHMENT_NOT_FOUND` 또는 `ATTACHMENT_AUTHOR_MISMATCH`입니다. 작성자는 호출한 세션의 actor ID이며 `artifact_begin`이 `owner`로 기록하는 값과 같습니다. 트랜잭션 안에서는 파일을 읽지 않습니다.
- **digest는 행과 비교합니다.** 첨부의 sha256이 선언한 `digest`와 같으면 서버가 `artifactChecks`에 `SHA256_MATCHED`를 기록합니다. 다르면 통과하는 단계는 `BINDING_INVALID`(`details.reason` `ARTIFACT_DIGEST_MISMATCH`)로 실패하고, 다른 단계는 `NOT_CHECKED`로 기록합니다. 두 항목이 같은 첨부를 가리킬 수 있습니다.
- **출력 파일과 고정된 요청은 트랜잭션 앞에서 읽습니다.** 진입점이 각 첨부에서 `AttachmentContents`로 읽고(읽기·복호화·해시 계산은 트랜잭션 안에서 하지 않습니다) 내용을 유스케이스에 넘깁니다. 출력 파일은 16 MiB 이하이며 sha256이 선언한 `digest`와 같아야 합니다.
- **finalize는 호출자 컴퓨터의 파일을 읽지 않습니다.** 필수 감사는 감사한 첨부가 기록된 sha256 그대로 남아 있고 전체를 읽을 수 있는지 `AttachmentContents`로 확인합니다. 아니면 finalize가 `GATE_FAILED`(`details.reason` `AUDIT_TARGET_UNAVAILABLE`)로 실패합니다. 근거로 쓰인 첨부는 지워지지 않으므로, 업로드한 뒤 원본 파일을 바꾸거나 지워도 finalize는 실패하지 않습니다.
- **번들 검토 단계는 첨부로 판정합니다.** `cs-implementation-review`, `test-sensitivity-review`, `change-code-review` 단계는 파일 번들을 검사합니다. 번들의 manifest와 보고서는 단계 결과 `output.artifacts[]`의 산출물(`cs-review-bundle`과 `cs-review-report`, 또는 `engineering-stage-bundle`과 `engineering-test-result` / `engineering-review-result`)이고, manifest나 검사가 읽는 모든 원파일(task, plan 또는 request, proof 또는 report, result, 후보 파일)은 `evidence[]` 항목입니다. 모두 결과를 기록하는 세션이 업로드한 첨부이며 선언한 digest를 갖습니다. 첨부를 올릴 때의 파일 이름(`artifact_begin`의 `fileName`)이 번들 폴더 아래의 경로입니다. `/`로 구분한 이식 가능한 상대 경로이며, `..`·드라이브·역슬래시·예약 이름이 없고, 대소문자를 구분하지 않고 비교해도 겹치지 않아야 하며, 합계는 64 MiB 이하입니다. 진입점이 파일을 비공개 임시 폴더에 두고 그 안에서 바뀌지 않은 검사(`check-stage-bundle`: 서명된 task 결속, 원파일 digest, 후보 바이트)를 실행한 뒤 폴더를 지웁니다. 보고서 산출물은 검사가 읽은 그 첨부(같은 경로와 digest)여야 합니다. 쓸 수 없는 파일은 `INVALID_INPUT`(`workflow.bundle.fileNotUsable`)이며 `details.reason`은 `PATH_NOT_PORTABLE`, `DUPLICATE_PATH`, `TOO_LARGE`, `ATTACHMENT_NOT_FOUND` 중 하나입니다.
- **서버는 로컬 파일의 사본을 보관하지 않습니다.** 크기 한도(`max-file-bytes`)는 업로드에서 `artifact_begin`의 `ARTIFACT_LIMIT`로 적용합니다.

## 하위 호환

6.0.0은 5.x와 호환하지 않으며 호환 장치(shim)도 없습니다. 단계 결과는 `locator` 대신 `attachmentId`로 파일을 가리키고, 새 도구, V21의 표, `sennard.attachments` 설정이 새로 생깁니다. 6.0.0은 이전 판의 상태를 읽지 않으므로 5.x에서 옮길 것은 없습니다. [이전 주 판에서 올리는 순서](../release-notes/release-notes-v6.0.0.md)와 [상태 저장과 정리](../user/state.ko.md)를 참조하십시오.
