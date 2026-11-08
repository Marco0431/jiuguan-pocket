import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import path from 'node:path';
import fs from 'node:fs';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { channel } from 'node:diagnostics_channel';
import { RelayDiagnostics } from '../server-plugin/jiuguan-relay/relay-diagnostics.mjs';
import { RelayController, RelayError, RuntimeRouting, validateMutation, validatePhone, registerRoutes }
    from '../server-plugin/jiuguan-relay/relay-controller.mjs';
import { createBridge, probePhone, isLocalDestination }
    from '../server-plugin/jiuguan-relay/phone-relay-bridge.mjs';

const id = '12345678-1234-1234-1234-123456789abc';
const otherId = '87654321-4321-4321-4321-cba987654321';
const password = 'a'.repeat(64);
const csrf = 'test-csrf-token-with-sufficient-length';
const phoneUrl = `socks5h://jiuguan:${password}@192.168.1.20:10808`;
function request(overrides = {}) {
    return {
        user: { profile: { admin: true, handle: 'default-user' } },
        session: { csrfToken: csrf },
        headers: { origin: 'http://192.168.1.10:8000', host: '192.168.1.10:8000',
            'content-type': 'application/json', 'x-csrf-token': csrf, ...overrides.headers },
        socket: { remoteAddress: '::ffff:192.168.1.20', ...overrides.socket },
        body: { clientId: id, proxyUrl: phoneUrl, ...overrides.body },
        ...Object.fromEntries(Object.entries(overrides).filter(([key]) => !['headers', 'socket', 'body'].includes(key))),
    };
}
const rejectsStatus = (operation, status) => assert.rejects(operation, error => error instanceof RelayError && error.status === status);
function fixture(overrides = {}) {
    const events = [], bridges = [];
    const routing = { activate: value => events.push(['activate', value]), restore: () => events.push(['restore']) };
    const controller = new RelayController({
        probe: async () => events.push(['probe']),
        bridgeFactory: async () => {
            const bridge = { url: `http://127.0.0.1:${20000 + bridges.length}`,
                closes: 0, async close() { this.closes++; } };
            bridges.push(bridge);
            return bridge;
        }, routing, ...overrides,
    });
    return { controller, events, bridges, routing };
}

async function collect(stream) {
    const chunks = [];
    for await (const chunk of stream) chunks.push(chunk);
    return Buffer.concat(chunks);
}

