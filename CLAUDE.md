# CLAUDE.md

## Project Overview

**Flow** (`ai.rever.boss.plugin.dynamic.flowtab`) is a dynamic plugin for the BOSS desktop application.

A node-based flow canvas - spawn nodes and connect them with edges, n8n style. Lightweight,
self-contained Compose UI with a pan/zoom canvas, draggable nodes, and bezier edges.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.flowtab`
- **Main Class**: `ai.rever.boss.plugin.dynamic.flowtab.FlowTabDynamicPlugin`
- **API Version**: 1.0.75 · **minApiVersion**: 1.0.75 · **minBossVersion**: 9.2.63
  (the MCP tool framework needs 9.2.20; `PluginContext.llmProvider` needs 9.2.63; `AiGatewayAPI`
  is a new type and needs `minApiVersion` only)

## Agent AI

The `agent` node runs through the shared **AI Gateway** plugin (`AiGatewayAPI`), via
`GatewayAgentProvider`. It works with **whatever provider is active** in Settings, AI Providers.

That is a behaviour change worth knowing about. The node used to speak Anthropic's tool-use
format directly, so `anthropicConfigFrom` had to search for an Anthropic provider - the active
one if it happened to be Anthropic, else any other configured one, else a raw secret-store
lookup for `ANTHROPIC_API_KEY`. A user whose active provider was OpenAI got an agent that either
ran on a stale hand-stored key or did not run at all. All of that is gone: the gateway resolves
the credential, and every provider it supports can drive an agent node.

`GatewayAgentProvider` is only a translation layer between the runtime's transcript types and
the gateway's. The `AgentProvider` seam itself is unchanged, so `FakeProvider` and every runtime
test work exactly as before.

**The tool round trip is the part that can break quietly.** A provider will not accept a tool
result on its own: Anthropic rejects a `tool_result` whose `tool_use` was not replayed, and the
Responses API needs the `function_call` item alongside its output. The runtime hands back a
`ToolResultsMsg` with no assistant turn attached, so `GatewayAgentProvider` remembers the turns
itself - they cannot be rebuilt from the transcript, where the call ids are no longer attached.

**It keeps every round, not just the last**, and that distinction only shows up from the third
step. With one slot, a three-step run showed the model nothing of what its first round's tools
returned, so it re-called them or answered without the evidence and burned the step budget; and
the resulting transcript had adjacent assistant turns, which Anthropic and Google reject
outright. The default budget is `maxSteps = 8`, so three steps is ordinary, and a two-step test
sees neither problem - which is how it got past review. `GatewayAgentProviderTest` now runs to a
third step and is mutation-verified: `rounds.takeLast(1)` fails *round one's observation is still
visible on step three*.

A `ToolResultsMsg` is also deliberately **not** sent as transcript text. Sending it twice would
show the model the same observation as both data and a fresh instruction, which is the
prompt-injection shape the runtime's separate message type exists to avoid. A test asserts the
outcome text appears in `toolOutcomes` and nowhere in `messages`.

Model selection belongs to Settings → AI Providers because `AiGatewayAPI` exposes the active model,
not a per-request override. The Parameters tab therefore renders a non-editable explanation rather
than the old editable `claude-sonnet-5` value that never affected execution. The legacy `model` key
remains untouched in raw saved JSON for compatibility but is not parsed or sent. Optional Agent
temperature is a real request parameter: blank defers to whatever the active provider sends (AI
Gateway v1.1.2+ omits it when the provider setting is also absent), while an explicit value from 0 to
2 is forwarded. The node timeout is capped at 12 minutes so its 5% hard-stop grace remains
comfortably below the 15-minute flow controller watchdog, then forwarded to relax the gateway's
shorter per-turn default; `AgentRuntime` still enforces the decreasing whole-run time remaining and
fires first.

`maxTokens` is still overridden per request (4096, not the provider's chat-completion default of
2000) because a bounded tool-use loop needs the headroom. A run is bounded by `AgentBudget`.

`timeoutMs` is a hard node deadline, not merely a check between agent steps. `AgentRuntime` owns
its loop on a 64-call, concurrency-limited elastic IO view so a non-cooperative provider or tool
boundary cannot prevent the caller from publishing TIMEOUT or consume every unrelated host IO
permit. The budget clock starts only after the loop is admitted to that lane. If all slots remain
occupied, a short bounded admission wait fails explicitly as Agent capacity exhaustion instead of
pretending a provider timed out without running. The loop gets the configured cooperative deadline;
a watchdog grace (at least 500ms, or 5% for longer runs) lets it publish complete counters normally
before the hard caller deadline abandons a non-cooperative call. The scope is cancelled best-effort,
no new host work starts after the deadline, and progress counters are snapshotted for the timeout
diagnostic. A call that ignores cancellation may still finish against run-scoped state while cleanup
is running; the lane bound contains that residue but cannot interrupt arbitrary host code. A
serialized log gate closes before return so late completion cannot mutate the already-published node
state. An agent TIMEOUT is converted to `ExecError` by `AgentNodeExecutor`;
it must be a red node failure rather than a successful output carrying `stopReason: TIMEOUT`.
`MAX_STEPS` and `TOKEN_BUDGET` remain successful bounded results because they may carry a usable
partial answer. User-configured step, timeout, and token budgets must be positive whole numbers;
zero does not mean unlimited.

Agent diagnostics are incremental and sanitized. The runtime logs each model step before the
provider call, each tool's name plus started/succeeded/failed status, and a terminal stop line.
Provider failures therefore retain the number of completed steps and attempted tool calls even
when the loop throws. Raw prompts, model text, tool arguments, and tool-result content are never
written to these progress logs; model-controlled tool names are single-line, length-bounded tokens,
and a timeout reports only the withheld partial text's character count. The node error keeps the
provider's existing error message with the same `FAILED` counters, so provider-controlled boundary
details remain visible there even though they are not copied into progress logs. Admission failure
remains its own plain capacity error because no Agent run started. Runtime-owned terminal logging is
intentional—the executor cannot reliably log after a thrown provider boundary.

The Agent tool allowlist is static per node: it does not resolve `{{ }}` expressions. It accepts a
JSON array entered in the inspector or raw flow JSON, legacy comma/newline-separated names, and
stable scoped kind-ids such as `tool:boss:docker_ps`. Every non-empty entry must resolve against the
tools available at run start. If any entry is misspelled, not registered yet, or temporarily absent
because its external source is unavailable, the node fails before its first model request rather
than letting the model answer without expected evidence. The startup log lists the resolved tool
count and bounded names. An omitted allowlist, a blank value, or `[]` intentionally advertises no
ordinary tools and remains valid for tool-free agents; structured output may still add its reserved
submission tool. Explicitly listing that synthetic tool by name or `tool:flow:flow_submit_output` is
accepted as a backward-compatible no-op; a real allowlisted tool that shadows the reserved name is
still a configuration conflict.

Agent structured output is optional and backward-compatible. A non-blank `outputSchema` must have
root `"type": "object"`; Flow advertises a reserved `flow_submit_output` tool whose input schema
is that exact contract and appends a system instruction requiring the model to call it alone for
the final answer. Provider-side argument checking is not the trust boundary: Flow parses and
validates the submitted object locally before it becomes an item. An invalid submission is returned
as a tool error so the model can correct it within the existing step/token/time budgets. Structured
mode permits three non-empty invalid attempts total—the initial attempt plus two corrections—and
fails immediately on an empty model turn so provider transcripts cannot acquire adjacent user
messages. If a valid object is never submitted, the node fails closed and withholds model prose. A
valid structured object becomes the item itself, with no
`text`, `stopReason`, or counter fields added; agents without `outputSchema` retain the historical
free-text item shape exactly. Structured-output logs record only accepted/rejected/missing status,
never submitted values.

The agent's browser tool lane is bound to the run's `defaultSessionId`. In that lane,
`session_id` is optional and omission means the same browser session native Open/Navigate/Click/
Type/Extract nodes use. An explicit id still wins for multi-session agents. `browser_open` without
an id opens the reserved default session when needed and reuses it when an upstream node already
opened it, so it does not replace the page the flow established; explicitly naming that default id
also reuses it for compatibility with old prompts. Named secondary sessions reuse too; an agent can
close and reopen one when it needs a fresh page. Secondary sessions are always headless because the
interactive UI owns only one visible-tab lifecycle slot; allowing another visible session would lose
track of a Fluck tab. `browser_close` treats the run-owned default as a successful no-op even when
explicitly named, preventing cleanup retry loops while the run retains ownership of the shared page.
An agent may close additional sessions it opened under other ids. Close results use a boolean
`closed` plus `session_id`; `closed: false` means the flow retained its run-owned session.
A default-constructed `FlowBrowserToolSource` keeps the explicit-session contract and schemas.

This binding is intentionally shared state. If the agent opens the default session, downstream
native nodes inherit that page and its visibility. A later or parallel native Open Browser can
replace the page, and the per-session fence serializes individual actions but does not make an
agent branch atomic with respect to a parallel native branch; their actions may interleave.

`plugin.json` declares `ai.rever.boss.plugin.dynamic.aigateway` as an **optional** dependency.
Declaring it makes the host's one existing check work - `DynamicPluginManager.checkCanUnload`
refuses to uninstall a plugin a loaded plugin depends on. Nothing reads `dependencies` at *install*
time (no resolver, no prompt), so installing Flow without the gateway just means agent nodes fail
with `NO_GATEWAY_MESSAGE` while every other node type works - which is exactly why the dependency
is optional rather than hard.

The provider is built per run, not once at spec construction, because neither the gateway nor the
active provider exposes a change signal - a provider changed in Settings is picked up by the next
run instead of needing the tab reopened. A per-run instance also keeps each run's replayed tool
turn to itself.

### The api jar must never be pinned by filename

`build.gradle.kts` resolves the **newest** `boss-plugin-api-*.jar` in the sibling checkout, for
both `compileOnly` and `testImplementation`. It used to name a specific version that no longer
existed - and `compileOnly(files(…))` on a missing path contributes nothing *silently*, so every
api symbol came back "unresolved reference" with no hint the filename was stale.

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew deployPlugin       # Build + copy JAR to ~/.boss/plugins/
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing (`./gradlew deployPlugin`).

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.flowtab)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Source files
- `FlowTabDynamicPlugin.kt` - entry point, registers the tab type.
- `FlowTabType.kt` / `FlowTabData.kt` - tab type + tab config.
- `FlowLauncherPanel.kt` - sidebar browser for creating, listing, and reopening persisted flows.
- `FlowModel.kt` - serializable graph model (NodeType, NodeModel, EdgeModel, GraphSnapshot) + port geometry.
- `FlowGraphState.kt` - runtime state: nodes/edges, pan/zoom transform, selection, pending connection.
- `FlowCanvas.kt` - grid + edge rendering (Canvas) and pan/zoom/tap gestures.
- `FlowNodeView.kt` - node card + ports, drag-to-move, drag-port-to-connect.
- `FlowTabComponent.kt` - TabComponentWithUI: toolbar, palette, persistence wiring.

### Performance notes
- Grid and all edges are drawn in single `Canvas` draw passes (cheap, GPU-backed).
- Nodes are positioned with `Modifier.offset { }` (lambda) + per-node `graphicsLayer` scale,
  so moving/zooming relayouts rather than recomposing the whole tree.
- Pan/zoom use a manual world↔screen transform; pointer deltas inside scaled nodes arrive
  in world units automatically (graphicsLayer-aware hit testing).

### Key Patterns
- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`.
- Tab plugin: registers a `TabTypeInfo` via `context.tabRegistry.registerTabType(...)`.
- UI: `TabComponentWithUI` with `@Composable Content()`.
- Persistence: graph JSON saved per-tab via `context.pluginStorageFactory`.
- Null-safe provider access: providers may be null, UI must handle gracefully.

