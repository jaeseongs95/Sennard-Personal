[한국어](system-flow.ko.md)

# Sennard System Flow

These diagrams show how Sennard 1.3.0 handles requests, observes host sessions, tracks workflows, and delivers messages between sessions. Host hooks report events to the local Sennard server. The agent uses the server's MCP tools to choose a gate and record work.

## Handling a User Request

```mermaid
flowchart TD
    request["User submits a request"] --> select["Agent must choose a gate as its first step"]
    select -->|Existing workflow fits| reuse["Continue the existing workflow"]
    select -->|New answer or investigation| inquiry["Choose INQUIRY"]
    select -->|Other work| entry["Enter the selected gate"]
    inquiry --> entry
    entry --> guidance["Receive the skill target list and intake guidance"]
    guidance --> skills["Agent selects applicable skills"]
    advice["JEV advice when enabled"] -.-> skills
    reuse --> work["Respond or work within the selected workflow"]
    skills --> work
    work -->|Supported tool requested| check{"Does the workflow gate allow the call?"}
    check -->|Yes| tool["Run the tool"] --> work
    check -->|No| refused["Refuse the call and show what is required"] --> work
    work -->|New input arrives| request
    work -->|Agent attempts to stop| stopKind{"Which kind of work is stopping?"}
    stopKind -->|INQUIRY| stop["Stop processing"]
    stopKind -->|Workflow subject to checks| workflowCheck{"Do the workflow checks pass?"}
    workflowCheck -->|Yes| stop
    workflowCheck -->|No| workflowStop{"Can the host continue this Stop?"}
    workflowStop -->|No, recheck limit is 0| blocked
    workflowStop -->|Yes| workflowLimit{"Does the workflow have rechecks left?"}
    workflowLimit -->|Yes| correct["Ask the agent to address the failing check"] --> work
    workflowLimit -->|No| blocked["Mark the run BLOCKED and record the plan hash"]
    select -.->|Agent tries to stop without selecting a gate| inputStop{"Can the host continue this Stop?"}
    inputStop -->|No, recheck limit is 0| inputExhausted
    inputStop -->|Yes| inputLimit{"Does this input have rechecks left?"}
    inputLimit -->|Yes| rethink["Ask the agent to reconsider and select a gate"]
    rethink --> select
    inputLimit -->|No| inputExhausted["Record STOP_EXHAUSTED for this input"]
    inputExhausted --> exhausted{"Which host handles the exhausted limit?"}
    blocked --> exhausted
    exhausted -->|Claude Code or Codex| forced["Stop processing immediately and show the reason"]
    exhausted -->|Antigravity| delayed["Allow the agent to stop, record the violation, and notify on the next input"]
    forced --> available["Keep any answer already sent and leave the session available"]
    delayed --> available
```

