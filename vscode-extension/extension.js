// Saddle debug extension: connects VS Code's debug UI to the DAP server
// embedded in the Saddle Fabric mod (TCP, default 127.0.0.1:16352).
const vscode = require('vscode');
const crypto = require('crypto');

const DEFAULT_HOST = '127.0.0.1';
const DEFAULT_PORT = 16352;

class SaddleConfigurationProvider {
	// Allows F5 in a .mcfunction file without a launch.json.
	resolveDebugConfiguration(folder, config) {
		if (!config.type && !config.request && !config.name) {
			config.type = 'saddle';
			config.name = 'Attach to Minecraft (Saddle)';
		}
		config.request = 'attach';
		config.host = config.host || DEFAULT_HOST;
		config.port = config.port || DEFAULT_PORT;
		return config;
	}
}

class SaddleAdapterDescriptorFactory {
	createDebugAdapterDescriptor(session) {
		const { host, port } = session.configuration;
		return new vscode.DebugAdapterServer(port || DEFAULT_PORT, host || DEFAULT_HOST);
	}
}

function activeSession() {
	const session = vscode.debug.activeDebugSession;
	if (!session || session.type !== 'saddle') {
		throw new Error('No active Saddle debug session. Start "Attach to Minecraft (Saddle)" first.');
	}
	return session;
}

// Debug hover targets in .mcfunction files: macro arguments `$(name)`,
// entity selectors `@e[...]` and coordinate triples `1 2 3` / `~ ~2 ~`.
// The extracted text is sent as an evaluate(context: "hover") request, which
// the Saddle adapter resolves without executing any command.
const COORD = '(?:[~^]-?(?:\\d+(?:\\.\\d+)?)?|-?\\d+(?:\\.\\d+)?)';
const COORD_TRIPLE = new RegExp(`(?<![\\w~^.-])${COORD}\\s+${COORD}\\s+${COORD}(?![\\w.-])`, 'g');

function matchBrackets(text, open) {
	let depth = 0;
	let quote = null;
	for (let i = open; i < text.length; i++) {
		const c = text[i];
		if (quote) {
			if (c === '\\') i++;
			else if (c === quote) quote = null;
		} else if (c === '"' || c === "'") quote = c;
		else if (c === '[' || c === '{') depth++;
		else if (c === ']' || c === '}') {
			if (--depth === 0) return i;
		}
	}
	return -1;
}

class SaddleEvaluatableExpressionProvider {
	provideEvaluatableExpression(document, position) {
		const line = document.lineAt(position.line).text;
		const within = (start, end) => position.character >= start && position.character <= end;
		const range = (start, end) =>
			new vscode.Range(position.line, start, position.line, end);

		for (const m of line.matchAll(/\$\(\w+\)/g)) {
			const end = m.index + m[0].length;
			if (within(m.index, end)) {
				return new vscode.EvaluatableExpression(range(m.index, end), m[0]);
			}
		}
		for (const m of line.matchAll(/@[a-z]/g)) {
			let end = m.index + m[0].length;
			if (line[end] === '[') {
				const close = matchBrackets(line, end);
				if (close < 0) continue;
				end = close + 1;
			}
			if (within(m.index, end)) {
				return new vscode.EvaluatableExpression(range(m.index, end), line.slice(m.index, end));
			}
		}
		for (const m of line.matchAll(COORD_TRIPLE)) {
			const end = m.index + m[0].length;
			if (within(m.index, end)) {
				return new vscode.EvaluatableExpression(range(m.index, end), m[0]);
			}
		}
		return undefined;
	}
}

// Saddle Watch: a webview view that replicates the native Variables/WATCH
// row styling exactly (monospace 13px, debugTokenExpression.* theme colors,
// list hover backgrounds) — the TreeView API cannot color name and value
// separately, so styling parity requires a webview. Data comes from the
// stateless "saddle/live" request, so values refresh in real time while the
// game runs; score/NBT rows are edited in place via "saddle/liveSet".
class SaddleWatchViewProvider {
	constructor() {
		this.view = undefined;
		this.expanded = new Set();
		this.refreshing = false;
	}

	session() {
		const session = vscode.debug.activeDebugSession;
		return session && session.type === 'saddle' ? session : undefined;
	}

