---
docType: guide
scope: repo
status: current
authoritative: true
owner: tv-dashboard
language: zh-CN
whenToUse: "When using or maintaining TV Dashboard."
whenToUpdate: "When configuration, supported devices, builds or release behavior changes."
checkPaths: [app/**, config/**, scripts/**, tests/**, .github/**, gradle.properties]
lastReviewedAt: 2026-10-02
lastReviewedCommit: fa94e49
---

# 天工电视看板

[English](README.md)

用于 Android TV / MIUI TV 的网页看板客户端，内置 GeckoView 浏览器内核。

- 全屏、前台屏幕常亮，遥控器菜单键或返回键打开操作菜单。
- 支持刷新、大屏、工作台、修改启动网址及查看系统信息。
- 主页面连接失败后每15秒重试；内容进程停止后前台自动恢复，后台等待重新打开应用。
- 单一 APK 同时包含 **ARM 32位和64位**，要求 **Android 8或更新**。

## 安装

从 [GitHub Releases](https://github.com/tiangong-ai/tv-dashboard/releases) 下载已签名 APK，复制到U盘，通过电视文件管理器安装。相同签名的新版可覆盖安装。

默认服务器根地址为 `http://192.168.1.12:8787/`，启动打开 `/display/`，工作台打开 `/`。本仓库只包含客户端，服务器需要单独提供。电视必须能够访问服务器。

通过菜单中的“修改看板地址”可以设置设备启动网址；已保存的设置优先于安装包默认值，更新不会重置该设置。

## 自定义默认地址

修改 `config/dashboard.properties`：

```properties
dashboardBaseUrl=http://192.168.1.12:8787/
```

也可以在构建时覆盖：

```sh
./gradlew :app:assembleRelease -PdashboardBaseUrl=https://dashboard.example.org/
```

仅接受 HTTP(S) 根地址，不包含账号密码、查询参数或片段。支持路径前缀，例如 `/monitor/` 对应大屏 `/monitor/display/`。编译默认值用于首次安装；电视已保存的网址继续生效。

## 构建与发布

安装 JDK17、Python3.11+、Android SDK37.1及 Build Tools36.0.0，设置 `ANDROID_HOME` 或本地 `local.properties` 的 `sdk.dir`，运行：

```sh
python3 -m unittest discover -s tests -v
./gradlew :app:assembleRelease :app:lintRelease
python3 scripts/check_config.py --gradle
```

默认构建包含 `armeabi-v7a` 与 `arm64-v8a`。未签名 APK 输出于 `app/build/outputs/apk/release/`。签名和自动发布流程见 [发布说明](docs/releasing.md)：推送与版本一致的 `v*` tag 后，GitHub Actions 自动验证、签名并发布 APK及校验文件。

本应用不提供开机自启、服务器数据采集或网页API错误修复。早期版本在Android10小米电视验证可用；公开新版仍需目标电视安装确认。

应用代码使用MIT许可证，浏览器内核及依赖另遵循其许可证，详见 [NOTICE.md](NOTICE.md)。
