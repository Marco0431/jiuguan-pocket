import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import fs from 'node:fs';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
import { buildEnvironment, createBridge, isLocalDestination, parsePhoneUrl, probePhone, supportsProxyNode } from './phone-relay-bridge.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const candidates = [process.env.JIUGUAN_TAVERN_DIR, process.cwd()];
for (let ancestor = here, i = 0; i < 6; i++, ancestor = path.dirname(ancestor)) {
    candidates.push(path.join(ancestor, 'st/SillyTavern'), path.join(ancestor, 'SillyTavern'));
}
const tavern = candidates.find(candidate => candidate && fs.existsSync(path.join(candidate, 'node_modules/socks/package.json')));
if (!tavern) throw new Error('找不到已安装依赖的酒馆目录；请设置 JIUGUAN_TAVERN_DIR 后运行测试。');
const { SocksClient } = createRequire(path.join(tavern, 'package.json'))('socks');
const password = '1'.repeat(64);

async function listen(server) {
    server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port;
}

function collect(stream) {
    return new Promise((resolve, reject) => {
        const chunks = []; stream.on('data', x => chunks.push(x));
        stream.on('error', reject); stream.on('end', () => resolve(Buffer.concat(chunks)));
    });
}

async function mockPhone() {
    const sockets = new Set(), destinations = [];
    const server = net.createServer({ allowHalfOpen: true }, async client => {
        sockets.add(client); client.once('close', () => sockets.delete(client));
        client.on('error', () => {});
        let pending = Buffer.alloc(0), waiting;
        const onData = chunk => { pending = Buffer.concat([pending, chunk]); waiting?.(); };
        let ended = false;
        client.on('data', onData);
        client.on('close', () => { ended = true; waiting?.(); });
        const read = async count => {
            while (pending.length < count) {
                if (ended) throw new Error('Closed');
                await new Promise(resolve => { waiting = resolve; });
            }
            const value = pending.subarray(0, count); pending = pending.subarray(count); return value;
        };
        try {
            const hello = await read(2); await read(hello[1]);
            client.write(Buffer.from([5, 2]));
            const auth = await read(2); const username = (await read(auth[1])).toString();
            const plen = (await read(1))[0], pass = (await read(plen)).toString();
            const valid = username === 'jiuguan' && pass === password;
            client.write(Buffer.from([1, valid ? 0 : 1]));
            if (!valid) { client.end(); return; }
            const request = await read(4); assert.equal(request[1], 1);
            let host;
            if (request[3] === 3) host = (await read((await read(1))[0])).toString();
            else if (request[3] === 1) host = [...await read(4)].join('.');
            else throw new Error('Unsupported mock address');
            const port = (await read(2)).readUInt16BE(); destinations.push({ host, port });
            const upstream = net.createConnection({ host: '127.0.0.1', port, allowHalfOpen: true });
            sockets.add(upstream); upstream.once('close', () => sockets.delete(upstream));
            upstream.on('error', () => client.destroy());
            await once(upstream, 'connect');
            client.pause(); client.removeListener('data', onData);
            client.write(Buffer.from([5, 0, 0, 1, 127, 0, 0, 1, 0, 0]));
            if (pending.length) upstream.write(pending);
            client.pipe(upstream); upstream.pipe(client); client.resume();
            client.on('close', () => upstream.destroy()); upstream.on('close', () => client.destroy());
        } catch { client.destroy(); }
    });
    const port = await listen(server);
    return { phone: { host: '127.0.0.1', port, type: 5, userId: 'jiuguan', password }, destinations,
        async close() { for (const socket of sockets) socket.destroy(); await new Promise(r => server.close(r)); } };
}

async function childNode(code, env) {
    const child = spawn(process.execPath, ['--use-env-proxy', '--import',
        pathToFileURL(path.join(here, 'phone-relay-preload.mjs')).href, '--input-type=module', '-e', code], {
        cwd: tavern, env: { ...env, JIUGUAN_RELAY_PRELOAD: '1' }, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
    });
    const output = collect(child.stdout), errors = collect(child.stderr);
    const [exit] = await once(child, 'exit');
    const result = (await output).toString(), stderr = (await errors).toString();
    assert.equal(exit, 0, stderr); return result;
}

test('phone URL accepts only the app LAN format and Node support is explicit', () => {
    const value = `socks5h://jiuguan:${password}@192.168.1.5:10808`;
    assert.equal(parsePhoneUrl(value).url, value);
    for (const bad of [value.replace('socks5h:', 'http:'), value.replace('192.168.1.5', '8.8.8.8'),
        value.replace('192.168.1.5', 'phone.local'), value + '?x=1', value.replace(password, 'short'), value + '/path']) {
        assert.throws(() => parsePhoneUrl(bad));
    }
    assert.equal(supportsProxyNode('24.4.0'), false); assert.equal(supportsProxyNode('24.5.0'), true);
    assert.equal(supportsProxyNode('25.4.0'), true);
    assert.equal(isLocalDestination('192.168.2.3'), true);
    assert.equal(isLocalDestination('unresolvable.invalid'), false);
    assert.equal(isLocalDestination('127.0.0.1.evil.invalid'), false);
});

