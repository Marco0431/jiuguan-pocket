// Starts an isolated copy of the real SillyTavern server. Never reads production config/data.
// Both model APIs and the phone SOCKS endpoint are local fixtures; no real model is called.
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { once } from 'node:events';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const option = name => { const i = process.argv.indexOf(name); return i < 0 ? undefined : process.argv[i + 1]; };
const tavern = path.resolve(option('--tavern') || process.env.JIUGUAN_TAVERN_DIR || path.resolve(project, '../st/SillyTavern'));
const requireTavern = createRequire(path.join(tavern, 'package.json'));
const yaml = requireTavern('yaml');
const openssl = option('--openssl') || process.env.OPENSSL || (process.platform === 'win32' && fs.existsSync('C:/Program Files/Git/usr/bin/openssl.exe') ? 'C:/Program Files/Git/usr/bin/openssl.exe' : 'openssl');
const isPrivate = value => /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(value);
const lan = option('--lan-address') || Object.values(os.networkInterfaces()).flat().find(entry => entry?.family === 'IPv4' && !entry.internal && isPrivate(entry.address))?.address;
if (!lan || !isPrivate(lan)) throw new Error('A local RFC1918 IPv4 interface is required for the unchanged plugin peer validation.');
fs.mkdirSync(path.join(project, 'dist'), { recursive: true });
const scratch = fs.mkdtempSync(path.join(project, 'dist', 'tavern-e2e-'));
const copy = path.join(scratch, 'SillyTavern');
const data = path.join(scratch, 'data');
const tlsDir = path.join(scratch, 'tls');
const token = crypto.randomBytes(32).toString('hex');
const apiKey = 'offline-fixture-api-key';
const basicPassword = crypto.randomBytes(32).toString('hex');
const serviceKeys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const clientId = crypto.randomUUID();
const requests = [], sockets = new Set();
let child, origin, phone, startupLog = '', completed = false, oauthMode = 'ok';
const track = socket => { sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket)); return socket; };
const collect = async stream => { const chunks = []; for await (const part of stream) chunks.push(part); return Buffer.concat(chunks); };
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const checkedRun = (command, args, cwd) => {
    const result = spawnSync(command, args, { cwd, windowsHide: true, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
    if (result.error || result.status !== 0) throw new Error(`Fixture setup failed (${path.basename(command)}): ${result.error?.message || result.stderr}`);
    return result.stdout;
};
const pass = text => console.log(`PASS ${text}`);

try {
    // Copy only tracked program files. Personal imports, credentials and chat data are excluded.
    const tracked = checkedRun('git', ['ls-files', '-z', '--', 'src', 'default', 'public', 'server.js', 'package.json', 'webpack.config.js'], tavern).split('\0').filter(Boolean);
    for (const relative of tracked) {
        const destination = path.resolve(copy, relative);
        assert(destination.startsWith(copy + path.sep));
        fs.mkdirSync(path.dirname(destination), { recursive: true });
        fs.copyFileSync(path.join(tavern, relative), destination);
    }
    fs.symlinkSync(path.join(tavern, 'node_modules'), path.join(copy, 'node_modules'), process.platform === 'win32' ? 'junction' : 'dir');
    fs.cpSync(path.join(project, 'server-plugin/jiuguan-relay'), path.join(copy, 'plugins/jiuguan-relay'), { recursive: true });
    fs.mkdirSync(data, { recursive: true });
    fs.mkdirSync(tlsDir, { recursive: true });
    checkedRun(openssl, ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '1', '-subj', '/CN=relay-target.invalid',
        '-addext', 'subjectAltName=DNS:relay-target.invalid,DNS:oauth2.googleapis.com,DNS:aiplatform.googleapis.com,DNS:us-central1-aiplatform.googleapis.com',
        '-keyout', path.join(tlsDir, 'key.pem'), '-out', path.join(tlsDir, 'cert.pem')], scratch);

    origin = https.createServer({ key: fs.readFileSync(path.join(tlsDir, 'key.pem')), cert: fs.readFileSync(path.join(tlsDir, 'cert.pem')) }, async (req, res) => {
        const raw = await collect(req), body = raw.length ? (req.url === '/token' ? Object.fromEntries(new URLSearchParams(raw.toString())) : JSON.parse(raw)) : null;
        requests.push({ path: req.url, body, authorization: req.headers.authorization, sni: req.socket.servername });
        if (req.url === '/token') {
            assert.equal(req.socket.servername, 'oauth2.googleapis.com');
            assert.equal(body.grant_type, 'urn:ietf:params:oauth:grant-type:jwt-bearer');
            const [header, payload, signature] = body.assertion.split('.');
            assert(crypto.verify('RSA-SHA256', Buffer.from(`${header}.${payload}`), serviceKeys.publicKey, Buffer.from(signature, 'base64url')));
            if (oauthMode === 'disconnect') { req.socket.destroy(); return; }
            if (oauthMode === 'reject') { res.writeHead(400, { 'content-type': 'application/json' }).end('{"error":"fixture_invalid_grant"}'); return; }
            res.writeHead(200, { 'content-type': 'application/json' }).end('{"access_token":"offline-fixture-oauth-token","token_type":"Bearer","expires_in":3600}'); return;
        }
        if (req.socket.servername === 'aiplatform.googleapis.com') {
            assert.equal(req.headers.authorization, 'Bearer offline-fixture-oauth-token');
            assert(req.url.startsWith('/v1/projects/offline-fixture-project/locations/global/publishers/google/models/'));
            if (req.url.includes(':streamGenerateContent')) {
                res.writeHead(200, { 'content-type': 'text/event-stream' });
                res.write('data: {"candidates":[{"content":{"parts":[{"text":"vertex first"}]}}]}\n\n');
                setTimeout(() => { if (!res.destroyed) res.end('data: {"candidates":[{"content":{"parts":[{"text":" vertex last"}]},"finishReason":"STOP"}]}\n\n'); }, 100);
            } else res.writeHead(200, { 'content-type': 'application/json' }).end('{"candidates":[{"content":{"role":"model","parts":[{"text":"vertex fixture complete"}]},"finishReason":"STOP"}]}');
            return;
        }
        if (req.headers.authorization !== `Bearer ${apiKey}`) { res.writeHead(401).end(); return; }
        if (req.url.startsWith('/bad/')) { res.writeHead(400, { 'content-type': 'application/json' }).end(JSON.stringify({ error: { message: 'fixture rejection' } })); return; }
        if (req.url === '/v1/models') { res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ data: [{ id: 'fixture-model', object: 'model' }] })); return; }
        if (req.url === '/v1/chat/completions' && body?.stream) {
            res.writeHead(200, { 'content-type': 'text/event-stream' });
            res.write('data: {"choices":[{"delta":{"content":"fixture first"}}]}\n\n');
            setTimeout(() => { if (!res.destroyed) res.end('data: {"choices":[{"delta":{"content":" fixture last"}}]}\n\ndata: [DONE]\n\n'); }, 100);
            return;
        }
        if (req.url === '/v1/chat/completions') { res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ choices: [{ message: { role: 'assistant', content: 'fixture complete' } }] })); return; }
        res.writeHead(404).end();
    });
    origin.on('connection', track); origin.listen(0, '127.0.0.1'); await once(origin, 'listening');
    const apiPort = origin.address().port;
    const javaHome = option('--java-home') || process.env.JAVA_HOME || fs.readdirSync(path.resolve(project, '../.android-tools/jdk'), { withFileTypes: true }).filter(item => item.isDirectory()).map(item => path.resolve(project, '../.android-tools/jdk', item.name)).find(item => fs.existsSync(path.join(item, 'bin/java.exe')));
    if (!javaHome) throw new Error('Provide --java-home or JAVA_HOME for the real Android SOCKS Java core.');
    const javaBinary = name => path.join(javaHome, 'bin', name + (process.platform === 'win32' ? '.exe' : ''));
    const classes = path.join(scratch, 'classes'); fs.mkdirSync(classes);
    checkedRun(javaBinary('javac'), ['-encoding', 'UTF-8', '-d', classes, path.join(project, 'app/src/main/java/com/jiuguan/client/SocksProxyServer.java'), path.join(project, 'tools/RelayIntegrationFixture.java')], project);
    phone = spawn(javaBinary('java'), ['-cp', classes, 'RelayIntegrationFixture', String(apiPort), String(apiPort), lan, '10808', 'jiuguan', token, 'vertex-fixtures'], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let phoneOutput = '', phoneErrors = '';
    phone.stdout.on('data', part => { phoneOutput += part; }); phone.stderr.on('data', part => { phoneErrors += part; });
    const phoneDeadline = Date.now() + 10_000;
    while (!phoneOutput.includes('READY 10808')) {
        if (phone.exitCode !== null || Date.now() > phoneDeadline) throw new Error('Java SOCKS fixture unavailable: ' + phoneErrors.replaceAll(token, '[redacted]'));
        await pause(50);
    }
    const reservation = net.createServer(); reservation.listen(0, '127.0.0.1'); await once(reservation, 'listening');
    const port = reservation.address().port; await new Promise(resolve => reservation.close(resolve));
    const config = yaml.parse(fs.readFileSync(path.join(copy, 'default/config.yaml'), 'utf8'));
    Object.assign(config, { dataRoot: data, listen: true, port, listenAddress: { ipv4: lan, ipv6: '[::1]' }, whitelistMode: true,
        whitelist: [lan, '127.0.0.1'], basicAuthMode: true, basicAuthUser: { username: 'fixture', password: basicPassword }, enableUserAccounts: true,
        enableServerPlugins: true, enableServerPluginsAutoUpdate: false, skipContentCheck: true });
    config.browserLaunch = { ...config.browserLaunch, enabled: false };
    config.protocol = { ipv4: true, ipv6: false };
    config.requestProxy = { enabled: false, url: '', bypass: [] };
    config.extensions = { ...config.extensions, enabled: false, autoUpdate: false, models: { autoDownload: false } };
    config.logging = { enableAccessLog: false, minLogLevel: 2 };
    const configFile = path.join(scratch, 'config.yaml'); fs.writeFileSync(configFile, yaml.stringify(config));
    const environment = { ...process.env, NODE_EXTRA_CA_CERTS: path.join(tlsDir, 'cert.pem') };
    for (const key of Object.keys(environment)) if (/^(?:http|https|all|no)_proxy$/i.test(key) || key.startsWith('SILLYTAVERN_')) delete environment[key];
    child = spawn(process.execPath, ['server.js', '--configPath', configFile, '--dataRoot', data, '--port', String(port)], { cwd: copy, env: environment, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    for (const stream of [child.stdout, child.stderr]) stream.on('data', part => { startupLog = (startupLog + part).slice(-30_000); });
    const base = `http://${lan}:${port}`, jar = new Map(); let csrf;
    const request = (route, body, { token: suppliedToken = csrf, localAddress = lan } = {}) => new Promise((resolve, reject) => {
        const payload = body === undefined ? null : Buffer.from(JSON.stringify(body));
        const req = http.request(base + route, { method: payload ? 'POST' : 'GET', localAddress, agent: false, headers: {
            Origin: base, Authorization: 'Basic ' + Buffer.from(`fixture:${basicPassword}`).toString('base64'), Cookie: [...jar].map(([key, value]) => `${key}=${value}`).join('; '),
            ...(suppliedToken ? { 'x-csrf-token': suppliedToken } : {}), ...(payload ? { 'content-type': 'application/json', 'content-length': payload.length } : {}),
        } }, res => {
            for (const item of res.headers['set-cookie'] || []) { const first = item.split(';')[0], equal = first.indexOf('='); jar.set(first.slice(0, equal), first.slice(equal + 1)); }
            const chunks = [], arrivals = [];
            res.on('data', part => { chunks.push(part); arrivals.push(Date.now()); });
            res.on('error', reject);
            res.on('end', () => { const text = Buffer.concat(chunks).toString(); let json; try { json = JSON.parse(text); } catch {} resolve({ status: res.statusCode, json, text, arrivals }); });
        });
        req.setTimeout(15_000, () => req.destroy(new Error('Fixture request timeout'))); req.on('error', reject); req.end(payload);
    });
    const deadline = Date.now() + 120_000;
    while (true) {
        if (child.exitCode !== null) throw new Error('Isolated SillyTavern exited before listening.');
        try { const response = await request('/csrf-token'); if (response.status === 200) { csrf = response.json.token; break; } } catch {}
        if (Date.now() > deadline) throw new Error('Isolated SillyTavern startup exceeded 120 seconds.');
        await pause(300);
    }
    assert.equal((await request('/api/plugins/jiuguan-relay/diagnostics')).status, 403);
    assert.equal((await request('/api/users/login', { handle: 'default-user', password: '' })).status, 200);
    csrf = (await request('/csrf-token')).json.token;
    const status = await request('/api/plugins/jiuguan-relay/status'); assert.equal(status.status, 200); assert.equal(status.json.supported, true, JSON.stringify(status.json));
    const enableBody = { clientId, proxyUrl: `socks5h://jiuguan:${token}@${lan}:10808` };
    assert.equal((await request('/api/plugins/jiuguan-relay/enable', enableBody, { token: 'invalid' })).status, 403);
    const enabled = await request('/api/plugins/jiuguan-relay/enable', enableBody); assert.equal(enabled.status, 200, enabled.text); assert.equal(enabled.json.mode, 'mobile');
    pass('real server.js + account login + CSRF rejection + authenticated phone pairing, without modified middleware');
    const diagnostic = async () => (await request('/api/plugins/jiuguan-relay/diagnostics')).json;
    const beforeMissingKey = await diagnostic();
    const missingKey = await request('/api/backends/chat-completions/status', { chat_completion_source: 'openai' });
    assert.equal(missingKey.status, 400); assert.equal(missingKey.json.error, true);
    const afterMissingKey = await diagnostic(); assert.deepEqual(afterMissingKey.bridge, beforeMissingKey.bridge); assert.deepEqual(afterMissingKey.outbound, beforeMissingKey.outbound);
    pass('real /status with missing key returns local HTTP 400 and no outbound/bridge request');
    assert.equal((await request('/api/secrets/write', { key: 'api_key_custom', value: apiKey, label: 'isolated fixture only' })).status, 200);
    const common = { chat_completion_source: 'custom', custom_url: `https://relay-target.invalid:${apiPort}/v1` };
    const models = await request('/api/backends/chat-completions/status', common);
    assert.equal(models.status, 200); assert.equal(models.json.data[0].id, 'fixture-model');
    assert.equal(requests.at(-1).authorization, `Bearer ${apiKey}`);
    pass('real model-list endpoint forwards isolated saved key through plugin → SOCKS → verified TLS API');
    const generation = { ...common, model: 'fixture-model', messages: [{ role: 'user', content: 'offline integration fixture' }], max_tokens: 8, stream: false };
    const generated = await request('/api/backends/chat-completions/generate', generation);
    assert.equal(generated.status, 200); assert.equal(generated.json.choices[0].message.content, 'fixture complete');
    assert.deepEqual(requests.at(-1).body.messages, generation.messages);
    pass('real non-streaming generation preserves model/messages and returns API response');
    const streamed = await request('/api/backends/chat-completions/generate', { ...generation, stream: true });
    assert.equal(streamed.status, 200); assert(streamed.text.includes('fixture first')); assert(streamed.text.includes('fixture last')); assert(streamed.text.includes('[DONE]'));
    assert(streamed.arrivals.length >= 2 && streamed.arrivals.at(-1) - streamed.arrivals[0] >= 50);
    pass('real streaming generation forwards delayed SSE chunks without buffering to completion');
    const badModels = await request('/api/backends/chat-completions/status', { ...common, custom_url: `https://relay-target.invalid:${apiPort}/bad` });
    assert.equal(badModels.status, 200); assert.equal(badModels.json.error, true);
    const beforeBadGenerate = await diagnostic();
    const badGenerate = await request('/api/backends/chat-completions/generate', { ...generation, custom_url: `https://relay-target.invalid:${apiPort}/bad` });
    assert.equal(badGenerate.status, 200); assert.equal(badGenerate.json.error.message, 'Bad Request');
    const afterBadGenerate = await diagnostic();
    assert.equal(afterBadGenerate.outbound.nodeHttp.last.status, 400); assert.equal(afterBadGenerate.outbound.nodeHttp.last.phase, 'api_response');
    assert(afterBadGenerate.bridge.tunnelsEstablished >= 1); assert.equal(afterBadGenerate.bridge.failures, 0);
    assert.equal(afterBadGenerate.outbound.nodeHttp.requests, beforeBadGenerate.outbound.nodeHttp.requests + 1);
    assert.equal(afterBadGenerate.outbound.nodeHttp.responses, beforeBadGenerate.outbound.nodeHttp.responses + 1);
    pass('upstream 400: real /status returns 200+error; real /generate returns 200+"Bad Request" while diagnostics show API 400 and successful tunnel');
    assert((phoneOutput.match(/CONNECT relay-target.invalid /g) || []).length >= 1);
    assert.equal(requests.length, 5); // Keep-alive may carry all five HTTP requests on one TCP tunnel.
    assert(requests.every(item => item.sni === 'relay-target.invalid'));
    const printable = JSON.stringify(afterBadGenerate);
    for (const secret of [token, apiKey, '/v1', '/bad', 'offline integration fixture']) assert(!printable.includes(secret));
    pass('remote DNS hostname/SNI retained; diagnosis excludes fixture API key, phone credential, paths and prompt');
    const vertexRequest = { chat_completion_source: 'vertexai', vertexai_auth_mode: 'full', vertexai_region: 'global', model: 'gemini-3.8-flash', messages: generation.messages, max_tokens: 8, stream: false };
    const serviceAccount = { type: 'service_account', project_id: 'offline-fixture-project', client_email: 'fixture@offline-fixture-project.iam.gserviceaccount.com', private_key: serviceKeys.privateKey.export({ type: 'pkcs8', format: 'pem' }) };
    assert.equal((await request('/api/secrets/write', { key: 'vertexai_service_account_json', value: JSON.stringify(serviceAccount), label: 'isolated fixture only' })).status, 200);
    for (const mode of ['reject', 'disconnect']) {
        oauthMode = mode;
        const before = await diagnostic(), modelCalls = requests.filter(item => item.sni === 'aiplatform.googleapis.com').length;
        const rejected = await request('/api/backends/chat-completions/generate', vertexRequest);
        assert.equal(rejected.status, 400); assert.equal(rejected.json.error, true); assert(rejected.json.message.includes('Service account authentication failed'));
        const after = await diagnostic();
        assert.equal(after.outbound.nodeHttp.last.hostname, 'oauth2.googleapis.com');
        assert.equal(after.outbound.nodeHttp.last.phase, mode === 'reject' ? 'api_response' : 'request_failed');
        assert.equal(after.outbound.nodeHttp.last.status, mode === 'reject' ? 400 : null);
        assert(after.bridge.tunnelsEstablished >= before.bridge.tunnelsEstablished);
        assert.equal(after.bridge.failures, before.bridge.failures);
        assert.equal(after.outbound.nodeHttp.requests, before.outbound.nodeHttp.requests + 1);
        assert.equal(requests.filter(item => item.sni === 'aiplatform.googleapis.com').length, modelCalls);
        pass(`real Vertex full/global ${mode === 'reject' ? 'OAuth HTTP rejection' : 'OAuth transport disconnect'} becomes local HTTP 400 before model call`);
    }
    oauthMode = 'ok';
    const beforeVertex = await diagnostic();
    const vertex = await request('/api/backends/chat-completions/generate', vertexRequest);
    assert.equal(vertex.status, 200, vertex.text); assert.equal(vertex.json.choices[0].message.content, 'vertex fixture complete');
    const afterVertex = await diagnostic();
    assert(afterVertex.bridge.tunnelsEstablished >= beforeVertex.bridge.tunnelsEstablished);
    assert.equal(afterVertex.bridge.failures, beforeVertex.bridge.failures);
    assert.equal(afterVertex.outbound.nodeHttp.requests, beforeVertex.outbound.nodeHttp.requests + 2);
    assert.equal(afterVertex.outbound.nodeHttp.responses, beforeVertex.outbound.nodeHttp.responses + 2);
    assert.equal(afterVertex.outbound.nodeHttp.last.hostname, 'aiplatform.googleapis.com');
    assert.equal(afterVertex.outbound.nodeHttp.last.status, 200);
    pass('real Vertex full/global RSA JWT → OAuth token → model response all pass through current plugin and actual Android Java SOCKS core');
    const vertexStream = await request('/api/backends/chat-completions/generate', { ...vertexRequest, stream: true });
    assert.equal(vertexStream.status, 200); assert(vertexStream.text.includes('vertex first') && vertexStream.text.includes('vertex last'));
    assert(vertexStream.arrivals.length >= 2 && vertexStream.arrivals.at(-1) - vertexStream.arrivals[0] >= 50);
    pass('real Vertex streaming route forwards delayed Google SSE after OAuth through Java SOCKS');
    assert.equal((await request('/api/plugins/jiuguan-relay/disable', { clientId })).status, 200);
    assert.equal((await diagnostic()).routeMode, 'normal');
    const beforeDirect = requests.length;
    const direct = await request('/api/backends/chat-completions/status', common);
    assert.equal(direct.json.error, true); assert.equal(requests.length, beforeDirect);
    pass('negative control: disabling phone route cannot reach the fixture-only .invalid API');
    completed = true;
} catch (error) {
    console.error(`FAIL ${error.stack || error.message}`);
    console.error('Isolated server output (fixture data only):\n' + startupLog.slice(-9000));
    process.exitCode = 1;
} finally {
    if (child && child.exitCode === null) { child.kill(); await Promise.race([once(child, 'exit'), pause(5000)]); }
    for (const socket of sockets) socket.destroy();
    if (phone && phone.exitCode === null) { phone.stdin.end('\n'); await Promise.race([once(phone, 'exit'), pause(3000)]); if (phone.exitCode === null) phone.kill(); }
    if (origin?.listening) await new Promise(resolve => origin.close(resolve));
    // Remove the dependency junction first; recursive cleanup is bounded to the generated workspace directory.
    const nodeModules = path.join(copy, 'node_modules');
    if (fs.existsSync(nodeModules) && fs.lstatSync(nodeModules).isSymbolicLink()) fs.unlinkSync(nodeModules);
    const expectedParent = path.resolve(project, 'dist');
    assert(path.dirname(scratch) === expectedParent && path.basename(scratch).startsWith('tavern-e2e-'));
    fs.rmSync(scratch, { recursive: true, force: true, maxRetries: 3 });
    if (completed) pass('isolated server and fixture listeners stopped; temporary account/data/config removed');
}
