import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import readline from 'node:readline/promises';

const folder = path.dirname(fileURLToPath(import.meta.url));
export const PLUGIN_FILES = ['package.json', 'index.mjs', 'relay-controller.mjs', 'phone-relay-bridge.mjs'];
const NPM_LINE = 'call npm install --no-save --no-audit --no-fund --loglevel=error --no-progress --omit=dev --ignore-scripts';
const GUARDED_NPM_LINE = 'if not exist "node_modules\\socks\\package.json" ' + NPM_LINE;

const hash = data => crypto.createHash('sha256').update(data).digest('hex');
const inside = (parent, target) => {
    const relative = path.relative(parent, target);
    return relative && relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative);
};

function ensureNoLinks(root, target) {
    if (!inside(root, target)) throw new Error('安装目标超出酒馆目录，已停止。');
    let current = root;
    for (const segment of path.relative(root, target).split(path.sep)) {
        current = path.join(current, segment);
        if (fs.existsSync(current) && fs.lstatSync(current).isSymbolicLink()) {
            throw new Error('安装目标包含符号链接或目录联接；为避免写入其他位置，已停止。');
        }
    }
}

export function patchConfiguration(text, yaml) {
    let doc;
    try { doc = yaml.parseDocument(text, { keepSourceTokens: true }); }
    catch { throw new Error('config.yaml 无法解析，尚未修改任何文件。'); }
    if (doc.errors.length || !yaml.isMap(doc.contents) || doc.contents.flow) {
        throw new Error('config.yaml 不是有效的普通 YAML 配置，尚未修改任何文件。');
    }
    const changes = [];
    const additions = [];
    for (const [key, value] of [['enableServerPlugins', true], ['enableServerPluginsAutoUpdate', false]]) {
        const pair = doc.contents.items.find(item => yaml.isScalar(item.key) && item.key.value === key);
        if (!pair) { additions.push(`${key}: ${value}`); continue; }
        if (!yaml.isScalar(pair.value) || typeof pair.value.value !== 'boolean' || !pair.value.range) {
            throw new Error(`config.yaml 的 ${key} 必须是 true 或 false；尚未修改任何文件。`);
        }
        if (pair.value.value !== value) changes.push({ start: pair.value.range[0], end: pair.value.range[1], value: String(value) });
    }
    let next = text;
    for (const change of changes.sort((a, b) => b.start - a.start)) {
        next = next.slice(0, change.start) + change.value + next.slice(change.end);
    }
    if (additions.length) {
        const newline = text.includes('\r\n') ? '\r\n' : '\n';
        const terminator = /^\.\.\.[ \t]*(?:#.*)?(?:\r?\n)?$/m.exec(next);
        const insertion = terminator ? terminator.index : next.length;
        const before = next.slice(0, insertion);
        next = before + (before && !before.endsWith('\n') ? newline : '') +
            additions.join(newline) + newline + next.slice(insertion);
    }
    let verified;
    try { verified = yaml.parseDocument(next); }
    catch { throw new Error('无法安全更新配置，尚未修改任何文件。'); }
    if (verified.errors.length || verified.get('enableServerPlugins') !== true ||
        verified.get('enableServerPluginsAutoUpdate') !== false) {
        throw new Error('配置验证未通过，尚未修改任何文件。');
    }
    return next;
}

export function patchStartup(text) {
    const lines = text.split(/(\r?\n)/);
    const original = [], guarded = [], unfamiliar = [];
    for (let i = 0; i < lines.length; i += 2) {
        const trimmed = lines[i].trim();
        if (trimmed === NPM_LINE) original.push(i);
        else if (trimmed === GUARDED_NPM_LINE) guarded.push(i);
        else if (!/^\s*(?:rem\s|::)/i.test(lines[i]) && /\bnpm(?:\.cmd)?\s+(?:install|ci)\b/i.test(lines[i])) unfamiliar.push(i);
    }
    if (unfamiliar.length || original.length + guarded.length > 1) {
        throw new Error('Start.bat 的依赖安装命令与支持的版本不同，尚未修改任何文件。请先将每次启动的 npm install 改为仅在依赖缺失时执行，再重新安装。');
    }
    if (guarded.length || original.length === 0) return text;
    const index = original[0];
    lines[index] = lines[index].match(/^\s*/)[0] + GUARDED_NPM_LINE;
    return lines.join('');
}

export function discoverPluginSource() {
    const candidates = [path.join(folder, 'jiuguan-relay'), path.join(folder, '../server-plugin/jiuguan-relay'),
        path.join(folder, '../jiuguan-relay')];
    return candidates.find(candidate => PLUGIN_FILES.every(file => fs.existsSync(path.join(candidate, file))));
}

function installedTavern(candidate) {
    return candidate && fs.existsSync(path.join(candidate, 'server.js')) && fs.existsSync(path.join(candidate, 'package.json'));
}

export function discoverTavern(explicit) {
    if (explicit) return path.resolve(explicit);
    const candidates = [process.cwd()];
    for (let ancestor = folder, i = 0; i < 6; i++, ancestor = path.dirname(ancestor)) {
        candidates.push(path.join(ancestor, 'st/SillyTavern'), path.join(ancestor, 'SillyTavern'));
    }
    return candidates.find(installedTavern);
}

export function makeInstallPlan({ tavernDirectory, pluginSource, yaml, checkDependencies = true }) {
    if (!installedTavern(tavernDirectory)) throw new Error('目标目录中没有找到酒馆的 server.js 和 package.json。');
    const root = fs.realpathSync(tavernDirectory);
    if (typeof http.setGlobalProxyFromEnv !== 'function') {
        throw new Error('当前 Node.js 不支持运行时切换网络代理。请升级到 Node.js 24.14+ 或 25.4+ 后再安装。');
    }
    if (!pluginSource || !fs.existsSync(pluginSource) || fs.lstatSync(pluginSource).isSymbolicLink()) {
        throw new Error('找不到完整的 jiuguan-relay 插件文件夹，请完整解压安装包后重试。');
    }
    const source = fs.realpathSync(pluginSource);
    for (const file of PLUGIN_FILES) {
        const from = path.join(source, file);
        if (!fs.existsSync(from) || !fs.lstatSync(from).isFile() || fs.lstatSync(from).isSymbolicLink()) {
            throw new Error('插件安装包不完整，尚未修改任何文件。');
        }
    }
    const requireTavern = createRequire(path.join(root, 'package.json'));
    if (checkDependencies) {
        try { requireTavern.resolve('socks'); yaml ||= requireTavern('yaml'); }
        catch { throw new Error('酒馆依赖未安装完整。请先完成酒馆依赖安装；本安装器不会联网下载安装。'); }
    }
    if (!yaml) throw new Error('缺少 YAML 解析器，无法安全修改配置。');
    const desired = new Map();
    const configPath = path.join(root, 'config.yaml'), startupPath = path.join(root, 'Start.bat');
    for (const target of [configPath, startupPath]) {
        ensureNoLinks(root, target);
        if (!fs.existsSync(target) || !fs.lstatSync(target).isFile()) throw new Error('没有找到正常的 config.yaml 或 Start.bat，尚未修改任何文件。');
    }
    desired.set('config.yaml', Buffer.from(patchConfiguration(fs.readFileSync(configPath, 'utf8'), yaml)));
    desired.set('Start.bat', Buffer.from(patchStartup(fs.readFileSync(startupPath, 'utf8'))));
    for (const file of PLUGIN_FILES) desired.set(path.join('plugins', 'jiuguan-relay', file), fs.readFileSync(path.join(source, file)));
    const edits = [];
    for (const [relative, after] of desired) {
        const target = path.resolve(root, relative);
        ensureNoLinks(root, target);
        if (fs.existsSync(target) && !fs.lstatSync(target).isFile()) throw new Error('插件安装位置存在同名目录，尚未修改任何文件。');
        const before = fs.existsSync(target) ? fs.readFileSync(target) : null;
        if (!before || !before.equals(after)) edits.push({ relative, target, before, after });
    }
    return { root, source, edits };
}

function atomicWrite(target, data) {
    const temporary = path.join(path.dirname(target), '.jiuguan-install-' + crypto.randomBytes(8).toString('hex') + '.tmp');
    try { fs.writeFileSync(temporary, data, { flag: 'wx' }); fs.renameSync(temporary, target); }
    finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); }
}

