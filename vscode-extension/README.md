# Saddle — Minecraft Datapack Debugger

> **S**addle: **A** **D**atapack **D**ebugger for **L**ive **E**diting

Debug datapack `.mcfunction` files in VS Code through the Saddle Fabric mod: put a saddle on your datapack's execution — stop it, back it up, and steer it while it runs.

## Requirements

- Minecraft 26.2 – 26.3 with Fabric Loader, Fabric API and the **Saddle mod** — get it from [Modrinth](https://modrinth.com/mod/saddle-datapack-debugger), [CurseForge](https://www.curseforge.com/minecraft/mc-mods/saddle) or [GitHub Releases](https://github.com/d1n-0/saddle/releases). Start a world/server; Saddle listens on `127.0.0.1:16352` (`-Dsaddle.host` / `-Dsaddle.port` to change).
- Keep the mod and this extension on matching versions: the mod reports its version on attach and the extension warns when the two drift apart (major.minor).

## Usage

1. Open your datapack workspace (any layout containing `data/<ns>/function/*.mcfunction`).
2. Set breakpoints in `.mcfunction` files — plain commands and macro (`$...`) lines are breakable; breakpoints on comments/blank lines shift to the next executable line.
3. Run and Debug → **Attach to Minecraft (Saddle)** (or press F5 in a `.mcfunction` file). A `launch.json` is only needed to change the host or port:

```jsonc
{
	"type": "saddle",
	"request": "attach",
	"name": "Attach to Minecraft (Saddle)",
	"host": "127.0.0.1",
	"port": 16352
}
```

4. Trigger the function in game (`/function ...`, tick functions, …), from the **Debug Console** (real Minecraft commands, also while stopped at a breakpoint), or with **Run Function From File** (below).

While stopped you get the call stack, stepping (over/into/out), pause, and live scopes per frame — **Executor** (position/rotation/dimension plus the executing entity's editable NBT tree), **Macro Arguments** (`$(...)` values of the current macro frame), **Command** (the pending command with its selectors resolved to matched entities and coordinate triples resolved to blocks), **Scoreboard** (editable scores) and **Storage** (editable NBT) — edit values directly in the Variables view.

## Features

- **Saddle Watch (real time + editable)**: the **Saddle Watch** view in the Run and Debug sidebar is watch and variables in one, with no breakpoint required — pinned expressions refresh continuously while the game runs (scoreboards tick, entities move, storage changes as your datapack writes it), and rows backed by scores or NBT carry an inline pencil to edit the live value — including pins that name a single value, such as `score kills Steve` or `storage mypack:store players.count`. It renders with VS Code's native debug styling (monospace rows, `debugTokenExpression.*` token colors), so it looks just like Variables/WATCH. Refresh rate: `saddle.liveWatchRefreshInterval` (default 1 s). Add expressions with the `+` button (or `Saddle: Watch Expression`), remove them inline, or right-click a nested row (NBT key, list item, objective, score holder, storage id, entity) and choose **Add to Saddle Watch** to watch it as its own entry. Right-click a pin and choose **Rename** (or select it and press F2) to give it a display name such as `pig inventory`; the expression stays the same, shows in the tooltip, and the name is remembered per workspace. The Variables view's built-in **Add to Watch** works for the same nodes. **Tip**: to keep a single watch panel, right-click a Run and Debug section header and uncheck **Watch** (extensions cannot remove built-in views, and the built-in WATCH cannot poll), then drag Saddle Watch into its place; VS Code remembers both.
- **Hover**: point at `$(name)`, `@e[...]` or coordinate triples (`1 2 3`, `~ ~2 ~`) in `.mcfunction` files while stopped to see the value / matched entities / targeted block inline.
- **Watch & pin**: the WATCH panel accepts `@e[type=pig]`, `storage <id> [path]`, `entity <player|uuid|@selector> [path]` (e.g. `entity Steve SelectedItem`), `block <x> <y> <z> [path]`, `score <objective> [holder]` (whole objective when the holder is omitted), bare `scoreboard`/`storage`, and `$(macroArg)` — each re-resolves at every stop and expands into live (editable) trees. `Saddle: Watch Expression (Pin to Variables)` pins the same expressions as a persistent **Watched** scope in the Variables view; `Saddle: Unpin Watched Expression` removes them.
- **Debug Console autocomplete**: command input is completed by the server's own Brigadier suggestions, like in-game chat. (VS Code does not support syntax highlighting for console input.)
- **Chat mirroring**: everything shown in the in-game chat — `/say` broadcasts, player chat, `/tellraw`/`/msg` deliveries — streams into the Debug Console while attached.
- **Time travel**: while stopped, the **Step Back** and **Reverse Continue** toolbar buttons walk backwards through the recorded execution. The editor highlights the past command, the call stack is reconstructed for that moment, and the **Scoreboard** / **Storage** scopes show the values *as they were right before that command ran* (read-only; the `(time travel)` row in the Executor scope marks history mode). Scope names stay the same as in the present, so your expanded rows survive the jump. Stepping forward replays the recording to the present; `Continue` past the recording resumes the live game. `Saddle: Show Execution Trace` prints the recent command history.
- **Run Function From File**: press **Ctrl+Alt+Enter** (**Cmd+Alt+Enter** on macOS), click ▶ in the editor title bar, or use the editor/explorer context menu on a `.mcfunction` file to run its function in the game. Attaches automatically when no Saddle session is running (using the `saddle` configuration from `launch.json` if there is one), saves the file first, asks for arguments when the file contains macro (`$`) lines (the last value is remembered per file), and logs the result to the Saddle output channel without switching away from your current panel tab (a one-line summary appears in the status bar). Set `saddle.runFunctionExecutor` (e.g. `@p`) to run it `as` an entity `at` its position instead of as the server. Breakpoints in the function stop as usual.
- **Reload on save**: with `saddle.reloadOnSave` on — or toggled from the **Reload on Save** status bar item shown while attached — saving a datapack file (`pack.mcmeta` or anything under the `data/` folder next to it) reloads the datapacks, nodemon style. Saves are debounced (Save All reloads once), and **Run Function** waits for the reload triggered by its own save, so it always runs the code you just wrote. Reloads are refused while stopped at a breakpoint; continue and save again (or run `Saddle: Reload Datapacks`).

## Commands (palette)

- `Saddle: Run Function From File` (Ctrl+Alt+Enter / Cmd+Alt+Enter)
- `Saddle: Reload Datapacks` / `Saddle: Toggle Reload on Save`
- `Saddle: Run Minecraft Command`
- `Saddle: Show Scoreboard` / `Saddle: Set Scoreboard Score`
- `Saddle: List Entities`
- `Saddle: Get NBT Data` / `Saddle: Set NBT Data` (storage / entity / block)
- `Saddle: Inspect Block`
- `Saddle: Watch Expression (Pin to Variables)` / `Saddle: Unpin Watched Expression`
- `Saddle: Show Execution Trace`

## Settings

- `saddle.liveWatchRefreshInterval` — Saddle Watch refresh interval in ms (default 1000)
- `saddle.reloadOnSave` — reload datapacks when a datapack file is saved (default off)
- `saddle.runFunctionExecutor` — entity that runs functions started with Run Function From File (default: the server)

## Shipping the map

The mod adds `/deploy` (permission level 4): it saves the world as a distributable map without player data, either as a copy or in place. See the [main README](https://github.com/d1n-0/saddle#deploy--ship-the-map) for details.

## Install from source

```sh
cd vscode-extension
npx --yes @vscode/vsce package   # produces saddle-debug-<version>.vsix
code --install-extension saddle-debug-*.vsix
```

Or open `vscode-extension/` in VS Code and press F5 to try it in an Extension Development Host.

## Links

- Source & issues: [github.com/d1n-0/saddle](https://github.com/d1n-0/saddle) · [Issues](https://github.com/d1n-0/saddle/issues)
- Mod: [Modrinth](https://modrinth.com/mod/saddle-datapack-debugger) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/saddle)
- Extension: [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=d1n0.saddle-debug)