Headless registry synchronization is owned by `FlowController`, not `PluginContext.pluginScope`.
Every `buildHeadlessController` caller must call `dispose()`; its optional scope controls runs only.
Visible tabs deliberately synchronize tools on their tab scope instead because their lifetime ends
with `FlowTabComponent`.

### Plugin storage JSON keys

The current desktop `PluginStorageProvider` stores `putJson("key", value)` under the physical
key `json:key`. `getJson` adds that prefix, but `getAllKeys`, `contains`, and `remove` operate on
raw backing keys. Consequently, normalize the optional `json:` prefix when enumerating and use
`removeJsonValue` when deleting JSON so both desktop-shaped and logical providers are supported.
Tests that exercise this behavior should use `DesktopStorage`; `TestStorage` intentionally models
a provider that exposes logical keys.

`flow_list` keeps its legacy `{ "flows": [tabId, ...] }` result by default. Passing
`{ "detail": true }` additionally returns `flowDetails` entries with metadata, node count, and
readability. `flow_delete` permanently removes a graph and its UI run-state snapshot, closing a
matching open tab first. The launcher uses the same controller and storage namespace as these MCP
contracts, lets users rename readable flows, and asks for confirmation before deletion. An open
flow shows its current name in the canvas toolbar with an edit action; that path persists the live
snapshot immediately, so even a brand-new tab can be named before its debounced autosave runs.
MCP authoring is mutable: `flow_rename` changes flow metadata, `flow_update_node` patches a title
and/or merges config keys, and `flow_delete_node` removes the node plus incident edges while
`flow_delete_edge` removes one connection. These operations use the same coordinator as open-tab
autosave, so an agent can repair a graph without rebuilding it or having a live canvas overwrite
the repair. `flow_stop` cancels an in-memory `flow_run`; for compatibility its terminal state is
`FAILED` with an explicit `Flow run stopped by caller` error, and repeated stops are idempotent.
A lanager sub-run's lifetime is bound to its node: Canvas Stop, `flow_stop`, or a parent watchdog
timeout explicitly stops the sub-run, and nested lanagers cascade that stop through their own
children. The stop publishes `FAILED` and requests cancellation without joining, so child execution
and session cleanup may briefly overlap a subsequent run; the stop's storage persistence has no
separate deadline. If stop races with terminal publication, stop may win and leave a `FAILED` child
record whose already-published node snapshot contains only successful nodes.
Browser-session cleanup is ownership-aware. Interactive canvas runs leave their last visible tab
open for inspection and close it when the UI admits the next run. Controller/MCP `flow_run` calls
have no UI owner, so `FlowExecutor` marks their `SessionRegistry` as owning visible tabs and closes
every visible session during terminal/cancellation cleanup. Keep that distinction explicit when
adding another headless run entrypoint; otherwise each invocation leaks a Fluck tab.
`flow_result` returns status, errors, and bounded logs by default, with node outputs omitted so
large HTML/SVG values cannot exhaust the MCP response path. A caller may pass `nodeId` together
with `includeOutput: true` to fetch that node's recursively bounded output; explicit response flags
report whether output was omitted or included and whether any content was truncated. Oversized log
lists retain both their beginning and tail around an omission marker so a terminal Agent stop or
failure line is not discarded.
The launcher/controller and an open tab are independent full-snapshot writers for the same graph
key. Controller/MCP mutations and open-tab autosave therefore serialize through
`FlowPersistenceCoordinator`. Controller writes publish revisioned snapshots that an open canvas
loads before acknowledging the revision; autosaves captured against an older revision are skipped.
In-canvas rename additionally uses a temporary name guard that clears after convergence.