async function mockPhone() {
    const sockets = new Set(), destinations = [];
    let offline = false;
    const server = net.createServer({ allowHalfOpen: true }, async client => {
        sockets.add(client); client.once('close', () => sockets.delete(client));
        client.on('error', () => {});
        let pending = Buffer.alloc(0), waiting, ended = false;
        const onData = bytes => { pending = Buffer.concat([pending, bytes]); waiting?.(); };
        client.on('data', onData); client.on('close', () => { ended = true; waiting?.(); });
        const read = async count => {
            while (pending.length < count) {
                if (ended) throw new Error('Closed');
                await new Promise(resolve => { waiting = resolve; });
            }
            const value = pending.subarray(0, count); pending = pending.subarray(count); return value;
        };
        try {
            const hello = await read(2); await read(hello[1]); client.write(Buffer.from([5, 2]));
            const auth = await read(2), username = (await read(auth[1])).toString();
            const secret = (await read((await read(1))[0])).toString();
            assert.equal(username, 'jiuguan'); assert.equal(secret, password);
            client.write(Buffer.from([1, 0]));
            const connection = await read(4); assert.equal(connection[1], 1);
            let host;
            if (connection[3] === 3) host = (await read((await read(1))[0])).toString();
            else if (connection[3] === 1) host = [...await read(4)].join('.');
            else throw new Error('Unsupported fixture address');
            const port = (await read(2)).readUInt16BE(); destinations.push({ host, port });
            if (offline || isLocalDestination(host)) {
                client.end(Buffer.from([5, 5, 0, 1, 0, 0, 0, 0, 0, 0])); return;
            }
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
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    return { phone: { host: '127.0.0.1', port: server.address().port, type: 5, userId: 'jiuguan', password },
        destinations, setOffline(value) { offline = value; },
        async close() { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); },
    };
}

async function runtimeChild() {
    const here = path.dirname(fileURLToPath(import.meta.url));
    const tavern = process.env.JIUGUAN_TAVERN_DIR || path.resolve(here, '../../st/SillyTavern');
    assert(fs.existsSync(path.join(tavern, 'node_modules/socks/package.json')));
    const { SocksClient } = createRequire(path.join(tavern, 'package.json'))('socks');
    const originals = { http: http.globalAgent, https: https.globalAgent, environment: { ...process.env } };
    const body = Buffer.from('plugin-streaming-upload\n'.repeat(20000));
    const received = [];
    const origin = http.createServer(async (req, res) => {
        received.push({ path: req.url, bytes: await collect(req) });
        res.writeHead(200, { 'Content-Type': 'text/plain', Connection: 'close' });
        res.write('first\n'); setTimeout(() => res.end('last\n'), 30);
    });
    origin.listen(0, '127.0.0.1'); await once(origin, 'listening');
    const port = origin.address().port;
    const phone = await mockPhone();
    let delegated = false;
    const routing = new RuntimeRouting({ filter: {
        isDelegated: () => delegated, setDelegated: value => { delegated = value; },
    } });
    const diagnostics = new RelayDiagnostics();
    const controller = new RelayController({ routing, diagnostics, probe: () => probePhone(phone.phone),
        bridgeFactory: () => createBridge({ phone: phone.phone, SocksClient, localDirect: false, diagnostics }),
    });
    try {
        await controller.enable(request());
        assert.equal(delegated, true); assert.equal(process.env.NO_PROXY, '');
        assert.equal(process.env.npm_config_noproxy, '');
        assert.equal(await (await fetch(`http://plugin-target.invalid:${port}/native`)).text(), 'first\nlast\n');
        const response = await new Promise((resolve, reject) => {
            const outgoing = http.request(`http://plugin-target.invalid:${port}/stream`, { method: 'POST' }, incoming => {
                collect(incoming).then(bytes => resolve(bytes.toString()), reject);
            });
            outgoing.on('error', reject); outgoing.write(body.subarray(0, 12345));
            setTimeout(() => outgoing.end(body.subarray(12345)), 20);
        });
        assert.equal(response, 'first\nlast\n'); assert.deepEqual(received[1].bytes, body);
        const diagnosticResult = controller.diagnosticStatus(request());
        assert.equal(diagnosticResult.outbound.nodeHttp.requests, 1, 'Internal bridge forwarding must not be counted again');
        assert.equal(diagnosticResult.outbound.nativeFetch.requests, 1);
        assert.equal(diagnosticResult.bridge.httpForwarded, 1);
        assert.equal(diagnosticResult.bridge.tunnelsEstablished, 1);
        assert(phone.destinations.every(destination => destination.host === 'plugin-target.invalid'));
        const originalCount = received.length;
        await assert.rejects(() => fetch(`http://127.0.0.1:${port}/must-not-bypass`));
        assert.equal(received.length, originalCount);
        assert.equal(phone.destinations.at(-1).host, '127.0.0.1');
        phone.setOffline(true);
        await assert.rejects(() => fetch(`http://plugin-target.invalid:${port}/offline`));
        assert.equal(controller.status(request()).mode, 'mobile');
        assert.equal(received.length, originalCount);
        await controller.disable(request());
        assert.equal(delegated, false); assert.equal(http.globalAgent, originals.http); assert.equal(https.globalAgent, originals.https);
        assert.deepEqual({ ...process.env }, originals.environment);
        const direct = await new Promise((resolve, reject) => http.get(`http://127.0.0.1:${port}/normal`,
            incoming => collect(incoming).then(bytes => resolve(bytes.toString()), reject)).on('error', reject));
        assert.equal(direct, 'first\nlast\n');
        console.log('PASS runtime native fetch + delayed HTTP upload + remote DNS + private-target denial + fail-closed + exact restore');
    } finally {
        await controller.close(); await phone.close();
        await new Promise(resolve => origin.close(resolve));
    }
}

if (process.argv.includes('--runtime-child')) {
    await runtimeChild();
} else {
    test('mutations require admin, active CSRF, JSON and exact same origin', async () => {
        const { controller, events } = fixture();
        for (const req of [request({ user: null }), request({ user: { profile: { admin: false, handle: 'user' } } }),
            request({ session: {} }), request({ session: { csrfToken: 'disabled' } }),
            request({ headers: { 'x-csrf-token': 'wrong-token' } }), request({ headers: { origin: 'http://evil.invalid' } }),
            request({ headers: { origin: 'null' } }), request({ headers: { origin: 'http://192.168.1.10:8000/path' } }),
            request({ headers: { origin: 'https://192.168.1.10:8000', 'x-forwarded-proto': 'https' } })]) {
            await rejectsStatus(() => controller.enable(req), 403);
        }
        await rejectsStatus(() => controller.enable(request({ headers: { 'content-type': 'text/plain' } })), 415);
        assert.equal(events.length, 0);
        assert.deepEqual(validateMutation(request({ headers: { host: '192.168.1.10', origin: 'http://192.168.1.10:80' } })),
            { handle: 'default-user', clientId: id });
    });

    test('phone address is bound to the real LAN peer and fixed protocol/port/credential format', async () => {
        const { controller, events } = fixture();
        await rejectsStatus(() => controller.enable(request({ socket: { remoteAddress: '192.168.1.21' },
            headers: { 'x-forwarded-for': '192.168.1.20' } })), 403);
        await rejectsStatus(() => controller.enable(request({ body: { proxyUrl: phoneUrl.replace('10808', '10809') } })), 403);
        for (const value of [phoneUrl.replace('socks5h:', 'http:'), phoneUrl.replace(password, 'short'),
            phoneUrl.replace('192.168.1.20', '8.8.8.8'), phoneUrl + '/path', phoneUrl + '?secret=yes']) {
            await rejectsStatus(() => controller.enable(request({ body: { proxyUrl: value } })), 400);
        }
        await rejectsStatus(() => controller.enable(request({ body: { clientId: 'not-a-uuid' } })), 400);
        assert.equal(validatePhone(request()).host, '192.168.1.20'); assert.equal(events.length, 0);
    });

    test('single owner and duplicate enable preserve the current generation', async () => {
        const { controller, events, bridges } = fixture();
        assert.deepEqual(await controller.enable(request()), { ok: true, mode: 'mobile' });
        assert.deepEqual(await controller.enable(request()), { ok: true, mode: 'mobile' });
        assert.equal(events.filter(x => x[0] === 'probe').length, 1); assert.equal(bridges.length, 1);
        await rejectsStatus(() => controller.enable(request({ body: { clientId: otherId } })), 409);
        await rejectsStatus(() => controller.disable(request({ body: { clientId: otherId } })), 409);
        const status = controller.status(request());
        assert.deepEqual(status, { protocol: 1, mode: 'mobile', supported: true });
        assert.equal(JSON.stringify(status).includes(password), false);
        await controller.disable(request()); await controller.disable(request());
        assert.equal(bridges[0].closes, 1); assert.equal(events.filter(x => x[0] === 'restore').length, 1);
    });

    test('same owner can replace a changed phone IP, closing only the replaced bridge', async () => {
        const { controller, bridges } = fixture();
        await controller.enable(request());
        await controller.enable(request({ socket: { remoteAddress: '192.168.1.22' },
            body: { proxyUrl: phoneUrl.replace('192.168.1.20', '192.168.1.22') } }));
        assert.equal(bridges[0].closes, 1); assert.equal(bridges[1].closes, 0);
        await controller.close(); assert.equal(bridges[1].closes, 1);
    });

    test('failed pairing and failed route activation leave an existing mode intact', async () => {
        let failProbe = false, failActivation = false, activeUrl;
        const { controller, bridges } = fixture({ probe: async () => { if (failProbe) throw new Error(password); },
            routing: { activate: url => { if (failActivation) throw new Error(password); activeUrl = url; }, restore() {} },
        });
        await controller.enable(request()); const oldUrl = activeUrl;
        const changed = request({ body: { proxyUrl: phoneUrl.replace(password, 'b'.repeat(64)) } });
        failProbe = true;
        await assert.rejects(() => controller.enable(changed), error => error.status === 502 && !error.message.includes(password));
        assert.equal(activeUrl, oldUrl); assert.equal(bridges[0].closes, 0);
        failProbe = false; failActivation = true;
        await rejectsStatus(() => controller.enable(changed), 502);
        assert.equal(activeUrl, oldUrl); assert.equal(bridges[1].closes, 1);
        assert.equal(controller.status(request()).mode, 'mobile');
    });

    test('competing enable calls are serialized and a late phone cannot seize ownership', async () => {
        let release;
        const gate = new Promise(resolve => { release = resolve; });
        const { controller, bridges } = fixture({ probe: async () => gate });
        const first = controller.enable(request());
        const second = controller.enable(request({ body: { clientId: otherId } }));
        const denied = rejectsStatus(() => second, 409);
        release(); await first; await denied; assert.equal(bridges.length, 1);
        await controller.close();
    });

    test('closing during pairing prevents a delayed bridge from activating', async () => {
        let release;
        const gate = new Promise(resolve => { release = resolve; });
        const { controller, events } = fixture({ probe: async () => gate });
        const enabling = controller.enable(request());
        await Promise.resolve();
        const closing = controller.close(); release();
        await rejectsStatus(() => enabling, 502); await closing;
        assert.equal(events.some(x => x[0] === 'activate'), false);
        await rejectsStatus(() => controller.enable(request()), 503);
    });

    test('disable queued behind an in-flight enable leaves normal mode after pairing completes', async () => {
        let release;
        const gate = new Promise(resolve => { release = resolve; });
        const { controller, bridges, events } = fixture({ probe: async () => gate });
        const enabling = controller.enable(request());
        await Promise.resolve();
        const disabling = controller.disable(request());
        release();
        assert.equal((await enabling).mode, 'mobile');
        assert.equal((await disabling).mode, 'normal');
        assert.equal(controller.status(request()).mode, 'normal');
        assert.equal(bridges[0].closes, 1);
        assert.equal(events.at(-1)[0], 'restore');
    });

    test('phone authentication has an absolute deadline and closes a stalled socket', async () => {
        let accepted;
        const sockets = new Set();
        const server = net.createServer(socket => {
            accepted = socket;
            sockets.add(socket);
            socket.on('close', () => sockets.delete(socket));
            socket.on('error', () => {});
            socket.resume(); // Deliberately never send a SOCKS authentication response.
        });
        server.listen(0, '127.0.0.1'); await once(server, 'listening');
        const before = performance.now();
        try {
            await assert.rejects(() => probePhone({ host: '127.0.0.1', port: server.address().port,
                userId: 'jiuguan', password }, { timeoutMs: 100 }));
            assert(performance.now() - before < 1000, 'The probe ignored its total deadline');
            if (!accepted.destroyed) await once(accepted, 'close');
            assert.equal(sockets.size, 0);
        } finally {
            for (const socket of sockets) socket.destroy();
            await new Promise(resolve => server.close(resolve));
        }
    });

    test('unsupported status is explicit and route errors are sanitized', async () => {
        const controller = new RelayController({ supported: false, message: '需要更新 Node.js。' });
        assert.equal(controller.status(request()).supported, false);
        await rejectsStatus(() => controller.enable(request()), 503);
        const routes = {};
        registerRoutes({ get: (route, handler) => { routes[route] = handler; }, post: (route, handler) => { routes[route] = handler; } }, controller);
        let status = 200, result, cache;
        const response = { set(_key, value) { cache = value; return this; }, status(value) { status = value; return this; },
            json(value) { result = value; return this; } };
        await routes['/enable'](request({ user: null }), response);
        assert.equal(status, 403); assert.equal(result.ok, false); assert.equal(cache, 'no-store');
        assert.equal(JSON.stringify(result).includes(password), false);
    });

    test('diagnostics route requires an administrator and retains the stopped session without caching', async () => {
        const { controller } = fixture();
        const routes = {};
        registerRoutes({ get: (route, handler) => { routes[route] = handler; }, post() {} }, controller);
        let code = 200, body, cache;
        const response = { set(_key, value) { cache = value; return this; }, status(value) { code = value; return this; }, json(value) { body = value; } };
        await routes['/diagnostics'](request({ user: null }), response);
        assert.equal(code, 403); assert.equal(cache, 'no-store');
        await controller.enable(request());
        let current = controller.diagnosticStatus(request());
        assert.equal(current.protocol, 1); assert.equal(current.routeMode, 'mobile'); assert(current.since);
        assert.equal(current.bridge.connectRequests, 0); assert.equal(current.outbound.nodeHttp.requests, 0);
        controller.diagnostics.bridgeEvent(undefined, 'connect_request');
        await controller.enable(request());
        assert.equal(controller.diagnosticStatus(request()).bridge.connectRequests, 1, 'Idempotent enable must retain evidence');
        await controller.disable(request());
        current = controller.diagnosticStatus(request());
        assert.equal(current.routeMode, 'normal'); assert(current.stoppedAt); assert.equal(current.bridge.connectRequests, 1);
        code = 200; await routes['/diagnostics'](request(), response);
        assert.equal(code, 200); assert.equal(body.routeMode, 'normal'); assert.equal(cache, 'no-store');
        await controller.close();
    });

    test('passive diagnostics expose only host/status/phase and discard old or internal requests', () => {
        const diagnostics = new RelayDiagnostics(), first = {}, second = {};
        diagnostics.start(first);
        try {
            const secret = 'DO-NOT-REPORT';
            const req = { method: 'POST', host: 'api.example.invalid', path: `/private?key=${secret}`, headers: { Authorization: secret } };
            channel('http.client.request.created').publish({ request: req });
            channel('http.client.request.start').publish({ request: req });
            channel('http.client.response.finish').publish({ request: req, response: { statusCode: 400, headers: { secret } } });
            let result = diagnostics.snapshot('mobile');
            assert.equal(result.outbound.nodeHttp.requests, 1); assert.equal(result.outbound.nodeHttp.responses, 1);
            assert.deepEqual(result.outbound.nodeHttp.last, { hostname: 'api.example.invalid', status: 400, phase: 'api_response' });
            const internalAgent = {}; diagnostics.ignoreAgent(internalAgent);
            channel('http.client.request.created').publish({ request: { method: 'GET', host: 'api.example.invalid', agent: internalAgent } });
            channel('http.client.request.created').publish({ request: { method: 'CONNECT', host: '127.0.0.1' } });
            assert.equal(diagnostics.snapshot('mobile').outbound.nodeHttp.requests, 1);
            const native = { method: 'GET', origin: 'https://native.example.invalid', path: secret };
            channel('undici:request:create').publish({ request: native });
            channel('undici:request:error').publish({ request: native, error: new Error(secret) });
            assert.deepEqual(diagnostics.snapshot('mobile').outbound.nativeFetch.last,
                { hostname: 'native.example.invalid', status: null, phase: 'request_failed' });
            diagnostics.bridgeEvent(first, 'connect_request'); diagnostics.bridgeEvent(first, 'tunnel_established');
            diagnostics.bridgeEvent(first, 'failure', { hostname: `user:${secret}@bad.invalid`, phase: 'format', error: secret });
            result = diagnostics.snapshot('mobile');
            assert.equal(JSON.stringify(result).includes(secret), false);
            assert.deepEqual(result.bridge.lastError, { hostname: null, status: 400, phase: 'format' });
            diagnostics.stop(); const stopped = diagnostics.snapshot('normal');
            channel('http.client.request.created').publish({ request: { method: 'GET', host: 'stopped.invalid' } });
            diagnostics.bridgeEvent(first, 'connect_request');
            assert.deepEqual(diagnostics.snapshot('normal'), stopped);
            diagnostics.start(second);
            channel('http.client.request.start').publish({ request: req });
            channel('http.client.response.finish').publish({ request: req, response: { statusCode: 200 } });
            diagnostics.bridgeEvent(first, 'failure', { phase: 'phone_connect', hostname: 'old.invalid' });
            result = diagnostics.snapshot('mobile');
            assert.equal(result.outbound.nodeHttp.requests, 0); assert.equal(result.outbound.nodeHttp.responses, 0);
            assert.equal(result.bridge.failures, 0); assert.equal(result.stoppedAt, null);
        } finally { diagnostics.stop(); }
    });

    test('runtime route replacement restores original agents, environment and filtering', () => {
        const originalHttp = { name: 'original HTTP' }, originalHttps = { name: 'original HTTPS' };
        const environment = { HTTPS_PROXY: 'http://original', no_proxy: '*', GIT_CONFIG_COUNT: '1',
            GIT_CONFIG_KEY_0: 'core.autocrlf', GIT_CONFIG_VALUE_0: 'false', UNRELATED: 'keep' };
        const snapshot = { ...environment };
        let delegated = false, fail = false, destroyed = 0;
        const httpsModule = { globalAgent: originalHttps };
        const httpModule = { globalAgent: originalHttp, setGlobalProxyFromEnv(values) {
            if (fail) { fail = false; throw new Error('Temporary setup failure'); }
            assert.equal(values.NO_PROXY, ''); assert.equal(values.npm_config_noproxy, '');
            const previousHttp = this.globalAgent, previousHttps = httpsModule.globalAgent;
            this.globalAgent = { destroy() { destroyed++; } }; httpsModule.globalAgent = { destroy() { destroyed++; } };
            return () => { this.globalAgent = previousHttp; httpsModule.globalAgent = previousHttps; };
        } };
        const routing = new RuntimeRouting({ environment, httpModule, httpsModule,
            filter: { isDelegated: () => delegated, setDelegated: value => { delegated = value; } } });
        routing.activate('http://127.0.0.1:20001'); assert.equal(delegated, true);
        assert.equal(environment.no_proxy, undefined); assert.equal(environment.GIT_CONFIG_COUNT, '2');
        fail = true; assert.throws(() => routing.activate('http://127.0.0.1:20002'));
        assert.equal(environment.HTTP_PROXY, 'http://127.0.0.1:20001'); assert.equal(delegated, true);
        assert.equal(destroyed, 0, 'A failed replacement must preserve the old active streams');
        routing.activate('http://127.0.0.1:20003');
        routing.restore(); assert.equal(delegated, false);
        assert.deepEqual(environment, snapshot); assert.equal(httpModule.globalAgent, originalHttp);
        assert.equal(httpsModule.globalAgent, originalHttps); assert.equal(destroyed, 4);
    });

    test('isolated runtime routes streamed HTTP/native fetch, denies private bypass and restores normal mode', async () => {
        const env = { ...process.env };
        delete env.NODE_USE_ENV_PROXY;
        const child = spawn(process.execPath, [fileURLToPath(import.meta.url), '--runtime-child'],
            { env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
        const stdout = collect(child.stdout), stderr = collect(child.stderr);
        const [code] = await once(child, 'exit');
        assert.equal(code, 0, (await stderr).toString());
        assert.match((await stdout).toString(), /PASS runtime native fetch/);
    });
}