	resolveWebviewView(webviewView) {
		this.view = webviewView;
		webviewView.webview.options = { enableScripts: true };
		webviewView.webview.html = this.html();
		webviewView.webview.onDidReceiveMessage((msg) => this.onMessage(msg));
		webviewView.onDidChangeVisibility(() => {
			if (webviewView.visible) this.refresh();
		});
		this.refresh();
	}

	key(expr, path) {
		return JSON.stringify([expr, ...path]);
	}

	async onMessage(msg) {
		try {
			if (msg.type === 'toggle') {
				const key = this.key(msg.expr, msg.path);
				if (this.expanded.has(key)) this.expanded.delete(key);
				else this.expanded.add(key);
				await this.refresh();
			} else if (msg.type === 'edit') {
				const value = await vscode.window.showInputBox({
					prompt: `New value for ${msg.name} (SNBT / integer)`,
					value: String(msg.value ?? ''),
				});
				if (value === undefined) return;
				await this.session().customRequest('saddle/liveSet', {
					expression: msg.expr, path: msg.path, name: msg.name, value,
				});
				await this.refresh();
			} else if (msg.type === 'remove') {
				await this.session().customRequest('saddle/unpin', { expression: msg.expr });
				await this.refresh();
			} else if (msg.type === 'add') {
				await vscode.commands.executeCommand('saddle.pin');
			}
		} catch (err) {
			vscode.window.showErrorMessage(String((err && err.message) || err));
		}
	}

	// Builds the visible rows: every pin, plus children of expanded rows.
	async buildRows() {
		const session = this.session();
		if (!session) return { rows: [], attached: false };
		const { pins } = await session.customRequest('saddle/pins', {});
		const rows = [];
		const walk = async (expr, path, name, value, hasChildren, editable, depth) => {
			const key = this.key(expr, path);
			const isOpen = hasChildren && this.expanded.has(key);
			rows.push({ expr, path, name, value, hasChildren, editable, depth, open: isOpen });
			if (!isOpen) return;
			const body = await session.customRequest('saddle/live', { expression: expr, path });
			for (const child of body.children || []) {
				await walk(expr, [...path, child.name], child.name, child.value,
					child.hasChildren, child.editable, depth + 1);
			}
		};
		for (const expr of pins) {
			try {
				const body = await session.customRequest('saddle/live', { expression: expr, path: [] });
				await walk(expr, [], expr, body.value, body.hasChildren, false, 0);
			} catch (err) {
				rows.push({ expr, path: [], name: expr, depth: 0,
					value: `(${(err && err.message) || 'unresolvable'})`, error: true });
			}
		}
		return { rows, attached: true };
	}

	async refresh() {
		if (!this.view || !this.view.visible || this.refreshing) return;
		this.refreshing = true;
		try {
			const data = await this.buildRows();
			this.view.webview.postMessage(data);
		} catch (err) {
			// Session detached mid-refresh; next tick will retry.
		} finally {
			this.refreshing = false;
		}
	}

