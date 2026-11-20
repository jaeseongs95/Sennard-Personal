# Provider-neutral model routing

This policy lets the delegating agent pick a model and a reasoning level for an approved assignment on hosts that expose compatible subagent controls. The shared machine-readable data is the Sennard runtime file [`model-routing-presets.json`](../../../runtime/model-routing-presets.json). It names no model, no host effort label and no per-role table. It carries the shared orders, the role names, and, per provider adapter, how a selection is delivered to the host (field names, delivery, verification). A different provider can supply the same adapter contract without changing the shared selection logic.

The shared `lightweight < general < deep < frontier` model-class order and `shallow < standard < deep < deepest` reasoning-depth order only rank a judgment; they never refuse delegation, tool access, or result recording. The same class or depth is not measured evidence of equal ability across vendors, and a host's own effort labels are calibrated per model: they are not equal across hosts or models. Actual output quality requires separate evaluation evidence.

Explicit user choices, higher-priority instructions, project instructions, current host capabilities, and runtime restrictions take precedence over every judgment made here.

## Take the model and effort from the host, not from this product

The product holds no list of model names, aliases, effort labels, or per-model limits. The delegating agent assembles the `supported` list at dispatch time:

- read the models the current host exposes for subagents, with the efforts it accepts for each, from the host's own model list or schema;
- assign each listed model a class (`lightweight`, `general`, `deep`, `frontier`) from the vendor's official model guidance for that model, and use `modelClass: null` when no such guidance can be found. Do not infer a class from a name or version pattern;
- list the effort labels the host accepts for that model exactly as the host names them, from the shallowest to the deepest. The resolver maps a judged depth to the label at the matching position: the first listed label is `shallow`, the last is `deepest`, and the labels between fill `standard` and `deep` in order. Leave out labels that must not be chosen, such as a label the host reserves for an explicit user request or one below a model's minimum, so the resolver cannot pick them.

A model that appears on the host after this policy was written needs no change here: it is classified from the host list and the vendor's guidance, then competes with every other listed model. For the judged class the resolver takes a listed model of that class; when none exists it takes the nearest class that does (the higher class on a tie, then the host's listing order). If no classified model matches those requirements, the resolver uses the first compatible unclassified model in the host list. Its class remains unknown and the selection records a fallback reason. A user's explicit effort label is used as given when the host lists it for the chosen model.

## Judge the class and depth for each assignment

The delegating agent judges, for each assignment, the model class and reasoning depth it needs, and passes them as `judgment: {modelClass, effortDepth}`. The role, difficulty, risk and verification limits describe the assignment; they do not select a value by table. Use these criteria:

- the less the assignment has to decide for itself, the lighter the class and the shallower the depth, down to the lightest class and shallowest depth when the target, method and criteria are all fixed in the brief and a mechanical check can confirm the result;
- the more the assignment has to decide for itself, the stronger the class and the deeper the depth, up to the strongest class and deepest depth when a lower level would still leave a decision that nobody else can check;
- a result a verifier will check directly needs less than a result nobody checks, and a first convention that later units copy needs more than one unit that follows an existing convention;
- only the assignment changes the judgment. Nothing else does, such as the order in which the host lists its models.

Illustrative judgments, not a mapping: collecting and tallying with a fixed method leaves nothing to decide; writing to a fixed specification leaves little; finding a cause, making a design decision, or verifying whether a result is correct and satisfies the requirement leaves much to decide and a wrong answer hard to see.

Without a judgment the resolver does not choose a model, so a host that lists models needs the judgment (or a user who named both the model and the effort) on every call; the resolver refuses the input without one instead of falling back to inheritance. Only a host that lists no model it takes per call, or a host or context that forbids overrides, inherits.

## Name the model and effort on every call