### External MCP lifecycle

External MCP is plugin-wide, OFF by default, and configured from the Flow toolbar. The
`ExternalMcpManager` owns one supervised IO request actor for config writes, connection lifecycle,
and tool discovery; accepted mutations must survive dialog/tab composition cancellation. Each
settled change performs one discovery pass and publishes cached descriptor and per-server status
`StateFlow`s. Every UI/headless registry may collect the descriptor snapshot and apply it through
its own `ToolNodeSync`, but collectors must never call transport `listTools` or cancel shared MCP
requests. Each server connect, discovery, and close has an independent cooperative 15-second deadline;
timeouts must attempt bounded reaping and let queued mutations continue. Reconciliation is deliberately
serial for deterministic ordering, so its worst-case bound is the sum of per-server deadlines; the
dialog stays dismissible and Remove/refresh actions remain queueable behind the pass. A startup pass
with any server error is not latched as initialized, and an uncached headless `list()` submits the same
idempotent retry no more than once per 30-second cooldown; concurrent implicit callers coalesce and
await it for at most one second before serving the last descriptor snapshot. The manager-owned retry
continues after that caller latency bound. Explicit Refresh always bypasses the cooldown floor.
Changing a connected server config or resolved secret closes and reopens its transport. Server names
are one routing segment: `/` and control characters are invalid.
Server configs persist only secret references; resolved values stay in the host vault /
transport boundary and must be redacted from bounded, control-free, single-line UI and log diagnostics. Disposal
stops accepting requests, drains accepted work, concurrently attempts every live close under the
two-second NonCancellable cleanup bound, and publishes the terminal empty snapshot before terminating
the manager actor; a terminal actor failure or forced `cancelNow` must also stop acceptance, fail every
queued request, and boundedly reap already-open transports instead of leaving work or child processes
without an owner. Plugin disposal joins that forced cleanup and the actor finalizer within a bounded
unload budget. Fatal actor failure is logged without provider payloads and rejects later requests as crashed with plugin
reload guidance, distinct from normal disposal.

