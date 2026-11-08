import crypto from 'node:crypto';
import http from 'node:http';
import https from 'node:https';
import { parsePhoneUrl, buildEnvironment } from './phone-relay-bridge.mjs';
import { RelayDiagnostics } from './relay-diagnostics.mjs';

export class RelayError extends Error {
    constructor(status, message) { super(message); this.status = status; }
}

function sameSecret(first, second) {
    if (typeof first !== 'string' || typeof second !== 'string' || first.length < 16 || first.length > 512) return false;
    const a = Buffer.from(first), b = Buffer.from(second);
    return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function profileHandle(request) {
    const profile = request.user?.profile;
    if (profile?.admin !== true || typeof profile.handle !== 'string' || !profile.handle) {
        throw new RelayError(403, '请先使用酒馆管理员账号登录。');
    }
    return profile.handle;
}

export function validateMutation(request) {
    const handle = profileHandle(request);
    const csrf = request.session?.csrfToken;
    if (csrf === 'disabled' || !sameSecret(csrf, request.headers?.['x-csrf-token'])) {
        throw new RelayError(403, '请开启酒馆 CSRF 保护并重新登录后再切换。');
    }
    const contentType = request.headers?.['content-type'];
    if (typeof contentType !== 'string' || !/^application\/json(?:\s*;|$)/i.test(contentType)) {
        throw new RelayError(415, '需要 JSON 请求。');
    }
    try {
        const host = request.headers.host;
        const origin = request.headers.origin;
        if (typeof host !== 'string' || typeof origin !== 'string' || /[\s/@?#\\]/.test(host)) throw new Error();
        const expected = new URL(`${request.socket?.encrypted ? 'https' : 'http'}://${host}`).origin;
        const actual = new URL(origin);
        if (actual.origin !== expected || actual.href !== actual.origin + '/') throw new Error();
    } catch { throw new RelayError(403, '只允许从当前酒馆页面切换网络。'); }
    const clientId = request.body?.clientId;
    if (typeof clientId !== 'string' || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(clientId)) {
        throw new RelayError(400, '客户端标识无效，请更新手机 App。');
    }
    return { handle, clientId: clientId.toLowerCase() };
}

export function validatePhone(request) {
    const value = request.body?.proxyUrl;
    if (typeof value !== 'string' || value.length > 256) throw new RelayError(400, '手机中转参数无效。');
    let phone;
    try { phone = parsePhoneUrl(value); }
    catch { throw new RelayError(400, '手机中转参数无效。'); }
    let remote = request.socket?.remoteAddress || '';
    if (remote.startsWith('::ffff:')) remote = remote.substring(7);
    if (phone.port !== 10808 || phone.host !== remote) {
        throw new RelayError(403, '手机中转地址必须属于当前直接连接酒馆的手机。');
    }
    return phone;
}

function owns(first, second) {
    return first.handle === second.handle && first.clientId === second.clientId;
}

/** Serializes only explicit control changes; it never retries application requests. */
export class RelayController {
    constructor({ probe, bridgeFactory, routing, diagnostics = new RelayDiagnostics(), supported = true, message = '' }) {
        this.probe = probe;
        this.bridgeFactory = bridgeFactory;
        this.routing = routing;
        this.supported = supported;
        this.message = message;
        this.diagnostics = diagnostics;
        this.active = null;
        this.queue = Promise.resolve();
        this.closed = false;
    }

    status(request) {
        profileHandle(request);
        return { protocol: 1, mode: this.active ? 'mobile' : 'normal', supported: this.supported,
            ...(this.message ? { message: this.message } : {}) };
    }

    diagnosticStatus(request) {
        profileHandle(request);
        return this.diagnostics.snapshot(this.active ? 'mobile' : 'normal');
    }

    exclusive(operation) {
        const result = this.queue.then(operation);
        this.queue = result.catch(() => {});
        return result;
    }

    async enable(request) {
        const owner = validateMutation(request);
        const phone = validatePhone(request);
        return this.exclusive(async () => {
            if (this.closed || !this.supported) throw new RelayError(503, this.message || '电脑端中转暂不可用。');
            if (this.active && !owns(this.active.owner, owner)) throw new RelayError(409, '另一台手机正在提供流量，请先在那台手机关闭。');
            // Repeated pairing of the same generation is idempotent and must not interrupt streams.
            if (this.active?.phone.url === phone.url) return { ok: true, mode: 'mobile' };
            let replacement;
            try {
                await this.probe(phone);
                if (this.closed) throw new Error('Closing');
                replacement = await this.bridgeFactory(phone);
                if (this.closed) throw new Error('Closing');
                this.routing.activate(replacement.url);
            } catch {
                if (replacement) await replacement.close();
                throw new RelayError(502, '电脑暂时连不上手机中转，请保持 Wi-Fi 和移动数据开启后重试。');
            }
            const previous = this.active;
            this.active = { owner, phone, bridge: replacement };
            this.diagnostics.start(replacement.diagnosticId);
            if (previous) await previous.bridge.close();
            return { ok: true, mode: 'mobile' };
        });
    }

    async disable(request) {
        const owner = validateMutation(request);
        return this.exclusive(async () => {
            if (this.active && !owns(this.active.owner, owner)) throw new RelayError(409, '只有当前提供流量的手机可以关闭中转。');
            if (this.active) {
                const previous = this.active;
                this.routing.restore();
                this.diagnostics.stop();
                this.active = null;
                await previous.bridge.close();
            }
            return { ok: true, mode: 'normal' };
        });
    }

    async close() {
        this.closed = true;
        return this.exclusive(async () => {
            const previous = this.active;
            this.active = null;
            this.routing?.restore();
            this.diagnostics.stop();
            if (previous) await previous.bridge.close();
        });
    }
}

const managedEnvironment = /^(?:https?_proxy|all_proxy|no_proxy|npm_config_(?:https?_proxy|proxy|noproxy|no_proxy)|node_use_env_proxy|sillytavern_requestproxy_(?:enabled|url|bypass)|git_config_(?:count|key_\d+|value_\d+))$/i;

function installEnvironment(environment, snapshot) {
    for (const key of Object.keys(environment)) if (managedEnvironment.test(key)) delete environment[key];
    for (const [key, value] of Object.entries(snapshot)) if (managedEnvironment.test(key)) environment[key] = value;
}

/** Captures the post-startup routing once and restores it exactly when mobile mode ends. */
export class RuntimeRouting {
    constructor({ filter, environment = process.env, httpModule = http, httpsModule = https }) {
        this.filter = filter;
        this.environment = environment;
        this.http = httpModule;
        this.https = httpsModule;
        this.baseline = null;
        this.undo = null;
        this.proxyUrl = null;
        this.agents = [];
    }

    activate(proxyUrl) {
        const initial = this.baseline === null;
        if (!this.baseline) {
            this.baseline = { environment: { ...this.environment }, delegated: this.filter.isDelegated() };
        }
        const previousEnvironment = { ...this.environment };
        const previousDelegation = this.filter.isDelegated();
        const previousAgents = this.agents;
        let rollback;
        let replacementAgents = [];
        try {
            const next = buildEnvironment(this.baseline.environment, proxyUrl);
            // No host bypasses: mobile mode sends every outbound target to the phone's public-address policy.
            next.NO_PROXY = '';
            next.npm_config_noproxy = '';
            next.SILLYTAVERN_REQUESTPROXY_BYPASS = '[]';
            installEnvironment(this.environment, next);
            rollback = this.http.setGlobalProxyFromEnv(next);
            replacementAgents = [this.http.globalAgent, this.https.globalAgent];
            this.filter.setDelegated(true);
        } catch (error) {
            if (rollback) rollback();
            for (const agent of new Set(replacementAgents)) agent.destroy();
            installEnvironment(this.environment, previousEnvironment);
            this.filter.setDelegated(previousDelegation);
            if (initial) this.baseline = null;
            throw error;
        }
        // The first restorer retains the real pre-plugin agents and native fetch dispatcher.
        // A replacement is committed before retiring the old agents, so failure preserves streams.
        if (initial) this.undo = rollback;
        this.agents = replacementAgents;
        this.proxyUrl = proxyUrl;
        for (const agent of new Set(previousAgents)) agent.destroy();
    }

    unapply() {
        if (!this.baseline) return;
        if (this.undo) this.undo();
        this.undo = null;
        for (const agent of new Set(this.agents)) agent.destroy();
        this.agents = [];
        installEnvironment(this.environment, this.baseline.environment);
        this.filter.setDelegated(this.baseline.delegated);
        this.proxyUrl = null;
    }

    restore() {
        this.unapply();
        this.baseline = null;
    }
}

export function registerRoutes(router, controller) {
    const run = operation => async (request, response) => {
        response.set('Cache-Control', 'no-store');
        try { response.json(await operation(request)); }
        catch (error) {
            response.status(error instanceof RelayError ? error.status : 500)
                .json({ ok: false, message: error instanceof RelayError ? error.message : '中转控制失败，请稍后重试。' });
        }
    };
    router.get('/status', run(request => controller.status(request)));
    router.get('/diagnostics', run(request => controller.diagnosticStatus(request)));
    router.post('/enable', run(request => controller.enable(request)));
    router.post('/disable', run(request => controller.disable(request)));
}
