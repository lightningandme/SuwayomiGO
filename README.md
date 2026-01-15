![image](https://github.com/lightningandme/SuwayomiGO/blob/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp)
# SuwayomiGO🚀
[![GitHub](https://img.shields.io/badge/GitHub-181717?logo=github)](https://github.com/lightningandme/SuwayomiGO)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![GitHub tag (latest by date)](https://img.shields.io/github/v/tag/lightningandme/SuwayomiGO?label=download&color=green)](https://github.com/lightningandme/SuwayomiGO/releases)

SuwayomiGO 是一个轻量化的安卓漫画阅读器。它需要搭配漫画服务器 [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server)
使用（一个免费、开源、跨平台的漫画服务器，原名 Tachidesk）。
这个阅读器本质上是利用安卓设备上系统自带的WebView内核来访问 Suwayomi WebUI
的套壳客户端，在此基础上增强了一些功能和体验。

## ✨ 核心功能
- ⚡ **阅读助手**：加入日语查词与AI翻译功能，为你看生肉、学日语提供便利。
- 🔒 **自动登录**：支持保存WebUI的基础验证信息，自动完成服务器验证。
- 📖 **物理翻页**：支持使用音量键直接翻页，适配移动设备的物理按键。
- 🖼️ **图片保存**：支持直接将漫画页长按保存至本地相册，以漫画及章节命名。
- ✒ **适配墨水屏**：尽量减少界面动画，并支持按键翻页时全屏刷新以消除残影。
- 🔬 **更多功能酝酿中...**

## 🔍️ 简单演示
![demo2](demo/demo2_search.gif)
![demo3](demo/demo3_anki.gif)

## 📘 快速开始
0、准备工作：你需要一台能保持开机的电脑和一部不太旧的安卓设备。\
1、前往Suwayomi-Server的 [发布页](https://github.com/Suwayomi/Suwayomi-Server/releases)
下载适合你电脑系统的服务器安装包，新手建议选择Windows上手，家里有NAS的可自行docker部署。\
2、以Windows服务器为例，首先添加漫画源
https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json 点击启动。
