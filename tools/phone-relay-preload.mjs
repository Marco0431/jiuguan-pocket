import path from 'node:path';
import { createRequire } from 'node:module';

/**
 * Keep HTTP uploads on a CONNECT tunnel as well as HTTPS. Some installed
 * http-proxy-agent versions regenerate headers after an async connection and
 * duplicate them when a chunked request writes again later on recent Node.
 * The exported proxy-agent constructor registry lets us select the existing
 * CONNECT agent without patching Node internals or any SillyTavern file.
 */
export function installConnectProxyAgents(tavernDirectory = process.cwd()) {
    const requireTavern = createRequire(path.resolve(tavernDirectory, 'package.json'));
    const { proxies } = requireTavern('proxy-agent');
    const { HttpsProxyAgent } = requireTavern('https-proxy-agent');
    if (!proxies?.http || !proxies?.https || typeof HttpsProxyAgent !== 'function') {
        throw new Error('当前酒馆的代理依赖不兼容手机中转，请先更新酒馆依赖。');
    }
    const connectAgent = async () => HttpsProxyAgent;
    proxies.http = [connectAgent, proxies.http[1]];
    proxies.https = [connectAgent, proxies.https[1]];
}

if (process.env.JIUGUAN_RELAY_PRELOAD === '1') installConnectProxyAgents();
