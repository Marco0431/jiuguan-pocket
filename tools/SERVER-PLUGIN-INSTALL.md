# 一次安装，以后照常启动酒馆

1. 完整解压安装包，保持安装器和 `jiuguan-relay` 文件夹的相对位置不变。
2. 双击 `安装酒馆插件.cmd`。可以先停止酒馆再安装，也可以完成安装后再重启；安装本身不会中断正在运行的酒馆。
3. 安装器会查找酒馆目录；未找到时，填写包含 `server.js` 的 SillyTavern 文件夹。
4. 安装完成后，重启酒馆一次使插件生效，照常使用原来的 `Start.bat`。以后不需要另开手机代理启动器。
5. 在新版手机 App 的网络模式中选择「使用手机流量」，完成与电脑酒馆的连接。

电脑只需要局域网。酒馆依赖须已安装，本安装器不会联网下载或运行 `npm install`。Node.js 需要支持运行时切换代理的版本，例如 24.14+、25.4+；安装器会实际检测所需能力。

1.3.3 增加了服务端诊断组件。升级时请同时更新 APK 和本插件，再重启酒馆一次；仅更新 APK 不会替换电脑插件。诊断用于继续排查请求失败，安装完成不代表手机发送时的 `Bad Request` 原因已经确认或解决，仍需在实际手机网络上验证。

## 安装器修改什么

- 只复制插件所属的五个文件到 `plugins/jiuguan-relay`：`package.json`、`index.mjs`、`relay-controller.mjs`、`phone-relay-bridge.mjs`、`relay-diagnostics.mjs`；不会删除该目录中的其他文件。
- 将 `config.yaml` 的 `enableServerPlugins` 改为 `true`。
- 将 `enableServerPluginsAutoUpdate` 改为 `false`，避免电脑无外网时启动阶段等待插件 Git 更新。需要更新服务端插件时，请在联网后手动更新。
- 对已知版本 `Start.bat` 的 npm 安装命令加上依赖存在检查。已安装依赖时直接启动，避免每次离线启动先尝试 npm 联网。若以后更新酒馆新增了依赖，需要在可联网时手动执行该版本要求的依赖安装。

原文件会先备份到酒馆目录下的 `backups/jiuguan-relay-install/日期时间-随机标识/`，属于酒馆已忽略的备份目录，不会因普通 Git 添加操作而意外提交。配置文件中的密码、注释和其他设置都保留，不会输出到安装日志。备份包含原配置，请与原酒馆数据一样妥善保存。

安装器遇到无法识别的启动脚本、配置格式、符号链接或不完整插件包会停止，避免盲目修改。重复运行是安全的：文件相同就不会重复修改或生成新备份。

旧版已经安装的四文件插件可直接覆盖升级，安装器会补上新的 `relay-diagnostics.mjs`，不要求安装目录中预先存在它。如果安装包缺少该文件，会在写入前停止，避免安装出不完整的新插件。原有备份和失败回滚流程保持不变。

## 安装包完整性

分发包应包含 `安装酒馆插件.cmd`、`install-server-plugin.cmd`、`install-server-plugin.mjs`、本说明，以及 `jiuguan-relay` 文件夹中的上述五个运行文件。项目构建将 `server-plugin/jiuguan-relay` 文件夹一起打包；发布前应检查压缩包内确实包含 `jiuguan-relay/relay-diagnostics.mjs`，不能只复制旧版四个文件。

## 命令行检查

```powershell
.\install-server-plugin.cmd --dry-run --tavern "C:\SillyTavern"
.\install-server-plugin.cmd --tavern "C:\SillyTavern"
```

`--dry-run` 只检查并显示预计变更文件数，不修改酒馆。安装失败会尝试恢复本次已修改文件；若同时有其他程序改动文件，安装器会保留备份并提示手工恢复，不覆盖新的编辑。

## 恢复

先停止酒馆。使用安装器提示的备份目录，把需要恢复的 `config.yaml`、`Start.bat` 或原插件文件复制回对应位置。恢复前如这些文件已有新的修改，请先合并或另存当前版本，避免覆盖之后的设置。首次安装前不存在的插件文件会在备份 `manifest.json` 中标为 `existed: false`；停止使用本功能时也可以仅在酒馆配置中关闭服务端插件，但这会影响其他服务端插件。

安装器不会自动删除用户文件或更改 Windows 系统代理。
