[한국어](attachments.ko.md)

# Attachments and Uploads

This document describes how Sennard stores and uploads attachments: the evidence files of a stage result. The code is in `io.github.jaeseongs95.sennard.app.attachment` (`sennard-app`), the tables are created by `V21__attachment.sql` (`sennard-store-pg`), and the tools are declared in `ArtifactTools` (`sennard-server`).

## Purpose and Rationale

- **An attachment is a bundle of files.** One upload can carry several files (up to 64), and they are kept together as one attachment under one ID. A caller never has to compress files only to attach them as one. A record points at the attachment (its ID), or at one file of it (the ID and the file's number `seq`). The files of an attachment are checked one by one (size, sha256), and the attachment is made only when all of them pass.
- **Everything about an attachment is kept for audit.** The three tables of a kept attachment (`attachment`, `attachment_file`, `attachment_storage`) only receive `INSERT`: no `UPDATE`, no `DELETE`, no flag for "used" or "deleted". What changes over time (a file written again under another key) is a new row, never a changed one.
- **The body of a file is not in the database.** It is stored as encrypted segments in a file of the server's own folder. A table holds metadata and the name of the file only. No BLOB, BYTEA, CLOB or long text column holds a body. This keeps the table space of the database small and lets a copy of the database alone reveal nothing about a file.
- **A file is unreadable without its key.** A person who reaches the folder cannot tell what a file is: the name is random, and the content is AES-256-GCM ciphertext. The keys are in a separate folder.
- **There is no foreign key.** A record that points at an attachment carries only the attachment's primary key. The use case that writes the record checks that the attachment exists and was made by the author of the record (see "Link to Stage Results" below).
- **One protocol.** The model calls the same five tools for any attachment, of one file or of many. The server never reads a path on the caller's computer through these tools; the bytes arrive in chunks.
- **One implementation.** Writing, reading, closing, moving, deleting and checking files is done by `AttachmentFileStore`, which serves uploads only.

## Tables

All four tables are in the schema `sennard`. Every time column is a `TIMESTAMP` that holds a UTC value written from the injected `Clock`. No table has a foreign key.

### `upload_staging`

An upload in progress: one row per file of the attachment being uploaded. It is the only one of the four tables that changes.

| Column | Type | Meaning |
| --- | --- | --- |
| `id` | `CHAR(36)` | Primary key. The upload ID of one file |
| `attachment_id` | `CHAR(36)` | The ID of the attachment the finish makes. The files begun together share it, so the attachment ID is known from the begin on |
| `seq` | `INTEGER` | The number of the file in the attachment: its position in the `files` list of `artifact_begin`, from 0. `CHECK (seq >= 0)` |
| `owner` | `VARCHAR(255)` | The author: the actor ID of the calling session |
| `operation_key` | `VARCHAR(128)` | The `operationKey` of `artifact_begin` |
| `file_name` | `VARCHAR(512)` | Declared file name |
| `media_type` | `VARCHAR(255)` | Declared media type |
| `declared_size` | `BIGINT` | Declared size of the whole file in bytes |
| `declared_sha256` | `CHAR(64)` | Declared sha256 of the whole file (64 hex digits) |
| `received_bytes` | `BIGINT` | Plain bytes received |
| `stored_bytes` | `BIGINT` | Size of the file that the row has taken in |
| `next_sequence` | `BIGINT` | The number of the next chunk |
| `last_chunk_sha256` | `CHAR(64)` | sha256 of the last chunk taken in; null before the first chunk |
| `storage_key` | `CHAR(32)` | Random name of the file |
| `key_id` | `VARCHAR(64)` | The key the file is written with |
| `state` | `VARCHAR(16)` | `STAGING` or `COMPLETED` |
| `expires_at`, `created_at`, `updated_at` | `TIMESTAMP` | Expiry and bookkeeping times |

Constraints: `UNIQUE (owner, operation_key, seq)` (one begin with several files makes one row each under its operation key), `UNIQUE (attachment_id, seq)`, `UNIQUE (storage_key)`, `CHECK (state IN ('STAGING', 'COMPLETED'))`, and a `CHECK` that the four counters are not negative. An index on `(state, expires_at)` serves the cleaner. The expiry of the files of one attachment is always the same: a chunk of any file counts the expiry of all of them again, so an attachment lives and dies as a whole.

### `attachment`

A kept attachment, which is the bundle. It is a record: rows are inserted and never changed. It has no body column. A record that points at an attachment holds this key only.

| Column | Type | Meaning |
| --- | --- | --- |
| `id` | `CHAR(36)` | Primary key. It was the `attachment_id` of the upload rows from the begin on, so no column points from `upload_staging` to `attachment` or the other way: an `artifact_finish` that is sent again after the upload rows have been removed finds the attachment by the same ID |
| `created_by` | `VARCHAR(255)` | The author |
| `created_at` | `TIMESTAMP` | |

### `attachment_file`

One file of an attachment, with the facts of the file as it was sent. It is a record: rows are inserted and never changed.

| Column | Type | Meaning |
| --- | --- | --- |
| `attachment_id` | `CHAR(36)` | The attachment (no foreign key) |
| `seq` | `INTEGER` | The number of the file in the attachment, from 0, in the order of the `files` list of `artifact_begin`. `CHECK (seq >= 0)` |
| `file_name`, `media_type` | `VARCHAR` | As declared (the file name of a file of a review bundle is its path under the bundle's folder) |
| `size_bytes` | `BIGINT` | Size of the plain bytes, `CHECK (size_bytes >= 0)` |
| `content_sha256` | `CHAR(64)` | sha256 of the plain bytes |

The primary key is the pair `(attachment_id, seq)`; there is no surrogate key. `artifact_finish` inserts all the rows of an attachment together with the attachment row, in one transaction.

### `attachment_storage`

Where the bytes of one file are: one row per stored copy of the file. It is a record: rows are inserted and never changed.

| Column | Type | Meaning |
| --- | --- | --- |
| `attachment_id` | `CHAR(36)` | The attachment (no foreign key) |
| `seq` | `INTEGER` | The file |
| `rev` | `INTEGER` | 0 for the copy that `artifact_finish` closed; one more for each copy a re-encryption wrote. `CHECK (rev >= 0)` |
| `storage_key` | `CHAR(32)` | Random name of the file in the folder, `UNIQUE` |
| `key_id` | `VARCHAR(64)` | The key the copy is written with |
| `created_at` | `TIMESTAMP` | |

The primary key is the triple `(attachment_id, seq, rev)`; there is no surrogate key. The current copy of a file is the row with the highest `rev` (`created_at` does not decide the order, because two rows can carry the same time). `artifact_finish` inserts `rev` 0 of every file. A re-encryption inserts the highest `rev` of the file read in its own transaction plus one; if two re-encryptions read the same highest number, the second ends in the translated duplicate-key conflict and nothing is run again.

The architecture check `w-sql-write-verb` allows only `INSERT` on `attachment`, `attachment_file` and `attachment_storage` ([Architecture checks](../sennard-architecture-checks.md)).

## Storage Format

### Location and names

- The bodies are in `sennard.attachments.directory`. The key files are in `sennard.attachments.key-directory`, which must be outside the first.
- The name of a file (`storage_key`) is 128 random bits from `SecureRandom` in lower-case hex (32 characters). The subfolder is the first two characters of the name. The name has no extension, date, original file name, ID or project.
- Folders and files are created with owner-only access from their first instant (a POSIX mode, or on a file system with access control lists an entry for the owner only). The personal edition trusts the whole computer, so an existing folder or file is used as it is: its owner, its access and links are never a reason to refuse it. The refusals are a storage path that is not what the design says (a file where a folder belongs, a folder where a key file belongs) and a file system that cannot make an owner-only path (no permission bits and no access control list, such as FAT; or an account the system does not know): `ARTIFACT_STORAGE_UNSAFE`. A key file that is empty or not 256 bits is no usable key: `ARTIFACT_KEY_MISSING` (`details.reason` is `empty` or `wrong-length`).
- A closed file is read-only. Whether a file is closed is decided by the row state (`COMPLETED`) and the end mark, not by the permission bit, because a process that runs as root writes anyway.

### Segments

A chunk is one segment. The cipher is the JDK's `AES/GCM/NoPadding` with a 256-bit key; no vendor library is used. A segment on disk is:

| Part | Size | Content |
| --- | --- | --- |
| Length | 4 bytes | The number of bytes that follow (IV, ciphertext and tag), big-endian |
| IV | 12 bytes | New for each segment |
| Ciphertext | n bytes | The encrypted chunk |
| Tag | 16 bytes | The GCM tag |

The length lets a reader find the boundaries even when chunk sizes differ; a changed length shifts the boundaries and the tag check fails.

The additional authenticated data (AAD) is the `storage_key`, the number of the segment and a mark that tells whether the segment is the last. As a result, a segment moved to another file, segments in another order, and a file cut short all fail to decrypt.

Before `artifact_finish` the server does not know which chunk is the last, so each chunk is written as "not last". The end mark is one more segment with an empty plain text, marked "last", which `artifact_finish` appends after it has checked the whole file. A reader that finds no end mark reports a truncated file. The disk shows only the size of a file.

## Appending and the File Lock

The entry (`AttachmentUploads`, `AttachmentFileStore`) appends outside any transaction; the service takes the result into the row in one transaction.

1. A read-only use case reads `next_sequence`, `stored_bytes` and `last_chunk_sha256` of the row. If the chunk number is `next_sequence - 1` and the chunk hash equals `last_chunk_sha256`, the chunk is already in and the current state is answered without appending. A number that is not `next_sequence` is `ARTIFACT_CHUNK_CONFLICT`.
2. The file is opened and the one byte beyond its end is locked: `FileChannel.tryLock(Long.MAX_VALUE - 1, 1, false)`. Only those who append exclude each other. A read or a status reads the data range, which the lock does not cover, so it is not blocked (Windows stops a read of a locked range, so nothing in the data range is locked). The lock is tried once; if it is held by another process or by another request of the same JVM (`OverlappingFileLockException`) the answer is `ARTIFACT_BUSY`. It is neither waited for nor retried.
3. Inside the lock the file size is compared with `stored_bytes`:
   - Equal: the segment is appended and `force(true)` is called.
   - Larger: the tail `[stored_bytes, size)` is a segment appended by an earlier request that the row has not taken in (the transaction was lost). It is decrypted with this chunk's number as AAD and its plain hash is compared with this chunk's hash. If they are equal nothing is appended (a resend of the same chunk, or the retry of the loser of a race); otherwise it is `ARTIFACT_CHUNK_CONFLICT`. The tail is never cut off, because it may belong to a request still in progress.
   - Smaller: the file is damaged, `ARTIFACT_CORRUPTED`.
4. The service runs one conditional `UPDATE upload_staging SET stored_bytes = ?, received_bytes = ?, next_sequence = ?, last_chunk_sha256 = ?, expires_at = ?, updated_at = ? WHERE id = ? AND next_sequence = ? AND state = 'STAGING'`. If no row changed, the row is read again: the same chunk already taken in is answered as before, and anything else is `ARTIFACT_CHUNK_CONFLICT`. There is no retry. When the row took the chunk in, the same transaction counts the expiry of every file of the attachment again (`UPDATE upload_staging SET expires_at = ?, updated_at = ? WHERE attachment_id = ? AND state = 'STAGING'`).

The file lock decides the order of bytes, and the conditional `UPDATE` decides which chunk the row has taken in. The size of a chunk is a constant of the contract, not a setting: `AttachmentFileStore.MAX_CHUNK_BYTES = 262144` bytes (256 KiB). `maxChunkBytes` of the `artifact_begin` answer is this value. Every append and every write of a closing segment first checks the disk margin (`disk-margin-bytes`).

## Finish

`artifact_finish` takes the ID of the attachment and finishes all its files. The entry takes the files one after the other (in the order of their numbers), and for each file checks the whole file first and closes it only when the check passes (steps 1 to 3). Only when every file has passed does the service make the attachment (step 4). A file that fails ends the finish before any row is written: no attachment, no file row and no copy row is made, the upload rows stay `STAGING`, and the uploads go on until they expire. A file that was closed before the failing one stays closed; the next finish checks it again, finds its end mark and appends nothing the second time.

1. The entry reads the file under the same lock. If the file is longer than `stored_bytes`, the tail must be exactly the end mark (empty plain text, marked last, passing the AAD): an earlier finish appended it and lost its transaction, so the check continues up to it and does not append again. Any other tail is `ARTIFACT_INCOMPLETE`.
2. Every segment before the end mark is decrypted and its tag verified, and the sha256 and the size of the plain bytes are computed. Fewer bytes than declared is `ARTIFACT_INCOMPLETE`; more bytes, or a different sha256, is `ARTIFACT_HASH_MISMATCH`. In both cases the file is not closed, the row stays `STAGING`, and the upload can go on until it expires.
3. The end mark segment is appended and `force(true)` is called, and the file is made read-only. An existing name is never overwritten.
4. The service (one transaction) compares the checked size and sha256 of every file with the declared ones, marks every upload row `COMPLETED` with `UPDATE ... WHERE id = ? AND state = 'STAGING' AND expires_at > ?`, and inserts the `attachment` row, an `attachment_file` row for each file and an `attachment_storage` row with `rev` 0 for each file. All of it is there or none of it is: a row that is no longer `STAGING` (the bundle expired and was cleaned, or another finish completed it first) ends the transaction, and the attachment of another finish that won is answered as it is. Calling `artifact_finish` again answers the same attachment, also after the cleaner has removed the upload rows.

## Tools

The MCP tools are `artifact_begin`, `artifact_finish`, `artifact_status` and `artifact_read`. Each input requires `schemaVersion` (`"1.0.0"`) and follows `contracts/artifact-*-request.v1.schema.json`. The session that calls `artifact_begin` owns the attachment. The upload client sends each file in a multipart `file` part to `POST /attachments/upload` with the `X-Sennard-Upload-Id` header. The upload ID also authorizes an `artifact_status` lookup. The attachment ID authorizes `artifact_finish`. A completed attachment status lookup, `artifact_read` and `artifact_begin` require a session.

`sha256` values are written `sha256:<64 lower-case hex digits>`.

| Tool | Input | Answer |
| --- | --- | --- |
| `artifact_begin` | `operationKey`, `files` (1 to 64 items of `fileName` (1 to 512 characters), `mediaType` (1 to 255), `size` (bytes, at least 0), `sha256` (whole file)), `purpose` (optional) | `attachmentId` (the ID the finish makes), `files` (`seq` and `uploadId` of each file, in the order of the list), `maxChunkBytes` (262144), `expiresAt` |
| `artifact_finish` | `attachmentId` | `attachmentId`, `files` (`seq`, `fileName`, `mediaType`, `size`, `sha256` of each file) |
| `artifact_status` | exactly one of `uploadId` and `attachmentId` | by `uploadId`: `kind` `upload`, `uploadId`, `attachmentId`, `seq`, `state`, `fileName`, `mediaType`, `size`, `sha256`, `received`, `expiresAt`, `sequence`; by `attachmentId`: `kind` `attachment`, `attachmentId`, `state` and `files` (as in the finish). It never answers a path or a storage key |
| `artifact_read` | `attachmentId`, `seq` (the file; default 0), `offsetBytes` (default 0), `maxBytes` (1 to 262144, default 262144) | `attachmentId`, `seq`, `offsetBytes`, `bytes`, `size` (of the whole file), `end` (the page reaches the end), `data` (base64), `text` (only when the media type begins with `text/` and the page is whole UTF-8) |

Rules:

- **`operationKey`** is supplied to `artifact_begin` using 16 to 128 letters, digits, or `_ . : -` characters. Repeating the operation key with the same author and ordered file names, sizes and hashes returns the same attachment and upload IDs. Different metadata returns `ARTIFACT_OPERATION_CONFLICT`. Begin returns random IDs that authorize the file transfer and finish while keeping ownership with the beginning session. Repeating finish with the same attachment ID returns the same attachment.
- **Files of an attachment.** The files are numbered 0, 1, 2 ... in the order of the `files` list of `artifact_begin`; the number (`seq`) is how a stage result points at one file. At most 64 files make one attachment (`ARTIFACT_LIMIT`, `details.limit` `file-count`, `details.requested` and `details.allowed` are counts of files). An attachment of one file is the ordinary case: `files` has one item and the file is pointed at with `seq` 0 (or no `seq`).
- **`purpose`** is `upload`, `staged` or `evidence`. Sennard supports `evidence` only, which is also the meaning of no `purpose`; the other two are refused with `ARTIFACT_UNSUPPORTED_PURPOSE`.
- **Limits** (the number of files, file size, budgets, open uploads per author) are checked in `artifact_begin`. A request over a limit is refused whole, never cut. The size limit is that of each file; the budgets count the sum of the sizes of the files; the open uploads of an author are counted by attachment, so an attachment of several files counts once. The disk is asked once before the upload begins whether the declared sizes of all the files fit.
- **Two begins at the same moment.** Two `artifact_begin` calls of the same author with the same `operationKey` that both find no upload insert at the same moment, and the unique key `(owner, operation_key)` lets one of them through. The other one is answered with `ARTIFACT_BUSY` (`details.reason` is `begin-in-progress`, `retryable` true), never with the store's raw unique violation. The entrance makes this answer after the use case's transaction has ended: the use case does not catch the violation and go on, and the server does not send the begin again. The same `artifact_begin` sent again answers with the winner's `uploadId` (or with `ARTIFACT_OPERATION_CONFLICT` when its content differs).
- **Accepted limit of concurrent begins.** The limits are checked by reading the rows in a transaction at the isolation level `READ COMMITTED`, and there is no lock between two begins. Begins of different `operationKey`s that run at the same moment can therefore together exceed the open-upload count (`max-open-per-owner`) and the budgets (`staging-budget-bytes`, `attachment-budget-bytes`) by what they declare at that moment. This is accepted: the limits are guards of the disk, not of accounting; uploads that were begun are not cut, and the next `artifact_begin` sees all of them and is refused until they end or expire.
- **Chunks.** The last chunk sent again with the same content is answered as before. The same number with other content, a number out of order, or a chunk for an upload that is `COMPLETED` is `ARTIFACT_CHUNK_CONFLICT`. A chunk hash that does not match is `CHUNK_HASH_MISMATCH`. A chunk that would take the upload past its declared size is refused as `ARTIFACT_HASH_MISMATCH` before anything is appended. Every time a chunk is taken in, the expiry of all the files of the attachment is counted again from that moment (`upload-ttl`), so an attachment lives and dies as a whole and a file that was sent early is not cleaned while the others are still being sent. A chunk of an expired upload is `ARTIFACT_EXPIRED`. An unknown `uploadId` is `ARTIFACT_NOT_FOUND`; a chunk with the right `uploadId` is accepted whichever session or client sends it.
- **Read.** One file of the attachment is read (`seq`; a number the attachment has no file for is `ARTIFACT_NOT_FOUND`). The segments of the requested page are decrypted and their tags verified; a page that reaches the end also checks the end mark and the plain size. The sha256 of the whole file is checked by `artifact_finish` and by `sennard attachments check`, not by every page.
- The tools are idempotent and not destructive; `artifact_status` and `artifact_read` are read-only.

## Settings

All keys are in the one record `AttachmentProperties` (`@ConfigurationProperties("sennard.attachments")`). A size is Spring's `DataSize`: `64MB` means 64 mebibytes (1,048,576 bytes each).

| Key | Default | Meaning |
| --- | --- | --- |
| `sennard.attachments.directory` | none in code; `application.yaml` gives `${sennard.state-dir}/attachments` | Where the bodies are written |
| `sennard.attachments.key-directory` | none in code; `application.yaml` gives `${sennard.state-dir}/secrets/attachment-keys` | Where the key files are. It must be outside `directory`; inside it the settings fail to bind and the server does not start (a settings failure, not a tool error code) |
| `sennard.attachments.active-key-id` | `primary` | The key that new uploads are written with |
| `sennard.attachments.max-file-bytes` | `1GB` | The largest file of one attachment |
| `sennard.attachments.staging-budget-bytes` | `4GB` | The most the open uploads may declare together |
| `sennard.attachments.attachment-budget-bytes` | `10GB` | The most the kept attachments, the declared size of the open uploads and the new upload may be together. A new `artifact_begin` over it is refused; kept attachments are not deleted |
| `sennard.attachments.max-open-per-owner` | `4` | How many uploads one author may have open (at least 1) |
| `sennard.attachments.disk-margin-bytes` | `64MB` | Space the disk must keep after a file is written; short of it a write is refused as `ARTIFACT_STORAGE_FULL`. The test is `usable - size < margin`. The default reserves 64 MiB independently of the file limit. Administrators can adjust this resource policy; it is not a measurement |
| `sennard.attachments.upload-ttl` | `10m` | Expiry of an upload, counted again from the last chunk |
| `sennard.attachments.orphan-grace` | `24h` | How long a file without a row, or a replaced file, waits before it is moved to `orphans/` |
| `sennard.attachments.reaper-interval` | `1m` | How often the cleaner runs (fixed delay after the end of a round). The cleaner only finds the expired and the completed upload rows in the database and deletes them |
| `sennard.attachments.reconcile-interval` | `24h` | How often the folder is compared with the rows (fixed delay after the end of a comparison): files without a row, replaced files and the files of `orphans/`. It also runs once when the store is ready after the start, and by `sennard attachments purge-orphans` without `--yes`. The default equals the default of `orphan-grace`: a file is moved only once `orphan-grace` has passed, so a comparison more often than that finds the same files again, and one per grace moves a file at most one interval late. This is a judgement, not a measurement. The comparison looks at every file of the folder with one to three lookups each, which is why it is not run every minute |

There is no key for the chunk size or for the number of files of an attachment: they are the constants 262144 bytes and 64 (`AttachmentFileStore.MAX_CHUNK_BYTES`, `AttachmentService.MAX_FILES`). Values that are not positive (`max-file-bytes`, `upload-ttl`, `reaper-interval`, `reconcile-interval`), a negative `orphan-grace` and an invalid `active-key-id` stop the start.

## Error Codes

A tool failure carries `error.code` and, in `details`, `retryable`: true when the same request sent again later can succeed. The product never sends a request again by itself (a retry is for a failure of communication only). Every code is made in `ArtifactErrors`, so none leaves without `retryable`. The `error.code` of these codes is the name of the code itself, with no `reasonCode`; the category is the exception class.

| Code | Category | Meaning | `retryable` |
| --- | --- | --- | --- |
| `ARTIFACT_OPERATION_CONFLICT` | Conflict | The same `operationKey` of the same author began a different upload | false |
| `ARTIFACT_CHUNK_CONFLICT` | Conflict | A chunk out of order, or a received number sent again with other content (`details.reason`) | false |
| `CHUNK_HASH_MISMATCH` | Integrity | The sha256 of the chunk is not the declared one | false |
| `ARTIFACT_BUSY` | Conflict | Another request of the same upload is in progress: an append (send the same chunk again later), or the same begin of the same `operationKey` (`details.reason` is `begin-in-progress`; send the same begin again and receive the winner's `uploadId`) | true |
| `ARTIFACT_INCOMPLETE` | Conflict | A tail the row has not taken in, or a file with fewer bytes than declared. The missing chunks must be sent; the same `artifact_finish` sent again does not pass. No attachment is made while one file is incomplete | false |
| `ARTIFACT_HASH_MISMATCH` | Integrity | The declared sha256 or size is not that of the bytes (`details.reason` is `size` or `sha256`) | false |
| `ARTIFACT_CORRUPTED` | Integrity | A tag, hash, size or end mark does not match, or the file is shorter than the row says (`details.reason`) | false |
| `ARTIFACT_LIMIT` | Unavailable | A limit is exceeded; `details.limit` names it (table below) | by limit |
| `ARTIFACT_STORAGE_UNSAFE` | Unavailable | A storage path cannot be used for this purpose: it is not a regular file or folder (a file where a folder belongs, and the like), or the file system cannot make it owner-only (FAT and the like; an account the system does not know). `details.reason` tells which. The personal edition never gives this code for the owner or the access of an existing path | false (the owner has to fix the path) |
| `ARTIFACT_STORAGE_IO_ERROR` | Unavailable | The disk could not be read or written | false (the owner has to fix the disk) |
| `ARTIFACT_KEY_MISSING` | Unavailable | There is no usable key for the `key_id` a row names: the key file is not there, is empty, or is not 256 bits (`details.keyId`; `details.reason` is `empty` or `wrong-length` for the last two) | false (the owner has to restore the key file) |
| `ARTIFACT_STORAGE_FULL` | Unavailable | The free space less the file would be under `disk-margin-bytes` (`details.requestedBytes`, `usableBytes`, `marginBytes`). A disk that cannot be read is an I/O failure, not this | false (the owner has to free disk space) |
| `ARTIFACT_UNSUPPORTED_PURPOSE` | Invalid input | A `purpose` the product does not keep | false |
| `ARTIFACT_EXPIRED` | Conflict | The upload has expired | false |
| `ARTIFACT_NOT_FOUND` | Not found | No upload or attachment has the ID | false |

The `details.limit` values of `ARTIFACT_LIMIT` (the details also carry `requested` and `allowed`):

| `limit` | Raised when | `retryable` |
| --- | --- | --- |
| `file-size` | A file is larger than `max-file-bytes` | false |
| `file-count` | The attachment has more than 64 files (`requested` and `allowed` are counts of files) | false |
| `attachment-budget` | Kept attachments plus the open uploads' declared sizes plus the sizes of the new files exceed `attachment-budget-bytes`; the owner has to clean up attachments | false |
| `staging-budget` | The open uploads' declared sizes plus the sizes of the new files exceed `staging-budget-bytes`; an upload that ends or the cleaner frees it | true |
| `open-uploads` | The author has `max-open-per-owner` attachments with uploads open; an upload that ends or the cleaner frees it | true |

## Keys

- A key file is 256 random bits and nothing else. Its name is the key ID (letters, digits, `_` and `-`, 1 to 64 characters, so that it cannot name a path). It is made readable by the owner only from its first instant. Keys are never written to a log, a row or a message.
- At the start (`ServerStartTasks`, task `attachmentStorage`, before a request is let in) the folders are made (owner-only) when they are not there, the first key (`primary`) is made if the key folder has no key, and the key file of every key that a current row points at is checked: the key of each upload in progress and of the current file (highest `seq`) of each attachment. `active-key-id` must name a usable key. The check really reads each key file and judges it the way a request does, so a start never passes a key that would fail at the first request: a folder in the key's place stops the start with `ARTIFACT_STORAGE_UNSAFE` (`details.reason` is `key-not-a-file`), and a key file that is not there, is empty or is not 256 bits stops it with `ARTIFACT_KEY_MISSING` (`details.reason` is `empty` or `wrong-length` for the last two), not the first upload.
- **Replacing a key**: make a new key (`sennard attachments keygen --id <keyId>`) and set `sennard.attachments.active-key-id` to it. New uploads use it; files written with the old key are still read with the old key, which is chosen by the `key_id` of the row.
- **Re-encryption** (`sennard attachments rekey --from <keyId>`): for each current copy of a file written with that key, the entry decrypts it and verifies the tags, the end mark, the size and the sha256, writes a new file under a new `storage_key` with the active key and forces it to disk, and then the service adds the `attachment_storage` row of the next `rev` of that file (one transaction). The old copy becomes a replaced file (see "Cleaning"). If the run dies before the row is added, the new file has no row and the old row stays valid. A run that stops on a failure leaves what was done before it; it can be started again. `--from` must not be the active key.
- An old key is deleted only after no current row points at it, and only by the owner. Sennard does not delete keys.

## Cleaning

Two jobs run on their own, on two intervals, because they cost differently (`AttachmentReaperRounds` registers both).

- **The cleaner** (`AttachmentMaintenance.reap`) runs its first round `reaper-interval` after the start and then again `reaper-interval` after the end of each round. It only asks the database for the expired and the completed upload rows and deletes them (the first two rules below). It never lists the folder and asks no per-file question.
- **The comparison of the folder with the rows** (`AttachmentMaintenance.reconcileFolder`, the last two rules below that move files) looks at every file of the folder with one to three lookups each. It runs in three situations: once when the store is ready after the start (`ServerStartTasks`, a task that runs after requests are let in, so a request does not wait for it), every `reconcile-interval` after that (the first one `reconcile-interval` after the start), and each time `sennard attachments purge-orphans` is run without `--yes`.

A failed round or comparison is logged and tried again in the next one.

- **Expired uploads.** For each candidate the cleaner runs `DELETE FROM upload_staging WHERE id = ? AND state = 'STAGING' AND expires_at <= ?`. The whole condition is evaluated again on the current row, so an upload that was completed or whose expiry moved on in the meantime is skipped. Only when one row was deleted is the file deleted. A process that dies between the two leaves a file without a row, which the next rule handles.
- **Completed uploads.** The row is deleted with `DELETE ... WHERE id = ? AND state = 'COMPLETED'`. The file belongs to the attachment and is not deleted.
- **Replaced files** (the comparison). A file that has an `attachment_storage` row that is not the current row of its file (after a re-encryption) is moved to `orphans/` when `orphan-grace` has passed since the replacing row was written, so that a read that began with the old row does not meet a file that is gone.
- **Files without a row** (the comparison). A file named by no row of `upload_staging` or `attachment_storage` is moved to `orphans/` (under `directory`) when `orphan-grace` has passed since the file was last written. It is not deleted: a database restored from an older backup makes sound files look like this. When a row names a file in `orphans/` again, the comparison moves it back to its place.
- **Emptying `orphans/`** is an explicit command only: `sennard attachments purge-orphans --yes`. The names of the deleted files are written to the log (`ATTACHMENT_QUARANTINE_PURGED`). The confirmed run deletes what is in `orphans/` and does not compare the folder first, so it deletes exactly the files that the run without `--yes` listed; a file that the comparison would move now is not deleted unseen.
- **Damage is reported, not repaired.** An attachment whose current file is missing, or fails its tag, hash, size or end mark, is reported by `sennard attachments check` and never deleted. An attachment that no record points at is kept.

## Backup and Restore

Back up and restore the attachment folder, the key folder and the database together. After a restore, `sennard attachments check` must report no findings. A database restored from an older backup can leave sound files without a row; they are moved to `orphans/` after `orphan-grace` and are moved back when a row names them again. The key folder holds the only copy of the keys: a backup without it cannot be read.

## The `sennard attachments` Command

The maintenance actions below run without the server (the upload actions `prepare` and `send` are described in "The Upload Client"), in their own Spring context that reads the settings of the installation and opens the store the way the server does. It prints JSON. The exit code is 1 when `check` found something.

| Command | Effect |
| --- | --- |
| `sennard attachments check` | Compares the rows with the folder: opens and verifies the current copy of every file of every attachment (tags, end mark, size, sha256), looks for the files of the open uploads, and lists files that no row names. Findings are of the kinds `attachment-without-file`, `file-missing`, `damaged` and `file-without-row`; a finding about a file names the attachment in `id` and the number of the file in `seq`. Changes nothing |
| `sennard attachments rekey --from <keyId>` | Writes the current copies of the files of that key again with the active key and prints how many |
| `sennard attachments keygen --id <keyId>` | Makes a new key file; the key's value is never printed. An existing key is not replaced |
| `sennard attachments purge-orphans [--yes]` | Without `--yes` compares the folder with the rows first (moves the files without a row and the replaced files whose `orphan-grace` has passed to `orphans/`, and moves back the files that have a row again), then lists the files in `orphans/` and deletes nothing; with `--yes` only deletes the files in `orphans/` and writes their names to the log |

A misuse of the command is an input error: `INVALID_INPUT` with the reason `VALIDATION_FAILED` and the problem in `details.violations` (for example `--from: ...`), `details.retryable` false. The misuses are `rekey --from` naming the active key, `keygen --id` with an id that is not a key ID (letters, digits, `_` and `-`, 1 to 64 characters) and `keygen --id` with an id whose path already exists in the key folder. They are never `ARTIFACT_STORAGE_UNSAFE`, which says only that a storage path cannot be used and is fixed by the owner changing the storage location, not the command line.

## Administrator Storage Settings

On the local admin page, open **General settings → Attachment storage**. Enter the absolute storage folder, maximum file size and total attachment capacity, then save with the registered passkey. Sizes are displayed in MiB. The existing transfer capacity determines the maximum permitted file size and the minimum total capacity. Signed settings apply to later requests and survive restarts. Lowering a limit preserves existing attachments and can prevent new uploads or transfers above the current file size limit.

Changing the folder does not move files. New files use the selected folder, while completed attachments and partially written files remain in their earlier folders. Reads and maintenance consult the location history in the verified admin log. Keep those earlier folders and the separate encryption key directory in backups. Do not delete or move an earlier folder merely because another is selected. The selected location must not overlap the key directory. If a save fails, the draft remains; for a stale setting, copy any needed values, discard changes to load the latest revision, then edit and sign again.

## The Upload Client

Run `sennard attachments upload --session-ref <sessionRef> [--name <fileName>] [--operation-key <key>] [--port <port>] <file>` once for a local file. The shared client measures it, begins the attachment, transfers it and finishes it. It prints one JSON line with the attachment ID and file metadata. The model receives no file bytes. Obtain the current session reference from `open_session`; an observed caller receives its existing session, while an unobserved caller must keep the returned reference for subsequent calls. The reference supplies attribution and does not establish host observation.

| Command | Effect |
| --- | --- |
| `sennard attachments upload --session-ref <sessionRef> <file>` | Automatically measures, begins, transfers and finishes one file. The default operation key depends on the normalized absolute path, content hash and declared name; repeating the same invocation returns the same attachment |
| `sennard attachments prepare [--name <fileName>] <file>` | Measures locally and prints `fileName`, `size`, `sha256` and `mediaType` without connecting |
| `sennard attachments send --upload-id <id> [--port <port>] <file>` | Transfers one file of an attachment begun separately. Prints `uploadId`, `attachmentId`, `seq` and `received`; the caller finishes the bundle once all files are sent |

File bytes use one standard `multipart/form-data` POST to `/attachments/upload` with `X-Sennard-Upload-Id`. Metadata uses MCP at `/mcp`. The JDK streams the local file, and Spring's `StandardServletMultipartResolver` uses Servlet multipart parts (the Servlet 3.0 mechanism). The container spools the body to the owner-only `attachment-transfer` folder under the server state directory, with memory threshold zero, and cleans the temporary parts after the request. The server verifies declared length and whole-file hash before appending encrypted segments with bounded 262144-byte buffers. A resumed transfer sends the whole multipart file again; the server skips bytes already committed. A completed upload returns its earlier result.

The pre-parser filter requires Content-Length and checks the current signed file limit, declared upload size (plus at most 65536 bytes of multipart overhead), upload ID and available temporary disk space. The existing Origin/Host guard runs first. Hash failure does not advance upload progress. Concurrent requests for the same upload are refused as busy. These are disk and processing limits; there is no 1 MiB token limit.

The client finds the server through the installation address file; `--port` explicitly selects a port. A refusal prints `CODE: message` on stderr and exits 1. An uncertain transfer response causes one status read, with incomplete work reported as an error. An uncertain begin or finish is reported as `UPLOAD_RESULT_UNKNOWN`: repeat the same upload invocation and operation key to recover the result. There is no automatic mutation retry. An expired upload requires a fresh operation key. For a multi-file bundle, retain prepare → one artifact_begin → send for each file → one artifact_finish; the model passes metadata only.

## Link to Stage Results

A stage result names every file by attachment id and file number (the contract is `contracts/stage-result.v1.schema.json`): `evidence[]`, `output.artifacts[]` and `outputFile` each carry `attachmentId`, `seq` (the number of the file in the attachment; 0 when left out, which is the only file of an attachment of one file) and `digest` (the sha256 of that file); the `priorFailure.evidenceRefs` of `claim_workflow_attempt` name attachments by id. The server never reads a path on the caller's computer. The session that records the result uploads the content through the upload client, then puts the returned `attachmentId`, the `seq` and the `sha256` of each file into those fields. Several items may point at different files of one attachment. The contract requires `digest` together with `attachmentId`, so a missing digest is refused as `INVALID_INPUT` before any judgement.

- **The pointer is judged in the recording transaction, from rows.** `EvidenceAttachments` looks up every attachment named by the result and checks two things: the attachment exists, and its author is the session that records the result. If either is not so, the recording fails and nothing is recorded. The error is `BINDING_INVALID` with `details.reason` `ATTACHMENT_NOT_FOUND` or `ATTACHMENT_AUTHOR_MISMATCH`. The author is the actor ID of the calling session, the same value `artifact_begin` records as `owner`. No file is read in the transaction.
- **The digest is compared with the row.** When the sha256 of the attachment equals the declared `digest`, the server records `SHA256_MATCHED` in `artifactChecks`. When it differs, a passing stage fails with `BINDING_INVALID` (`details.reason` `ARTIFACT_DIGEST_MISMATCH`) and any other stage records `NOT_CHECKED`. Two items may name the same attachment.
- **The output file and the frozen request are read before the transaction.** The entry reads them from their attachments through `AttachmentContents` (reading, decrypting and hashing are never done inside a transaction) and hands the content to the use case. The output file is limited to 16 MiB, and its sha256 must equal the declared `digest`.
- **Finalize reads no file of the caller's computer.** For a mandatory audit it asks `AttachmentContents` whether each audited attachment is still there with the recorded sha256 and can be read whole. If not, finalize fails with `GATE_FAILED` (`details.reason` `AUDIT_TARGET_UNAVAILABLE`). An attachment used as evidence is never deleted, so changing or deleting the original file after the upload does not make finalize fail.
- **Bundle review stages are judged from attachments.** The stages `cs-implementation-review`, `test-sensitivity-review` and `change-code-review` check a bundle of files. The bundle's manifest and report are artifacts of the stage result (`cs-review-bundle` and `cs-review-report`, or `engineering-stage-bundle` and `engineering-test-result` / `engineering-review-result`) in `output.artifacts[]`, and every raw file the manifest or the check reads (task, plan or request, proof or report, result, candidate files) is an `evidence[]` item. All of them are attachments uploaded by the session that records the result, with declared digests. The file name an attachment is uploaded under (`fileName` of `artifact_begin`) is its path under the bundle's folder: a portable relative path, `/`-separated, without `..`, a drive, a backslash or a reserved name, unique when compared case-insensitively, and at most 64 MiB in total. The entry puts the files in a private temporary folder, runs the unchanged check (`check-stage-bundle`: binding to the signed task, raw-file digests, candidate bytes) there and removes the folder. The report artifact must be the attachment the check read (the same path and digest). A file that cannot be used is `INVALID_INPUT` (`workflow.bundle.fileNotUsable`) with `details.reason` `PATH_NOT_PORTABLE`, `DUPLICATE_PATH`, `TOO_LARGE` or `ATTACHMENT_NOT_FOUND`.
- **The server keeps no copy of a local file.** The size limit (`max-file-bytes`) applies at the upload, as `ARTIFACT_LIMIT` of `artifact_begin`.

## Compatibility

Stage results identify files by `attachmentId`. See [State storage and cleanup](../user/state.md) for storage configuration.
