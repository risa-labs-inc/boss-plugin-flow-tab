# BOSS Flow Tab

A node-based flow canvas tab for the BOSS desktop application — spawn nodes and wire them
together with edges, n8n style.

## Features

- **Canvas** with smooth pan (drag empty space) and zoom (scroll wheel, toward cursor).
- **Nodes** you can spawn from the toolbar palette (Trigger, HTTP, Code, If, Set, Merge).
- **Edges**: drag from an output port to an input port to connect nodes; bezier curves.
- **Move / select / delete**: drag nodes to reposition, click to select, `Delete` to remove
  the selected node (and its edges) or a selected edge.
- **Persistence**: each tab's graph is saved automatically and restored when reopened.
- **Lightweight & performant**: pure Compose, single-pass canvas rendering for grid + edges,
  relayout-not-recompose node movement.

## Building

```bash
./gradlew buildPluginJar
```

The JAR is generated at `build/libs/boss-plugin-flow-tab-1.0.0.jar`.

## Installation (local development)

```bash
./gradlew deployPlugin
```

This copies the JAR to `~/.boss/plugins/`. Restart BossConsole (or reload plugins) and open a
new **Flow** tab.

## License

Proprietary - Risa Labs Inc.
