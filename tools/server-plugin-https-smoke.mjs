// Offline regression probe for the installed ST node-fetch + Node global proxy runtime.
// The mock phone accepts only one .invalid hostname and maps it to a loopback TLS fixture.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { once } from 'node:events';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { RuntimeRouting } from '../server-plugin/jiuguan-relay/relay-controller.mjs';
import { createBridge } from '../server-plugin/jiuguan-relay/phone-relay-bridge.mjs';
import { RelayDiagnostics } from '../server-plugin/jiuguan-relay/relay-diagnostics.mjs';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const option = name => { const index = process.argv.indexOf(name); return index < 0 ? undefined : process.argv[index + 1]; };
const tavern = path.resolve(option('--tavern') || process.env.JIUGUAN_TAVERN_DIR || path.resolve(project, '../st/SillyTavern'));
const requireTavern = createRequire(path.join(tavern, 'package.json'));
const collect = async stream => { const chunks = []; for await (const chunk of stream) chunks.push(chunk); return Buffer.concat(chunks); };

async function child(scratch) {
    const { default: nodeFetch } = await import(pathToFileURL(requireTavern.resolve('node-fetch')).href);
    const { SocksClient } = requireTavern('socks');
    const { ProxyAgent } = requireTavern('proxy-agent');
    // Model a previously initialized ST proxy. Its configured route must be replaced dynamically.
    const oldAgent = new ProxyAgent({ getProxyForUrl: () => 'http://127.0.0.1:1' });
    http.globalAgent = oldAgent; https.globalAgent = oldAgent;
    let delegated = false;
    const routing = new RuntimeRouting({ filter: { isDelegated: () => delegated, setDelegated: value => { delegated = value; } } });
    const diagnostics = new RelayDiagnostics();
    const sockets = new Set(), destinations = [], requests = [];
    const track = socket => { sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket)); return socket; };
    const origin = https.createServer({ key: fs.readFileSync(path.join(scratch, 'key.pem')), cert: fs.readFileSync(path.join(scratch, 'cert.pem')) }, async (req, res) => {
        requests.push({ url: req.url, sni: req.socket.servername, body: await collect(req) });
        res.writeHead(req.url.startsWith('/bad-request') ? 400 : 200, { 'Content-Type': 'text/plain', Connection: 'close' });
        res.write('first\n'); setTimeout(() => res.end('last\n'), 25);
    });
    origin.on('connection', track); origin.listen(0, '127.0.0.1'); await once(origin, 'listening');
    const originPort = origin.address().port;
    const phone = net.createServer({ allowHalfOpen: true }, async client => {
        track(client);
        let bytes = Buffer.alloc(0), wake, ended = false;
        const onData = data => { bytes = Buffer.concat([bytes, data]); wake?.(); };
        client.on('data', onData); client.on('close', () => { ended = true; wake?.(); });
        const read = async size => {
            while (bytes.length < size) { if (ended) throw new Error('closed'); await new Promise(resolve => { wake = resolve; }); }
            const result = bytes.subarray(0, size); bytes = bytes.subarray(size); return result;
        };
        try {
            const hello = await read(2); await read(hello[1]); client.write(Buffer.from([5, 2]));
            const auth = await read(2); assert.equal((await read(auth[1])).toString(), 'fixture');
            assert.equal((await read((await read(1))[0])).toString(), 'offline-test'); client.write(Buffer.from([1, 0]));
            const head = await read(4); assert.equal(head[3], 3);
            const host = (await read((await read(1))[0])).toString(), port = (await read(2)).readUInt16BE();
            destinations.push({ host, port });
            if (host !== 'relay-target.invalid' || port !== originPort) {
                client.end(Buffer.from([5, 5, 0, 1, 0, 0, 0, 0, 0, 0])); return;
            }
            const upstream = track(net.connect({ host: '127.0.0.1', port, allowHalfOpen: true })); await once(upstream, 'connect');
            client.pause(); client.removeListener('data', onData); client.write(Buffer.from([5, 0, 0, 1, 127, 0, 0, 1, 0, 0]));
            if (bytes.length) upstream.write(bytes);
            client.pipe(upstream); upstream.pipe(client); client.resume();
            client.on('close', () => upstream.destroy()); upstream.on('close', () => client.destroy());
        } catch { client.destroy(); }
    });
    phone.listen(0, '127.0.0.1'); await once(phone, 'listening');
    let bridge;
    try {
        bridge = await createBridge({ phone: { host: '127.0.0.1', port: phone.address().port, type: 5, userId: 'fixture', password: 'offline-test' }, SocksClient, localDirect: false, diagnostics });
        routing.activate(bridge.url);
        diagnostics.start(bridge.diagnosticId);
        assert.equal(diagnostics.snapshot('mobile').outbound.nodeHttp.requests, 0);
        assert.notEqual(https.globalAgent, oldAgent);
        const url = `https://relay-target.invalid:${originPort}`;
        const body = Buffer.from('offline model generation\n'.repeat(20000));
        for (const [label, fetcher] of [['ST node-fetch', nodeFetch], ['Node native fetch', fetch]]) {
            const models = await fetcher(url + '/models', { signal: AbortSignal.timeout(4000) });
            assert.equal(await models.text(), 'first\nlast\n');
            const generated = await fetcher(url + '/chat/completions', { method: 'POST', body, signal: AbortSignal.timeout(4000) });
            assert.equal(await generated.text(), 'first\nlast\n');
            assert.deepEqual(requests.at(-1).body, body);
            console.log(`PASS ${label}: verified TLS models GET + large generation POST through runtime bridge/SOCKS`);
        }
        assert.equal(destinations.length, 4);
        assert(requests.every(req => req.sni === 'relay-target.invalid'));
        console.log('PASS .invalid target transferred unchanged to phone; TLS SNI preserved; no PC target DNS');
        for (const [kind, fetcher] of [['nodeHttp', nodeFetch], ['nativeFetch', fetch]]) {
            const result = await fetcher(url + '/bad-request?secret=DO-NOT-REPORT', { headers: { Authorization: 'Bearer DO-NOT-REPORT' }, signal: AbortSignal.timeout(4000) });
            assert.equal(result.status, 400); await result.text();
            const snapshot = diagnostics.snapshot('mobile');
            assert.deepEqual(snapshot.outbound[kind].last, { hostname: 'relay-target.invalid', status: 400, phase: 'api_response' });
            assert.equal(snapshot.bridge.failures, 0);
            assert(snapshot.bridge.tunnelsEstablished >= 5);
            assert.equal(JSON.stringify(snapshot).includes('DO-NOT-REPORT'), false);
        }
        assert.equal(diagnostics.snapshot('mobile').outbound.nodeHttp.requests, 3);
        assert.equal(diagnostics.snapshot('mobile').outbound.nativeFetch.requests, 3);
        console.log('PASS HTTPS API 400 distinguished from successful CONNECT; no path, query, headers or secret retained');
        const invalid = net.connect(new URL(bridge.url).port, '127.0.0.1');
        await once(invalid, 'connect'); invalid.end('GET /bad-format?DO-NOT-REPORT HTTP/1.1\r\nHost: unused\r\nConnection: close\r\n\r\n');
        assert.match((await collect(invalid)).toString(), /400/);
        assert.deepEqual(diagnostics.snapshot('mobile').bridge.lastError, { hostname: null, status: 400, phase: 'format' });
        assert.equal(JSON.stringify(diagnostics.snapshot('mobile')).includes('DO-NOT-REPORT'), false);
        await assert.rejects(() => nodeFetch(`https://denied-target.invalid:${originPort}/private-secret`, { signal: AbortSignal.timeout(4000) }));
        assert.deepEqual(diagnostics.snapshot('mobile').bridge.lastError, { hostname: 'denied-target.invalid', status: 502, phase: 'phone_connect' });
        // Node 25's proxy-connect failure can bypass its request.error channel. The bridge
        // still identifies the failure; never infer a successful API response from a created request.
        assert.equal(diagnostics.snapshot('mobile').outbound.nodeHttp.last.status, null);
        console.log('PASS bridge format 400 and phone SOCKS failure have distinct fixed phases');
        routing.restore(); assert.equal(https.globalAgent, oldAgent); assert.equal(http.globalAgent, oldAgent); assert.equal(delegated, false);
        diagnostics.stop();
        const stopped = diagnostics.snapshot('normal');
        const directAgent = new https.Agent();
        try {
            await new Promise((resolve, reject) => https.get({ hostname: '127.0.0.1', port: originPort, servername: 'relay-target.invalid', path: '/normal', agent: directAgent }, response => collect(response).then(resolve, reject)).on('error', reject));
        } finally { directAgent.destroy(); }
        assert.deepEqual(diagnostics.snapshot('normal'), stopped);
        assert(stopped.stoppedAt);
        console.log('PASS normal mode stops collection and preserves the previous session');
        console.log('PASS prior ST ProxyAgent identity restored');
    } finally {
        diagnostics.stop();
        routing.restore(); oldAgent.destroy();
        if (bridge) await bridge.close();
        for (const socket of sockets) socket.destroy();
        await Promise.all([origin, phone].map(server => new Promise(resolve => server.close(resolve))));
    }
}

