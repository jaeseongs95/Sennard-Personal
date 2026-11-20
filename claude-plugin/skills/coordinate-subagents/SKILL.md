---
name: coordinate-subagents
description: Use when a task has a unit that can be completed and verified on its own or a result needs a check by someone other than its author, and when the user asks for subagents, parallel work or an independent auditor. Handles ownership assignment, choosing each approved assignment's model and reasoning effort from the models the host lists and naming them on every call the host accepts them on (inherit only where it cannot, with the reason recorded), evidence integration and separating high-risk audits. A brief names goal, scope, forbidden areas, write ownership, chosen skills and completion and verification criteria, and never hands off waiting (watching results, polling). Not for one step that cannot be split, or solely because a task is complex or orchestration is enabled.
license: AGPL-3.0-or-later
metadata:
  version: "2.1.0"
---

# Coordinate Subagents

Coordinate work across subagents without expanding the user's scope, permissions, or requested side effects. The coordinator decides, connects, and checks; bulk reading and writing goes to subagents, and large artifacts stay out of the coordinator's context.

Keep delegation, agent count, batching, context, permissions, the model and reasoning effort of each approved assignment, and independent-audit decisions here. A model choice never authorizes dispatch or attests the settings that took effect.

Name the model and the reasoning effort on every subagent call, for each assignment, whenever the current host takes them per call. Inherit the main agent's settings only where the host cannot take them (no per-call model or effort in its listing or schema) or the context forbids overriding them, and then record that the call inherits and why. Never pass a model or an effort the host does not list: the call fails. A model that lists no effort gets the model alone.

## Apply instruction priority

<!-- policy-contract: authority.inherit-mode -->

- Follow system, developer, user, project instruction, and mode constraints before this skill.
- Treat delegation as a way to execute authorized work, never as new authorization.
- Apply the current mode and write restrictions to every delegated task.
- Honor an explicit user choice about whether to delegate, how many agents to use, and which supported model or reasoning effort to select; a model or effort the user names is named on the call as given, where the host lists it.

<!-- optimization-navigation:start condition="the skill is activated for the request" reference="references/entry-details.md" do-not-load-otherwise="true" -->
- If the skill is activated for the request, read [entry details](references/entry-details.md) before producing any result or taking any action; otherwise do not read it.
<!-- optimization-navigation:end -->
## Complete the task

Before reporting completion:

<!-- policy-contract: completion.current-evidence -->
<!-- policy-contract: completion.report-facts -->

- account for every assignment;
- confirm that each subagent's scoped verification actually passed;
- verify each requirement against current evidence with checks proportional to its failure impact;
- avoid checks that merely repeat the work being checked, and reverify only the affected scope when a later change invalidates earlier evidence;
- integrate accepted results without overwriting unrelated user work;
- complete required independent audits and resolve their findings;
- report the result, material changes, checks actually run, their outcomes, and remaining constraints;
- never present an unrun check or unverified setting as completed.
