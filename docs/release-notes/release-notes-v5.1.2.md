# AGS 5.1.2 Release Notes

AGS 5.1.2 is a hotfix release. It lets the user approve the release of another session's convergence root on the local admin page, for hosts whose client does not show MCP forms (for example Codex), and it corrects the description of `userApprovalRefs` in `release_convergence_root`. Skills, other tool input and output contracts and storage file formats are unchanged.

## Before upgrading

- There are no breaking changes, and the 5.1.1 data is used as it is. The storage file formats (table definitions) are unchanged; the stored records gain new values: approval rows with the source `admin-passkey` in the workflow store, and signed admin log records of the change `rootRelease.approve`.
- Restart after installing. A running MCP server keeps the code it started with: restart the process that serves the admin page (it is one of the AGS MCP servers; any host session that is restarted may take it over) and restart the host sessions that are blocked by a root they cannot release (for example the Codex sessions), so that their MCP servers run 5.1.2. Until then the 5.1.1 behavior applies.
- Restart every AGS MCP process, not only the ones named above. A 5.1.1 process that has not been restarted keeps trying to serve the admin page and takes it over when the current holder exits. If an approval has already been signed by then, that 5.1.1 process cannot read the record of the change `rootRelease.approve`, and the admin page may show the same verification failure band as described under "Rolling back". The fix is to restart all AGS MCP processes so that none runs 5.1.1.
- The admin page has a new menu, "root 해제 승인" (root release approval). Approving there needs a registered passkey, as every other change on the page does.

## Rolling back

Going back to 5.1.1 is possible, but this was derived from the code and has not been run: a 5.1.1 server does not know the change `rootRelease.approve`, so a signed approval record that already exists may be reported as a failed record (a verification failure band on the admin page) and may not be removable with "버리기" (discard). If you roll back after approving, expect that band; the other signed settings keep their last valid state. To avoid it, roll back before anyone signs a root release approval, or keep 5.1.2 until the approvals have been used (an unused approval lapses 30 minutes after it was signed, but its record stays in the log).

## Changes

- **Another session's root can be released on hosts that cannot show a form.** `release_convergence_root` used to take the user's approval of another session's root only from an MCP form. A client that answers a form with decline without showing it (the Codex client) could therefore never release such a root. Now, on a host not known to show forms, a release without `userApprovalRefs` changes nothing, shows no form, and stores a request that the user finds in the admin page menu "root 해제 승인": the root, its owner sessions and their presence, the reason the agent gave and the effect of the release. The user approves with a passkey, and the agent calls `release_convergence_root` again with the returned approval ID in `userApprovalRefs`.
- **The approval is narrow.** It is valid for one root at one revision, for the reason the user saw (a call with a different reason is refused), and for the session that asked, for 30 minutes after it was signed, and one release uses it up. A root that changed in the meantime, another session's use, an expired or already used approval and an approval nobody signed are refused. The existing checks are unchanged: a root whose owner session is online is still refused, and the release still records who released it, when, on which presence reading and with which approval.
- **Hosts that show forms keep the form.** Claude Code is asked with the form as before; a form host whose client does not advertise forms is asked on the admin page instead of being refused. Every host accepts the approval ID from the admin page.
- **The tool description is accurate.** `userApprovalRefs` of `release_convergence_root` previously gave "the user's message" as an example, which the server never accepted. The tool description, the field description and the `ROOT_CONFLICT` guidance now say which references are accepted: the approval ID signed on the admin page, or the ID of a form approval. The user's message in the conversation is not an approval.

## Limits

