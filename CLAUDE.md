# CLAUDE.md

## Project Overview

**Flow** (`ai.rever.boss.plugin.dynamic.flowtab`) is a dynamic plugin for the BOSS desktop application.

A node-based flow canvas - spawn nodes and connect them with edges, n8n style. Lightweight,
self-contained Compose UI with a pan/zoom canvas, draggable nodes, and bezier edges.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.flowtab`
- **Main Class**: `ai.rever.boss.plugin.dynamic.flowtab.FlowTabDynamicPlugin`
- **API Version**: 1.0.56 · **minApiVersion**: 1.0.74 · **minBossVersion**: 9.2.63
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