export function applyInstallPlan(plan, { write = atomicWrite } = {}) {
    const { root, edits } = plan;
    if (!edits.length) return { changed: false, backupDirectory: null };
    // Confirm the reviewed snapshot still matches before creating backups or changing targets.
    for (const edit of edits) {
        ensureNoLinks(root, edit.target);
        const current = fs.existsSync(edit.target) ? fs.readFileSync(edit.target) : null;
        if (edit.before ? !current?.equals(edit.before) : current !== null) {
            throw new Error('安装前发现文件已发生变化，尚未修改任何文件。请重新运行安装器。');
        }
    }
    // SillyTavern already ignores /backups in git; original config can contain secrets.
    const backupRoot = path.join(root, 'backups', 'jiuguan-relay-install');
    ensureNoLinks(root, backupRoot);
    const timestamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z');
    const backupDirectory = path.join(backupRoot, timestamp + '-' + crypto.randomBytes(4).toString('hex'));
    fs.mkdirSync(backupDirectory, { recursive: true });
    // Complete every original-file backup before touching any target.
    for (const edit of edits) {
        if (edit.before !== null) {
            const backup = path.join(backupDirectory, edit.relative);
            fs.mkdirSync(path.dirname(backup), { recursive: true });
            fs.writeFileSync(backup, edit.before, { flag: 'wx' });
        }
    }
    fs.writeFileSync(path.join(backupDirectory, 'manifest.json'), JSON.stringify({
        version: 1, createdAt: new Date().toISOString(), files: edits.map(edit => ({
            path: edit.relative, existed: edit.before !== null,
            previousSha256: edit.before === null ? null : hash(edit.before), installedSha256: hash(edit.after),
        })),
    }, null, 2) + '\n', { flag: 'wx' });
    const completed = [];
    try {
        for (const edit of edits) {
            ensureNoLinks(root, edit.target);
            fs.mkdirSync(path.dirname(edit.target), { recursive: true });
            write(edit.target, edit.after);
            completed.push(edit);
        }
    } catch {
        let restored = true;
        for (const edit of completed.reverse()) {
            try {
                ensureNoLinks(root, edit.target);
                if (!fs.existsSync(edit.target) || !fs.readFileSync(edit.target).equals(edit.after)) { restored = false; continue; }
                if (edit.before === null) fs.unlinkSync(edit.target);
                else atomicWrite(edit.target, edit.before);
            } catch { restored = false; }
        }
        throw new Error((restored ? '安装失败，已恢复本次修改。' : '安装失败，部分文件无法自动恢复，请使用备份恢复。') + `备份目录：${backupDirectory}`);
    }
    return { changed: true, backupDirectory };
}