- Replacing a root (`open_convergence_root` with `parentRootId`) still asks with an MCP form only. A host that cannot show forms cannot give that approval yet.
- Which hosts show forms is a list in the server (`FORM_APPROVAL_HOSTS`): only Claude Code is on it. Other hosts, including ones not checked yet, use the admin page.
- An approval given on the admin page can be used only by the session that asked, because it is bound to that session's identity. A session that continues under a different session ID, for example after it is restarted as a new session, asks again and the user approves again.
- On Claude Code the form is the only way: if the user declines or cancels the form, or no one answers it, the request does not move on to the admin page. Ask again after the user is available to answer the form.
- Asking again with a different reason or after the root's revision changed stores a separate pending request. Pending requests do not expire and are not replaced by the new one; reject the old ones on the admin page.
- On the admin page, the status text of the menus "세션 결속" and "root 해제 승인" shares one slot, so the result of a reject or approve in one of them may show in the other.

## Verification

Candidate: commit `3edbce83` of `hotfix/5.1.2`, the candidate just before this note was edited. This note change alters documentation only, so after it only `pnpm lint` and `pnpm release:check` are run again.

- Environment: Windows 11, PowerShell, the Windows built-in `tar` (the GNU tar of Git Bash was removed from `PATH`, because GNU tar reads `C:` as a remote host and the archive extraction tests then fail), and `--maxWorkers=1`.
- Generated files are reproducible: after `node scripts/i18n.mjs --write`, `--accept ko`, `pnpm build` and `pnpm claude:build`, `git status --porcelain` is empty, so the committed generated files equal the output of the repository scripts.
- `pnpm lint`, `pnpm claude:check`, `pnpm bundle:check`, `pnpm release:check`, `pnpm source:check`, `pnpm validate:all` and `git diff --check` all exited with code 0.
- `pnpm test -- --maxWorkers=1`: 347 test files passed and 2 were skipped (349). Of 5,088 Vitest tests, 5,051 passed, 0 failed, 29 were skipped and 8 are todo. The 600 Node tests: 599 passed, 0 failed, 1 skipped. The Vitest and Node exit codes are both 0.
- Earlier runs failed for environment reasons: with `--maxWorkers=2` some tests exceeded the time limit, and with the GNU tar environment 6 archive extraction tests failed. Both are classified as environment factors and pass under the conditions above.
- Not run: `pnpm source:verify` (comparison with the remote origin), `pnpm validate:official` (Codex development environment only), signing with a passkey in a real browser, releasing a root from a real Codex host to the end, the Linux test server, and the rollback described above. The installation check is done after publication on Claude Code on this PC; the installation on Codex and Antigravity has not been checked (unverified).

---

# AGS 5.1.2 릴리스 노트

AGS 5.1.2는 핫픽스 판입니다. MCP 폼을 보여 주지 않는 호스트(예: Codex)에서도 사용자가 로컬 관리자 페이지에서 다른 세션의 convergence root 해제를 승인할 수 있게 하고, `release_convergence_root`의 `userApprovalRefs` 설명을 바로잡았습니다. 스킬, 그 밖의 도구 입력·출력 계약, 저장 파일 형식은 바뀌지 않았습니다.

## 업그레이드 전 확인

- 깨지는 변경이 없고 5.1.1의 데이터를 그대로 씁니다. 저장 파일 형식(테이블 정의)은 바뀌지 않았고, 저장되는 기록에 새 값이 생깁니다. workflow 저장소의 승인 기록에 원본 `admin-passkey`가, 관리자 서명 기록에 변경 `rootRelease.approve`가 들어갑니다.
- 설치한 뒤 다시 시작해야 합니다. 실행 중인 MCP 서버는 시작할 때의 코드로 계속 동작합니다. 관리자 페이지를 띄운 프로세스(AGS MCP 서버 중 하나이며, 다시 시작한 어느 호스트 세션이든 이어받을 수 있습니다)와, 해제하지 못하는 root에 막힌 호스트 세션(예: Codex 세션)을 다시 시작해 그 MCP 서버가 5.1.2로 돌게 하십시오. 그 전에는 5.1.1 동작입니다.
- 위에 적은 것만이 아니라 모든 AGS MCP 프로세스를 다시 시작하십시오. 다시 시작하지 않은 5.1.1 프로세스는 관리자 페이지를 띄우려고 계속 시도하다가 지금 띄운 프로세스가 끝나면 이어받습니다. 그때 이미 서명된 승인이 있으면 그 5.1.1 프로세스는 변경 `rootRelease.approve`의 기록을 읽지 못해, 관리자 페이지에 「되돌리기」 절에서 설명한 것과 같은 검증 실패 띠가 보일 수 있습니다. 해결은 5.1.1로 도는 프로세스가 없도록 모든 AGS MCP 프로세스를 다시 시작하는 것입니다.
- 관리자 페이지에 「root 해제 승인」 메뉴가 생겼습니다. 이 메뉴에서 승인하려면 페이지의 다른 변경과 같이 등록된 패스키가 필요합니다.

