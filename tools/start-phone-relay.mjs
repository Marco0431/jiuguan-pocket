import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { spawn, spawnSync } from 'node:child_process';
import readline from 'node:readline/promises';
import { stdin, stdout } from 'node:process';
import { parsePhoneUrl, probePhone, createBridge, buildEnvironment, supportsProxyNode } from './phone-relay-bridge.mjs';

const folder = path.dirname(fileURLToPath(import.meta.url));
const configPath = path.join(folder, 'phone-relay.local.json');

function protect(value, mode) {
    if (process.platform !== 'win32') throw new Error('此启动器使用 Windows 本机加密保存连接信息，请在 Windows 上运行。');
    const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', path.join(folder, 'phone-relay-secret.ps1'), mode], {
        input: value, encoding: 'utf8', windowsHide: true, timeout: 15_000,
    });
    if (result.status !== 0 || !result.stdout.trim()) throw new Error('无法读写本机加密设置，请检查 Windows 用户账户和目录权限。');
    return result.stdout.trim();
}

function isTavern(candidate) {
    return candidate && fs.existsSync(path.join(candidate, 'server.js')) && fs.existsSync(path.join(candidate, 'package.json'));
}

async function main() {
    if (!supportsProxyNode()) throw new Error('需要 Node.js 24.5 或更新版本，以便酒馆原生 fetch 也通过手机联网。');
    const args = process.argv.slice(2);
    const options = { configure: false, check: false, directory: '' };
    for (let i = 0; i < args.length; i++) {
        if (args[i] === '--configure') options.configure = true;
        else if (args[i] === '--check') options.check = true;
        else if (args[i] === '--tavern' && args[i + 1]) options.directory = path.resolve(args[++i]);
        else if (args[i] === '--help') {
            console.log('用法：start-phone-relay.cmd [--configure] [--check] [--tavern "酒馆目录"]\n首次启动粘贴手机连接地址；--configure 可更换手机地址；--check 只检查并保存连接，不启动酒馆。');
            return;
        } else throw new Error('参数无效。使用 --help 查看用法。');
    }
    let saved = {};
    try { saved = JSON.parse(fs.readFileSync(configPath, 'utf8')); } catch { /* First launch. */ }
    const candidates = [options.directory, saved.tavernDirectory, process.cwd(),
        path.resolve(folder, '../../st/SillyTavern'), path.resolve(folder, '../st/SillyTavern'),
        path.resolve(folder, '../SillyTavern'), path.resolve(folder, '../../SillyTavern')];
    let tavernDirectory = options.directory || candidates.find(isTavern);
    let proxyUrl = '';
    if (!options.configure && typeof saved.protectedProxy === 'string') {
        try { proxyUrl = protect(saved.protectedProxy, 'unprotect'); parsePhoneUrl(proxyUrl); }
        catch { proxyUrl = ''; console.log('上次连接设置不可用，需要重新粘贴手机连接地址。'); }
    }
    const prompt = readline.createInterface({ input: stdin, output: stdout });
    try {
        if (!isTavern(tavernDirectory)) {
            tavernDirectory = path.resolve((await prompt.question('请输入已有 SillyTavern 的目录：')).trim().replace(/^"|"$/g, ''));
        }
        if (!isTavern(tavernDirectory)) throw new Error('目录中没有找到 server.js 和 package.json。');
        if (!fs.existsSync(path.join(tavernDirectory, 'node_modules'))) {
            throw new Error('酒馆依赖尚未安装。请先在有网络时完成酒馆安装；此启动器不会自动运行 npm install。');
        }
        if (!proxyUrl || options.configure) {
            proxyUrl = (await prompt.question('先在手机开启“手机流量中转”，然后粘贴“电脑连接地址”：\n')).trim();
        }
    } finally { prompt.close(); }
    const phone = parsePhoneUrl(proxyUrl);
    let SocksClient;
    try { ({ SocksClient } = createRequire(path.join(tavernDirectory, 'package.json'))('socks')); }
    catch { throw new Error('酒馆缺少 socks 依赖，请先完成当前酒馆版本的依赖安装。'); }
    console.log('正在检查手机中转连接……');
    try { await probePhone(phone); }
    catch { throw new Error('无法连接手机中转：请确认手机和电脑在同一局域网、手机中转显示就绪。若手机 IP 变化，请加 --configure 重新配置。'); }
    const configuration = JSON.stringify({ version: 1, tavernDirectory,
        protectedProxy: protect(phone.url, 'protect') }, null, 2) + '\n';
    fs.writeFileSync(configPath + '.tmp', configuration, { mode: 0o600 });
    fs.renameSync(configPath + '.tmp', configPath);
    if (options.check) { console.log('手机配对检查通过，设置已安全保存。未启动酒馆，也未调用外网 API。'); return; }
    const bridge = await createBridge({ phone, SocksClient });
    try {
        const env = buildEnvironment(process.env, bridge.url);
        env.JIUGUAN_RELAY_PRELOAD = '1';
        console.log('手机中转已连接。正在启动酒馆；请关闭之前单独启动的酒馆窗口，避免端口冲突。');
        console.log('酒馆的 HTTP/HTTPS、原生 fetch、Git HTTPS 和 npm 请求将通过手机；局域网资源仍直接连接。');
        console.log('保持此窗口和手机中转运行。Ctrl+C 可停止本次酒馆和中转，不改变系统代理或酒馆配置。\n');
        // Never put the phone password in the child argv, environment or SillyTavern's proxy log.
        const child = spawn(process.execPath, ['--use-env-proxy', '--import',
            pathToFileURL(path.join(folder, 'phone-relay-preload.mjs')).href, 'server.js'], {
            cwd: tavernDirectory, env, stdio: 'inherit', windowsHide: true,
        });
        let stopping = false;
        const stop = () => {
            if (stopping) return;
            stopping = true;
            child.kill('SIGTERM');
            void bridge.close();
        };
        process.once('SIGINT', stop); process.once('SIGTERM', stop);
        const exitCode = await new Promise(resolve => {
            child.once('error', () => { console.error('无法启动酒馆进程。'); resolve(1); });
            child.once('exit', code => resolve(code ?? (stopping ? 0 : 1)));
        });
        process.removeListener('SIGINT', stop); process.removeListener('SIGTERM', stop);
        process.exitCode = exitCode;
    } finally { await bridge.close(); }
}

main().catch(error => {
    console.error(`\n启动失败：${error.message}`);
    process.exitCode = 1;
});
