<p align="center">
  <img src="https://github.com/lightningandme/SuwayomiGO/blob/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" width="120" alt="SuwayomiGO Logo">
</p>

<h1 align="center">SuwayomiGO 🚀</h1>

<p align="center">
  <a href="https://github.com/lightningandme/SuwayomiGO"><img src="https://img.shields.io/badge/GitHub-181717?logo=github" alt="GitHub"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License"></a>
  <a href="https://github.com/lightningandme/SuwayomiGO/releases"><img src="https://img.shields.io/github/v/tag/lightningandme/SuwayomiGO?label=Download&color=green" alt="Download"></a>
  <a href="https://t.me/+zpmMSsWPdQ0yOTU1"><img src="https://img.shields.io/badge/Telegram-Group-blue?logo=telegram" alt="Telegram Group"></a>
</p>

---

**SuwayomiGO** 是一个专为安卓设计的轻量化漫画阅读客户端。它基于 [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server)（原 Tachidesk）构建，通过 WebView 技术完美衔接 WebUI，并在其基础上深度定制了**日语学习与 AI 翻译**等增强功能。

---

## ✨ 核心功能

- ⚡ **阅读助手**：深度集成 **Manga-OCR** 与 **AI 翻译**，看生肉漫画、查词学日语从未如此简单。
- 🔒 **自动登录**：内置基础验证（Auth）支持，告别反复输入密码的烦恼。
- 📖 **物理按键**：完整适配音量键翻页，单手握持体验更佳。
- 🖼️ **高清存图**：支持长按保存漫画页，自动按“漫画名-章节”命名，整理更方便。
- ✒️ **墨水屏优化**：针对 E-Ink 设备精简动画，支持全屏刷新，有效消除余影。
- 🔬 **持续进化**：更多实用功能正在密集开发中...

## 🔍 演示动画

|生肉漫画点选翻译|查词与 Anki 制卡|
|:-------------------------------:|:-----------------------------:|
| ![demo2](demo/demo2_search.gif) | ![demo3](demo/demo3_anki.gif) |

---

## 📘 快速开始

### 🛠️ 第一阶段：搭建基础阅读环境 (Level 1)

1. **准备服务器**：下载并运行 [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server/releases)（Windows 用户推荐一键安装包），可在漫画网页的 **Settings-Appearance-Language** 里设置中文。
2. **添加图源**：在 **设置-浏览-扩展源** 中添加 `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json` 等插件仓库，在 **浏览-插件** 中，点击右上角设置并打开日语筛选，搜索并添加Rawkuma、KissLove等生肉图源）。更多有关Suwayomi的基本操作，可参考 [这个教程](https://ivonblog.com/posts/suwayomi-tachidesk/) 。
3. **安装客户端**：下载本项目的 [最新 APK](https://github.com/lightningandme/SuwayomiGO/releases) 。
4. **连接配置**：
    - 确保手机与电脑在**同一局域网**。
    - 输入服务器 IP（如 `192.168.1.5:4567`）。
    - 若设置了安全验证，请对应填写用户名与密码。

### 🤖 第二阶段：激活 AI 翻译与 OCR 功能 (Level 2)

1. **获取 API Key**：推荐前往 [DeepSeek 开放平台](https://platform.deepseek.com/) 获取高性价比的 API 令牌。
2. **部署 OCR 后端**：
    - 前往 [Manga-OCR-Server](https://github.com/lightningandme/Manga-OCR-Server) 下载**离线整合包**。
    - 解压并双击 `Run_Server.bat` 启动。
3. **客户端联动**：在 SuwayomiGO 的“更多设置”中填入 OCR 服务器地址。
4. **开启姿势**：在漫画阅读界面 **[大幅度下拉]** 或 **[长按音量+]** 即可进入 OCR 划词模式。

### 🌐 第三阶段：更多用法等你探索 (Level 3)
1. **全天候运行**：若你拥有 NAS（如运行 **fnOS (飞牛)** 的小主机），建议使用 Docker 部署服务器，实现 24 小时待命。
2. **远程访问**：
    - **公网方案**：申请域名并动态绑定家中的 **IPv6** 地址。
    - **内网穿透**：利用 **ZeroTier** 或 **Tailscale** 搭建虚拟局域网。
3. **词典自定义**：内置词典源自《明镜日汉双解词典_Yomitan_民间版》。若需扩充，可运行 [Manga-OCR-Server](https://github.com/lightningandme/Manga-OCR-Server) 项目中的 `convert_yomitan.py` 自行转换其他 **Yomitan** 格式词典。

---

## ❓ 常见问题 (FAQ)

### Q1: OCR 服务器启动时显示 `未检测到 API_KEY` 是怎么回事？
**A**: 这是因为程序检测到同目录下的 `.env` 文件中没有填写大模型API Key，服务器会自动启用Google翻译模式，如果想要更好的翻译效果，请填写你自己的 API 令牌。

### Q2: 为什么我的手机连接不上电脑上的服务器？
**A**: 请按顺序检查：
1. 手机和电脑是否连接在同一个局域网下（即同一个路由器下）。
2. 电脑的防火墙是否拦截了 `4567` (Suwayomi) 或 `12233` (OCR) 端口。
3. 在电脑命令行输入 `ipconfig` 确认填写的 IP 地址是否正确。

### Q3: 使用 AI 翻译功能收费吗？
**A**: 本项目完全免费。但你使用的 AI 接口（如 DeepSeek, OpenAI）会根据字符量收取极低的费用（有一定日语基础，正常频率查词，通常 10 元钱足够阅读上千话漫画）。

### Q4: OCR 模式下反应有点慢？
**A**: 如果你的电脑有 NVIDIA 显卡，请确保已正确安装显卡驱动，OCR 整合包将自动启用 GPU 加速。纯 CPU 模式下的识别速度取决于你的电脑 CPU 性能。同时，也请尽量把服务器安装在固态硬盘上面。

---

## 📢 联系与反馈
- **问题反馈**：请提交 [GitHub Issues](https://github.com/lightningandme/SuwayomiGO/issues)
- **交流群组**：[Telegram Group](https://t.me/+zpmMSsWPdQ0yOTU1)
- **鸣谢**：感谢 [Suwayomi](https://github.com/Suwayomi) 提供的强大后端支持。

---
<p align="center">Made with ❤️ for Manga Lovers</p>