	html() {
		// Mirrors debug.contribution.css: 13px monospace rows, name/value
		// token colors, 18px row height and list hover backgrounds.
		const nonce = crypto.randomBytes(16).toString('base64');
		return `<!DOCTYPE html>
<html>
<head>
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
<style nonce="${nonce}">
	body { padding: 0; margin: 0; }
	#empty {
		font-family: var(--vscode-font-family);
		font-size: 13px;
		color: var(--vscode-descriptionForeground);
		padding: 8px 12px;
	}
	#empty a { color: var(--vscode-textLink-foreground); cursor: pointer; text-decoration: none; }
	.row {
		display: flex;
		align-items: center;
		height: 22px;
		line-height: 22px;
		cursor: pointer;
		white-space: pre;
		overflow: hidden;
	}
	.row:hover { background: var(--vscode-list-hoverBackground); }
	.twistie {
		flex-shrink: 0;
		width: 16px;
		height: 16px;
		display: flex;
		align-items: center;
		justify-content: center;
		color: var(--vscode-icon-foreground);
	}
	.twistie svg { transition: transform 70ms; }
	.row.open .twistie svg { transform: rotate(90deg); }
	.expression {
		font-family: var(--vscode-editor-font-family, monospace);
		font-size: 13px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: pre;
		flex: 1;
	}
	.name { color: var(--vscode-debugTokenExpression-name); }
	.value { margin-left: 6px; color: var(--vscode-debugTokenExpression-value); }
	.value.number { color: var(--vscode-debugTokenExpression-number); }
	.value.string { color: var(--vscode-debugTokenExpression-string); }
	.value.boolean { color: var(--vscode-debugTokenExpression-boolean); }
	.value.error { color: var(--vscode-debugTokenExpression-error); }
	.actions { display: none; flex-shrink: 0; padding-right: 4px; }
	.row:hover .actions { display: flex; gap: 2px; }
	.action {
		width: 16px; height: 16px;
		display: flex; align-items: center; justify-content: center;
		border-radius: 3px;
		color: var(--vscode-icon-foreground);
	}
	.action:hover { background: var(--vscode-toolbar-hoverBackground); }
</style>
</head>
<body>
<div id="empty" style="display:none">
	No expressions yet. <a id="addLink">Add an expression</a> — it updates live while the game runs.<br><br>
	Supported: @selector · storage &lt;id&gt; [path] · entity &lt;uuid&gt; [path] · block &lt;x&gt; &lt;y&gt; &lt;z&gt; [path] · score &lt;objective&gt; [holder] · scoreboard · storage<br><br>
	Tip: right-click the Run and Debug section headers and uncheck "Watch" to keep this as your only watch panel.
</div>
<div id="tree"></div>
<script nonce="${nonce}">
	const vscodeApi = acquireVsCodeApi();
	const CHEVRON = '<svg width="16" height="16" viewBox="0 0 16 16"><path fill="currentColor" d="M6.22 3.22a.75.75 0 0 1 1.06 0l4.25 4.25a.75.75 0 0 1 0 1.06l-4.25 4.25a.75.75 0 1 1-1.06-1.06L9.94 8 6.22 4.28a.75.75 0 0 1 0-1.06z"/></svg>';
	const EDIT = '<svg width="14" height="14" viewBox="0 0 16 16"><path fill="currentColor" d="M13.23 1h-1.46L3.52 9.25l-.16.22L1 13.59 2.41 15l4.12-2.36.22-.16L15 4.23V2.77L13.23 1zM2.41 13.59l1.51-3 1.45 1.45-2.96 1.55zm3.83-2.06L4.47 9.76l8-8 1.77 1.77-8 8z"/></svg>';
	const CLOSE = '<svg width="14" height="14" viewBox="0 0 16 16"><path fill="currentColor" d="M8 8.707l3.646 3.647.708-.707L8.707 8l3.647-3.646-.707-.708L8 7.293 4.354 3.646l-.707.708L7.293 8l-3.646 3.646.707.708L8 8.707z"/></svg>';

	function classify(text) {
		if (text === undefined || text === null || text === '') return 'value';
		if (/^\\(.*\\)$/.test(text)) return 'value error';
		if (/^-?\\d+(\\.\\d+)?[bslfdBSLFD]?$/.test(text)) return 'value number';
		if (/^".*"$/.test(text) || /^'.*'$/.test(text)) return 'value string';
		if (text === 'true' || text === 'false') return 'value boolean';
		return 'value';
	}

	window.addEventListener('message', (event) => {
		const { rows, attached } = event.data;
		const tree = document.getElementById('tree');
		const empty = document.getElementById('empty');
		if (!attached || !rows.length) {
			tree.innerHTML = '';
			empty.style.display = 'block';
			return;
		}
		empty.style.display = 'none';
		tree.innerHTML = '';
		for (const row of rows) {
			const el = document.createElement('div');
			el.className = 'row' + (row.open ? ' open' : '');
			el.style.paddingLeft = (row.depth * 16 + 2) + 'px';

			const twistie = document.createElement('span');
			twistie.className = 'twistie';
			if (row.hasChildren) twistie.innerHTML = CHEVRON;
			el.appendChild(twistie);

			const expression = document.createElement('span');
			expression.className = 'expression';
			const name = document.createElement('span');
			name.className = 'name';
			name.textContent = row.name;
			expression.appendChild(name);
			if (row.value !== undefined && row.value !== null && row.value !== '') {
				expression.appendChild(document.createTextNode(':'));
				const value = document.createElement('span');
				value.className = row.error ? 'value error' : classify(String(row.value));
				value.textContent = String(row.value);
				expression.appendChild(value);
			}
			el.appendChild(expression);
			el.title = String(row.value ?? '');

			const actions = document.createElement('span');
			actions.className = 'actions';
			if (row.editable) {
				const edit = document.createElement('span');
				edit.className = 'action';
				edit.title = 'Edit value';
				edit.innerHTML = EDIT;
				edit.addEventListener('click', (e) => {
					e.stopPropagation();
					vscodeApi.postMessage({ type: 'edit', expr: row.expr,
						path: row.path.slice(0, -1), name: row.name, value: row.value });
				});
				actions.appendChild(edit);
			}
			if (row.path.length === 0) {
				const remove = document.createElement('span');
				remove.className = 'action';
				remove.title = 'Remove';
				remove.innerHTML = CLOSE;
				remove.addEventListener('click', (e) => {
					e.stopPropagation();
					vscodeApi.postMessage({ type: 'remove', expr: row.expr });
				});
				actions.appendChild(remove);
			}
			el.appendChild(actions);

			if (row.hasChildren) {
				el.addEventListener('click', () =>
					vscodeApi.postMessage({ type: 'toggle', expr: row.expr, path: row.path }));
			}
			tree.appendChild(el);
		}
	});
	document.getElementById('addLink').addEventListener('click', () =>
		vscodeApi.postMessage({ type: 'add' }));
</script>
</body>
</html>`;
	}
}