## 되돌리기

5.1.1로 되돌릴 수는 있지만, 아래는 코드를 읽고 추론한 것이며 실행해 확인하지 않았습니다. 5.1.1 서버는 변경 `rootRelease.approve`를 모르므로, 이미 서명된 승인 기록이 검증 실패 항목(관리자 페이지의 검증 실패 띠)으로 남을 수 있고 「버리기」로 지워지지 않을 수 있습니다. 승인한 뒤 되돌리면 그 띠가 보일 것으로 예상하십시오. 다른 서명 설정은 마지막 유효 판으로 그대로 동작합니다. 피하려면 아무도 root 해제 승인에 서명하기 전에 되돌리거나, 승인을 모두 쓸 때까지 5.1.2를 유지하십시오(쓰지 않은 승인은 서명 30분 뒤 효력이 끝나지만 그 기록은 로그에 남습니다).

## 바뀐 점

- **폼을 못 보여 주는 호스트에서도 다른 세션의 root를 놓을 수 있습니다.** `release_convergence_root`는 다른 세션 root에 대한 사용자 승인을 MCP 폼으로만 받았습니다. 폼을 보여 주지 않고 바로 decline으로 답하는 클라이언트(Codex 클라이언트)에서는 그런 root를 놓을 수 없었습니다. 이제 폼을 보여 주는 것으로 확인되지 않은 호스트에서 `userApprovalRefs` 없이 호출하면 아무것도 바꾸지 않고 폼도 띄우지 않으며, 관리자 페이지의 「root 해제 승인」 메뉴에 요청을 남깁니다. 요청에는 root, 소유 세션과 접속 상태, 에이전트가 적은 사유, 해제했을 때의 영향이 보입니다. 사용자가 패스키로 승인하면 에이전트가 받은 승인 ID를 `userApprovalRefs`에 넣어 `release_convergence_root`를 다시 호출합니다.
- **승인의 범위가 좁습니다.** 승인은 root 하나의 revision 하나, 사용자가 본 사유(다른 사유로 호출하면 거절합니다), 요청한 세션에만, 서명한 뒤 30분 동안 유효하고, 한 번 해제하면 소진됩니다. 그 사이 바뀐 root, 다른 세션의 사용, 만료되었거나 이미 쓴 승인, 아무도 서명하지 않은 승인은 거절합니다. 기존 검사는 그대로입니다. 소유 세션이 online인 root는 여전히 거절하고, 해제 기록에는 누가·언제·어떤 presence 판독과 승인으로 놓았는지 남습니다.
- **폼을 보여 주는 호스트는 폼을 유지합니다.** Claude Code는 기존처럼 폼으로 묻습니다. 폼을 보여 주는 호스트라도 클라이언트가 폼을 알리지 않으면 거절하지 않고 관리자 페이지에서 묻습니다. 모든 호스트가 관리자 페이지에서 받은 승인 ID를 받아들입니다.
- **도구 설명이 사실과 같아졌습니다.** `release_convergence_root`의 `userApprovalRefs`는 서버가 받지 않는 「사용자 메시지」를 예로 들었습니다. 도구 설명, 필드 설명, `ROOT_CONFLICT` 안내가 이제 받는 참조를 정확히 말합니다. 관리자 페이지에서 서명한 승인 ID, 또는 폼 승인의 ID입니다. 대화에 있는 사용자 메시지는 승인이 아닙니다.

