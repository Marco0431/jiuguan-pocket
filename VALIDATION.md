# 1.3.4 验证记录

Android 版本：1.3.4（versionCode 8），包名 `com.jiuguan.client`。验证日期：2026-11-10。电脑插件沿用 1.3.3，不需要重复安装。

## 真机复现与修复验证

设备为小米 17，系统报告 Android 17。通过 USB 调试测试实际 App、局域网中的真实酒馆和用户已配置的手机 VPN；没有导出聊天或读取 API 密钥。测试消息使用酒馆已有的 Test Message 功能，只发送 `Hi`。

1. 在 1.3.3 手机模式下，电脑已确认切换到手机代理。同一桥接链路访问百度、小米的 HTTPS 均完成证书校验；Google OAuth 与 AI Platform 连接失败，电脑桥接记录 `phone_connect / 502`。
2. 真实 Vertex AI Full/global 测试在 OAuth 阶段失败，酒馆返回 HTTP 400，尚未发出模型请求。手机诊断显示蜂窝可用、DNS 已返回，公网 TCP 连接超时。
3. 1.3.3 的实现显式绑定蜂窝网络，绕过手机 VPN。1.3.4 改为优先选择覆盖本应用的默认 VPN，并将公网 DNS 和 Socket 绑定到同一个 VPN Network；没有适用 VPN 时保持蜂窝直连。酒馆页面和控制接口仍走局域网 Wi-Fi。
4. 覆盖安装 1.3.4 后，既有连接配置和登录保留，App 重新进入酒馆。经过手机中转的 Google OAuth 和 AI Platform TLS 检查均通过。
5. 再执行一次真实 Test Message：酒馆返回 HTTP 200，响应包含模型回复，无 `error`；插件记录 OAuth 和模型请求均有响应，最近上游 `aiplatform.googleapis.com` 返回 200。

**验证边界：本次 VPN 报告的底层是 Wi-Fi。已验证实际手机 VPN 出口与真实模型请求；尚未模拟校园外网断开后 VPN 改走蜂窝的全过程。** 普通应用不能替其他 VPN 应用强制选择底层网络；无外网 Wi-Fi 场景需要确认手机代理自身可通过移动数据联网。

## 自动检查

- `assembleDebug testDebugUnitTest lintDebug` 成功，最低 Android API 26，目标 API 35。
- Android JVM：72 项全部通过，无失败、错误或跳过。新增默认网络/VPN 选择、旧回调隔离、阻断状态、受限 FakeIP 与诊断过滤测试，保留地址回退和总连接预算回归。
- Android Lint：0 errors / 5 warnings。未关闭 lint 错误检查。
- 电脑端现有 33 项 Node.js 测试与 HTTPS 回归在前次修订已通过，本次插件运行文件未改动。
- 本次新增真实酒馆集成工具 `tools/tavern-e2e-smoke.mjs`，13 项场景全部通过，保留 keep-alive。测试链路包含真实 `server.js`、登录/CSRF/来源校验、当前插件和 App 的实际 Java SOCKS 核心，上游为本机可信 TLS 假 API。
- 这 13 项包含模型列表、普通生成、延迟 SSE、无密钥本地 400、上游 400、Vertex Full/global JWT 与 OAuth、认证连接中断映射 400、代理关闭反向检查和诊断隐私过滤。临时账号、配置、进程与监听已清理，不使用真实服务账号或模型额度。
- 旧 Java SOCKS 集成工具通过回归。新工具与真机测试是不同层次的证据，不能相互代替。
- `apksigner verify --verbose` 通过，v2 调试签名证书与 1.3.2/1.3.3 相同；已在真机覆盖安装并核对版本号。

## 安装包校验

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `jiuguan-pocket-1.3.4.apk` | 1,253,055 | `3600b54a73e38e74be8ecd207ab90bb554a753fd0f903e4dfffdf4b6c23fa9d3` |
| `jiuguan-server-plugin.zip` | 20,312 | `71910022fd29c9469a14cdebb9b1e316fd21f01cc666a22f366918368196cf74` |

插件 ZIP 与 1.3.3 相同，包含 5 个运行文件、3 个安装入口文件、安装说明和 MIT 许可证。已安装 1.3.3 插件的电脑只需更新手机 APK；更早版本应先更新电脑插件并重启酒馆。

## 仍需验证的场景

不同厂商/系统版本、不同 VPN 的分应用规则、VPN 严格锁定、校园断网后的真实蜂窝底层切换，以及长期锁屏流式回复仍需验证。VPN 必须覆盖酒馆口袋并允许局域网访问；FakeIP 仅对 VPN DNS 返回的 IPv4 `198.18.0.0/15` 且原目标为合法域名时放行，其他内网地址继续禁止。

本版没有新增代理节点或修改第三方代理配置。手机网页仍在手机渲染，后台保持不保证所有情况下不断流，取消或断开的生成不会自动重发。复现方法见 [TESTING.md](tools/TESTING.md)，范围与限制见 [README](README.md)。
