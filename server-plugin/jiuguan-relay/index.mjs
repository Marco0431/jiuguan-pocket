import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createBridge, probePhone } from './phone-relay-bridge.mjs';
import { RelayController, RuntimeRouting, registerRoutes } from './relay-controller.mjs';

export const info = {
    id: 'jiuguan-relay',
    name: '酒馆口袋手机流量',
    description: '由已登录的手机 App 在正常连接与手机流量之间切换，无需复制连接地址。',
};

let controller;

export async function init(router) {
    let dependencies;
    let message = '';
    try {
        if (typeof http.setGlobalProxyFromEnv !== 'function') {
            throw new Error('需要 Node.js 24.14+ 或 25.4+，请更新电脑 Node.js 后重启酒馆。');
        }
        const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
        const requireTavern = createRequire(path.join(root, 'package.json'));
        const { SocksClient } = requireTavern('socks');
        const filter = await import(pathToFileURL(path.join(root, 'src/private-request-filter.js')).href);
        if (typeof filter.default !== 'function' || typeof filter.getUntrustedRequestAgent !== 'function') {
            throw new Error('当前酒馆版本的网络接口不兼容，请更新酒馆后重试。');
        }
        dependencies = {
            probe: probePhone,
            bridgeFactory: phone => createBridge({ phone, SocksClient, localDirect: false }),
            routing: new RuntimeRouting({ filter: {
                isDelegated: () => filter.getUntrustedRequestAgent() === undefined,
                setDelegated: delegated => filter.default({ listen: false, enabled: false, requestProxyEnabled: delegated }),
            } }),
        };
    } catch (error) {
        message = /^需要 Node\.js|^当前酒馆版本/.test(error.message) ? error.message
            : '手机流量插件依赖不完整，请重新安装插件或更新酒馆依赖。';
    }
    controller = new RelayController({ ...dependencies, supported: !!dependencies, message });
    registerRoutes(router, controller);
}

export async function exit() { if (controller) await controller.close(); }
