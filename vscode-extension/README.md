# Saddle — Minecraft Datapack Debugger

> **S**addle: **A** **D**atapack **D**ebugger for **L**ive **E**diting

Debug datapack `.mcfunction` files in VS Code through the Saddle Fabric mod: put a saddle on your datapack's execution — stop it, back it up, and steer it while it runs.

Install from the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=d1n0.saddle-debug) (search "Saddle" in the Extensions view).

## Requirements

- Minecraft 26.2 with Fabric Loader, Fabric API and the Saddle mod installed. Start a world/server; Saddle listens on `127.0.0.1:16352` (`-Dsaddle.host` / `-Dsaddle.port` to change).
- Keep the mod and this extension on matching versions: the mod reports its version on attach and the extension warns when the two drift apart (major.minor).

## Usage

1. Open your datapack workspace (any layout containing `data/<ns>/function/*.mcfunction`).
2. Set breakpoints in `.mcfunction` files — plain commands and macro (`$...`) lines are breakable; breakpoints on comments/blank lines shift to the next executable line.
3. Run and Debug → **Attach to Minecraft (Saddle)** (or press F5 in a `.mcfunction` file), `launch.json`:

```jsonc
{
	"type": "saddle",
	"request": "attach",
	"name": "Attach to Minecraft (Saddle)",
	"host": "127.0.0.1",
	"port": 16352
}
```

4. Trigger the function in game (`/function ...`, tick functions, …) or from the **Debug Console**, which runs real Minecraft commands — also while stopped at a breakpoint.

While stopped you get the call stack, stepping (over/into/out), pause, and live scopes per frame — **Executor** (position/rotation/dimension plus the executing entity's editable NBT tree), **Macro Arguments** (`$(...)` values of the current macro frame), **Command** (the pending command with its selectors resolved to matched entities and coordinate triples resolved to blocks), **Scoreboard** (editable scores) and **Storage** (editable NBT) — edit values directly in the Variables view.

## Features

- **Saddle Watch (real time + editable)**: the **Saddle Watch** view in the Run and Debug sidebar is watch and variables in one, with no breakpoint required — pinned expressions refresh continuously while the game runs (scoreboards tick, entities move, storage changes as your datapack writes it), and rows backed by scores or NBT carry an inline pencil to edit the live value. It renders with VS Code's native debug styling (monospace rows, `debugTokenExpression.*` token colors), so it looks just like Variables/WATCH. Refresh rate: `saddle.liveWatchRefreshInterval` (default 1 s). Add expressions with the `+` button (or `Saddle: Watch Expression`), remove them inline. **Tip for a single watch panel**: right-click a Run and Debug section header and uncheck **Watch** to hide the built-in panel (extensions cannot remove built-in views, and the built-in WATCH cannot be made to poll — platform limits), then drag Saddle Watch into its place; VS Code remembers both.
- **Hover**: point at `$(name)`, `@e[...]` or coordinate triples (`1 2 3`, `~ ~2 ~`) in `.mcfunction` files while stopped to see the value / matched entities / targeted block inline.
- **Watch & pin**: the WATCH panel accepts `@e[type=pig]`, `storage <id> [path]`, `entity <uuid> [path]`, `block <x> <y> <z> [path]`, `score <objective> [holder]` (whole objective when the holder is omitted), bare `scoreboard`/`storage`, and `$(macroArg)` — each re-resolves at every stop and expands into live (editable) trees. `Saddle: Watch Expression (Pin to Variables)` pins the same expressions as a persistent **Watched** scope in the Variables view; `Saddle: Unpin Watched Expression` removes them.
- **Debug Console autocomplete**: command input is completed by the server's own Brigadier suggestions, like in-game chat. (VS Code does not support syntax highlighting for console input.)
- **Chat mirroring**: everything shown in the in-game chat — `/say` broadcasts, player chat, `/tellraw`/`/msg` deliveries — streams into the Debug Console while attached.
- **Time travel**: while stopped, the **Step Back** and **Reverse Continue** toolbar buttons walk backwards through the recorded execution. The editor highlights the past command, the call stack is reconstructed for that moment, and the **Scoreboard** / **Storage** scopes show the values *as they were right before that command ran* (read-only; the `(time travel)` row in the Executor scope marks history mode). Scope names stay the same as in the present, so your expanded rows survive the jump. Stepping forward replays the recording to the present; `Continue` past the recording resumes the live game. `Saddle: Show Execution Trace` prints the recent command history.

## Commands (palette)

- `Saddle: Run Minecraft Command`
- `Saddle: Show Scoreboard` / `Saddle: Set Scoreboard Score`
- `Saddle: List Entities`
- `Saddle: Get NBT Data` / `Saddle: Set NBT Data` (storage / entity / block)
- `Saddle: Inspect Block`
- `Saddle: Watch Expression (Pin to Variables)` / `Saddle: Unpin Watched Expression`
- `Saddle: Show Execution Trace`

## Install from source

```sh
cd vscode-extension
npx --yes @vscode/vsce package --allow-missing-repository   # produces saddle-debug-<version>.vsix
code --install-extension saddle-debug-*.vsix
```

Or open `vscode-extension/` in VS Code and press F5 to try it in an Extension Development Host.