test('launch environment replaces conflicting proxies and covers Git and npm without mutating parent', () => {
    const base = { HTTPS_PROXY: 'http://old', http_proxy: 'http://other', NO_PROXY: '*',
        npm_config_no_proxy: '*', GIT_CONFIG_COUNT: '1', GIT_CONFIG_KEY_0: 'core.autocrlf', GIT_CONFIG_VALUE_0: 'false' };
    const env = buildEnvironment(base, 'http://127.0.0.1:12345');
    assert.equal(base.NO_PROXY, '*'); assert.equal(env.http_proxy, undefined);
    assert.equal(env.HTTPS_PROXY, env.npm_config_proxy);
    assert.equal(env.SILLYTAVERN_REQUESTPROXY_URL, env.HTTPS_PROXY);
    assert.equal(env.NODE_USE_ENV_PROXY, '1'); assert.equal(env.GIT_CONFIG_COUNT, '2');
    assert.equal(env.GIT_CONFIG_KEY_1, 'http.proxy'); assert.equal(env.GIT_CONFIG_VALUE_1, env.HTTPS_PROXY);
    assert.notEqual(env.NO_PROXY, '*'); assert.equal(env.npm_config_no_proxy, undefined);
});

test('phone preflight authenticates without an upstream request and rejects wrong credentials', async t => {
    const mock = await mockPhone(); t.after(() => mock.close());
    await probePhone(mock.phone); assert.equal(mock.destinations.length, 0);
    await assert.rejects(probePhone({ ...mock.phone, password: '0'.repeat(64) }));
    assert.equal(mock.destinations.length, 0);
});

test('native fetch, Node HTTP and ST node-fetch stream through SOCKS with phone-side DNS', async t => {
    const requests = [];
    const origin = http.createServer((req, res) => {
        requests.push({ path: req.url, proxyAuth: req.headers['proxy-authorization'] });
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.write('first\n'); setTimeout(() => res.end('second\n'), 25);
    });
    const port = await listen(origin); t.after(() => new Promise(r => origin.close(r)));
    const mock = await mockPhone(); t.after(() => mock.close());
    const bridge = await createBridge({ phone: mock.phone, SocksClient }); t.after(() => bridge.close());
    const env = buildEnvironment(process.env, bridge.url);
    const target = `http://does-not-resolve.invalid:${port}/stream`;
    const output = await childNode(`
        import http from 'node:http';
        console.log(await (await fetch(${JSON.stringify(target)})).text());
        console.log(await new Promise((resolve,reject)=>http.get(${JSON.stringify(target)}, r=>{let s='';r.on('data',b=>s+=b);r.on('end',()=>resolve(s));}).on('error',reject)));
        const {ProxyAgent}=await import('proxy-agent');
        const {default:nodeFetch}=await import('node-fetch');
        console.log(await (await nodeFetch(${JSON.stringify(target)}, {agent:new ProxyAgent()})).text());
    `, env);
    assert.equal((output.match(/second/g) || []).length, 3);
    assert.equal(requests.length, 3); assert.ok(requests.every(x => x.proxyAuth === undefined));
    assert.equal(mock.destinations.length, 3);
    assert.ok(mock.destinations.every(x => x.host === 'does-not-resolve.invalid'));
});

test('CONNECT forwards raw bytes and closing bridge closes an active tunnel', async t => {
    const echo = net.createServer(socket => { socket.on('error', () => {}); socket.pipe(socket); });
    const port = await listen(echo); t.after(() => new Promise(r => echo.close(r)));
    const mock = await mockPhone(); t.after(() => mock.close());
    const bridge = await createBridge({ phone: mock.phone, SocksClient }); t.after(() => bridge.close());
    const proxy = new URL(bridge.url);
    const client = net.createConnection({ host: proxy.hostname, port: +proxy.port });
    client.on('error', () => {}); t.after(() => client.destroy());
    await once(client, 'connect');
    const head = once(client, 'data');
    client.write(`CONNECT fake-api.invalid:${port} HTTP/1.1\r\nHost: fake-api.invalid:${port}\r\n\r\n`);
    assert.match((await head)[0].toString(), /200 Connection Established/);
    const bytes = Buffer.from([0, 1, 255, 200, 10]); const reply = once(client, 'data');
    client.write(bytes); assert.deepEqual((await reply)[0], bytes);
    assert.equal(mock.destinations[0].host, 'fake-api.invalid');
    const ended = once(client, 'close'); await bridge.close(); await ended;
});

test('phone failure returns 502 and never falls back to the computer internet', async t => {
    let attempts = 0;
    const bridge = await createBridge({ connector: async () => { attempts++; throw new Error('offline'); } });
    t.after(() => bridge.close());
    const u = new URL(bridge.url);
    const response = await new Promise((resolve, reject) => {
        http.get({ host: u.hostname, port: u.port, path: 'http://does-not-resolve.invalid/test', agent: false },
            r => { r.resume(); resolve(r.statusCode); }).on('error', reject);
    });
    assert.equal(response, 502); assert.equal(attempts, 1);
});

