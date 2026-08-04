# CLAUDE.md

## Project Overview

**Flow** (`ai.rever.boss.plugin.dynamic.flowtab`) is a dynamic plugin for the BOSS desktop application.

A node-based flow canvas - spawn nodes and connect them with edges, n8n style. Lightweight,
self-contained Compose UI with a pan/zoom canvas, draggable nodes, and bezier edges.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.flowtab`
- **Main Class**: `ai.rever.boss.plugin.dynamic.flowtab.FlowTabDynamicPlugin`
- **API Version**: 1.0.56 · **minApiVersion**: 1.0.71 · **minBossVersion**: 9.2.63
  (the MCP tool framework needs 9.2.20; `PluginContext.llmProvider` needs 9.2.63)

## Agent credentials

The `agent` node's Anthropic key and endpoint come from the shared AI provider config the
**secret-manager** plugin owns (Settings → AI Providers), read via `PluginContext.llmProvider`.
`anthropicConfigFrom` searches in this order, and the order is the point:

1. the **active** provider, if it speaks `ANTHROPIC_MESSAGES`;
2. any other **configured** provider that does - the node only speaks Anthropic's tool-use
   format, so an active OpenAI provider is no use to it, and using its key anyway would send an
   `sk-…` key to `api.anthropic.com`;
3. `SecretResolver.fromSecrets` - the old direct secret-store lookup, kept as a last resort for
   a user who stored an `ANTHROPIC_API_KEY` secret by hand and never opened the provider panel.

Two deliberate non-adoptions: **the model stays the node's** `Model` config field (taking
`LlmConfig.modelId` would silently change which model existing flows run on, decided in a panel
the flow author may never have opened), and `LlmConfig.maxTokens` is ignored because it is a
chat-completion default (2000) while a bounded tool-use loop wants `AnthropicProvider`'s 4096 -
a run is bounded by `AgentBudget`, not by that.

An empty `configuredProviders()` means **unknown**, not "nothing configured": the api declares a
default body returning an empty list, so an implementation that never overrides it is
indistinguishable from an unconfigured one. That is why step 3 exists.

The provider is resolved per run (inside `providerFor`), not once at spec construction, because
`LlmProvider` exposes no change signal - a key changed in Settings is picked up by the next run
instead of needing the tab reopened.

`AgentCredentialResolutionTest` covers the order and asserts on the actual wire (key + endpoint
from the config, model from the node) against a local server. Mutation-verified: dropping the
`ANTHROPIC_MESSAGES` check fails two cases by name.

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
