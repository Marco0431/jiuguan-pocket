import http from 'node:http';
import net from 'node:net';
import { once } from 'node:events';

const IDLE_TIMEOUT = 15 * 60 * 1000;
const HOP_HEADERS = new Set(['connection', 'proxy-connection', 'proxy-authorization',
    'proxy-authenticate', 'keep-alive', 'te', 'trailer', 'transfer-encoding', 'upgrade']);

export function supportsProxyNode(version = process.versions.node) {
    const [major, minor] = version.split('.').map(Number);
    return major > 24 || (major === 24 && minor >= 5);
}

export function parsePhoneUrl(value) {
    let url;
    try { url = new URL(String(value).trim()); } catch { throw new Error('请粘贴手机中转页面复制的完整连接地址。'); }
    const octets = url.hostname.split('.').map(Number);
    const isLan = net.isIP(url.hostname) === 4 && (octets[0] === 10 ||
        (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
        (octets[0] === 192 && octets[1] === 168));
    if (url.protocol !== 'socks5h:' || !isLan || url.username !== 'jiuguan' ||
        !/^[a-f0-9]{64}$/i.test(url.password) || !/^\d+$/.test(url.port) ||
        +url.port < 1 || +url.port > 65535 || (url.pathname && url.pathname !== '/') || url.search || url.hash) {
        throw new Error('手机地址格式不正确：请使用 App 中“复制电脑连接地址”，不要手动修改。');
    }
    return Object.freeze({ host: url.hostname, port: +url.port, userId: 'jiuguan',
        password: url.password, type: 5, url: url.href.replace(/\/$/, '') });
}

// Local services continue using the computer's LAN. Public hostnames never use PC DNS.
export function isLocalDestination(host) {
    const h = host.replace(/^\[|\]$/g, '').toLowerCase();
    if (h === 'localhost' || h.endsWith('.localhost') || h === '::1') return true;
    if (net.isIP(h) === 4) {
        const n = h.split('.').map(Number);
        return n[0] === 10 || n[0] === 127 || (n[0] === 172 && n[1] >= 16 && n[1] <= 31) ||
            (n[0] === 192 && n[1] === 168) || (n[0] === 169 && n[1] === 254);
    }
    return net.isIP(h) === 6 && (/^f[cd]/i.test(h) || /^fe[89ab]/i.test(h));
}

function cleanHeaders(headers, targetHost) {
    const nominated = new Set(String(headers.connection || '').toLowerCase().split(',').map(x => x.trim()));
    const result = {};
    for (const [name, value] of Object.entries(headers)) {
        if (!HOP_HEADERS.has(name) && !nominated.has(name) && name !== 'expect') result[name] = value;
    }
    if (targetHost) result.host = targetHost;
    return result;
}

function authority(value) {
    if (!/^(?:\[[0-9a-f:.]+\]|[^\s/:?#@]+):[0-9]+$/i.test(value)) throw new Error('Invalid destination');
    const u = new URL(`http://${value}`);
    const port = Number(value.slice(value.lastIndexOf(':') + 1));
    if (port < 1 || port > 65535) throw new Error('Invalid port');
    return { host: u.hostname.replace(/^\[|\]$/g, ''), port };
}

function rawError(socket, status = 502) {
    if (socket.destroyed) return;
    socket.end(`HTTP/1.1 ${status} Relay unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
}

export async function connectTcp(host, port, track = socket => socket) {
    const socket = track(net.createConnection({ host, port, allowHalfOpen: true }));
    socket.setTimeout(20_000, () => socket.destroy(new Error('Connection timed out')));
    try { await once(socket, 'connect'); return socket; }
    catch (error) { socket.destroy(); throw error; }
}

export function makePhoneConnector(phone, SocksClient, track = socket => socket) {
    return async (host, port) => {
        // Connect to a numeric LAN address first: no PC-side public DNS resolution.
        const raw = await connectTcp(phone.host, phone.port, track);
        try {
            const { socket } = await SocksClient.createConnection({
                proxy: { host: phone.host, port: phone.port, type: 5,
                    userId: phone.userId, password: phone.password },
                command: 'connect', destination: { host, port }, timeout: 20_000, existing_socket: raw,
            });
            return socket;
        } catch (error) { raw.destroy(); throw error; }
    };
}

// Validate the phone's authentication without contacting any external API.
export async function probePhone(phone, { timeoutMs = 1200 } = {}) {
    // This control request must finish before the phone's short HTTP read timeout.
    // Use an absolute deadline covering connect + authentication, not an inactivity timer.
    const socket = net.createConnection({ host: phone.host, port: phone.port });
    const deadline = setTimeout(() => socket.destroy(new Error('Phone authentication timed out')), timeoutMs);
    let buffer = Buffer.alloc(0);
    let waiter;
    let failure;
    socket.on('data', bytes => { buffer = Buffer.concat([buffer, bytes]); waiter?.(); });
    socket.on('error', error => { failure = error; waiter?.(); });
    socket.on('close', () => { failure ||= new Error('Connection closed'); waiter?.(); });
    const read = async count => {
        while (buffer.length < count) {
            if (failure) throw failure;
            await new Promise(resolve => { waiter = resolve; });
        }
        const result = buffer.subarray(0, count); buffer = buffer.subarray(count); return result;
    };
    try {
        await once(socket, 'connect');
        socket.write(Buffer.from([5, 1, 2]));
        const hello = await read(2);
        if (hello[0] !== 5 || hello[1] !== 2) throw new Error('SOCKS authentication unavailable');
        const user = Buffer.from(phone.userId), password = Buffer.from(phone.password);
        socket.write(Buffer.concat([Buffer.from([1, user.length]), user, Buffer.from([password.length]), password]));
        const auth = await read(2);
        if (auth[0] !== 1 || auth[1] !== 0) throw new Error('SOCKS authentication rejected');
    } finally { clearTimeout(deadline); socket.destroy(); }
}

/** Loopback-only HTTP + CONNECT adapter. Never retries a request or decrypts TLS. */
export async function createBridge({ phone, SocksClient, connector, localDirect = true } = {}) {
    const sockets = new Set();
    let closing = false;
    const track = socket => {
        if (closing) { socket.destroy(); return socket; }
        sockets.add(socket);
        socket.once('close', () => sockets.delete(socket));
        socket.on('error', () => {});
        return socket;
    };
    const viaPhone = connector || makePhoneConnector(phone, SocksClient, track);
    const connect = async (host, port) => {
        if (closing) throw new Error('Relay closed');
        const socket = localDirect && isLocalDestination(host)
            ? await connectTcp(host, port, track) : track(await viaPhone(host, port));
        if (closing) { socket.destroy(); throw new Error('Relay closed'); }
        socket.setTimeout(IDLE_TIMEOUT, () => socket.destroy());
        socket.setNoDelay(true);
        return socket;
    };
    const server = http.createServer({ maxHeaderSize: 32 * 1024 }, async (request, response) => {
        let target;
        try {
            target = new URL(request.url);
            if (target.protocol !== 'http:' || target.username || target.password) throw new Error('Invalid target');
        } catch { response.writeHead(400).end(); return; }
        let socket;
        try { socket = await connect(target.hostname.replace(/^\[|\]$/g, ''), +(target.port || 80)); }
        catch { response.writeHead(502).end('手机中转不可用，请检查手机网络和中转开关。'); return; }
        if (request.destroyed || response.destroyed) { socket.destroy(); return; }
        const agent = new http.Agent({ keepAlive: false });
        agent.createConnection = (_options, callback) => { callback(null, socket); };
        const upstream = http.request({ protocol: 'http:', hostname: target.hostname, port: target.port || 80,
            method: request.method, path: target.pathname + target.search, agent,
            headers: cleanHeaders(request.headers, target.host) });
        upstream.on('response', incoming => {
            response.writeHead(incoming.statusCode, cleanHeaders(incoming.headers));
            incoming.on('error', () => response.destroy());
            incoming.pipe(response);
        });
        upstream.on('error', () => {
            if (!response.headersSent) response.writeHead(502).end('手机中转连接已中断。');
            else response.destroy();
        });
        request.on('aborted', () => upstream.destroy());
        request.on('error', () => upstream.destroy());
        response.on('close', () => { upstream.destroy(); agent.destroy(); });
        request.pipe(upstream);
    });
    const tunnel = async (request, client, head, upgrade) => {
        client.pause();
        let destination, target;
        try {
            if (upgrade) {
                target = new URL(request.url);
                if (!['http:', 'ws:'].includes(target.protocol) || target.username || target.password) throw new Error();
                destination = { host: target.hostname.replace(/^\[|\]$/g, ''), port: +(target.port || 80) };
            } else destination = authority(request.url);
        } catch { rawError(client, 400); return; }
        let remote;
        try { remote = await connect(destination.host, destination.port); }
        catch { rawError(client); return; }
        if (client.destroyed) { remote.destroy(); return; }
        const cleanup = () => { client.destroy(); remote.destroy(); };
        client.on('error', cleanup); remote.on('error', cleanup);
        // A readable EOF closes only the peer's writable half. A hard close tears down both.
        client.on('close', cleanup); remote.on('close', cleanup);
        if (upgrade) {
            const headers = cleanHeaders(request.headers, target.host);
            headers.connection = 'Upgrade'; headers.upgrade = request.headers.upgrade;
            remote.write(`${request.method} ${target.pathname}${target.search} HTTP/1.1\r\n` +
                Object.entries(headers).map(([key, value]) => `${key}: ${Array.isArray(value) ? value.join(', ') : value}`).join('\r\n') + '\r\n\r\n');
        } else client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
        if (head.length) remote.write(head);
        client.pipe(remote); remote.pipe(client); client.resume();
    };
    server.on('connect', (req, socket, head) => { void tunnel(req, socket, head, false); });
    server.on('upgrade', (req, socket, head) => { void tunnel(req, socket, head, true); });
    server.on('connection', socket => {
        track(socket); socket.setTimeout(IDLE_TIMEOUT, () => socket.destroy());
    });
    server.on('clientError', (_error, socket) => rawError(socket, 400));
    server.maxConnections = 64;
    server.requestTimeout = 0;
    server.headersTimeout = 20_000;
    server.keepAliveTimeout = 5_000;
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    return {
        url: `http://127.0.0.1:${server.address().port}`,
        async close() {
            closing = true;
            const done = new Promise(resolve => server.close(resolve));
            for (const socket of sockets) socket.destroy();
            sockets.clear();
            await done;
        },
    };
}

export function buildEnvironment(base, proxyUrl) {
    const env = { ...base };
    // Windows treats environment names case-insensitively. Remove both spellings first.
    for (const key of Object.keys(env)) {
        const lower = key.toLowerCase();
        if (/^(http_proxy|https_proxy|all_proxy|no_proxy|npm_config_proxy|npm_config_https_proxy|npm_config_http_proxy|npm_config_noproxy|npm_config_no_proxy|node_use_env_proxy)$/.test(lower)) delete env[key];
    }
    Object.assign(env, {
        HTTP_PROXY: proxyUrl, HTTPS_PROXY: proxyUrl, ALL_PROXY: proxyUrl,
        NO_PROXY: 'localhost,127.0.0.1,::1,[::1]', NODE_USE_ENV_PROXY: '1',
        npm_config_proxy: proxyUrl, npm_config_https_proxy: proxyUrl, npm_config_noproxy: 'localhost,127.0.0.1,::1',
        SILLYTAVERN_REQUESTPROXY_ENABLED: 'true', SILLYTAVERN_REQUESTPROXY_URL: proxyUrl,
        SILLYTAVERN_REQUESTPROXY_BYPASS: JSON.stringify(['localhost', '127.0.0.1', '::1', '[::1]']),
    });
    // Git config applies only to this process tree, including extension clone/update.
    const current = Number(env.GIT_CONFIG_COUNT || '0');
    if (!Number.isInteger(current) || current < 0 || current > 100) throw new Error('Git 环境配置异常，请在普通终端中启动。');
    env[`GIT_CONFIG_KEY_${current}`] = 'http.proxy';
    env[`GIT_CONFIG_VALUE_${current}`] = proxyUrl;
    env.GIT_CONFIG_COUNT = String(current + 1);
    return env;
}