if (process.argv[2] === '--child') {
    await child(process.argv[3]);
} else {
    const build = path.join(project, 'build'); fs.mkdirSync(build, { recursive: true });
    const scratch = fs.mkdtempSync(path.join(build, 'plugin-https-smoke-'));
    try {
        fs.writeFileSync(path.join(scratch, 'openssl.cnf'), '[req]\ndistinguished_name=dn\n[dn]\n');
        let openssl = option('--openssl') || process.env.OPENSSL || 'openssl';
        if (openssl === 'openssl' && process.platform === 'win32'
            && spawnSync(openssl, ['version'], { windowsHide: true, timeout: 5000 }).status !== 0) {
            const gitOpenSsl = path.join(process.env.ProgramFiles || 'C:/Program Files', 'Git/usr/bin/openssl.exe');
            if (fs.existsSync(gitOpenSsl)) openssl = gitOpenSsl;
        }
        const generated = spawnSync(openssl, ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', path.join(scratch, 'key.pem'), '-out', path.join(scratch, 'cert.pem'), '-config', path.join(scratch, 'openssl.cnf'), '-days', '1', '-subj', '/CN=relay-target.invalid', '-addext', 'subjectAltName=DNS:relay-target.invalid'], { windowsHide: true, encoding: 'utf8', timeout: 15000 });
        assert.equal(generated.status, 0, generated.error?.message || generated.stderr);
        const processChild = spawn(process.execPath, [fileURLToPath(import.meta.url), '--child', scratch], { windowsHide: true, stdio: 'inherit', env: { ...process.env, JIUGUAN_TAVERN_DIR: tavern, NODE_EXTRA_CA_CERTS: path.join(scratch, 'cert.pem') } });
        const timeout = setTimeout(() => processChild.kill(), 15000);
        const [code] = await once(processChild, 'exit'); clearTimeout(timeout); assert.equal(code, 0);
    } finally {
        assert.equal(path.dirname(scratch), build); assert(path.basename(scratch).startsWith('plugin-https-smoke-'));
        fs.rmSync(scratch, { recursive: true, force: true });
    }
}
