# CLAUDE.md

## Project Overview

**Flow** (`ai.rever.boss.plugin.dynamic.flowtab`) is a dynamic plugin for the BOSS desktop application.

A node-based flow canvas — spawn nodes and connect them with edges, n8n style. Lightweight,
self-contained Compose UI with a pan/zoom canvas, draggable nodes, and bezier edges.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.flowtab`
- **Main Class**: `ai.rever.boss.plugin.dynamic.flowtab.FlowTabDynamicPlugin`
- **API Version**: 1.0.56 (minBossVersion 9.2.20 — needs the host MCP tool framework)

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
- `FlowTabDynamicPlugin.kt` — entry point, registers the tab type.
- `FlowTabType.kt` / `FlowTabData.kt` — tab type + tab config.
- `FlowModel.kt` — serializable graph model (NodeType, NodeModel, EdgeModel, GraphSnapshot) + port geometry.
- `FlowGraphState.kt` — runtime state: nodes/edges, pan/zoom transform, selection, pending connection.
- `FlowCanvas.kt` — grid + edge rendering (Canvas) and pan/zoom/tap gestures.
- `FlowNodeView.kt` — node card + ports, drag-to-move, drag-port-to-connect.
- `FlowTabComponent.kt` — TabComponentWithUI: toolbar, palette, persistence wiring.

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
- Handle null providers gracefully — show fallback UI, never crash.

## CI/CD

Pushes to `main` trigger the release workflow which builds the JAR, creates a GitHub release,
and publishes to the BOSS Plugin Store. Defined in `.github/workflows/build.yml`.
