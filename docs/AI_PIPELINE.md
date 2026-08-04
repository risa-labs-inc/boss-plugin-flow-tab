# Flow Tab → Executable AI Pipeline - Design

**Status:** DRAFT (direction approved via office-hours design session)
**Scope:** Turn the `flow-tab` canvas from a visual graph editor into an executable AI / automation pipeline runtime.
**Mode:** Builder / internal platform tooling.

## Vision

Any node is an operation: a browser action (navigate / click / type / extract / inject), an HTTP/API call (with wait), an AI/LLM step, a function, control flow (if / merge / set), or a nested workflow. You draw the graph, hit Run, and watch data flow through it.

## Locked decisions

| Decision | Choice |
|---|---|
| Runtime | In-process coroutines inside the plugin. Topological DAG executor. Each node is a `suspend fun(inputs) -> outputs`. "Wait" is just coroutine suspension. **Not** the host gRPC bridge. |
| Edge data | n8n-style **items**: `Item { json: JsonObject, binary: Map<String, BinaryRef> }`. A port carries `List<Item>`. JSON via `kotlinx.serialization`. |
| Execution backbone | **Session-driven engine**: open an `RpaBrowserSession`, run nodes individually via `executeJavaScript` (browser ops; `extract` returns data). General per-node model that HTTP/AI/Code also use. |
| rpaengine reuse | `RpaRunner` whole-run path is a **later "polished segment" mode** (human-like timing, hardened selectors, retries) - not the foundation. |
| Config | Per-node **inspector panel** (form fields) + **raw-JSON tab** + n8n-style `{{ }}` **expressions** referencing upstream node outputs. |
| Persistence | `NodeModel` gains `config: JsonObject = {}` (backward compatible). Run results in-memory (ephemeral) for v1. |

## The finding that set the phasing

`RpaRunnerImpl.watch()` emits action **status** (`ActionCompleted(success, error, duration)`, terminal `Result`) - **not extracted values**. Extraction actions return `true/false` (found / not found). So the whole-run path can automate and **inject**, but cannot hand back scraped data.

Getting data **out** requires `RpaBrowserSession.executeJavaScript(): Any?` (it returns the value). That makes the session path required for the centerpiece (extraction) and the natural per-node backbone. Hence: session-path leads; rpaengine whole-run reuse follows as polish.

## Architecture

### Data
- `Item { json: JsonObject, binary: Map<String, BinaryRef> = {} }`; `PortData = List<Item>`. Empty list = no data / skip branch.

### Node model
- Extend the node kind set (still serializable by name) with per-kind metadata: category, named input/output ports, config field schema, executor, and a **run mode** (`perItem` vs `once`).
- `NodeModel` += `config: JsonObject` (default `{}`).
- Categories: **Trigger** (Manual/Start, Open Browser) · **Browser** (Navigate, Click, Type, Select, Extract, Inject/RunScript, WaitFor, Screenshot) · **HTTP Request** · **AI** (LLM Prompt) · **Control** (If, Merge, Set/Edit Fields) · **Code** (later) · **SubWorkflow** (later).

### Execution context & resources
- `RunContext`: per-node status `StateFlow`, logs, active browser session(s), expression evaluator, cancellation token.
- **Browser session lifecycle:** an `Open Browser` node calls `rpaBrowserProvider.openSession(spec)`; downstream Browser nodes share it; closed at run end. v1 = one session per run (multi-session later).
- Non-browser nodes (HTTP/AI/Code/Control) need no session.

### Executor
1. Build DAG from edges; topological sort; reject cycles.
2. Per node: gather inputs per input port (concat items from incoming edges), evaluate config expressions against upstream outputs + current item, run executor → outputs per output port, store, update status.
3. **Run mode:** data nodes (HTTP/AI/Set) run **once per input item**; browser-action + control nodes run **once** (operate on the shared session / whole set). Documented per kind; revisit when per-item RPA lands.
4. **Errors:** node error → mark Error, stop that branch; global `stopOnError` toggle.

