# Saddle

> **S**addle: **A** **D**atapack **D**ebugger for **L**ive **E**diting

A Fabric mod that embeds a [Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/) server in Minecraft, so datapack `.mcfunction` files can be debugged from VS Code (or any DAP client) — breakpoints, stepping, time travel, and live inspection *and editing* of the game state your functions run against.

In Minecraft, a saddle is what lets you take the reins of something that otherwise runs on its own. This mod puts a saddle on datapack execution: stop it where you want (breakpoints), back it up (time travel), and steer it (live edits) — instead of watching it gallop by.

- Minecraft **26.2** / Fabric Loader ≥ 0.19.0 / Fabric API
- Java 25 (26.x is unobfuscated; the buildscript uses the no-remap `net.fabricmc.fabric-loom` plugin)
- DAP endpoint: `127.0.0.1:16352` — override with `-Dsaddle.host` / `-Dsaddle.port`. Non-loopback binds are refused unless `-Dsaddle.allowRemote=true` is set: the debug port allows unauthenticated command execution, so only expose it on trusted or tunneled networks

## Quick start

1. Install the mod (plus Fabric API) and start a world or server.
2. Install the VS Code extension **[Saddle — Minecraft Datapack Debugger](https://marketplace.visualstudio.com/items?itemName=d1n0.saddle-debug)** from the Marketplace (or search "Saddle" in the Extensions view). To build it from source instead:

   ```sh
   cd vscode-extension
   npx --yes @vscode/vsce package --allow-missing-repository
   code --install-extension saddle-debug-*.vsix
   ```

3. Open your datapack folder in VS Code, set breakpoints in `.mcfunction` files, and run **Attach to Minecraft (Saddle)** (or press F5). The Debug Console and game chat both announce which world you attached to.
4. Trigger a function in game, from a tick function, or straight from the Debug Console.

See [`vscode-extension/README.md`](vscode-extension/README.md) for the editor-side feature tour.

## How it works

- A mixin into `CommandFunction.fromLines` wraps every parsed command entry with a decorator carrying its `(function id, source line)` origin and records the function source text. Macro (`$...`) lines are tagged at parse time and wrapped when `MacroFunction` instantiates them, so they are debuggable too. When no debug client is attached, the runtime overhead per command is one volatile read.
- On a breakpoint/step/pause hit, the server thread is parked *inside* command execution — the game freezes mid-function with vanilla execution state intact. While parked, the thread serves a task queue, so DAP requests that need game state (variables, evaluate, introspection) still run safely on the server thread.
- Breakpoint paths are mapped to function ids from the `data/<namespace>/function/<path>.mcfunction` segment of the file path, so any workspace layout works without configuration. Breakpoints requested on comments or blank lines shift down to the next executable line.

## DAP support

| Area | Requests |
|---|---|
| Lifecycle | `initialize`, `launch`/`attach`, `configurationDone`, `disconnect` |
| Breakpoints | `setBreakpoints`, `breakpointLocations` — plain commands and macro lines; comment/blank lines shift to the next executable line |
| Execution | `continue`, `next`, `stepIn`, `stepOut`, `pause` |
| State | `threads`, `stackTrace`, `scopes`, `variables`, `setVariable`, `source` |
| Console | `evaluate` — runs any command, responding asynchronously so a command that hits a breakpoint never delays the stack view; while suspended it executes in an isolated `ExecutionContext`. `completions` serves Brigadier suggestions, so the Debug Console autocompletes like the in-game chat |
| Hover | `evaluate(context: hover)` resolves macro arguments (`$(name)`), entity selectors (`@e[...]`) and coordinate triples (`~ ~2 ~`) without executing commands |
| Output | in-game chat is mirrored to the client as `output` events: system broadcasts (`/say`, deaths, joins), player chat, and per-player messages (`/tellraw`, `/msg`) |
| Time travel | `stepBack` / `reverseContinue` navigate a recording of executed commands (ring buffer, `-Dsaddle.ttd.steps`, default 20k). While in the past, the stack, executor, macro arguments and **reconstructed scoreboard/storage state** are shown for that moment — the scopes keep their live names so expanded rows survive moving between present and history, and the Executor scope carries a `(time travel)` marker. Forward stepping replays the recording back to the present; `continue` past the recording resumes live execution. `saddle/trace` returns the recent execution trace |

### Variables ("registers & memory")

Each stack frame exposes live scopes, all reading game state while stopped:

- **Executor** — command source summary (executor, position, rotation, dimension) plus a lazily expanded, editable NBT tree of the executing entity.
- **Macro Arguments** — the `$(...)` values of the current macro frame.
- **Command** — the command about to run, with every entity selector resolved to the entities it currently matches (each expandable into live NBT) and every coordinate triple resolved to the block it points at.
- **Watched** — user-pinned expressions (see below), re-resolved live on every request.
- **Scoreboard** — every objective with its scores; score values are editable via `setVariable`.
- **Storage** — every command storage id as an editable NBT tree; leaf values accept SNBT via `setVariable`. Container previews are size-based (`{400 entries}`), so browsing large storages stays fast; edits push an `invalidated` event so the client re-fetches stale rows.

Hovering over `$(name)`, `@e[...]` or `1 2 3`/`~ ~2 ~` in a `.mcfunction` file while stopped shows the same data inline (via the VS Code extension).

### Watch & pin expressions

The VS Code WATCH panel, the pinned "Watched" scope (`saddle/pin`, `saddle/unpin`, `saddle/pins`) and the **Saddle Watch** view all accept:

- `@e[type=pig]` — matched entities, expandable into live NBT
- `storage <id> [path]` / `entity <uuid> [path]` / `block <x> <y> <z> [path]` — live (editable) NBT at the target
- `score <objective> [holder]` — one score, or the whole objective (editable) when the holder is omitted
- `scoreboard` / `storage` — every objective / every storage id
- `$(name)` — macro argument of the selected frame; bare coordinate triples resolve to the block they point at

### Saddle Watch (real time + editable, no breakpoint required)

The extension adds a **Saddle Watch** view to the Run and Debug sidebar — one watch panel that does what WATCH and Variables do together, without needing a breakpoint:

- **Real time**: pinned expressions refresh on a timer (`saddle.liveWatchRefreshInterval`, default 1 s) through the stateless `saddle/live {expression, path}` request, which reads game state on the server thread whether the game is running or suspended — scoreboards tick up live, entity positions move, storage updates as your functions write it.
- **Editable**: rows backed by scores or NBT show an inline pencil; edits go through `saddle/liveSet {expression, path, name, value}` and apply to the live game immediately.
- **Native styling**: the view renders with VS Code's own debug token theme (monospace rows, `debugTokenExpression.*` colors for names, numbers, strings), so it looks exactly like the Variables/WATCH panels.

To keep a single watch panel, hide the built-in one once: right-click any section header in the Run and Debug sidebar and uncheck **Watch** — VS Code remembers. (Extensions cannot remove or replace built-in views, and the built-in WATCH only re-evaluates when the debugger stops; both are platform limits. Drag Saddle Watch to WATCH's old spot and the layout also sticks.)

### Custom requests

- `minecraft/getScoreboard`, `minecraft/setScore {objective, holder, value}`
- `minecraft/getStorage {id?, path?}`, `minecraft/listEntities {selector}`, `minecraft/getEntity {uuid}` (includes NBT)
- `minecraft/getData` / `minecraft/setData` `{type: storage|entity|block, target, path, value}` — vanilla `/data`-style access; block targets are `"x y z [dimension]"`
- `minecraft/getBlock {pos, dimension?}` — block state plus block-entity NBT

## Testing

```sh
# first time only: accept the EULA and disable the tick watchdog
mkdir -p run
printf 'eula=true\n' > run/eula.txt
printf 'max-tick-time=-1\nonline-mode=false\npause-when-empty-seconds=0\n' > run/server.properties

./gradlew runServer                 # terminal 1
python3 scripts/dap_smoke_test.py   # terminal 2
```

The script installs `scripts/test-datapack` into the world, `/reload`s, and exercises the full debug loop end to end (108 checks): breakpoints, stepping, time travel (step back / reverse continue / historical state reconstruction), macro breakpoints and macro-argument values, comment-line shifting, live variable read/write (scoreboard, storage NBT, entity NBT), selector/coordinate resolution, hover evaluation, console completions, chat output mirroring, entity/block data requests, evaluate and pause. CI runs the same suite against a real dedicated server, and pushing a `v*` tag publishes the jar and vsix as a GitHub Release.

## Notes

- Keep the mod and the VS Code extension on matching versions: the mod sends a `saddle/version` event on attach and the extension warns when the two drift apart (major.minor).
- On dedicated servers set `max-tick-time=-1`: suspending at a breakpoint parks the server thread, which would otherwise trip the watchdog.
- In singleplayer, the internal client may disconnect if the game stays suspended for a long time.
- Stepping past the end of all queued commands leaves the session in the running state until the next breakpoint/pause hit (e.g. the next tick-function command).

## Troubleshooting

- **Edits don't seem to apply / `data get` shows old values.** First check the attach announcement: on attach, Saddle prints `Saddle: debugger attached to world '…'` in the Debug Console and `[Saddle] Debugger attached` in the game chat. If the chat message does not appear in *your* world, another Minecraft instance with Saddle owns the port (its log shows `Failed to bind DAP server`) and your edits are landing in that instance — close it or use `-Dsaddle.port` to separate them. Also note that a tick function which rewrites a scoreboard/storage every tick will overwrite manual edits as soon as you resume.
- The `Watched`/`Storage` scopes edit live data only. While time-traveling, the same-named scopes show recorded values and are read-only — the `(time travel)` row in the Executor scope tells you which mode you are looking at.
