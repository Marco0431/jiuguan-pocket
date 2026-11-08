import { channel } from 'node:diagnostics_channel';

const emptyOutbound = () => ({ requests: 0, responses: 0, failures: 0, last: null });
const emptyBridge = () => ({ httpRequests: 0, connectRequests: 0, httpForwarded: 0,
    tunnelsEstablished: 0, failures: 0, lastError: null });

// Only hostnames leave this module. Never retain URL paths, headers, bodies or error text.
function hostname(value, origin = false) {
    if (typeof value !== 'string' || value.length > 2048) return null;
    try {
        if (!origin && /[\s/@?#\\]/.test(value)) return null;
        const result = new URL(origin ? value : `http://${value}`).hostname.toLowerCase();
        return result.length <= 253 && /^[a-z0-9.:[\]-]+$/.test(result) ? result : null;
    } catch { return null; }
}

/** Passive process-level observations while mobile routing is active; TLS remains end-to-end. */
export class RelayDiagnostics {
    constructor() {
        this.active = false;
        this.since = null;
        this.stoppedAt = null;
        this.bridgeId = null;
        this.bridgeCounts = emptyBridge();
        this.outbound = { nodeHttp: emptyOutbound(), nativeFetch: emptyOutbound() };
        this.requests = new WeakMap();
        this.generation = null;
        this.ignoredAgents = new WeakSet();
        this.subscriptions = [];
    }

    start(bridgeId) {
        this.stop();
        this.active = true;
        this.bridgeId = bridgeId;
        this.since = new Date().toISOString();
        this.stoppedAt = null;
        this.bridgeCounts = emptyBridge();
        this.outbound = { nodeHttp: emptyOutbound(), nativeFetch: emptyOutbound() };
        this.generation = {};
        const subscribe = (name, handler) => {
            // Diagnostic consumers must never throw into the HTTP implementation.
            const guarded = value => { try { if (this.active) handler(value); } catch {} };
            const source = channel(name); source.subscribe(guarded);
            this.subscriptions.push([source, guarded]);
        };
        // Creation also covers failures while opening a proxy tunnel, before request.start fires.
        subscribe('http.client.request.created', ({ request }) => this.observeStart('nodeHttp', request));
        subscribe('http.client.request.start', ({ request }) => this.observeStart('nodeHttp', request));
        subscribe('http.client.response.finish', ({ request, response }) => this.observeResult('nodeHttp', request, response?.statusCode));
        subscribe('http.client.request.error', ({ request }) => this.observeResult('nodeHttp', request, null));
        subscribe('undici:request:create', ({ request }) => this.observeStart('nativeFetch', request));
        subscribe('undici:request:headers', ({ request, response }) => this.observeResult('nativeFetch', request, response?.statusCode));
        subscribe('undici:request:error', ({ request }) => this.observeResult('nativeFetch', request, null));
    }

    stop() {
        if (this.active) this.stoppedAt = new Date().toISOString();
        this.active = false;
        for (const [source, handler] of this.subscriptions) source.unsubscribe(handler);
        this.subscriptions = [];
    }

    ignoreAgent(agent) { this.ignoredAgents.add(agent); }

    observeStart(kind, request) {
        if (!request || request.method === 'CONNECT' || this.ignoredAgents.has(request.agent)) return null;
        if (this.requests.has(request)) {
            const known = this.requests.get(request);
            return known.generation === this.generation ? known : null;
        }
        const host = hostname(kind === 'nativeFetch' ? String(request.origin) : request.host, kind === 'nativeFetch');
        if (!host) return null;
        const record = { kind, hostname: host, responded: false, failed: false, generation: this.generation };
        this.requests.set(request, record);
        const counts = this.outbound[kind]; counts.requests++;
        counts.last = { hostname: host, status: null, phase: 'started' };
        return record;
    }

    observeResult(kind, request, status) {
        // A completion from an older session must not enter the current session's counters.
        const record = this.requests.get(request);
        if (!record || record.kind !== kind || record.generation !== this.generation) return;
        const counts = this.outbound[kind];
        if (Number.isInteger(status) && status >= 100 && status <= 599) {
            if (record.responded) return;
            record.responded = true; counts.responses++;
            counts.last = { hostname: record.hostname, status, phase: 'api_response' };
        } else {
            if (record.failed) return;
            record.failed = true; counts.failures++;
            counts.last = { hostname: record.hostname, status: null, phase: 'request_failed' };
        }
    }

    bridgeEvent(bridgeId, event, details = {}) {
        if (!this.active || bridgeId !== this.bridgeId) return;
        const counters = { http_request: 'httpRequests', connect_request: 'connectRequests',
            http_forwarded: 'httpForwarded', tunnel_established: 'tunnelsEstablished' };
        if (counters[event]) this.bridgeCounts[counters[event]]++;
        if (event === 'failure' && ['format', 'phone_connect', 'upstream_io'].includes(details.phase)) {
            this.bridgeCounts.failures++;
            this.bridgeCounts.lastError = { hostname: hostname(details.hostname),
                status: details.phase === 'format' ? 400 : 502, phase: details.phase };
        }
    }

    snapshot(routeMode) {
        return structuredClone({ protocol: 1, routeMode, since: this.since, stoppedAt: this.stoppedAt,
            bridge: this.bridgeCounts, outbound: this.outbound });
    }
}
