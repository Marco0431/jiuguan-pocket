// Offline end-to-end smoke: ST's real proxy-agent -> Java SOCKS -> loopback HTTP/TLS fixtures.
// Requires local JDK 17+, Node, OpenSSL and an installed SillyTavern node_modules. No AI API calls.
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
import { createBridge } from './phone-relay-bridge.mjs';
import { installConnectProxyAgents } from './phone-relay-preload.mjs';

const folder = path.dirname(fileURLToPath(import.meta.url));
const project = path.dirname(folder);
const args = process.argv.slice(2);
const option = (name, fallback) => {
    const index = args.indexOf(name);
    return index < 0 ? fallback : args[index + 1];
};
const javaHome = option('--java-home', process.env.JAVA_HOME);
const tavern = path.resolve(option('--tavern', path.join(project, '../st/SillyTavern')));
const openssl = option('--openssl', 'openssl');
assert(javaHome, 'Set JAVA_HOME or pass --java-home.');
const executable = name => path.join(javaHome, 'bin', name + (process.platform === 'win32' ? '.exe' : ''));
const requireTavern = createRequire(path.join(tavern, 'package.json'));
const { ProxyAgent } = requireTavern('proxy-agent');
const { SocksClient } = requireTavern('socks');
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'jiuguan-relay-smoke-'));
const certPath = path.join(scratch, 'cert.pem');
const keyPath = path.join(scratch, 'key.pem');
const servers = [];
const acceptedSockets = new Set();
let java;
let bridge;
let stage = 'fixture startup';
let fixtureOutput = '';
const originEvents = [];

function run(command, argv) {
    const result = spawnSync(command, argv, { encoding: 'utf8', windowsHide: true, timeout: 30_000 });
    if (result.error || result.status !== 0)
        throw new Error(`${path.basename(command)} failed: ${result.error?.message || result.stderr || result.stdout}`);
}

const body = Buffer.from('streamed offline request\n'.repeat(20_000));
const expected = `length=${body.length}\nsha256=${crypto.createHash('sha256').update(body).digest('hex')}\ndone\n`;

function serve(request, response) {
    originEvents.push(`${request.socket.encrypted ? 'https' : 'http'} request`);
    assert.equal(request.url, '/stream?q=offline');
    assert.equal(request.headers['proxy-authorization'], undefined, 'Proxy credentials leaked to origin');
    const chunks = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('aborted', () => originEvents.push('request aborted'));
    request.on('error', error => originEvents.push(`request error ${error.code}`));
    request.on('end', () => {
        const received = Buffer.concat(chunks);
        originEvents.push(`request end ${received.length}`);
        const lines = [`length=${received.length}\n`,
            `sha256=${crypto.createHash('sha256').update(received).digest('hex')}\n`, 'done\n'];
        response.writeHead(200, { 'Content-Type': 'text/plain', 'Connection': 'close' });
        let index = 0;
        const write = () => {
            response.write(lines[index++]);
            if (index < lines.length) setTimeout(write, 40);
            else response.end();
        };
        write();
    });
}