### Phase-1 node executors (session-driven)
- **Open Browser** → `openSession(initialUrl)`; passes items through.
- **Navigate**(url) → `session.navigate(url)`; passthrough.
- **Click**(selector) → `executeJavaScript(clickScript(sel))`; error if not found.
- **Type**(selector, text) → `executeJavaScript(inputScript(sel, text))`.
- **Extract**(selector, mode = text/attr/html, multiple?) → `executeJavaScript(extractScript)` → wrap value(s) into items. **Returns data.**
- **Inject / RunScript**(script) → `executeJavaScript(script)`; optionally capture return as an item.

(JS for click/type/extract mirrors rpaengine's existing `clickScript` / `inputScript` / `elementExpr` - reuse those exact scripts.)

### Phase-2 node executors
- **HTTP Request**(method, url, headers, body) → JVM `HttpClient` → item `{ status, headers, body }`. perItem.
- **AI / LLM**(model, prompt, key from `secretDataProvider`) → HTTP to the LLM (Anthropic default) → item `{ text, raw }`. perItem.
- **If / Merge / Set** → control flow over items.

### Expressions
- `{{ expr }}` inside string config fields. Context: `$json` (current item), `$node["Name"].json`, `$items("Name")`, `$now`.
- v1 evaluator: property access + simple ops. Full JS deferred (would mean embedding GraalJS - heavy; weigh against plugin size).
- **Raw-JSON config tab** per node (the escape hatch): edit config as raw JSON.

### Run state & canvas visualization
- Per-node status: Idle / Running / Success / Error + last-output preview + logs + timing.
- Canvas: node badge/border reflects status; spinner while running; ✓/✕ on completion. Edge flow animation later.
- **Inspector panel** (right side): tabs - Parameters (form) · JSON (raw config) · Output (last-run items) · Logs.
- Toolbar: Run · Stop · run-status strip.

### rpaengine reuse (Phase 3)
- "Run as polished segment": select a contiguous Browser-action chain → compile to `RpaRunSpec` → `RpaRunnerRegistry.runner.startRun` → `watch()` → `ActionCompleted(index)` lights up node N. Gains human-like timing, hardened selectors, retries, profiles/auth.
- Value-returning extraction in run mode would need a small per-action `execute()` added to rpaengine's public API (a cross-plugin addition, like the `openTab` change).

## Incremental roadmap

- **Phase 1 - Wedge: session-driven executor + Browser nodes + scaffold.** DAG executor, items, `RunContext`, session lifecycle; Open Browser / Navigate / Click / Type / Extract / Inject; inspector (Params + JSON + Output + Logs); Run/Stop + per-node status. Proves end-to-end: open a page, click, type, **extract data, see it**.
- **Phase 2 - Data + AI + expressions.** HTTP Request, AI/LLM, Set/If/Merge, the `{{ }}` evaluator. Now it's a real AI pipeline.
- **Phase 3 - rpaengine polish mode + profiles/auth.** Compile-segment-to-run with live per-action status; cookies/headers/profiles via `RpaAuthSpec`; headless toggle.
- **Phase 4 - Nested workflows + Code node + per-item RPA + run history.** SubWorkflow node, Code/Function (embedded JS), per-item browser iteration, persisted run history.

## Risks / open questions
- Item-iteration semantics for browser nodes (perItem vs once) - start `once`, revisit at per-item RPA.
- Expression-engine scope creep - hold to a v1 subset; embed GraalJS only if truly needed (plugin-size cost).
- One session per run in v1; parallel branches / multi-session later.
- `executeJavaScript` returns page-context values - complex/binary extraction needs JSON serialization; large payloads cost.
- AI node secrets via `secretDataProvider` (present in `PluginContext`).

## Distribution
Same as today: `./gradlew deployPlugin` → `~/.boss_debug/plugins` + `~/.boss/plugins`, registered in `installed.json`. Phase 3's optional rpaengine per-action API is a cross-plugin API addition (rpaengine + boss-plugin-api), mirroring the `openTab` change.

## Next step
Build Phase 1 per the locked decisions below.

---

# Eng Review - Locked Decisions & Deltas

`/plan-eng-review` on 2026-06-02. These supersede any conflicting statement above.

## Architecture decisions
1. **Run semantics:** per-kind run mode. Data nodes (HTTP/AI/Set/Extract output) run once-per-item; browser side-effect nodes (Navigate/Click/Type/Inject) run **once** on the shared session; Extract runs once and emits one item per matched element. Per-item browser loops = Phase 4.
2. **Node modeling:** a **registry of `NodeKind` descriptors** `{ id, label, ports, configSchema, runMode, executor }`, keyed by the existing serialized `NodeType` name (persistence stays backward compatible; `config: JsonObject` defaults to `{}`). Mirrors host `tabRegistry`/`panelRegistry`.
3. **Concurrency:** branches run in **parallel**, EXCEPT session-touching nodes run on a **single serialized lane in topological order** (per-session fence = one mutex; the browser lane is a FIFO drained in topo order; DAG guarantees a browser node can't be ready before a browser node it depends on → no deadlock). "Session-touching" = any node whose executor receives the session handle.
4. **Browser execution:** flow-tab opens/owns the session via the host `rpaBrowserProvider` and runs actions via `executeJavaScript` **itself** (NOT routed through rpaengine - rpaengine isn't needed at runtime). *Reverses the earlier "use rpaengine" / `RpaActionExecutor` idea.* Reuse rpaengine's exact logic by **promoting its pure JS-builder snippets** (`clickScript` / `inputScript` / `elementExpr` / `existsExpr`) into `boss-plugin-api` as shared, tested functions; rpaengine later refactors onto the same copy to finish the dedupe. **2 repos** (boss-plugin-api + flow-tab), no cross-plugin runtime coupling / version-skew / stale-registry risk.
5. **Expressions:** **custom mini-evaluator in the plugin** (no GraalJS). `{{ }}` fields support `$json` / `$node["X"].json` path access, array indexing, string interpolation, and a small builtin fn set (`upper`, `length`, `default`, …). Lives entirely in flow-tab, no dependency, passes the binary-compat validator, jar stays thin.
   - **Superseded:** GraalJS (full JS) was chosen, then **killed by the spike** - see "GraalJS spike result" below. Full-JS parity is only possible if the BossConsole *host* ships GraalJS (shared classpath) or exposes an eval API; deferred unless that host change is taken on.
   - Evaluator is pure Kotlin → fully unit-testable (path/index/missing-field/malformed-expression cases).

### GraalJS spike result (2026-06-02) - why not GraalJS
Fat-jarring GraalJS into the plugin **breaks loading**. `DynamicPluginLoader.kt:131` runs `BinaryCompatibilityValidator.validate()` before load: it parses the constant pool of **every** `.class` in the jar and hard-resolves all symbolic references, soft-failing only `ai.rever.boss.plugin.runtime.*` (`BinaryCompatibilityValidator.kt:192`). GraalJS adds ~8,400 classes referencing `jdk.internal.*` / `jdk.vm.ci.*` / optional graal modules that don't resolve under the plugin classloader → `isCompatible=false` → the plugin is **silently rejected** (no tab type, no panel, no crash). Confirmed: bundling it made the Flow panel vanish; reverting restored it. **Rule for this codebase: heavy deps cannot be plugin-bundled - they must be host-provided.**

## Implementation requirements (folded in, not optional)
- **Executor scope:** `RunContext` owns its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)` - NOT the tab's `Dispatchers.Main` scope (`FlowTabComponent.kt:89`). Long JS waits must not run on the UI thread.
- **Extract data path:** Extract's JS returns `JSON.stringify({ ok, value })`; flow-tab parses it. Never trust raw `Any?` shape across the JxBrowser bridge. A single `anyToJson(Any?): JsonElement` normalizer guards the boundary. This also disambiguates "not found" from a falsy extracted value (`false`/`""`/`0`/`null`).
- **Multi-element extract = ONE JS eval:** `querySelectorAll(...).map(...)` returning an array; never a JS round-trip per element.
- **Session spec (v1):** `RpaProfileChoice.Ephemeral`, `auth=null`, `headless=false` (so the run is watchable). `RpaBrowserSpec` has no `initialUrl` - navigate via `session.navigate(url)` after open.
- **Session lifecycle:** opened in `try`, closed in `finally` on success / error / Stop / tab `onDestroy`. No leaked ephemeral profiles.
- **Run seed:** trigger output emits `[{ json: {} }]` (one empty item), not an empty list (empty list = skip branch).
- **Open Browser vs Trigger:** "Open Browser" is its own node that opens the session. The auto-seeded `TRIGGER` (`FlowTabComponent.kt:122`) becomes a plain Manual Trigger (or the seed is dropped). Decide at build; don't ship both with overlapping meaning.
- **Status viz:** per-node status = a small enum state; **logs live in a separate buffer that does not drive canvas recomposition.**
- **JS escaping:** the reused snippets use single-quote-only escaping; note the limitation (no double-quote/newline/unicode robustness). Prefer `JSON.stringify` on the return path.

## Test plan (flow-tab has ZERO test infra today - adding it is part of Phase 1)
Add `kotlin("test")` + JUnit5 + `kotlinx-coroutines-test` + a `src/test/kotlin` set. Engine is pure Kotlin → unit-testable with fakes.
- **Engine (unit):** topo sort (linear/branch/diamond/**cycle→error**); input gather (1 edge / N→merge / none); run-mode (perItem N→N, once N→1); **parallel + fence** (2 IO branches concurrent; 2 browser nodes serialize in topo order); error propagation + stopOnError; **session lifecycle** (open once; close on success/error/cancel); cancellation (Stop); `anyToJson`; extract shape (single→1, multiple→N); run seed.
- **Expressions (unit):** `{{ $json.x }}`, `$node["X"]`, array index, missing path → undefined/null, sandbox denies `java.*`/IO, malformed expression → clean error (not crash).
- **Browser shims (unit, fake session):** Navigate/Click/Type build correct `RpaActionSpec` + pass items through; Extract parses `{ok,value}` → items; failure → node Error.
- **GraalJS-under-PluginClassLoader:** an actual load test in the plugin context (engine discovery is the risk), not just a JVM unit test.
- **Real browser (JxBrowser):** full open→click→type→extract = manual `[→E2E]` in the debug build (not CI).

## NOT in scope (Phase 1)
- Per-item iteration of browser segments (the "for each row, run this browser flow" loop) - Phase 4.
- Multi-session / parallel sessions - one session per run in v1 (the fence therefore serializes browser work; revisit when multi-session lands).
- rpaengine per-action `RpaActionExecutor` API - **dropped**, not deferred (host `rpaBrowserProvider` covers it).
- rpaengine's whole-run "polished segment" mode (human-like timing, hardened selectors, retries, profiles/auth) - Phase 3.
- Persisted run history - ephemeral in-memory results in v1.
- Nested workflows + Code node - Phase 4.

## Failure modes (each new path: failure / test / handling / visibility)
- **Element not found** (Click/Type/Extract): executor returns `ok=false` → node Error, downstream branch stops. Tested. User sees node ✕ + error in inspector.
- **`executeJavaScript` returns unexpected type:** `anyToJson` normalizes or errors cleanly. Tested. Visible as Error, not silent.
- **Browser session fails to open / crashes mid-run:** node Error + `finally` closes session. Tested with fake. Visible.
- **Expression references a node that didn't run / missing field:** JS yields `undefined` → define behavior (empty string vs Error). Tested. Must not silently inject "undefined" into a URL - **flag as the one to get right.**
- **Stop pressed mid-run:** scope cancelled, session closed in `finally`. Tested. **Critical path.**
- **GraalJS engine not found under PluginClassLoader:** load-time failure - caught by the load test, else the whole expressions feature is dead on arrival. **Critical, build/classloader.**

## Parallelization (build workstreams)
| Lane | Module | Depends on |
|------|--------|-----------|
| A | `boss-plugin-api`: promote JS snippets + (optional) extract-protocol helper | - |
| B | flow-tab: Item model, NodeKind registry, `RunContext`, `FlowEngine` (topo+parallel+fence), run-mode | - |
| C | flow-tab: GraalJS build wiring + expression evaluator (sandbox, classloader) | - (build), integrates with B |
| D | flow-tab: browser executors (shims over `rpaBrowserProvider` + Lane A snippets) | A, B |
| E | flow-tab: inspector panel + raw-JSON + Output/Logs; run/stop toolbar + status viz | B |

Launch A, B, C in parallel. D waits on A+B. E waits on B. Engine tests ride with B; expression tests with C.