## 한계

- root 교체(`open_convergence_root`의 `parentRootId`)는 여전히 MCP 폼으로만 묻습니다. 폼을 못 보여 주는 호스트는 아직 그 승인을 줄 수 없습니다.
- 어느 호스트가 폼을 보여 주는지는 서버의 목록(`FORM_APPROVAL_HOSTS`)이 정하며, 지금은 Claude Code만 있습니다. 아직 확인하지 않은 호스트를 포함해 나머지는 관리자 페이지를 씁니다.
- 관리자 페이지에서 준 승인은 요청한 세션만 쓸 수 있습니다. 승인이 그 세션의 식별자에 묶여 있기 때문입니다. 세션 ID가 달라지는 세션(예: 다시 시작해 새 세션이 된 경우)은 다시 요청해야 하고, 사용자도 다시 승인해야 합니다.
- Claude Code에서는 폼이 유일한 경로입니다. 사용자가 폼을 거절하거나 취소하거나 답할 사람이 없으면 요청이 관리자 페이지로 넘어가지 않습니다. 사용자가 폼에 답할 수 있을 때 다시 요청하십시오.
- 사유를 바꾸거나 root의 revision이 바뀐 뒤 다시 요청하면 대기 요청이 따로 쌓입니다. 대기 요청은 만료되지 않고 새 요청으로 대체되지도 않으니 이전 요청은 관리자 페이지에서 거절하십시오.
- 관리자 페이지에서 「세션 결속」과 「root 해제 승인」 메뉴는 상태 문구 칸을 같이 씁니다. 한 메뉴에서 거절·승인한 결과가 다른 메뉴에 보일 수 있습니다.

## 검증

후보는 `hotfix/5.1.2`의 커밋 `3edbce83`으로, 이 노트를 고치기 직전의 후보입니다. 이 노트 수정은 문서만 바꾸므로 그 뒤에는 `pnpm lint`와 `pnpm release:check`만 다시 돌립니다.

- 환경: Windows 11, PowerShell, Windows 내장 `tar`(Git Bash의 GNU tar를 `PATH`에서 뺐습니다. GNU tar는 `C:`를 원격 호스트로 읽어 압축 해제 시험이 실패하기 때문입니다), `--maxWorkers=1`.
- 생성물은 재현됩니다. `node scripts/i18n.mjs --write`, `--accept ko`, `pnpm build`, `pnpm claude:build`를 돌린 뒤 `git status --porcelain`이 비어 있어, 커밋된 생성물이 저장소 스크립트의 결과와 같습니다.
- `pnpm lint`, `pnpm claude:check`, `pnpm bundle:check`, `pnpm release:check`, `pnpm source:check`, `pnpm validate:all`, `git diff --check`가 모두 종료 코드 0으로 끝났습니다.
- `pnpm test -- --maxWorkers=1`: 시험 파일 347개 통과, 2개 건너뜀(349개). Vitest 5,088건 중 통과 5,051, 실패 0, 건너뜀 29, todo 8입니다. Node 시험 600건은 통과 599, 실패 0, 건너뜀 1입니다. Vitest와 Node 종료 코드는 모두 0입니다.
- 앞선 실행에서 환경 요인으로 실패한 일이 있었습니다. `--maxWorkers=2`에서는 일부 시험이 시간 제한을 넘겼고, GNU tar 환경에서는 압축 해제 시험 6건이 실패했습니다. 둘 다 환경 요인으로 분류하며, 위 조건에서는 통과합니다.
- 실행하지 않음: `pnpm source:verify`(원격 원본과의 대조), `pnpm validate:official`(Codex 개발 환경 전용), 실제 브라우저에서의 패스키 서명, 실제 Codex 호스트에서 root 해제를 끝까지 해 보는 것, Linux 시험 서버, 위 되돌리기. 설치 확인은 발행 뒤 이 PC의 Claude Code에서 하며, Codex와 Antigravity의 설치는 확인하지 않았습니다(미검증).
