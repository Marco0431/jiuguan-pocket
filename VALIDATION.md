# 发布验证记录

版本：1.3.2（versionCode 6），包名 `com.jiuguan.client`。验证日期：2026-11-07。

## 自动检查

- Android 构建：`assembleDebug testDebugUnitTest lintDebug` 成功，最低 Android API 26，目标 API 35。
- Android JVM：50 项测试全部通过，无失败或错误。覆盖地址校验、认证与凭据加密、SOCKS 核心、地址策略、插件控制、连接超时与取消、成功配置和单次自动启动策略。
- Android Lint：0 errors / 5 warnings，分别涉及 JavaScript、电池优化入口、备份规则和两个可拖动按钮的辅助功能提示。未关闭 lint 错误检查。
- Node.js：`server-plugin.test.mjs`、`server-plugin-install.test.mjs`、`phone-relay.test.mjs` 共 30 项测试全部通过，无失败或跳过。
- Node 测试覆盖管理员/CSRF/同源校验、来源地址约束、配对和启停竞态、原代理恢复、HTTP/fetch/WebSocket/Git 路由、分块上传，以及安装器预检、备份、并发修改防护、回滚和重复安装。
- `apksigner verify --verbose` 通过，APK 使用 v2 调试签名。发布版沿用原签名，签名私钥不在仓库中。
- 发布版默认酒馆地址留空，用户首次填写自己的局域网地址。没有打包酒馆配置、聊天数据、API 密钥、配对密钥或本机 SDK 路径。
- 插件 ZIP 包含 4 个插件文件、3 个安装入口文件、安装说明与 MIT 许可证。APK 包含项目及第三方许可说明。

以上网络测试使用本地夹具，不调用真实模型 API。运行方法和外部依赖要求见 [测试说明](tools/TESTING.md)。

## 安装包校验

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `jiuguan-pocket-1.3.2.apk` | 1,305,029 | `1f72005d089e4a0b3b9d8ccd30e1780130b05b7d273ad381d930804d89dd4c7f` |
| `jiuguan-server-plugin.zip` | 17,564 | `b252ab2fab77916d82613043d22604d371791652038056e501137f67bd1beaeb` |

安装器已经验证只修改预期插件开关和启动脚本的依赖检查，重复执行无多余修改，并保留原配置备份。安装后需要重启酒馆一次才会加载插件；安装器不会自动结束正在运行的酒馆。

## 尚待真机验收

当前没有连接安卓真机或模拟器。自动检查不能证明各品牌系统允许无外网 Wi-Fi 和蜂窝长期同时使用，也不能替代真实 WebView、AndroidKeyStore、登录与插件配对的集成测试。

需要在真实设备上检查首次配置、成功后自动进入、失败或超时返回配置、快速取消和重连、双网络路由、锁屏流式回复、网络恢复和厂商省电策略。用户若反馈问题，请附手机型号、安卓/WebView 版本和不包含密码、API 密钥的复现步骤。

手机网页仍在手机渲染；后台保持不保证所有情况下不断流，已取消的生成不会自动重发。具体网络限制见 [README](README.md)。
