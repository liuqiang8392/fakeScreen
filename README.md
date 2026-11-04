# fakeScreen

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/Xposed%20API-Modern%20API%20102-brightgreen)](https://github.com/libxposed/api)
[![Platform](https://img.shields.io/badge/Platform-Android%209%2B-orange)](https://developer.android.com/)

**FakeScreen** 是一款基于 Xposed 架构开发的 Android 模块，实现熄屏挂机功能，并可自定义电源键按下后的屏幕亮度，以及在硬件最低背光之上进一步压暗画面。

---

## 核心特性

模块激活并开启功能后按下电源键不再走原生锁屏流程，而是按以下规则响应：

- **熄屏挂机**：功能开启后按电源键仅熄灭屏幕，不锁屏、不中断后台。
- **开关状态跨重启保留**：功能开关、亮度目标、极暗强度都会持久化，重启后自动恢复上次状态。
- **电源键亮度自定义**：0 表示直接熄屏；1–255 表示按电源键把屏幕亮度降到该值，再按一次恢复原来的亮度和亮度模式。
- **极暗强度**：调用系统 Reduce Bright Colors（MIUI / HyperOS 的“极暗模式”），在背光最低档之后再从画面合成阶段继续压暗，可获得比硬件最低背光更暗的显示效果；0 为关闭，1–100 越大越暗。

---

## 兼容性

已在以下设备上进行验证

| 设备            | 系统                  | Android 版本 |
|:--------------|:--------------------|:-----------|
| Pixel 6       | GrapheneOS          | Android 16 |
| MiPad6 Pro    | HyperOS 2.0.213.0   | Android 15 |
| Redmi K20 Pro | MIUI / HyperOS      | Android 13 |

---

## 使用说明

1. **安装与激活**：
    * 下载安装 APK 后，在 LSPosed 管理器中启用 **FakeScreen** 模块。
    * **作用域说明**：选择 **系统框架**。
    * 重启设备。

2. **功能开关**：
    * 打开 **FakeScreen** App，切换主界面开关状态；开关状态跨重启保留。

3. **电源键亮度**：
    * 通过滑块、数字输入框或 `＋` / `－` 按钮设定 0–255 的目标亮度。
    * `0` = 按下电源键熄灭屏幕；`1–255` = 按下电源键把亮度降到该值，再按一次恢复原亮度。
    * 不允许负值；若原本是自动亮度，降亮期间临时切为手动，还原时一并恢复。

4. **极暗强度**：
    * 滑块 0–100，0 为关闭，数值越大画面越暗。
    * 这是系统级压暗（SurfaceFlinger 颜色矩阵），与背光无关，跨重启保留；与系统设置里的“极暗模式”是同一份状态。

---

## 🙏 致谢

本项目基于原作者的开源项目 [chuyb-re/fakeScreen](https://github.com/chuyb-re/fakeScreen) 二次开发，保留了其 Xposed 架构与熄屏挂机的核心实现，并在其上扩展了电源键亮度、极暗强度等功能。感谢原作者的开源工作。

---

## 🛠️ 项目构建 (Building)

你可以使用 Android Studio 打开项目并直接构建，或者通过 Gradle 命令行构建 release APK：

```bash
git clone https://github.com/chuyb-re/fakeScreen.git
cd fakeScreen
./gradlew assembleRelease
```