Choose INQUIRY for an answer or an investigation, including a request that only needs an answer. Gates for changes, execution, and lifecycle transitions serve their respective kinds of work. Product-file edits require a CHANGE workflow with those files in its change plan. The server stores EXECUTION as `OPERATION`. See [Usage](usage.md#workflow-kinds-and-the-workflow-gate) for the entry point of each procedure.

The gate-entry response provides the skill target list and intake guidance. JEV adds optional advice when enabled. The agent makes the final skill selection.

The workflow is Sennard's unit of work. When new input arrives, the agent decides whether to continue an existing workflow or open a new one. A host's Stop hook or equivalent triggers the checks when the agent attempts to stop.

A failing workflow uses a recheck only at a Stop the host can continue. At a continuable Stop, the current attempts-per-epoch setting on the admin page sets the recheck limit. If the host cannot continue the Stop, the limit is zero. When a check fails with no rechecks left, the server records the plan's SHA-256 hash and marks the run `BLOCKED`. The run is available for inspection only. Attempts to resume it, record a stage result, finalize it, or edit its plan are refused. If the agent calls `abort_workflow` before the limit is exhausted, the server records the hash only if the workflow has already failed a check. INQUIRY is outside the workflow check count and does not exhaust a workflow limit, become unavailable through a check, or have a plan hash recorded.

When the working folder, workflow kind, and SHA-256 hash match a blocked plan, the server refuses registration or plan submission across sessions and hosts. The response gives the reason and identifies the workflow that originally became unavailable. Refusal leaves other workflows in their current states. A changed plan with a different hash can be submitted. The user can lift a hash block on the admin page. The agent cannot lift it, and the block does not expire automatically.

If the agent tries to stop without selecting a gate, the input's recheck count increases only at a Stop the host can continue and while a recheck remains. The hook then asks the agent to reconsider and select a gate. If the host cannot continue the Stop, the server applies a recheck limit of zero. The server records `STOP_EXHAUSTED` for the input when the limit is reached, and the turn ends. The workflow's own recheck limit determines when these checks mark it `BLOCKED`.

At either limit, Claude Code and Codex stop processing immediately and show the reason. Antigravity allows the agent to stop without showing the violation at that point. The server records the violation and, for a workflow limit, the `BLOCKED` run. It shows the notice when the next input arrives. Any answer already sent remains visible, and the session can accept another request.

## Session Lifecycle

```mermaid
flowchart TD
    start["Host opens or resumes a session"] --> startup["Session-start hook contacts Sennard"]
    startup --> ready{"Is the local server available?"}
    ready -->|No| boot["Start the installed server and wait for readiness"]
    boot -->|Ready| host{"Which host supplies the context signal?"}
    boot -->|Unavailable| notice["Show a notice and let the host session proceed"]
    ready -->|Yes| host
    host -->|Claude Code or Codex| claudeCodex["Host signals session start, resume, or completed compaction"]
    host -->|Antigravity| agy["First model invocation of each request, including the next request after compaction"]
    claudeCodex --> context["Inject the full global, role, and situational contexts"]
    agy --> context
    context --> active["Host session handles requests"]
    active --> boundary["Supported hooks report input, stop, tool, and model events"]
    boundary -->|Session continues| active
    active -->|At the next host-specific context signal| host
    active --> exit["Host session ends"]
    exit --> ended["Sennard records the observed end"]
```

Claude Code and Codex use the host's official session-start, resume, and post-compaction signals. Antigravity injects context once per request, at the first model invocation (`invocationNum=0`). After automatic compaction during an Antigravity request, context is restored at the first model invocation of the next request.

Use `checkpoint_context` to save work that needs to survive a resume or compaction. Sennard can record a session end from an end hook or from the exit of a monitored host process. The local server continues to serve other sessions.

## Workflow and Run States

```mermaid
flowchart TD
    subgraph workflow["Workflow"]
        open["OPEN"]
        review["NEEDS_REVIEW"]
        user["NEEDS_USER"]
        completed["COMPLETED"]
        abandoned["ABANDONED"]
        open -->|Finalize passed work| completed
        open -->|Retry limit reached or frame review required| review
        open -->|User decision required| user
        review -->|Review permits a new epoch| open
        review -->|User decision required| user
        open -->|Release workflow| abandoned
        review -->|Release workflow| abandoned
        user -->|Release workflow| abandoned
    end

    subgraph attempt["Run"]
        running["RUNNING"]
        paused["PAUSED"]
        decision["NEEDS_INPUT, NEEDS_APPROVAL, or NEEDS_REDESIGN"]
        failed["FAILED"]
        blocked["BLOCKED"]
        passed["PASSED"]
        running -->|Pause| paused
        paused -->|Resume with current revision| running
        running -->|Stage requires a user decision| decision
        running -->|Stage fails| failed
        running -->|Stage blocked, check limit exhausted, abort, or release| blocked
        paused -->|Abort or release| blocked
        decision -->|Abort or release| blocked
        running -->|Finalize after all checks pass| passed
    end
```

A workflow tracks the work across attempts. Each attempt is a run. A CHANGE run can start in planning and enter execution once its file plan is submitted. Stage results can advance the run, stop the attempt, or put the workflow in a state that requires a user decision.

Pausing preserves the current run and its scope. Aborting ends the run and can leave the workflow open for another attempt. If the workflow stays open and the retry requirements are met, claim and start a new run. Successful finalization and release both free the workflow's scope. See [Usage](usage.md#workflows-and-the-edit-gate) for how to record progress and finish a run.

## Collaboration Between Sessions

```mermaid
flowchart TD
    board["Sender finds the recipient on the session board"]
    board --> prepare["Prepare PEER message and get its message ID"]
    prepare --> send["Send using the same message ID"]
    send --> queue["Sennard queues the message for the target"]
    queue -->|Wake path available| wake["Signal the idle target"]
    queue -->|Ordinary delivery boundary| claim["Target claims the body and receipt"]
    wake -->|Target reaches a delivery boundary| claim
    queue -->|Sender recalls before first claim| recalled["Remove from the queue"]
    claim --> processed["Recipient processes the message"]
    processed --> ack["Acknowledge using the original claim receipt"]
    claim -.->|Redelivery after the claim lease expires| queue
```

Sessions under the same OS user share the local server. The session board is how a sender looks up a target. The message queue delivers the body at a supported host boundary, and wake support depends on the host and its live connection. If the send result is uncertain, check the existing message ID. An acknowledgment confirms processing. Replies or work results travel as separate messages. See [Session Collaboration](collaboration.md) for tool names, status checks, and host-specific wake behavior.
