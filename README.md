# MotoBSD Android App

摩托车盲区检测（BSD）模块的配套 Android 应用。

配合运行于 nRF52840 + 60GHz 毫米波雷达（AT6010）的 MotoBSD 固件使用，通过 BLE 接收
告警状态、目标详情与设备状态，在导航 / 地图之上显示盲区指示。

> ⚠️ 固件仓库保持闭源。本 App 仅作为配套客户端，单独使用没有意义。

## 功能

- **BLE 连接**：扫描 / 手动重连 / 断线自动重连（手动重连限 3 次，骑行中断线重试 10 次）
- **雷达视图**：后向扇形显示最多 8 个目标（角度→方向、距离→半径，范围自适应 15–50m），
  带数据新鲜度提示与目标事件记录（以 obj_id 为单位，左右两列，消失保留 60 秒）
- **弧形盲区灯带**：屏幕边缘 `|)` / `(|` 弧形灯带，由威胁度连续驱动颜色（黄→橙→红）、
  亮度与弧长；Safe 全透明，断线灰色呼吸
- **骑行模式**：一键保持屏幕常亮并切到后台；退出恢复
- **告警通知 / 声音 / 震动**：Critical 触发；音频流可选媒体（默认，支持蓝牙耳机）或闹钟
- **设备管理**：DIS 信息、设备名称读写、雷达电源、系统复位、DFU 固件升级

## 截图

（待补充）

## 构建

环境要求：

- JDK 17
- Android SDK（compileSdk 36）

```bash
# 调试包
./gradlew assembleDebug

# 单元测试
./gradlew testDebugUnitTest
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

### 签名

仓库**不包含任何签名文件**。正式分发前：

1. 用 `keytool` 生成自己的 release keystore（请妥善保管，丢失无法补发升级）
2. 复制 `keystore.properties.template` 为 `keystore.properties` 并填写
3. 构建 `./gradlew assembleRelease`

没有 `keystore.properties` 时 release 构建会回退 debug 签名，仅用于本地验证，不能分发。

## 真机验证清单

以下是目前按文档假设、**尚未真机标定**的参数，欢迎实测后在 Issues 反馈：

- [ ] velocity 正负语义（正=靠近）——影响威胁度加成方向
- [ ] obj_id 跨帧稳定性与回收速度——影响目标事件记录
- [ ] 雷达水平 FOV（规格约 ±65°/±70°，代码默认 ±75°）
- [ ] DFU 全流程（触发 → bootloader → 传输 → 恢复）
- [ ] 骑行模式屏幕常亮在国产 ROM 上的表现

## BLE 协议

自定义服务与特征值、字节流解析见 [DESIGN.md](DESIGN.md)（协议解析、状态机、悬浮窗设计均已同步）。

## 权限

- BLE 扫描/连接、通知、悬浮窗（核心功能）
- 定位权限仅 Android 8–11 的 BLE 扫描需要（Android 12+ 使用 `neverForLocation` 的扫描权限）
- 详见 [DESIGN.md](DESIGN.md) 权限清单

## 许可证

[MIT](LICENSE)
