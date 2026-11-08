# 诊断测试版验证记录

版本：1.3.3（versionCode 7），包名 `com.jiuguan.client`。验证日期：2026-11-09。

本版用于定位“酒馆页面能打开，但模型列表或发送显示 Bad Request”。已修正公网多地址依次重试可能超过电脑代理等待上限的问题；尚未复现或确认用户实际 HTTP 400 的根因，不能据此宣称已经修复该错误。

## 自动检查

- Android 构建：`assembleDebug testDebugUnitTest lintDebug` 成功，最低 Android API 26，目标 API 35。
- Android JVM：61 项测试全部通过，无失败、错误或跳过。新增地址过滤与去重、IPv4/IPv6 交错回退、每次 3 秒与总计 12 秒预算、DNS 耗时扣除、网络取消、分路由诊断和隐私回归。
- Android Lint：0 errors / 5 warnings，涉及 JavaScript、电池优化入口、备份规则和两个可拖动按钮的辅助功能提示。未关闭 lint 错误检查。
- Node.js：`server-plugin.test.mjs`、`server-plugin-install.test.mjs`、`phone-relay.test.mjs` 共 33 项测试全部通过，无失败或跳过。
- Node 测试覆盖管理员/CSRF/同源校验、配对和启停竞态、代理恢复、HTTP/fetch/WebSocket/Git 路由、分块上传，以及诊断只读权限、会话隔离、元数据过滤和旧版四文件插件升级。
- 离线 HTTPS 回归：实际 SillyTavern `node-fetch` 与 Node 原生 `fetch` 经运行时代理、HTTP/CONNECT 桥及认证 SOCKS 夹具完成模型列表 GET 与约 480 KB POST；TLS 证书校验、SNI 和远端 DNS 保持正确。模拟 API 400 能与 CONNECT 成功、桥接格式 400、手机连接失败区分；关闭采集和旧代理恢复通过。
- `apksigner verify --verbose` 通过，APK 使用 v2 调试签名，与 1.3.2 的证书相同。签名私钥不在仓库中。
- `aapt dump badging` 确认包名、版本号、最低与目标 Android 版本。
- 插件 ZIP 已核对 10 个文件：5 个插件运行文件、3 个安装入口文件、安装说明和 MIT 许可证，包含新的 `relay-diagnostics.mjs`。

以上网络测试使用本地夹具，不调用真实模型 API，不使用真实 API 密钥。运行方法和外部依赖要求见 [测试说明](tools/TESTING.md)。

## 安装包校验

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `jiuguan-pocket-1.3.3.apk` | 1,248,799 | `f20b52967a5f5f60e274b48bbd1b0668dc95ed5168cbd6ede8b3aca9fa12659c` |
| `jiuguan-server-plugin.zip` | 20,312 | `71910022fd29c9469a14cdebb9b1e316fd21f01cc666a22f366918368196cf74` |

升级需要同时更新 APK 和电脑插件，再重启酒馆一次。安装器保留原配置备份，不自动结束正在运行的酒馆。

## 实际手机上的下一步

选择手机模式，重现一次模型列表或发送失败，然后进入「更多」→「连接诊断」→「刷新」→「复制诊断」。报告只包含网络状态、目标域名、状态码和计数，不包含 API 密钥、密码、URL 路径、请求头或聊天正文；诊断不会发送模型请求。

检查手机 DNS／TCP 阶段、电脑 HTTP 转发与 CONNECT 计数、实际上游状态。局域网页面成功、代理配对成功、TCP 隧道建立都不能单独证明 API 可用。并发请求可能覆盖“最近一次”记录，仍应结合发生时间和目标域名判断。

## 尚待真机验收

尚未执行本版安卓真机或模拟器测试。自动检查不能证明各品牌系统允许无外网 Wi-Fi 和蜂窝长期同时使用，也不能替代真实 WebView、AndroidKeyStore、登录与插件配对集成测试。

还需检查首次配置、自动进入与失败返回、取消重连、双网络路由、锁屏流式回复、网络恢复和厂商省电策略。蜂窝出口不经过手机 VPN；系统 DNS 调用本身不能强制限时，可能超过连接预算。

手机网页仍在手机渲染；后台保持不保证所有情况下不断流，已取消的生成不会自动重发。覆盖范围和实际限制见 [README](README.md)。
