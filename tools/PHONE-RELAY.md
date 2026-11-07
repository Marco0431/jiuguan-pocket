# 电脑通过手机流量运行酒馆

适用于：电脑和手机在同一个局域网，电脑无法连接外网，手机同时有 Wi-Fi 和移动数据。这里提供的是酒馆进程专用的启动方式，不修改 Windows 系统代理，也不改酒馆的 `config.yaml`。

## 第一次使用

1. 安装新版「酒馆口袋」，连接局域网 Wi-Fi，打开移动数据。
2. 在 App 的「手机流量中转」中开启中转，等状态显示就绪，然后复制「电脑连接地址」。它包含本机配对密码，请只给自己的电脑使用。
3. 把本目录中的文件保留在同一个文件夹中。关闭原先已经运行的酒馆窗口。
4. 在电脑双击 `手机流量启动酒馆.cmd`（或英文同名入口 `start-phone-relay.cmd`）。启动器会查找已有的酒馆目录；没找到时，填写包含 `server.js` 的 SillyTavern 文件夹路径。
5. 粘贴手机复制的完整地址并回车。检查手机连接成功后，启动器直接运行已有酒馆。
6. 保持这个窗口和手机中转开启，在手机 App 中照常打开酒馆聊天。

需要 Node.js **24.5 或更新版本**，并且酒馆已经安装好依赖。本机检查到的 Node.js 为 25.4.0。电脑离线时不会自动运行 `npm install`；首次安装酒馆及 Node.js 需要预先准备好。

后续双击同一个启动器即可复用保存的配置。手机 Wi-Fi IP 变化、手机重装 App 或更换酒馆目录时，可运行：

```powershell
.\start-phone-relay.cmd --configure
.\start-phone-relay.cmd --configure --tavern "C:\SillyTavern"
.\start-phone-relay.cmd --check
```

`--check` 只检查并保存手机连接，不启动酒馆；同样不会连接真实模型 API。

启动器在同一目录生成 `phone-relay.local.json`。连接地址使用 Windows 当前用户的 DPAPI 加密保存，换电脑或 Windows 用户后需重新填写。不要把这个本机配置文件放进分发包。删除该文件即可清除保存的设置。

## 覆盖的联网请求

- 酒馆标准 HTTP/HTTPS 请求，包括聊天 API、模型和资源下载。
- Node.js 原生 `fetch`，以及使用默认 HTTP/HTTPS Agent 的服务端扩展。
- Git 通过 HTTP/HTTPS 安装、更新扩展，以及继承启动器环境的 npm HTTP/HTTPS 下载。
- 通过 HTTP 代理发起的 WebSocket 连接。
- 外网域名由手机解析；电脑不需要外网 DNS。电脑本机和数字 IP 的局域网服务保留直接连接。

手机 App 的网页外部资源由 App 的中转功能处理。电脑启动器只负责电脑端酒馆进程及继承其环境的子进程。电脑上另外打开的浏览器、其他软件和另一个酒馆窗口不会自动改变联网方式。

第三方插件如果自行指定代理、创建不使用代理的连接、自定义网络 Agent、使用 UDP、SSH Git 地址或独立的外部程序，可能需要该插件单独设置代理；无法仅凭一个 HTTP/SOCKS 代理强制所有任意程序联网。优先使用插件的 HTTPS 下载地址。代理不绕过 API 服务对地区、账户或运营商网络的限制。

## 停止和故障处理

- `Ctrl+C` 停止当前酒馆和电脑本机中转；下次照常运行原来的酒馆启动方式，原配置仍然有效。
- 手机中转关闭、移动数据断开、Wi-Fi 断开或手机应用被系统停止时，正在进行的请求会失败。不会自动改走电脑外网，也不会自动重发生成请求。
- 出现「无法连接手机中转」时，确认 App 已就绪、手机与电脑在同一 Wi-Fi、路由器没有启用客户端隔离；手机 IP 改变后使用 `--configure`。
- 如果酒馆提示端口被占用，关闭此前启动的酒馆再运行这个启动器。
- 配对检查只验证手机 SOCKS 登录，不连接模型 API、不产生 AI 调用费用。显示已连接并不代表移动数据能够访问所有 API 服务。

## 开发验证

在项目根目录运行 `node --test tools/phone-relay.test.mjs`。测试使用本机模拟 SOCKS 和 HTTP 服务，验证认证、外网域名转交手机、原生 fetch/Node HTTP/酒馆 node-fetch 流式传输、二进制 CONNECT、上传、WebSocket、Git 路由、关闭清理和断线不回退。不会启动生产酒馆或调用真实 AI API。

实现依据 Node.js 官方的 [代理支持](https://nodejs.org/api/http.html#built-in-proxy-support) 和 [NODE_USE_ENV_PROXY](https://nodejs.org/api/cli.html#node_use_env_proxy1)。HTTPS 使用 CONNECT 隧道传输，证书验证仍由酒馆、Git 或发起请求的客户端执行；启动器不解密 HTTPS 内容。

启动器还会在本次酒馆进程预加载 `phone-relay-preload.mjs`，通过酒馆现有 `proxy-agent` 导出的构造器表，让普通 HTTP 也使用 CONNECT 隧道。这避免旧版 HTTP 代理依赖在新 Node.js 上重复写入流式上传请求头。适配只作用于本次进程，不修改酒馆依赖文件或 Node.js 私有字段。