test('HTTP uploads preserve data and never forward proxy credentials', async t => {
    const payload = Buffer.alloc(256 * 1024, 173);
    let received, leaked;
    const origin = http.createServer(async (req, res) => {
        leaked = req.headers['proxy-authorization']; received = await collect(req);
        res.writeHead(200).end('saved');
    });
    const port = await listen(origin); t.after(() => new Promise(r => origin.close(r)));
    const mock = await mockPhone(); t.after(() => mock.close());
    const bridge = await createBridge({ phone: mock.phone, SocksClient }); t.after(() => bridge.close());
    const u = new URL(bridge.url);
    const response = await new Promise((resolve, reject) => {
        const req = http.request({ host: u.hostname, port: u.port, method: 'POST', agent: false,
            path: `http://upload.invalid:${port}/file`, headers: {
                'Content-Length': payload.length, 'Proxy-Authorization': 'Basic do-not-forward',
            } }, async r => resolve((await collect(r)).toString()));
        req.on('error', reject); req.end(payload);
    });
    assert.equal(response, 'saved'); assert.deepEqual(received, payload); assert.equal(leaked, undefined);
});

test('WebSocket upgrade streams through the phone proxy', async t => {
    const originSockets = new Set();
    const origin = http.createServer();
    origin.on('upgrade', (_req, socket) => {
        originSockets.add(socket); socket.on('close', () => originSockets.delete(socket));
        socket.on('error', () => {});
        socket.write('HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n');
        socket.pipe(socket);
    });
    const port = await listen(origin);
    t.after(async () => { for (const s of originSockets) s.destroy(); await new Promise(r => origin.close(r)); });
    const mock = await mockPhone(); t.after(() => mock.close());
    const bridge = await createBridge({ phone: mock.phone, SocksClient }); t.after(() => bridge.close());
    const u = new URL(bridge.url), client = net.createConnection({ host: u.hostname, port: +u.port });
    client.on('error', () => {}); t.after(() => client.destroy()); await once(client, 'connect');
    const response = once(client, 'data');
    client.write(`GET http://ws.invalid:${port}/socket HTTP/1.1\r\nHost: ws.invalid\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n`);
    assert.match((await response)[0].toString(), /101 Switching Protocols/);
    const bytes = Buffer.from([129, 128, 1, 2, 3, 4]); const reply = once(client, 'data');
    client.write(bytes); assert.deepEqual((await reply)[0], bytes);
    assert.equal(mock.destinations[0].host, 'ws.invalid');
    client.destroy();
});

test('Git extension discovery reaches the phone proxy with remote DNS', async t => {
    let requestPath;
    const origin = http.createServer((req, res) => { requestPath = req.url; res.writeHead(404).end(); });
    const port = await listen(origin); t.after(() => new Promise(r => origin.close(r)));
    const mock = await mockPhone(); t.after(() => mock.close());
    const bridge = await createBridge({ phone: mock.phone, SocksClient }); t.after(() => bridge.close());
    const child = spawn('git', ['ls-remote', `http://git-does-not-resolve.invalid:${port}/test.git`], {
        env: { ...buildEnvironment(process.env, bridge.url), GIT_TERMINAL_PROMPT: '0' },
        stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
    });
    const errors = collect(child.stderr); child.stdout.resume();
    const [code] = await once(child, 'exit'); await errors;
    assert.notEqual(code, 0); // The fixture deliberately returns 404 after verifying transport.
    assert.match(requestPath, /^\/test\.git\/info\/refs\?service=git-upload-pack$/);
    assert.equal(mock.destinations[0].host, 'git-does-not-resolve.invalid');
});

test('chunked upload through ST ProxyAgent preserves delayed response chunks', async t => {
    const body = Buffer.from('streamed offline request\n'.repeat(20_000));
    const origin = http.createServer(async (req, res) => {
        assert.deepEqual(await collect(req), body);
        res.writeHead(200, { Connection: 'close', 'Content-Type': 'text/plain' });
        res.write('first\n'); setTimeout(() => { res.write('second\n'); setTimeout(() => res.end('done\n'), 40); }, 40);
    });
    const port = await listen(origin); t.after(() => new Promise(r => origin.close(r)));
    const mock = await mockPhone(); t.after(() => mock.close());
    const bridge = await createBridge({ phone: mock.phone, SocksClient }); t.after(() => bridge.close());
    const result = await childNode(`
        import http from 'node:http';
        import {ProxyAgent} from 'proxy-agent';
        const agent = new ProxyAgent();
        const body = Buffer.from('streamed offline request\\n'.repeat(20000));
        const result = await new Promise((resolve,reject)=>{
            const req=http.request({host:'delayed.invalid',port:${port},method:'POST',agent}, res=>{
                let text='';res.on('data',b=>text+=b);res.on('error',reject);res.on('end',()=>resolve(text));
            });
            req.on('error',reject);req.write(body.subarray(0,12345));
            setTimeout(()=>req.end(body.subarray(12345)),15);
        });
        process.stdout.write(result);agent.destroy();
    `, buildEnvironment(process.env, bridge.url));
    assert.equal(result, 'first\nsecond\ndone\n');
});