async function main() {
    let explicit = '', dryRun = false;
    const args = process.argv.slice(2);
    for (let i = 0; i < args.length; i++) {
        if (args[i] === '--tavern' && args[i + 1]) explicit = args[++i];
        else if (args[i] === '--dry-run') dryRun = true;
        else if (args[i] === '--help') {
            console.log('用法：安装酒馆插件.cmd [--tavern "酒馆目录"] [--dry-run]\n只安装一次；之后照常运行酒馆 Start.bat。--dry-run 仅检查，不写入文件。');
            return;
        } else throw new Error('参数无效。使用 --help 查看用法。');
    }
    let tavernDirectory = discoverTavern(explicit);
    if (!tavernDirectory) {
        const prompt = readline.createInterface({ input: process.stdin, output: process.stdout });
        try { tavernDirectory = path.resolve((await prompt.question('请输入酒馆目录（包含 server.js）：')).trim().replace(/^"|"$/g, '')); }
        finally { prompt.close(); }
    }
    const plan = makeInstallPlan({ tavernDirectory, pluginSource: discoverPluginSource() });
    console.log(`酒馆目录：${plan.root}`);
    console.log('安装内容：手机流量中转服务端插件；启用服务端插件；关闭服务端插件启动时自动更新；已有依赖时跳过启动前的 npm install。');
    if (dryRun) {
        console.log(`检查通过，预计修改 ${plan.edits.length} 个文件。尚未写入任何文件。`);
        return;
    }
    const result = applyInstallPlan(plan);
    if (result.changed) console.log(`安装完成。原文件已备份到：${result.backupDirectory}`);
    else console.log('插件和配置已是当前版本，无需修改。');
    console.log('请重启酒馆一次使插件生效。以后照常使用原来的 Start.bat，并在手机 App 中选择“使用手机流量”。');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main().catch(error => { console.error('安装失败：' + error.message); process.exitCode = 1; });
}
