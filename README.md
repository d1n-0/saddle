# Saddle

> **S**addle: **A** **D**atapack **D**ebugger for **L**ive **E**diting

A Fabric mod that embeds a [Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/) server in Minecraft, so datapack `.mcfunction` files can be debugged from VS Code (or any DAP client) — breakpoints, stepping, time travel, and live inspection *and editing* of the game state your functions run against.

In Minecraft, a saddle is what lets you take the reins of something that otherwise runs on its own. This mod puts a saddle on datapack execution: stop it where you want (breakpoints), back it up (time travel), and steer it (live edits) — instead of watching it gallop by.

## Download

| | |
|---|---|
| Mod | [Modrinth](https://modrinth.com/mod/saddle-datapack-debugger) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/saddle) · [GitHub Releases](https://github.com/d1n-0/saddle/releases) |
| VS Code extension | [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=d1n0.saddle-debug) (or search "Saddle" in the Extensions view) |
| Source & issues | [github.com/d1n-0/saddle](https://github.com/d1n-0/saddle) · [Issues](https://github.com/d1n-0/saddle/issues) |

## Requirements

- Minecraft **26.2 – 26.3**, Fabric Loader ≥ 0.19.0, Fabric API, Java 25
- The mod on the side that runs the world: the client for singleplayer, the server for multiplayer
- DAP endpoint: `127.0.0.1:16352` — override with `-Dsaddle.host` / `-Dsaddle.port`. Non-loopback binds are refused unless `-Dsaddle.allowRemote=true` is set: the debug port allows unauthenticated command execution, so only expose it on trusted or tunneled networks

## Quick start

1. Install the mod (plus Fabric API) and start a world or server.
2. Install the VS Code extension.
3. Open your datapack folder in VS Code, set breakpoints in `.mcfunction` files, and run **Attach to Minecraft (Saddle)** (or press F5). The Debug Console and game chat both announce which world you attached to.
4. Trigger a function in game, from a tick function or the Debug Console — or press **Ctrl+Alt+Enter** (**Cmd+Alt+Enter** on macOS) / ▶ in a `.mcfunction` editor to run that file's function.
5. Optional: turn on `saddle.reloadOnSave` (or click **Reload on Save** in the status bar) to `/reload` whenever a datapack file is saved.

The rest of this README covers the mod and its protocol; see [`vscode-extension/README.md`](vscode-extension/README.md) for the editor-side feature tour.

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
- `storage <id> [path]` / `entity <target> [path]` / `block <x> <y> <z> [path]` — live (editable) NBT at the target; `<target>` is anything `/data get entity` accepts: a player name, a UUID or a single-entity selector (`@s` is the executor of the selected frame)
- `score <objective> [holder]` — one score, or the whole objective (editable) when the holder is omitted
- `scoreboard` / `storage` — every objective / every storage id
- `$(name)` — macro argument of the selected frame; bare coordinate triples resolve to the block they point at

### Saddle Watch (real time + editable, no breakpoint required)

The extension adds a **Saddle Watch** view to the Run and Debug sidebar — one watch panel that does what WATCH and Variables do together, without needing a breakpoint:

- **Real time**: pinned expressions refresh on a timer (`saddle.liveWatchRefreshInterval`, default 1 s) through the stateless `saddle/live {expression, path}` request, which reads game state on the server thread whether the game is running or suspended — scoreboards tick up live, entity positions move, storage updates as your functions write it.
- **Editable**: rows backed by scores or NBT show an inline pencil; edits go through `saddle/liveSet {expression, path, name, value}` and apply to the live game immediately. Pins that name a single value — `score <objective> <holder>` or an NBT path such as `storage <id> <path>` / `entity <target> <path>` (e.g. added with **Add to Saddle Watch**) — are editable too: `saddle/liveSet` without `name` sets the expression's own value.
- **Watch a nested node**: right-click an expanded child row (an NBT key or list item, an objective, a score holder, a storage id, a matched entity) and choose **Add to Saddle Watch** to pin it as its own entry — e.g. `storage mypack:store players.list[0]`, `entity Steve Inventory[0]` or `score kills Steve`. Players are pinned by name; other entities by UUID, the only reference that keeps pointing at the same entity. Every variable that has a live, standalone equivalent reports it as its DAP `evaluateName`, which also enables VS Code's built-in **Add to Watch** in the Variables view. Recorded (time-travel) values and block NBT have none: a watch expression cannot name the block's dimension.
- **Display names**: right-click a pin and choose **Rename** (or select it and press F2) to show it under a name of your own, e.g. `pig inventory` instead of `entity 1a2b…-… Inventory`. Only the displayed name changes — the pinned expression stays the same and is shown in the tooltip. Names are kept per workspace, so a pin gets its name back when it is pinned again in a later session; clearing the name restores the expression.

### Editor integration: run from file & reload on save

- **Run Function From File** (`saddle/runFunction {path, arguments?, executor?}`): runs the function defined by a `.mcfunction` file. The path resolves exactly like a breakpoint path (`data/<namespace>/function/<path>.mcfunction`), so any workspace layout works. `arguments` is the SNBT compound for macro functions — the extension asks for it when the file contains `$` lines and remembers the last value per file. `executor` (setting `saddle.runFunctionExecutor`, e.g. `@p`) wraps the call in `execute as <executor> at @s run`; empty runs it as the server, like the Debug Console. The response is asynchronous, so a function that stops at a breakpoint behaves exactly like one started from the console. Unsaved changes are saved first, and with reload on save the run waits until that reload has finished.
- **Reload on save** (`saddle/reload`): the equivalent of `/reload` (current packs plus newly discovered, non-disabled ones), answered once the new resources are live. The extension debounces saves of datapack files — `pack.mcmeta` and anything under `data/` in a folder that also holds a `pack.mcmeta` — so "Save All" triggers a single reload, and reports the outcome in the status bar; the Debug Console logs `Saddle: datapacks reloaded`. Reloading is refused while execution is stopped at a breakpoint, because the paused function would otherwise run against a swapped function library — continue first. `Saddle: Reload Datapacks` reloads on demand.

### `/deploy` — ship the map

`/deploy` (permission level 4) saves the world as a distributable map: builds and command blocks (region files), entities, points of interest, saved data (scoreboards, command storage, maps, …), datapacks and world settings (`level.dat`) are kept; player data — the `players/` folder (inventories, positions, advancements, statistics; also the legacy `playerdata/`, `advancements/`, `stats/` folders) and the singleplayer owner and last played time (`singleplayer_uuid`, legacy `Player`, `LastPlayed`) recorded in `level.dat`/`level.dat_old` — is removed. Until the deployed world is first opened, the singleplayer world list shows no meaningful last played date for it.

Run by a player, `/deploy` opens a dialog with a destination field and two choices:

- **Create Copy** (`/deploy copy [path]`): flushes the world to disk and writes a cleaned copy to `path`. Relative paths are created next to the world folder (in singleplayer that is the saves folder, so the copy shows up in the world list); the default is `<world>-deploy`. The destination must be outside the world and either missing or an empty folder; a failed copy removes what it wrote. The running world is not modified. The copy runs on the server thread so nothing can write to the world mid-copy — the game pauses for as long as the copy takes.
- **Apply to This World** (`/deploy apply`): cleans this world itself. Because the game writes every online player's data again while shutting down, the world is closed first (singleplayer returns to the title screen; a dedicated server stops) and the cleanup runs after the final save.

Only the server console (including rcon and the Debug Console) and the singleplayer owner may copy to an arbitrary path; other operators — e.g. LAN guests with cheats on — are limited to destinations inside the folder that contains the world, and on a singleplayer/LAN world only the owner may apply in place. Both actions are refused while execution is stopped at a breakpoint. Without a player (server console, rcon, the Debug Console), `/deploy` prints the two subcommands instead of the dialog. The dialog buttons act immediately, without Minecraft's usual "run this command?" prompt: they send custom click actions (`saddle:deploy/copy`, `saddle:deploy/apply`) that the server checks for permission level 4 itself.

### Custom requests

- `minecraft/getScoreboard`, `minecraft/setScore {objective, holder, value}`
- `minecraft/getStorage {id?, path?}`, `minecraft/listEntities {selector}`, `minecraft/getEntity {uuid}` (includes NBT)
- `minecraft/getData` / `minecraft/setData` `{type: storage|entity|block, target, path, value}` — vanilla `/data`-style access; block targets are `"x y z [dimension]"`
- `minecraft/getBlock {pos, dimension?}` — block state plus block-entity NBT
- `saddle/runFunction {path, arguments?, executor?}`, `saddle/reload` — see [Editor integration](#editor-integration-run-from-file--reload-on-save)

## Building

```sh
./gradlew build                     # mod jar in build/libs/

cd vscode-extension                 # extension .vsix
npx --yes @vscode/vsce package
code --install-extension saddle-debug-*.vsix
```

Minecraft 26.x is unobfuscated, so the buildscript uses the no-remap `net.fabricmc.fabric-loom` plugin. The mod is built against 26.2 (`gradle.properties`); pass `-Pminecraft_version=26.3 -Pfabric_api_version=<version>` to build or `runServer` against another supported version.

## Testing

```sh
# first time only: accept the EULA and disable the tick watchdog
mkdir -p run
printf 'eula=true\n' > run/eula.txt
printf 'max-tick-time=-1\nonline-mode=false\npause-when-empty-seconds=0\n' > run/server.properties

./gradlew runServer                 # terminal 1
python3 scripts/dap_smoke_test.py   # terminal 2
```

The script installs `scripts/test-datapack` into the world, `/reload`s, and exercises the full debug loop end to end (141 checks): breakpoints, stepping, time travel (step back / reverse continue / historical state reconstruction), macro breakpoints and macro-argument values, comment-line shifting, live variable read/write (scoreboard, storage NBT, entity NBT), selector/coordinate resolution, hover evaluation, console completions, chat output mirroring, entity/block data requests, evaluate and pause, reload and run-from-file, and `/deploy copy`. CI runs the same suite against a real dedicated server for every supported Minecraft version (26.2 and 26.3), and pushing a `v*` tag publishes the jar and vsix as a GitHub Release.

## Notes

- Keep the mod and the VS Code extension on matching versions: the mod sends a `saddle/version` event on attach and the extension warns when the two drift apart (major.minor).
- On dedicated servers set `max-tick-time=-1`: suspending at a breakpoint parks the server thread, which would otherwise trip the watchdog.
- In singleplayer, the internal client may disconnect if the game stays suspended for a long time.
- Stepping past the end of all queued commands leaves the session in the running state until the next breakpoint/pause hit (e.g. the next tick-function command).

## Troubleshooting

- **Edits don't seem to apply / `data get` shows old values.** First check the attach announcement: on attach, Saddle prints `Saddle: debugger attached to world '…'` in the Debug Console and `[Saddle] Debugger attached` in the game chat. If the chat message does not appear in *your* world, another Minecraft instance with Saddle owns the port (its log shows `Failed to bind DAP server`) and your edits are landing in that instance — close it or use `-Dsaddle.port` to separate them. Also note that a tick function which rewrites a scoreboard/storage every tick will overwrite manual edits as soon as you resume.
- The `Watched`/`Storage` scopes edit live data only. While time-traveling, the same-named scopes show recorded values and are read-only — the `(time travel)` row in the Executor scope tells you which mode you are looking at.

## License

[MIT](LICENSE)
