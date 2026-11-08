import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { PLUGIN_FILES, patchConfiguration, patchStartup, makeInstallPlan, applyInstallPlan } from './install-server-plugin.mjs';

const folder = path.dirname(fileURLToPath(import.meta.url));
const candidates = [process.env.JIUGUAN_TAVERN_DIR];
for (let ancestor = folder, i = 0; i < 6; i++, ancestor = path.dirname(ancestor)) {
    candidates.push(path.join(ancestor, 'st/SillyTavern'), path.join(ancestor, 'SillyTavern'));
}
const dependencyRoot = candidates.find(candidate => candidate && fs.existsSync(path.join(candidate, 'node_modules/yaml/package.json')));
if (!dependencyRoot) throw new Error('请设置 JIUGUAN_TAVERN_DIR 为已安装依赖的酒馆目录。');
const yaml = createRequire(path.join(dependencyRoot, 'package.json'))('yaml');
const npmLine = 'call npm install --no-save --no-audit --no-fund --loglevel=error --no-progress --omit=dev --ignore-scripts';
const originalStartup = `@echo off\r\npushd %~dp0\r\nset NODE_ENV=production\r\n${npmLine}\r\nnode server.js %*\r\npause\r\npopd\r\n`;
const originalConfig = '# settings remain unchanged\r\nbasicAuthUser:\r\n  password: "fixture-secret-do-not-print" # keep this\r\nenableServerPlugins: false # server plugins\r\nenableServerPluginsAutoUpdate: true # online only\r\notherSetting: [1, 2, 3]\r\n';

function fixture(t) {
    const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'jiuguan-plugin-install-'));
    const tavern = path.join(scratch, 'Tavern with spaces'), source = path.join(scratch, 'Plugin with spaces');
    fs.mkdirSync(tavern); fs.mkdirSync(source);
    fs.writeFileSync(path.join(tavern, 'server.js'), '// fixture only\n');
    fs.writeFileSync(path.join(tavern, 'package.json'), '{"type":"module"}');
    fs.writeFileSync(path.join(tavern, 'Start.bat'), originalStartup);
    fs.writeFileSync(path.join(tavern, 'config.yaml'), originalConfig);
    for (const file of PLUGIN_FILES) fs.writeFileSync(path.join(source, file), file.endsWith('.json') ? '{"type":"module"}\n' : `// fixture ${file}\n`);
    t.after(() => {
        const target = path.resolve(scratch), allowed = path.resolve(os.tmpdir());
        assert.equal(path.dirname(target), allowed);
        assert.ok(path.basename(target).startsWith('jiuguan-plugin-install-'));
        fs.rmSync(target, { recursive: true, force: true });
    });
    const plan = () => makeInstallPlan({ tavernDirectory: tavern, pluginSource: source, yaml, checkDependencies: false });
    return { scratch, tavern, source, plan };
}

test('YAML changes only two booleans, preserving comments, secrets and CRLF', () => {
    const next = patchConfiguration(originalConfig, yaml);
    assert.equal(next, originalConfig.replace('enableServerPlugins: false', 'enableServerPlugins: true')
        .replace('enableServerPluginsAutoUpdate: true', 'enableServerPluginsAutoUpdate: false'));
    assert.equal(patchConfiguration(next, yaml), next);
});

test('missing flags are appended before YAML document terminator', () => {
    const text = '# header\npassword: "unchanged"\n... # end\n';
    const next = patchConfiguration(text, yaml);
    assert.equal(next, '# header\npassword: "unchanged"\nenableServerPlugins: true\nenableServerPluginsAutoUpdate: false\n... # end\n');
});

test('invalid YAML and nonboolean flags fail without exposing configuration', () => {
    for (const source of ['password: "secret-sentinel\n', 'enableServerPlugins: "secret-sentinel"\n',
        'enableServerPlugins: false\nenableServerPlugins: true\npassword: secret-sentinel\n']) {
        assert.throws(() => patchConfiguration(source, yaml), error => {
            assert.ok(!error.message.includes('secret-sentinel')); return true;
        });
    }
});

test('known Start.bat install line is guarded exactly once and unrelated text preserved', () => {
    const next = patchStartup(originalStartup);
    assert.equal(next, originalStartup.replace(npmLine, 'if not exist "node_modules\\socks\\package.json" ' + npmLine));
    assert.equal(patchStartup(next), next);
    assert.throws(() => patchStartup('npm install --different-options\r\nnode server.js\r\n'));
    assert.equal(patchStartup('@echo off\r\nnode server.js %*\r\n'), '@echo off\r\nnode server.js %*\r\n');
});