async function listen(server) {
    servers.push(server);
    server.on('connection', socket => {
        acceptedSockets.add(socket);
        socket.once('close', () => acceptedSockets.delete(socket));
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    return server.address().port;
}

async function request(protocol, port, proxyUrl, ca) {
    const agent = new ProxyAgent({ getProxyForUrl: () => proxyUrl });
    try {
        return await new Promise((resolve, reject) => {
            const client = protocol === 'https:' ? https : http;
            const req = client.request({ protocol, hostname: 'relay-target.invalid', port,
                path: '/stream?q=offline', method: 'POST', agent, ca }, res => {
                const chunks = [];
                res.on('data', chunk => chunks.push(chunk));
                res.on('error', reject);
                res.on('end', () => {
                    try {
                        assert.equal(res.statusCode, 200);
                        assert.equal(Buffer.concat(chunks).toString(), expected);
                        assert(chunks.length >= 3, 'Response was buffered instead of streamed');
                        resolve();
                    } catch (error) { reject(error); }
                });
            });
            req.on('error', reject);
            req.setTimeout(10_000, () => req.destroy(new Error('Offline request timed out')));
            req.write(body.subarray(0, 12345));
            setTimeout(() => req.end(body.subarray(12345)), 15);
        });
    } finally { agent.destroy(); }
}

try {
    const opensslConfig = path.join(scratch, 'openssl.cnf');
    fs.writeFileSync(opensslConfig, '[req]\ndistinguished_name=dn\n[dn]\n');
    run(openssl, ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', keyPath, '-out', certPath,
        '-config', opensslConfig, '-days', '1', '-subj', '/CN=relay-target.invalid',
        '-addext', 'subjectAltName=DNS:relay-target.invalid']);
    const ca = fs.readFileSync(certPath);
    const httpPort = await listen(http.createServer(serve));
    const httpsPort = await listen(https.createServer({ key: fs.readFileSync(keyPath), cert: ca }, serve));
    run(executable('javac'), ['-encoding', 'UTF-8', '-d', scratch,
        path.join(project, 'app/src/main/java/com/jiuguan/client/SocksProxyServer.java'),
        path.join(folder, 'RelayIntegrationFixture.java')]);
    java = spawn(executable('java'), ['-cp', scratch, 'RelayIntegrationFixture', String(httpPort), String(httpsPort)],
        { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    let errors = '';
    java.stdout.on('data', bytes => { fixtureOutput += bytes; });
    java.stderr.on('data', bytes => { errors += bytes; });
    const proxyPort = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Java fixture startup timed out')), 10_000);
        java.on('error', error => { clearTimeout(timer); reject(error); });
        java.once('exit', code => {
            clearTimeout(timer);
            if (!/READY (\d+)/.test(fixtureOutput)) reject(new Error(`Java fixture exited: ${code}; ${errors}`));
        });
        java.stdout.on('data', () => {
            const match = /READY (\d+)/.exec(fixtureOutput);
            if (match) { clearTimeout(timer); resolve(+match[1]); }
        });
    });
    const phone = { host: '127.0.0.1', port: proxyPort, type: 5, userId: 'smoke', password: 'offline-test' };
    const socksUrl = `socks5h://smoke:offline-test@127.0.0.1:${proxyPort}`;
    stage = 'direct SOCKS HTTP';
    await request('http:', httpPort, socksUrl, ca);
    stage = 'direct SOCKS HTTPS';
    await request('https:', httpsPort, socksUrl, ca);
    console.log('PASS actual ST proxy-agent -> Java SOCKS: HTTP + verified HTTPS streamed POST/response');

    // Match the launcher's Node 25-compatible agent setup for streaming HTTP bodies.
    installConnectProxyAgents(tavern);
    bridge = await createBridge({ phone, SocksClient });
    stage = 'PC HTTP bridge';
    await request('http:', httpPort, bridge.url, ca);
    stage = 'PC CONNECT bridge';
    await request('https:', httpsPort, bridge.url, ca);
    console.log('PASS PC HTTP/CONNECT bridge -> Java SOCKS: HTTP + verified HTTPS streamed POST/response');

    assert.equal((fixtureOutput.match(/CONNECT relay-target\.invalid /g) || []).length, 4,
        'Hostname was not handed unchanged to the relay');
    console.log('PASS all four requests hand .invalid hostname to relay; no PC public DNS resolution');
    const exited = once(java, 'close');
    java.stdin.end();
    const [exitCode] = await exited;
    assert.equal(exitCode, 0, errors);
    const totals = /TOTAL (\d+) (\d+)/.exec(fixtureOutput);
    assert(totals && +totals[1] > body.length * 4 && +totals[2] > 0, 'Missing wire byte counters');
    console.log('PASS byte counters and explicit Java relay shutdown');
} catch (error) {
    console.error(`FAIL at ${stage}; offline fixture events:\n${fixtureOutput}\n${originEvents.join('\n')}`);
    throw error;
} finally {
    if (java && java.exitCode === null) java.kill();
    if (bridge) await bridge.close();
    for (const socket of acceptedSockets) socket.destroy();
    await Promise.all(servers.map(server => new Promise(resolve => server.close(resolve))));
    // Only this tool's newly created OS temporary directory is removed.
    if (path.dirname(path.resolve(scratch)) !== path.resolve(os.tmpdir())
        || !path.basename(scratch).startsWith('jiuguan-relay-smoke-'))
        throw new Error('Unexpected temporary cleanup path');
    fs.rmSync(scratch, { recursive: true, force: true });
}
