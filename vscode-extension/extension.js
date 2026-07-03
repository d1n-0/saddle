// Saddle debug extension: connects VS Code's debug UI to the DAP server
// embedded in the Saddle Fabric mod (TCP, default 127.0.0.1:16352).
const vscode = require('vscode');

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

function activate(context) {
	const output = vscode.window.createOutputChannel('Saddle');

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
			'Expression to watch: @selector | storage <id> [path] | entity <uuid> [path] | block <x> <y> <z> [path] | score <objective> <holder>',
			{ value: selected });
		const body = await activeSession().customRequest('saddle/pin', { expression });
		vscode.window.setStatusBarMessage(`Saddle: watching ${body.pins.length} expression(s)`, 3000);
	});

	command('saddle.unpin', async () => {
		const { pins } = await activeSession().customRequest('saddle/pins', {});
		if (!pins.length) throw new Error('Nothing is pinned.');
		const expression = await vscode.window.showQuickPick(pins, { placeHolder: 'Unpin expression' });
		if (!expression) return;
		await activeSession().customRequest('saddle/unpin', { expression });
	});

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