function activate(context) {
	const output = vscode.window.createOutputChannel('Saddle');
	const liveWatch = new SaddleWatchViewProvider();

	let liveTimer;
	const restartLiveTimer = () => {
		if (liveTimer) clearInterval(liveTimer);
		const interval = Math.max(200,
			vscode.workspace.getConfiguration('saddle').get('liveWatchRefreshInterval', 1000));
		liveTimer = setInterval(() => {
			if (liveWatch.session()) liveWatch.refresh();
		}, interval);
	};
	restartLiveTimer();
	context.subscriptions.push(
		vscode.window.registerWebviewViewProvider('saddleLiveWatch', liveWatch),
		vscode.debug.onDidStartDebugSession(() => liveWatch.refresh()),
		vscode.debug.onDidTerminateDebugSession(() => liveWatch.refresh()),
		vscode.workspace.onDidChangeConfiguration((event) => {
			if (event.affectsConfiguration('saddle.liveWatchRefreshInterval')) restartLiveTimer();
		}),
		{ dispose: () => clearInterval(liveTimer) },
	);

	const show = (title, body) => {
		output.appendLine(`— ${title} ${'—'.repeat(Math.max(0, 60 - title.length))}`);
		output.appendLine(typeof body === 'string' ? body : JSON.stringify(body, null, 2));
		output.show(true);
	};

	const command = (id, handler) =>
		context.subscriptions.push(vscode.commands.registerCommand(id, async () => {
			try {
				await handler();
			} catch (err) {
				vscode.window.showErrorMessage(String(err.message || err));
			}
		}));

	const prompt = async (label, options = {}) => {
		const value = await vscode.window.showInputBox({ prompt: label, ...options });
		if (value === undefined) throw new Error('Cancelled');
		return value;
	};

	const pickDataType = async () => {
		const type = await vscode.window.showQuickPick(['storage', 'entity', 'block'], {
			placeHolder: 'Data target type',
		});
		if (!type) throw new Error('Cancelled');
		const hints = {
			storage: 'Storage id, e.g. mypack:store',
			entity: 'Entity UUID (use "Saddle: List Entities" to find one)',
			block: 'Block position "x y z" or "x y z dimension"',
		};
		const target = await prompt(hints[type]);
		return { type, target };
	};

	context.subscriptions.push(
		vscode.debug.registerDebugConfigurationProvider('saddle', new SaddleConfigurationProvider()),
		vscode.debug.registerDebugAdapterDescriptorFactory('saddle', new SaddleAdapterDescriptorFactory()),
		vscode.languages.registerEvaluatableExpressionProvider(
			'mcfunction', new SaddleEvaluatableExpressionProvider()),
		output,
	);

	command('saddle.runCommand', async () => {
		const expression = await prompt('Minecraft command to run', { placeHolder: 'e.g. function mypack:main' });
		const body = await activeSession().customRequest('evaluate', { expression, context: 'repl' });
		show(`/${expression.replace(/^\//, '')}`, body.result);
	});

	command('saddle.getScoreboard', async () => {
		show('scoreboard', await activeSession().customRequest('minecraft/getScoreboard', {}));
	});

	command('saddle.setScore', async () => {
		const objective = await prompt('Objective name');
		const holder = await prompt('Score holder');
		const value = parseInt(await prompt('New value'), 10);
		if (!Number.isInteger(value)) throw new Error('The score value must be an integer.');
		const body = await activeSession().customRequest('minecraft/setScore', { objective, holder, value });
		show(`setScore ${objective} ${holder}`, body);
	});

	command('saddle.listEntities', async () => {
		const selector = await prompt('Entity selector', { value: '@e[limit=50]' });
		show(`entities ${selector}`, await activeSession().customRequest('minecraft/listEntities', { selector }));
	});

	command('saddle.getData', async () => {
		const { type, target } = await pickDataType();
		const path = await prompt('NBT path (empty for the whole tag)', { value: '' });
		const body = await activeSession().customRequest('minecraft/getData', { type, target, path });
		show(`data get ${type} ${target} ${path}`, body.value);
	});

	command('saddle.setData', async () => {
		const { type, target } = await pickDataType();
		const path = await prompt('NBT path to set');
		const value = await prompt('SNBT value, e.g. 42, "text", {a: 1b}');
		const body = await activeSession().customRequest('minecraft/setData', { type, target, path, value });
		show(`data set ${type} ${target} ${path}`, body.value);
	});

	command('saddle.pin', async () => {
		const editor = vscode.window.activeTextEditor;
		const selected = editor && !editor.selection.isEmpty
			? editor.document.getText(editor.selection) : '';
		const expression = await prompt(
			'Expression to watch: @selector | storage <id> [path] | entity <uuid> [path] | block <x> <y> <z> [path] | score <objective> [holder] | scoreboard',
			{ value: selected });
		const body = await activeSession().customRequest('saddle/pin', { expression });
		liveWatch.refresh();
		vscode.window.setStatusBarMessage(`Saddle: watching ${body.pins.length} expression(s)`, 3000);
	});

	command('saddle.unpin', async () => {
		const { pins } = await activeSession().customRequest('saddle/pins', {});
		if (!pins.length) throw new Error('Nothing is pinned.');
		const expression = await vscode.window.showQuickPick(pins, { placeHolder: 'Unpin expression' });
		if (!expression) return;
		await activeSession().customRequest('saddle/unpin', { expression });
		liveWatch.refresh();
	});

	command('saddle.liveRefresh', async () => liveWatch.refresh());

	// Warn when the mod and this extension drift apart (major.minor compare).
	context.subscriptions.push(vscode.debug.onDidReceiveDebugSessionCustomEvent((event) => {
		if (event.session.type !== 'saddle' || event.event !== 'saddle/version') return;
		const modVersion = String(event.body?.version ?? 'unknown');
		const extVersion = String(context.extension.packageJSON.version ?? 'unknown');
		// "dev" = IDE build without resource expansion; nothing to compare.
		if (modVersion === 'dev' || modVersion === 'unknown') return;
		const minor = (v) => v.split('.').slice(0, 2).join('.');
		if (minor(modVersion) !== minor(extVersion)) {
			vscode.window.showWarningMessage(
				`Saddle version mismatch: mod ${modVersion} vs extension ${extVersion}. `
				+ 'Features may misbehave — update both to matching versions.');
		}
	}));

	command('saddle.showTrace', async () => {
		const body = await activeSession().customRequest('saddle/trace', { count: 200 });
		const lines = body.steps.map((s) =>
			`-${String(s.behind).padStart(5)}  ${'· '.repeat(Math.max(0, s.depth - 1))}${s.function}:${s.line}` +
			`  ${s.command}${s.executor && s.executor !== 'server' ? `  [as ${s.executor}]` : ''}`);
		show(`execution trace (${body.steps.length} steps, oldest first)`, lines.join('\n'));
	});

	command('saddle.getBlock', async () => {
		const pos = await prompt('Block position "x y z"');
		const dimension = await prompt('Dimension (empty for overworld)', { value: '' });
		const body = await activeSession().customRequest('minecraft/getBlock', { pos, dimension });
		show(`block ${pos}`, body);
	});
}

function deactivate() {}

module.exports = { activate, deactivate };
