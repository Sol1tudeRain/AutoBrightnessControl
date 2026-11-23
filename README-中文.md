# Brightness Link 0.1.0-beta（实验性源代码）

提供两个独立可调选项：

1. 系统原生自动亮度的最大允许输出，按 Android 亮度滑块的 Gamma 曲线近似换算到内部线性值。只在系统自动亮度控制器返回亮度时截断，手动亮度路径不修改。
2. AOD 亮度 = `DisplayManager.getBrightness(DEFAULT_DISPLAY)` × 自选百分比。系统 AOD 仍可能应用遮罩、熄屏动画、厂商面板调光限制；**不是物理 nits 的严格比例**。

## 状态和风险

**这是依据 AOSP API 编写的 Android 17 / PixelOS 实验版本，尚未在你的设备上编译或运行验证。** 我们所在环境没有 Android SDK/Gradle 网络构建能力，因此这里提供一键 GitHub Actions 构建的源代码和可安装的 KernelSU 控制面板 ZIP，而非假装已经编译成功的 APK。

默认 `auto_enabled=0` / `aod_enabled=0`，安装本身不改变亮度。对 System Framework 进行 Hook 有系统不稳定风险；请先确认能进入恢复模式，能通过 LSPosed 或救援模式禁用模块。

## 安装（两部分，缺一不可）

A. 构建和安装 `BrightnessLink-LSPosed`：将**整个工程**上传到你自己的 GitHub 仓库，Actions -> `Build LSPosed APK` -> `Run workflow`。下载 Actions artifact 中的 APK，在 Android 手机上安装，并于 LSPosed 管理器启用，scope 勾选 **System Framework (`system`/`android`) + System UI (`com.android.systemui`)**，重启。

B. 安装 `BrightnessLink-KernelSU-Panel.zip`：在 KernelSU 管理器的模块页安装，然后打开该模块的 WebUI。两个滑块和开关将保存到 Settings.Global，无需二次构建。通常无需重启来修改数值，但系统可能需要下一个亮度计算周期才刷新。

## 调试和恢复命令（Termux）

```sh
su -c 'settings get global brightnesslink_auto_enabled; settings get global brightnesslink_auto_cap; settings get global brightnesslink_aod_enabled; settings get global brightnesslink_aod_ratio'
```
依次读取自动上限启用状态、自动上限百分比、AOD 启用状态和 AOD 比例。

```sh
su -c 'settings put global brightnesslink_auto_enabled 0; settings put global brightnesslink_aod_enabled 0'
```
紧急关闭两个功能；`settings put global` 写入全局设置，`0` 表示关闭。

```sh
su -c 'logcat -d | grep BrightnessLink | tail -n 30'
```
查看最近的钩子错误/安装情况；有的 LSPosed 日志只在 LSPosed 管理器中显示。

严重问题时在 LSPosed 管理器**停用** Brightness Link APK 并重启；如果不能进入系统，需要通过恢复/救援模式禁用 Xposed 模块。KernelSU 面板 ZIP 本身只提供设置界面，不注入系统。

## 文件结构

- `app/`：LSPosed 模块 Android APK 工程；编译依赖本地只含接口签名的 `xposed-api-stubs.jar`（compileOnly，APK 里不会打包）。
- `.github/workflows/build.yml`：GitHub Actions 自动构建并输出 APK。
- `ksu-module/`：KernelSU WebUI 面板原始文件；发布 ZIP 为仓库中的独立文件。
- `stubs/`：用于编译的 Xposed API 接口桩，仅供 Gradle 编译，运行时使用手机已安装的 LSPosed 实现。

## AOD 与原先 RRO 的关系

你之前通过覆盖 `config_screenBrightnessDozeFloat` 试过固定 AOD 亮度。此钩子仅在 SystemUI 执行对应 AOD 计算路径时生效；先关闭旧 AOD RRO 再测试，避免互相影响。新 ROM 若不再包含本工程 Hook 的私有方法，模块会记录 `Doze hooks installed: 0/4 paths`，并维持原样，不应声称功能有效。

## 已知限制

- 自动亮度 `getAutomaticScreenBrightness` 的返回值可能因 HBM/面板驱动再做进一步处理；60% 是系统亮度滑块比例的近似对应，不是 nits 的 60%。
- AOD 跟随的是系统保存的当前显示亮度，**并不是 AOD 期间独立计算、持续更新的日间自动亮度**；AOD 熄屏后正常自动亮度算法可能停止接收传感器事件。
- AOD 最大可能非常亮，OLED 可能加速老化，默认只有 10%，推荐从 5–15% 测试。
- 修改 WebUI 的滑块后可能必须重新触发亮度计算（例如开关屏幕），因为这版没有注册强制刷新回调。
- 本版本针对 AOSP 公开源码做接口匹配，不保证你当前 PixelOS Android 17 和 LSPosed fork 私有方法签名一致。