### Runtime secret templates

HTTP node URL, headers, and body fields plus Type text and Inject scripts accept
`{{ $secret.name }}` references. `name` is matched against the website or username in the host
secret manager, using the same resolver as external MCP configuration. Flow stores only the
reference in graph JSON; the password is substituted at execution time and is never written back
or included in Flow's own node logs and secret-lookup errors. Type passes the resolved value
through `BrowserScripts` escaping before execution. Inject secret references must be placed inside
a single-quoted JavaScript string, as shown by its inspector placeholder; the resolver escapes the
secret as single-quoted literal content before executing the raw script.

### Browser script literals

Browser selectors, typed values, and extraction attribute names are embedded in generated
single-quoted JavaScript literals. `BrowserScripts` escapes quotes, backslashes, every JavaScript
line terminator, and ASCII control characters before interpolation; keep all new browser-script
string inputs on that shared escaping path.

### Optional Extract fallback

An Extract node normally fails when its selector matches no element. Enabling `optional` changes
only that absence case: the node succeeds with one item whose configured output field is JSON
`null`, including an empty `multiple` result. This preserves a data item so a following If node can
route to a fallback branch. Selector syntax errors, browser failures, and other script errors still
fail the node; optional extraction must not hide operational errors. Optional Extract still uses
its configured element wait before declaring a single match absent, so it does not turn slow-page
loading into a false fallback. In attribute mode, a present element without the requested attribute
also yields `null`; consumers that need a fallback for either case should guard the value with If.