When the current host takes a model and an effort per call, every subagent call names both. Inheriting the main agent's settings is not a default and not a convenience; it is the exception for a host that cannot take them. Judge that from what the host actually lists (its model list and tool schema, with the efforts it accepts for each model), never from the host's or the model's name.

- The host lists models and efforts: pass the selected `model` and `effort` (the adapter's field names).
- The chosen model lists no effort, or does not list the effort that was wanted: pass the `model` alone and leave the effort argument out; an effort the host does not list makes the call fail. The plan's `fallbackReason` says the effort was not passed.
- The host lists no model it takes per call, or the host or context forbids overriding (`inheritOnly`): inherit, pass neither argument, and record that the call inherits together with the reason.
- A user's model or effort wins over the judgment wherever the host lists it.

## Keep routing separate from delegation

Routing does not observe or change the main agent's own selection in the host interface. This document, its adapter data, and the shared model policy are the only routing engine sources; delegation, allocation, permissions, context, and required audits are decided separately in `coordinate-subagents`.

<!-- policy-contract: routing.preset-does-not-delegate -->
<!-- policy-contract: routing.user-override-precedence -->

Apply a routing selection only after work delegation or an independent audit has already been approved. A selection of a model class and reasoning depth for an assigned role never triggers delegation, increases agent count, changes batching, broadens authority, or waives a required audit.

A user-supplied model or effort overrides the judgment when the host supports it.

## Map roles before providers

<!-- policy-contract: routing.role-provider-matrix -->

Classify each approved assignment as one of these roles before consulting a provider mapping:

- `discovery`: bounded search, inventory, counting, extraction, or aggregation that needs no judgment and has a clear check. When the collected material is evidence that a verifier will rely on, so that a missing or wrong item would change the verdict, assign the collection as `general-implementation` instead;
- `general-implementation`: making or changing deliverables, investigation, organizing evidence, ordinary analysis, review, bounded revision, and a check that needs little judgment because it is compared mechanically with a stated criterion (for example, code analysis, feature work, refactoring). Most delegated work belongs here, including difficult diagnosis, design, and integration, whose difficulty shows in the judgment of class and depth and not in a separate role;
- `independent-audit`: a fresh verification that needs heavy judgment: a design choice, whether a finding is a defect or intended, agreement with the user's decisions and constraints, or a completion verdict on high-risk or hard-to-reverse work. It decides a verdict.

The role names the kind of work and who may do it; the class and depth come from the judgment above.

Select the role first, then resolve the current host's provider entry in `model-routing-presets.json`. Depths are abstract values, not cross-provider quality equivalences, and the adapter data names no model and no host effort label.

## Apply the OpenAI Codex adapter

<!-- policy-contract: routing.codex-adapter -->
<!-- policy-contract: routing.full-history-inherits -->

For Codex collaboration tools, pass the selected `model` and `reasoning_effort` as spawn arguments only when the current schema supports that exact combination. With `fork_turns="all"`, inherit the parent model and reasoning effort; do not set model or reasoning overrides (the call fails), and record the inheritance with the full-history reason (`inheritOnly: true`). A fork of the full history is therefore the one case on this host where a call inherits, so use it only when a limited brief cannot serve the unit. For an explicit override, use `fork_turns="none"` or a positive recent-turn count and include missing context in the brief. An `independent-audit` assignment always uses `fork_turns="none"`; a positive recent-turn count or full history is rejected for it regardless of any context reason.

Consult the current collaboration tool schema first and use the official OpenAI model guidance when current product guidance is needed.

## Apply the Anthropic Claude Code adapter

<!-- policy-contract: routing.claude-code-adapter -->

For Claude Code, take the model and the efforts it accepts for each model from the host's own Agent tool schema and model list, and verify that the host advertises the selected values. The Agent tool takes `model` and `effort` on every invocation, so pass both on each call; a custom subagent definition's `model` and `effort` are only the defaults the call overrides. Pass the `model` alone when the chosen model lists no effort or not the wanted one, and inherit only when the host's schema in use lists no per-invocation `model` or `effort` argument (an older host), recording that and the reason.

Before dispatch, check the installed Claude Code version, organization allowlist, provider availability, and any environment variable that forces a model. After dispatch, use the task view to confirm the actual model and effort when verification matters. Claude Code effort levels are calibrated within each model, so do not treat the same label as an exact match to a Codex reasoning level.

Use the official Claude Code documentation on model configuration and custom subagents when current support or precedence is uncertain.

## Apply the Google Gemini adapter

Execution hosts (such as Antigravity, Gemini CLI, Claude Code, and Codex) are separate from model vendors: a single host may expose models from multiple vendors, and the delegating agent classifies each listed model from that vendor's guidance rather than inferring the vendor or class from the host name or model name. When dispatching a Google Gemini model on any compatible host, take the model from the host's list, then translate the selection into the current host's invocation schema or subagent definition using the adapter application metadata without assuming a fixed list of model argument values. Verify actual dispatch against the host task view or invocation result.

## Apply runtime constraints

<!-- policy-contract: routing.settings-supported -->
<!-- policy-contract: routing.unsupported-fallback -->
<!-- policy-contract: routing.adapter-minimum-effort -->

Read the efforts the host lists for the chosen model; they are the only limit on effort for that model. If the judged combination is unsupported, choose another observed supported combination instead of lowering the host's effort limit. Never infer model class or effort support from a model name.

Inspect the current host schema and capabilities before setting an override. Never report an unsupported or unverified setting as applied. Treat subscription details as context, not proof that a model or runtime control is available.

The class and depth order only rank a judgment: a host-supported user choice, inherited setting, unclassified model, or `lightweight` model is still selected.

Use a speed or premium mode, or an effort level beyond the deepest label the host lists for the model, only when the user explicitly requests it and the current host documents and exposes the control. If the judged combination is unavailable, choose another listed combination, or the listed model alone. Delegate with the inherited configuration only when the host lists no model it takes per call or forbids overrides, and record why; record unobserved inherited settings as `unverified`. If neither is supported, report the capability limit without changing the approved work's scope.

## Resolve and record the actual dispatch

Use the `run_skill_check` MCP tool's model-routing check before dispatch:

```text
run_skill_check {"skill":"coordinate-subagents","command":"resolve","arguments":{"input":{"file":"/absolute/selection-input.json"},"presets":{"file":"/absolute/explicit-presets.json"}}}
```

The optional `presets` argument supplies the existing `customPresets` function input explicitly to both `resolve` and `record`. Without it, the bundled data is unchanged; the check does not discover nearby files or load environment overrides. This file is supplied policy data, not authenticated authority: the caller must establish its source, current host capabilities, and permitted settings separately. It cannot grant delegation, satisfy workflow admission or establish execution attestation. Support, risk, context and observed-application checks still apply. A malformed explicit file fails rather than falling back to bundled policy.

Supply `provider`, `role`, explicit `highRisk` (`true` is required for `independent-audit`), `judgment`, and `supported` entries `{model, modelClass, efforts}` from current host capabilities. Use `modelClass: null` when no class can be established from the vendor's guidance. The resolver chooses among the `supported` entries and the labels each lists, which the caller keeps in order from the shallowest to the deepest; an unclassified fallback stays eligible when the host lists it, with its class left unknown. Optional `user: {model, effort}` supports either override separately; optional `inherited: {model, effort}` is an observed inherited combination. When the host lists nothing it takes per call but supports inheritance and hides its effective settings, give an empty `supported` and set `inheritanceSupported: true` without inventing `inherited` values. This permits inheritance with `unverified` application at any risk level; it never replaces a listing, so a host that lists models is addressed explicitly. Set `inheritOnly: true` when the host/context prevents overrides; that call inherits and never blocks. A `supported` entry with no `efforts` is a model the host takes without an effort: it is selected without one. Unknown providers can use a supported explicit user combination or inheritance. A partial user override uses the judged class or depth for the part it leaves out, or a known inherited combination when there is no judgment; missing values remain explicit nulls rather than silently discarding the user choice. Application recording requires a declared provider adapter. `blocked` means the host offers no usable combination; model class and effort never cause it. The result's `requested` carries the judged `modelClass` and `effortDepth`, or the user's `model` and `effort`; `selection` is the listed model and effort label actually chosen, and `fallbackReason` is set when it is not the class that was judged.

Execution attestation is a separate trust boundary: an installed trusted `HostExecutionAdapter.resolveModel` can supply a capability descriptor for a new model ID, provided trusted composition selects it at both boundaries when using the hook/server path. A routing input, custom preset, or caller-supplied class does not create that authority. Without a matching trusted descriptor, an unknown model still runs with an unknown class and a recorded policy note. See [the current architecture contract](https://github.com/jaeseongs95/sennard/blob/release-3.0/docs/architecture.md#semantic-execution-assurance); native producer and loaded-runtime evidence remain separate from adapter registration.

Assemble the selection input from the current host capability snapshot and your judgment. Copy only observed supported combinations; do not substitute a fixed model example or treat the data as host execution evidence.

Translate the returned `selection` into the current host adapter's actual fields. For Codex explicit delivery, pass both `model` and `reasoning_effort` with `fork_turns="none"` or a positive recent-turn count; `independent-audit` accepts only `fork_turns="none"`. For inherited delivery, pass neither override and keep the plan's `fallbackReason` as the recorded reason. When the selection has no effort, pass the model alone. Inspect the actual call rather than copying the intended selection into a record.

After dispatch, call `run_skill_check {"skill":"coordinate-subagents","command":"record","arguments":{"input":{"file":"/absolute/dispatch-input.json"}}}` (with the same optional `presets`). Supply `assignmentId`, `selectionReason`, the same selection input under `options`, `spawnArguments` containing the actual model, effort, and context arguments in the host adapter's field names, and `contextReason` for full history. The recorder rejects `independent-audit` with any inherited parent turns or full history, even with a `contextReason`; record it with `fork_turns="none"`, or with `contextMode: "limited"` for a fresh context on adapters without a context field. For Codex, copy `model`, `reasoning_effort`, and `fork_turns` from the actual call; omitted `fork_turns` defaults to full history. The recorder derives `contextMode` and normalized `dispatched` from these arguments and rejects a contradictory optional `contextMode`. For adapters without a context field, provide `contextMode` (`limited` or `full-history`) explicitly. Keep task bodies and secrets out of the settings record. Supply `observation: null` if the response exposes no effective settings. Otherwise supply `{model, effort, source, reference}`: source is `spawn-result`, `host-task-view`, or an explicit `tool-contract` guaranteeing the passed combination; reference locates the evidence seen by the caller.

The result distinguishes `applied`, `inherited`, and `unverified`. Passing both arguments alone does not prove application. Worker self-report, assumed parent settings, and later evaluator inspection are not caller-visible host evidence. Keep later inspection separate as posthoc evidence. If the observed settings differ, preserve them and investigate the difference before relying on the worker's result. `unverified` records only that the settings were not observed. The script validates records against supplied observations; it does not authenticate evidence, launch agents, or grant permission.

For actual host verification, follow [the live dispatch check](live-dispatch-check.md). Deterministic routing tests do not prove host execution or concurrency.

## Diagnose before retrying

<!-- policy-contract: routing.no-mechanical-retry -->

Do not retry through progressively different models when the failure comes from missing information, permissions, tools, an unsupported setting, or an incomplete specification. Resolve the actual blocker or report it.

The shared data is versioned by maintainers. Update provider adapter mappings and deterministic fixtures when host capabilities change; the core delegation policy stays usable when every model on a host changes, because no model or effort label is named here.
