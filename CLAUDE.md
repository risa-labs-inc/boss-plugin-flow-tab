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

**The model stays the node's** `Model` config field, but it is now advisory: the gateway uses
whatever model the active provider has selected. Taking a node config the flow author may never
have opened and using it to override a user's chosen model would be the worse behaviour, and the
field is kept because saved flows carry a value for it. `AgentNode.DEFAULT_MODEL` holds the
default that used to live on `AnthropicProvider`.

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

The agent's browser tool lane is bound to the run's `defaultSessionId`. In that lane,
`session_id` is optional and omission means the same browser session native Open/Navigate/Click/
Type/Extract nodes use. An explicit id still wins for multi-session agents. `browser_open` without
an id opens the reserved default session when needed and reuses it when an upstream node already
opened it, so it does not replace the page the flow established; explicitly naming that default id
also reuses it for compatibility with old prompts. `browser_close` rejects the run-owned default
session even when explicitly named; the run owns that shared page's lifecycle, while an agent may
still close additional sessions it opened under other ids. A default-
constructed `FlowBrowserToolSource` keeps the explicit-session contract and schemas.

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
Browser-session cleanup is ownership-aware. Interactive canvas runs leave their last visible tab
open for inspection and close it when the UI admits the next run. Controller/MCP `flow_run` calls
have no UI owner, so `FlowExecutor` marks their `SessionRegistry` as owning visible tabs and closes
every visible session during terminal/cancellation cleanup. Keep that distinction explicit when
adding another headless run entrypoint; otherwise each invocation leaks a Fluck tab.
`flow_result` returns status, errors, and bounded logs by default, with node outputs omitted so
large HTML/SVG values cannot exhaust the MCP response path. A caller may pass `nodeId` together
with `includeOutput: true` to fetch that node's recursively bounded output; explicit response flags
report whether output was omitted or included and whether any content was truncated.
The launcher/controller and an open tab are independent full-snapshot writers for the same graph
key. Controller/MCP mutations and open-tab autosave therefore serialize through
`FlowPersistenceCoordinator`. Controller writes publish revisioned snapshots that an open canvas
loads before acknowledging the revision; autosaves captured against an older revision are skipped.
In-canvas rename additionally uses a temporary name guard that clears after convergence.

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
