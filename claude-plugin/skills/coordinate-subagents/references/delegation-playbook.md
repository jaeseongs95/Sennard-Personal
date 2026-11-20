# Delegation playbook

Read this reference when a task needs concrete assignment briefs, batching, shared-state coordination, failure recovery, or an independent audit.

## Lay out the task graph

For each execution unit, record:

| Field | Decision |
| --- | --- |
| Goal | Observable outcome owned by the unit |
| Dependencies | Prior results required before starting |
| Inputs | Files, facts, tools, and context it may use |
| Writes | Files or external state it alone may change |
| Completion | Conditions that make the unit finished |
| Verification | Evidence the unit must return |
| Risk | Ordinary or high risk, with the reason |
| Role | Collection without judgment (`discovery`), making or changing deliverables, investigation or evidence organizing (`general-implementation`), a check that needs little judgment, compared mechanically with a stated criterion (`general-implementation`), or a verdict check that needs heavy judgment, where a wrong result forces a person to judge again (`independent-audit`), and what the unit has to decide for itself |

Units are independent only when they can start from available inputs and their ownership, completion, and verification remain separable. Reading the same source does not create a conflict. Concurrent writes to the same file or external record do.

## Prepare a delegation brief

Use this shape and replace every field with task-specific content:

```text
Goal: <one observable outcome>
Prohibited actions: <each action the assignee must not take, with the allowed way to do the same thing, and whether it may start further agents (default: no)>
Ownership: <files, subsystem, records, or decision area>
Do not change: <other agents' areas and out-of-scope work>
Inputs: <required artifacts and facts>
Existing work: <artifacts and state already produced for this unit, what is done and what remains>
Constraints: <permissions, execution mode, tools, compatibility, style>
Deliverable: <artifact location, conclusion and evidence to return, not the artifact's full contents>
Done when: <completion conditions>
Verify with: <checks and evidence to report>
Coordination: <when to contact the coordinator before crossing scope, and what the coordinator's reply then authorizes (an action inside the assignee's scope and the user's granted authority), so the assignee can act on it without asking the user again>
```

Put the prohibited actions in the first instruction the assignee reads, not in a later message, and pair each with the permitted alternative (for example, edit files with the host's edit tool instead of shell redirection or in-place text editing; scratch results stay inside the assignee's own work area). An assignee that only reads a list of "do not" items still reaches for the forbidden tool when it is the shortest path, so the permitted path has to be named. Where the host can enforce a limit itself (a tool allow-list, a read-only mode, a permission setting for the subagent), enforce it there as well instead of relying on the text alone. Ask the assignee to report any prohibited action it took and what it changed, so the report can be compared with the brief.

When reusing a completed agent, send a complete new brief. Do not assume the old responsibility remains active. For auditor and Judge eligibility or reuse, follow `independent-audit-gate` (`references/audit-protocol.md`, `2. 감사자 독립성`).

## Batch and synchronize

<!-- policy-contract: allocation.explicit-single-delegation-playbook -->

- After the net-benefit decision approves work delegation, keep one useful work unit with the coordinator when two or more work units exist. If the user explicitly delegates the only work unit, give it to the subagent and keep coordination and evidence review with the coordinator.
- Interpret the host's slot contract before allocating work. A reported total team capacity includes the coordinator unless the host says otherwise; a reported subagent count does not.

<!-- policy-contract: audit.reserve-identity-playbook -->