### Template expression resolution

Flow templates are deterministic JSON paths, not JavaScript. They support `$json` and
`$node["Title"].json` roots with `.key`, `.0`, `["key"]`, and `[index]` segments. Numeric dotted
segments index arrays, so paths such as `$json.slides.0.title` are valid. A missing property,
missing node output, malformed segment, or JavaScript-like property access throws a
`TemplateResolutionException` naming the full expression; consuming nodes must preserve that
message instead of converting it to empty text or masking it as malformed config. An explicitly
present JSON null is resolved successfully and keeps its normal null/empty rendering semantics.

### Canvas node cards

Nodes render a one-based creation-order badge, their registry type, custom title, and a concise
action sentence. Summaries should describe intent and useful targets, never raw typed values,
credentials, request bodies, or assignment payloads. Metadata chips may identify a value as
fixed, dynamic, or secret without rendering the value itself. Card dimensions remain centralized
in `FlowModel.kt` so rendering, edges, hit-testing, imports, and fit-to-content stay aligned.

### Canvas layout

`FlowLayout.kt` owns deterministic graph placement. Tidy layout assigns longest-path dependency
rank to columns, orders each column from its parents' vertical barycenter and output-port order,
and spaces variable-height cards without overlap. Cyclic residuals remain drawable in one final
column even though execution will reject the cycle. Tidy is always explicit—controller/MCP edits
must not discard hand-tuned positions automatically—and the canvas retains one pre-tidy position
snapshot for Undo. Any later drag or topology edit retires that snapshot so Undo cannot discard
newer manual work. Controller-created nodes use the same geometry and widened standard-slot search
to choose the first collision-free authoring position, so deleting or moving a node cannot make its
replacement stack on a survivor or jump prematurely to the far-right fallback.

### Browser waits and human sign-in

Click, Type, and Extract expose `waitMs` while preserving the historical 20-second default for
saved flows that omit it. Await Login is the explicit human-in-the-loop gate: it gives an
already-authenticated page a one-second grace period to render its marker before prompting, then
focuses the visible run browser and shows an indefinite host notification only when user action is
actually needed. Its prompted phase polls once per second to avoid expensive DOM scans while a
human signs in. The notification is dismissed on success, timeout, or cancellation. Await Login
defaults to five minutes, all element waits are capped at 14 minutes (below the controller's
15-minute watchdog), and it passes input items through unchanged once the marker appears.

### Inject execution contract

Inject optionally waits for `waitFor` using `waitForType` and `waitMs` before executing its raw
JavaScript. A missing wait target fails Inject directly. An exact JavaScript boolean `false` also
fails the node; `null`/undefined and all other return values remain successful for backward
compatibility. Recorder-imported Select actions seed the known selector as their wait target and
return an explicit boolean from their generated script.

## Version Management

**`build.gradle.kts` is the single source of truth for version.**
The `processResources` task syncs the version into `plugin.json` at build time.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific).
- All Kotlin files must end with a newline.
- Handle null providers gracefully - show fallback UI, never crash.

## CI/CD

Pushes to `main` trigger the release workflow which builds the JAR, creates a GitHub release,
and publishes to the BOSS Plugin Store. Defined in `.github/workflows/build.yml`.