test('plan is read-only and install backs up originals, preserves unowned files and is idempotent', t => {
    const f = fixture(t);
    const pluginTarget = path.join(f.tavern, 'plugins/jiuguan-relay'); fs.mkdirSync(pluginTarget, { recursive: true });
    fs.writeFileSync(path.join(pluginTarget, 'user-settings.json'), '{"keep":true}');
    const plan = f.plan();
    assert.equal(plan.edits.length, 7);
    assert.equal(fs.readFileSync(path.join(f.tavern, 'config.yaml'), 'utf8'), originalConfig);
    assert.equal(fs.existsSync(path.join(f.tavern, 'backups/jiuguan-relay-install')), false);
    const result = applyInstallPlan(plan); assert.equal(result.changed, true);
    assert.equal(fs.readFileSync(path.join(result.backupDirectory, 'config.yaml'), 'utf8'), originalConfig);
    assert.equal(fs.readFileSync(path.join(result.backupDirectory, 'Start.bat'), 'utf8'), originalStartup);
    assert.equal(fs.readFileSync(path.join(pluginTarget, 'user-settings.json'), 'utf8'), '{"keep":true}');
    for (const file of PLUGIN_FILES) assert.deepEqual(fs.readFileSync(path.join(pluginTarget, file)), fs.readFileSync(path.join(f.source, file)));
    assert.equal(f.plan().edits.length, 0);
    assert.deepEqual(applyInstallPlan(f.plan()), { changed: false, backupDirectory: null });
    assert.equal(fs.readdirSync(path.join(f.tavern, 'backups/jiuguan-relay-install')).length, 1);
});

test('missing source file and unsupported startup fail before mutation', t => {
    const f = fixture(t);
    fs.unlinkSync(path.join(f.source, 'relay-diagnostics.mjs'));
    assert.throws(f.plan, /插件安装包不完整/);
    assert.equal(fs.existsSync(path.join(f.tavern, 'backups/jiuguan-relay-install')), false);
    assert.equal(fs.readFileSync(path.join(f.tavern, 'config.yaml'), 'utf8'), originalConfig);
    fs.writeFileSync(path.join(f.source, 'relay-diagnostics.mjs'), '// fixture\n');
    fs.writeFileSync(path.join(f.tavern, 'Start.bat'), 'call npm ci\r\nnode server.js\r\n');
    assert.throws(f.plan, /Start.bat/);
    assert.equal(fs.existsSync(path.join(f.tavern, 'plugins')), false);
});

test('upgrade from four-file plugin adds diagnostics without requiring an installed copy', t => {
    const f = fixture(t);
    const pluginTarget = path.join(f.tavern, 'plugins/jiuguan-relay');
    fs.mkdirSync(pluginTarget, { recursive: true });
    fs.writeFileSync(path.join(f.tavern, 'config.yaml'), patchConfiguration(originalConfig, yaml));
    fs.writeFileSync(path.join(f.tavern, 'Start.bat'), patchStartup(originalStartup));
    for (const file of ['package.json', 'index.mjs', 'relay-controller.mjs', 'phone-relay-bridge.mjs']) {
        fs.copyFileSync(path.join(f.source, file), path.join(pluginTarget, file));
    }
    const plan = f.plan();
    assert.deepEqual(plan.edits.map(edit => edit.relative), [path.join('plugins', 'jiuguan-relay', 'relay-diagnostics.mjs')]);
    assert.equal(plan.edits[0].before, null);
    const result = applyInstallPlan(plan);
    assert.deepEqual(fs.readFileSync(path.join(pluginTarget, 'relay-diagnostics.mjs')),
        fs.readFileSync(path.join(f.source, 'relay-diagnostics.mjs')));
    const manifest = JSON.parse(fs.readFileSync(path.join(result.backupDirectory, 'manifest.json'), 'utf8'));
    assert.equal(manifest.files[0].existed, false);
    assert.equal(f.plan().edits.length, 0);
});

test('concurrent edits abort before backups or writes', t => {
    const f = fixture(t), plan = f.plan();
    const changed = originalConfig + '# changed after planning\r\n';
    fs.writeFileSync(path.join(f.tavern, 'config.yaml'), changed);
    assert.throws(() => applyInstallPlan(plan), /文件已发生变化/);
    assert.equal(fs.readFileSync(path.join(f.tavern, 'config.yaml'), 'utf8'), changed);
    assert.equal(fs.existsSync(path.join(f.tavern, 'backups/jiuguan-relay-install')), false);
});

test('write failure rolls back only this installation and keeps backups', t => {
    const f = fixture(t); let writes = 0;
    assert.throws(() => applyInstallPlan(f.plan(), { write(target, data) {
        if (++writes === 3) throw new Error('injected filesystem failure');
        fs.writeFileSync(target, data);
    } }), /已恢复本次修改/);
    assert.equal(fs.readFileSync(path.join(f.tavern, 'config.yaml'), 'utf8'), originalConfig);
    assert.equal(fs.readFileSync(path.join(f.tavern, 'Start.bat'), 'utf8'), originalStartup);
    assert.equal(fs.readdirSync(path.join(f.tavern, 'backups/jiuguan-relay-install')).length, 1);
    assert.equal(fs.existsSync(path.join(f.tavern, 'plugins/jiuguan-relay/package.json')), false);
});