- When a required high-risk audit may not have access to a fresh identity later, reserve an uninvolved subagent and slot before assigning all delegated work.
- Fill only currently available subagent slots after that calculation.
- If more units remain, queue the next independent batch and reuse completed agents. When reusing completed agents across work batches, follow `independent-audit-gate` (`references/audit-protocol.md`, `2. 감사자 독립성`) for auditor and Judge non-reuse.
- Use a single writer for shared files. Other agents return proposed changes or evidence to that writer.
- Start dependent work only after validating the upstream result it consumes.
- After dispatch, the coordinator works on its own ready unit; it does not immediately wait while useful independent work remains. Collect finished results and refill available slots without a whole-batch barrier.
- Reserving an auditor does not disable work delegation. Keep the auditor out of the work being checked and begin its audit only after the final candidate and verification evidence are available.
- The MCP workflow records stage results in plan order. Run independent units inside the appropriate stage with host collaboration tools, then integrate their results before recording the stage. Parallel execution does not permit out-of-order stage receipts.
- Do not ask multiple agents to solve the same unit unless independent comparison or audit is the stated purpose.
- Before splitting one kind of change broadly across many units, follow this order: (1) fix the standard the units must follow; (2) lock the external behavior that must not change as reference results; (3) turn repeated checks into automated checks; (4) have a small pilot unit confirm the convention; (5) only then widen in parallel. Splitting broadly before the convention is settled copies the same mistake into every unit.

## Recover from dispatch problems

<!-- policy-contract: dispatch.inspect-and-recover -->

If a spawn or dispatch fails, inspect agent status before deciding what happened. Then choose the narrowest applicable recovery:

1. Retry a dispatch only when status confirms that no worker was created. Retry a communication error (connection failure, dropped connection, timeout before a response, HTTP 429 or 5xx) against the same target with growing intervals; the number of attempts is not fixed, the request's deadline and budget are the limit, and a `Retry-After` sent by the server is followed. Do not retry a non-communication error (missing key, rejected authentication such as 401 or 403, an invalid response format, any other 4xx); go to the next recovery below. A missing response or worker ID alone does not establish non-execution; retain the request identity and inspect the existing operation before dispatching a replacement.
2. Reuse an idle existing agent with a fresh brief. For auditor and Judge eligibility or reuse, follow `independent-audit-gate` (`references/audit-protocol.md`, `2. 감사자 독립성`).
3. Queue the unit for a later batch.
4. Keep the unit local only when delegation is no longer possible and disclose why.

Do not infer a permanent agent limit from a single failure. Do not switch models repeatedly when the actual blocker is missing data, permissions, tools, or specification.

## Continue an unfinished unit under a new worker

- When the worker of a unit in progress changes (another agent, another session, another model or reasoning level, or a resume after compaction or restart), do not restart the unit from scratch. First inspect what the previous worker left behind: changed files, new artifacts, and records.
- Give the new worker what is done, what remains, and why the worker changed in the brief's `Existing work` field. The new worker reads the remaining artifacts and records first, continues to completion from them, and corrects whatever does not match the requirements.
- Hand a write target to the new worker only after the previous worker is confirmed stopped. If that cannot be confirmed, do not hand over the same write target: assign only non-overlapping targets, or wait until it is confirmed. Never let two workers write to the same target at the same time.

## Run an independent audit

Whether the change needs an audit follows the high-risk classification of `mutation-risk-preflight`. Who may audit and what the auditor checks directly follow `independent-audit-gate` (`references/audit-protocol.md`). This skill hands the auditor the request, final artifacts or their changes, verification evidence, and the minimum raw context needed to judge the outcome. Do not provide a preferred verdict or hide known failures.

Resolve every finding. If a fix changes an audited area, re-audit the affected result under `independent-audit-gate` (`references/audit-protocol.md`, `2. 감사자 독립성`). For a deployment, audit the final readiness state before execution, then inspect deployment logs, migration results, and post-deployment checks before completion. If no independent auditor is available, state the limitation and do not mark the high-risk task complete.

## Integrate results

The coordinator reviews returned artifacts and evidence without repeating the assigned work. Accept or reject a result against requirements and current state. Compare the brief's prohibited actions with the assignee's report and with the actual changes and state it left; the assignee's own statement that it complied is not evidence. When a prohibited action was taken, find out what it changed or ended, undo or re-verify the affected part, record the violation with the unit's result, and judge whether the result built on it can be accepted. When a prohibition is broken again after it was restated, change the brief (a concrete permitted alternative, a host-enforced limit, a smaller unit) instead of repeating the same list. When results conflict, compare their evidence and validation rather than model names.

The final report must distinguish completed work, checks actually run, unresolved findings, and unavailable verification.
