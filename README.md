# BOSS Flow Tab

A node-based flow canvas tab for the BOSS desktop application - spawn nodes and wire them
together with edges, n8n style.

## Features

- **Canvas** with smooth pan (drag empty space) and zoom (scroll wheel, toward cursor).
- **Nodes** you can spawn from the toolbar palette (Trigger, HTTP, Code, If, Set, Merge).
- **Code node** applies a typed JSON output template per item; it does not execute
  JavaScript because the host does not currently provide a plugin-safe JS runtime. A
  value containing only `{{ expression }}` preserves its JSON type; add surrounding
  text when a string result is required.
- **Agent structured output** optionally takes an object JSON Schema. In that mode the
  model submits its result through a schema-shaped tool, Flow validates it locally, and
  downstream nodes receive the parsed object instead of model prose. Invalid submissions
  are returned to the model for correction and never enter the item stream.
- **Empty output stops a branch**: downstream nodes are marked Skipped rather than
  being seeded with a synthetic item and executed.
- **If conditions** support whitespace-delimited `==`, `!=`, `>`, `>=`, `<`, and `<=`.
  Ordering is numeric for two numbers and lexical for two text values (including ISO
  dates); an empty operand makes any ordering comparison false. Mixed numeric/text
  operands error the If node, discard that input batch, stop both outputs, and fail the
  run; normalize them upstream or use equality instead. A condition that contains only
  an expression is tested for truthiness and is never parsed as a comparison.
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

The JAR is generated at `build/libs/boss-plugin-flow-tab-<version>.jar`.

## Installation (local development)

```bash
./gradlew deployPlugin
```

This copies the JAR to `~/.boss/plugins/`. Restart BossConsole (or reload plugins) and open a
new **Flow** tab.

## License

Proprietary - Risa Labs Inc.
