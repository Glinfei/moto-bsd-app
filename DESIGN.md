# MotoBSD Android App — 设计文档

> 版本：0.5.0（2026-08-04）· 本文档与当前代码实现保持一致。
> 0.4 重构前的旧设计文档已失效，不再作为参考。

## 1. 项目背景

MotoBSD 是摩托车盲区检测（BSD）固件项目，运行于 nRF52840 + 60GHz 毫米波雷达（AT6010）。
通过 BLE 将告警状态、目标详情、设备状态上报给手机。

本 App 是配套的手机端应用——**只做 Android**。

## 2. 核心场景

```
用户骑车出行：
  1. 发动摩托车 → MotoBSD 设备上电 → BLE 开始广播
  2. 手机连接 MotoBSD（首次需手动扫描选择，之后自动重连）
  3. 用户打开高德地图全屏导航
  4. 屏幕两侧出现盲区指示（光带 / 圆点 / 竖条 / 箭头，叠加在地图之上）
     左侧 = 左盲区状态   右侧 = 右盲区状态
     灰色/透明 = 安全    黄色 = Warning / Alert
     红色闪烁 = Critical
  5. 用户余光感知指示变化，无需切换 App
```

**关键体验**：不遮挡、不打扰导航；需要关注时才进入视线。

## 3. 架构

```
┌─────────────────────────────────────────────────────────┐
│ MainActivity (Jetpack Compose)                          │
│  底部导航三页：                                          │
│  · 状态 Tab：盲区卡片 / 最近目标 / 电量温度 / 连接操作    │
│  · 设备 Tab：DIS 信息 / 设备名称 / DFU / 雷达开关 / 重启  │
│  · 图标 Tab：样式/大小/透明度 / 测试告警 / 声音 / 位置    │
│  子页面：设备列表（扫描）、首次引导（4 屏）               │
└───────────────────────────┬─────────────────────────────┘
                            │ startForegroundService
        ┌───────────────────┴───────────────────┐
        │ BleService (Foreground, connectedDevice) │
        │  - 观察仓库状态 → 持久通知 / 告警通知    │
        │  - 告警声音 + 震动 + WakeLock 保活       │
        │  - 状态推送 → OverlayWindowHolder        │
        └───────────────────┬───────────────────┘
                            │ 观察 StateFlow
        ┌───────────────────┴───────────────────┐
        │ BleRepositoryImpl（单例）              │
        │  - BLE 扫描 / 连接 / 重连状态机        │
        │  - alert / target / device / DIS 状态  │
        │  - 左右反转（雷达安装方向适配）         │
        │  - 数据经 BleConnectionManager (Nordic) │
        │    ←→ Protocol.parse 解析字节流        │
        └────────────────────────────────────────┘

        ┌────────────────────────────────────────┐
        │ OverlayService (Foreground, specialUse) │
        │  - WindowManager 叠加两个 BsdIndicatorView │
        │  - 监听 OverlayRepository.configFlow     │
        │  - 旋转时刷新位置（比例换算）            │
        │  - 无悬浮窗权限时不创建窗口、不崩溃      │
        └────────────────────────────────────────┘
```

数据流：

```
BLE notify/read → Protocol 解析 → BleConnectionManager 回调
  → BleRepositoryImpl StateFlow（alertState / targets / deviceStatus / disInfo / deviceName）
    → ViewModel → Compose UI
    → BleService → 持久通知 / 告警通知 / 声音 / 震动
    → OverlayWindowHolder → OverlayWindow → BsdIndicatorView 渲染
```

## 4. 技术栈

| 组件 | 技术 | 说明 |
|------|------|------|
| 语言 | Kotlin | Android 官方语言 |
| UI | Jetpack Compose（Material 3） | 声明式 UI；Compose BOM 2026.05.01 |
| 依赖注入 | Hilt + KSP | 2.56.1 |
| BLE | Nordic BLE Library 2.7.0 | `no.nordicsemi.android:ble` |
| DFU | Nordic DFU 2.7.0 | `no.nordicsemi.android:dfu` |
| 悬浮窗 | WindowManager (TYPE_APPLICATION_OVERLAY) | 系统级叠加层 |
| 前台服务 | Service.startForeground() | BleService / OverlayService |
| 状态管理 | StateFlow + combine | 响应式数据流 |
| 持久化 | DataStore Preferences | 位置、配置、声音设置 |
| 导航 | Navigation Compose | 底部三 Tab + 子页面 |
| 折叠屏 | androidx.window 1.5.1 | 已引入，避让逻辑未实现 |
| SDK | minSdk 26 / compile & target 36 | versionName 1.0.0 |

## 5. BLE 协议与数据映射

### 5.1 自定义服务

服务 UUID：`b1d30000-9e3f-4b1e-8a3e-7f2b1c3d5e7f`

| 特征值 | UUID 后缀 | 操作 | 字节 | 解析 |
|--------|----------|------|------|------|
| alert_status | 0001 | read + notify | 1B | hi_nibble=left, lo_nibble=right；0=无目标, 1=有目标（仅有无，不定级） |
| target_details | 0002 | notify | ≤33B | `[count, (range_m, angle_deg, velocity_ms, obj_id)*N]`（每目标 4B，最多 8 个） |
| device_status | 0003 | read + notify | 5B | `[batt_mv_lo, batt_mv_hi, temp_lo, temp_hi, flags]` |
| radar_power | 0005 | read + write | 1B | 0=off, 1=on |
| dfu_trigger | 0007 | write | 1B | 0x01 = 进入 DFU |
| system_reset | 0008 | write | 1B | 任意值 = 系统复位 |
| device_name | 0009 | read + write | ≤20B | UTF-8 设备名称（最长 20 字节） |

> 连接建立后 `alert_status` / `device_status` 会**主动读取一次初始值**，不依赖设备推送；
> 订阅 enableNotifications 在 initialize 中完成。

### 5.2 标准服务

DIS（0x180A）：

| 特征值 | UUID | 操作 | 类型 |
|--------|------|------|------|
| manufacturer_name | 2A29 | read | String |
| model_number | 2A24 | read | String |
| serial_number | 2A25 | read | String |
| hardware_revision | 2A27 | read | String |
| firmware_revision | 2A26 | read | String |

BAS（0x180F）：

| 特征值 | UUID | 操作 | 说明 |
|--------|------|------|------|
| battery_level | 2A19 | read + notify | 电量百分比，**优先于** device_status 计算的百分比 |

### 5.3 解析细节

**alert_status（1 字节）**

`hi_nibble = left`，`lo_nibble = right`。固件只标记**有无目标**（0/1），不再给出告警等级；
盲区范围 / 阈值 / 等级等策略已从固件移除，数据以 target_details 全量原样上报。

App 端同样简化：**不做告警等级决策矩阵**，按有无直接驱动显示——
有目标 → `Warning`（黄），无目标 → `Safe`（灰/透明）。

**device_status（5 字节）**

```
[batt_lo, batt_hi, temp_lo, temp_hi, flags]
```

- `batt_mv`：u16 LE，直接毫伏（固件已按 VDDHDIV5 缩放）
- `temp`：i16 LE，decidegC（255 = 25.5°C）
- `flags`：bit0 = USB 连接，bit4 = 雷达上电/在线
- 电量百分比：BAS 2A19 优先；无 BAS 时线性换算 `(mv-3200)/1000*100`，钳制 0-100

**target_details（每目标 4 字节）**

```
[count: u8, (range_m: i8, angle_deg: i8, velocity_ms: i8, obj_id: u8) × N]
```

| 字段 | 格式 | 说明 |
|------|------|------|
| range_m | i8 | 距离（m，带符号；有效 0.5-30m） |
| angle_deg | i8 | 角度（度，带符号；负=左、正=右、0=正后方） |
| velocity_ms | i8 | 速度（m/s，带符号；正=靠近、负=远离） |
| obj_id | u8 | 雷达跟踪目标 ID（跨帧稳定） |

固件零裁剪透传：任何角度/距离/速度的目标都原样上报，目标数据仅用于 Dashboard 展示。

## 6. 告警级别与视觉

| 级别 | 颜色 | 动画（图标模式） | UI 表现 |
|------|------|------------------|---------|
| Safe | 灰 `#9E9E9E` | 恒亮 | 卡片灰底、圆点灰 |
| Warning | 黄 `#FFC107` | 1000ms 周期，70/30 占空比脉冲 | 卡片黄底、黄圆点 |
| Alert | 橙 `#FF9800` | 500ms 周期，60/40 占空比脉冲 | 橙色圆点 + 闪电图标 |
| Critical | 红 `#F44336` | 500ms 周期（2Hz），50/50 闪烁 | 红底、红圆点 + 闪电 + 告警通知/震动/声音 |

级别文案统一中文：安全 / 警告 / 警惕 / 危险。

> 实车数据只产生 Safe / Warning（固件 alert_status 有无目标：有=Warning、无=Safe）；
> Alert / Critical 由图标设置页的测试告警模式驱动，级别体系保留便于后续扩展。

**告警不自动超时消失**：图标持续显示直到收到 Safe 通知或 BLE 断线。

**断线状态**：断线时悬浮指示切换为**灰色慢速呼吸**（2s 周期），与"安全"的恒亮灰
（光带模式下为全透明）明确区分；重连后恢复当前级别显示。
同时 BleRepositoryImpl 把告警/目标复位为 Safe。

> Critical 周期取 500ms（2Hz）而非 200ms（5Hz）：5Hz 闪烁处于光敏性癫痫诱发频段，骑行场景避免。

## 7. BLE 连接状态机

```kotlin
sealed class BleConnectionState {
    Disconnected                // 用户主动断开，永不自动重连
    Scanning                    // 扫描中
    Connecting(mac)             // 正在连接指定设备
    Ready                       // 已连接且特征值已订阅
    Reconnecting(attempt, delayMs) // 意外断开，指数退避重连
    Error(message)              // 重试耗尽，需用户干预
}
```

转换规则：

```
Disconnected ──connect(mac)──▶ Connecting ──onReady──▶ Ready
                                     │                    │ 意外断开
                           用户 disconnect()            ▼
                                     ▼             Reconnecting
                              Disconnected    (1s→2s→4s→8s→16s→30s)
                                                     │ 连续失败
                                            (15s 超时/异常，最多 10 次)
                                                     ▼
                                                   Error
```

- 扫描：`BleScanner`（callbackFlow），默认 10s 超时，200ms 节流发射，按 RSSI 降序，取消时自动停止扫描
- 重连：每轮等待退避延迟后直连上次 MAC，15s 内未就绪视为失败，最多 10 次
- 用户主动断开后不会自动重连
- 左右反转（`swapLeftRight`）在仓库层对告警左右交换，适配雷达安装方向

## 8. 悬浮窗设计

### 8.1 样式（4 种）

| 样式 | 名称 | 默认/安全 | 告警 |
|------|------|-----------|------|
| LightBar | 光带（默认） | 全透明 | 黄/橙/红渐变 + Critical 脉冲 |
| Dot | 圆点 | 灰 | 黄/橙/红 + 脉冲 |
| Bar | 竖条 | 灰 | 黄/橙/红 + 脉冲 |
| Arrow | 箭头 | 灰 | 黄/橙/红 + 脉冲 |

所有图标样式带深色描边，提升明亮地图背景下的辨识度。
断线时各样式统一切换为灰色慢速呼吸（光带不再透明）。

### 8.2 属性

| 属性 | 默认值 | 范围 |
|------|--------|------|
| 大小 | 中 40dp | 小 28 / 中 40 / 大 56dp |
| 透明度 | 60% | 35%-100%（下限 35%，保证户外可见） |
| 光带位置 | 左右边缘 | 左右边缘 / 上下边缘 |
| 左右反转 | 关 | 开/关 |

### 8.3 光带（LightBar）

- 厚度 40dp；左右边缘模式贴左右两侧纵向渐变（左条从左实到右 20% 底色，右条相反）；上下边缘模式贴上下两侧横向渐变（上条从上实到下 20% 底色，下条相反）
- 告警时渐变不到全透明：内侧保留 20% 恒定底色（不随脉冲缩放），整条保持可见；安全态仍全透明
- Safe 全透明（不遮挡）；Warning/Alert 常亮；Critical 500ms 脉冲
- Critical 脉冲暗相位下限 0.5，与 20% 底色保持明显对比
- 断线时显示灰色呼吸，不再全透明
- 灯带框架使用系统最大窗口区域定位（Android 11+），自动避开不透明状态栏/导航栏、适配透明栏与挖孔，不同手机上均贴屏幕边缘
- 光带不可触摸（`FLAG_NOT_TOUCHABLE`），不拦截导航手势

### 8.4 位置与拖拽（Dot/Bar/Arrow）

- 默认位置：图标贴左右边缘、垂直居中（避开状态栏/导航栏）
- 拖动：按下显示"左/右"标签（平时不显示）→ 跟随手指 → 松手吸附最近边缘
- 防重叠：两圆点中心距 < 3×size（像素）时自动弹回默认位置 + Toast 提示
- 持久化：保存像素坐标 + 当时屏幕尺寸；旋转/横竖屏切换时按新旧屏幕尺寸**比例换算**
- 交互：单击不响应（避免导航中误触）；双击打开 MainActivity；长按弹出状态菜单（左右两侧级别 + 电量）
- 样式切换时同步触摸标志与当前告警级别（0.5.0 修复）

### 8.5 权限处理

- OverlayService 启动时检查 `Settings.canDrawOverlays`；未授权不创建窗口，前台通知提示"需要悬浮窗权限"，不崩溃
- MainActivity 每次进程只提示一次跳转悬浮窗设置页（0.5.0 修复反复弹跳）
- 「图标设置」页提供「悬浮窗指示」开关；偏好持久化且**默认开启**——首次进入自动启动，手动关闭后保持关闭

## 9. 界面设计

### 9.1 状态（Dashboard）

```
┌──────────────────────────────────┐
│ MotoBSD                          │
│ ⚡ 已连接（绿=就绪/蓝=忙碌/红=失败/灰=断开）│
│ ┌────────┐  ┌────────┐          │
│ │   ●    │  │   ●    │          │
│ │  左    │  │  右    │          │
│ │ 安全   │  │ 危险    │          │
│ └────────┘  └────────┘          │
│ 最近目标                         │
│ 左侧 · 3.0m · 20.0° · 6m/s 靠近  │
│ ┌────────────────────────────┐  │
│ │ 82%  3.85V                 │  │
│ │ ████████░░░░（<20红/<50黄/else蓝）│
│ │ 25°C  USB  雷达 ●           │  │
│ └────────────────────────────┘  │
│ [ 扫描设备 / 断开连接 ]          │
│ [ 最小化到后台 ]                 │
└──────────────────────────────────┘
```

- 连接徽标文案：已连接/扫描中/连接中/重连中(第N次)/连接失败/未连接
- 忙碌态（扫描/连接/重连）用蓝色，避免与告警黄混淆
- 盲区卡片：按级别色叠加的底色 + 圆点 + 闪电图标（Alert/Critical）+ 非安全时显示最近目标距离/速度
- 最近目标卡片：按距离排序取前 2 个，显示侧/距离/角度(°)/速度/靠近方向，颜色跟随该侧有无目标状态
- 电量卡：百分比 + 电压 + 温度 + USB + 雷达在线状态
- 底部按钮：未连接→"扫描设备"跳设备列表；错误→"重试扫描"；已连接→红色"断开连接"；其余禁用
- 「最小化到后台」：启动 BleService + OverlayService 并 moveTaskToBack

### 9.2 设备（Device）

```
设备信息       产品型号 / 序列号 / 硬件版本 / 固件版本 / 制造商
设备名称       当前名称 / 刷新 / 修改（≤20 字节，重连后生效）
固件升级       当前版本 + [选择升级包]（zip → Nordic DFU）
雷达设置       雷达电源 [开关]（绑定 radarOnline 状态）
系统           [重启设备]（确认对话框）
```

- 设备名称：写入后延时回读，提示"名称已更新（重连后生效）"
- DFU：文件选择（application/zip）→ `DfuServiceInitiator`（buttonless、packet receipt 8、重试 5）

### 9.3 图标设置（Overlay）

```
图标样式        4 种卡片选择器（选中蓝色描边）
图标大小        小 / 中 / 大
透明度          Slider 35%-100%
测试告警        左/右独立「切换」循环 安全→警告→警惕→危险，实时驱动浮窗与声音
声音设置        音量 0-100、左频/右频 100-2000Hz、试听左/右/左急/右急
高级            左右反转 [开关]
[重置为默认位置]（立即生效，0.5.0 修复）
[光带位置：左右边缘/上下边缘 切换]
[悬浮窗指示：开/关]（默认开；关闭后需手动开启；无权限时提示先到系统设置授权）
```

测试告警会临时覆盖连接状态（断线呼吸），便于无设备时验证显示效果；「重置为安全」后恢复。

### 9.4 设备列表（扫描页）

- 扫描/停止切换，显示发现数量；名称过滤框；MotoBSD/BSD 设备置顶
- 每项：名称（MotoBSD 加标记）、MAC、RSSI 颜色（≥-60 绿 / ≥-80 黄 / 其余红）
- "已连接"标记按 MAC 精确匹配当前连接设备，不会整列表误标
- 手动输入 MAC 连接；连接成功自动返回；蓝牙未开显示引导

### 9.5 首次引导（Onboarding）

4 屏纯展示：欢迎 / 蓝牙连接 / 悬浮指示 / 告警通知。完成后写 `onboarding_complete`。

> 已知缺口：当前引导页**不实际请求权限**，运行时权限由 MainActivity.onResume 兜底请求。

## 10. 通知设计

### 10.1 持久通知（BleService）

- 通道 `motobsd_ble`（IMPORTANCE_LOW，无声音）
- 标题按连接状态：⚡已连接 / ⟳扫描中 / ⟳连接中 / ⟳重连中 / ✗连接失败 / ○未连接
- 正文（已连接）：`左:安全 右:危险`
- 点击打开 MainActivity；用户断开连接后服务自动停止

### 10.2 告警通知（Critical）

- 通道 `motobsd_alert`（IMPORTANCE_HIGH + 震动）
- 仅 Critical 触发；同侧 30s 防抖；震动 500ms；固定文案"有车辆靠近，请注意安全"；自动取消

### 10.3 浮窗通知（OverlayService）

- 通道复用 `motobsd_ble`，PRIORITY_MIN；文案"盲区指示运行中"；无权限时提示开启

> 已知缺口：持久通知不含电量/电压，告警通知不含目标详情（距离/角度/速度）。

## 11. 声音设计（SoundManager）

- AudioTrack 实时生成三角波 PCM，`USAGE_ALARM`（告警用途）
- Warning：3 声 × 100ms，间隔 500ms；循环间隔 3000ms
- Critical：5 声 × 80ms，间隔 100ms；循环间隔 1500ms
- 默认频率：左 1000Hz / 右 400Hz；Critical 左频 ×1.2（≤2500Hz）
- 优先级：Critical > Warning，左 > 右；全部安全时停止
- 音量/左右频率持久化到 DataStore，图标设置页可调

## 12. 权限清单

| 权限 | 用途 | 运行时请求 |
|------|------|-----------|
| BLUETOOTH / BLUETOOTH_ADMIN | 旧版蓝牙（minSdk 26 兼容） | 否 |
| BLUETOOTH_SCAN / BLUETOOTH_CONNECT | Android 12+ BLE | onResume 一次性请求 |
| ACCESS_FINE_LOCATION | BLE 扫描必需 | onResume 一次性请求 |
| POST_NOTIFICATIONS | Android 13+ 通知 | onResume 一次性请求 |
| SYSTEM_ALERT_WINDOW | 悬浮窗 | 每进程提示一次（跳系统设置） |
| FOREGROUND_SERVICE / CONNECTED_DEVICE / SPECIAL_USE | 前台服务 | 否 |
| USE_EXACT_ALARM | 预留（当前未使用） | 否 |
| VIBRATE | 告警震动 | 否 |

服务声明：BleService `connectedDevice`；OverlayService `specialUse`；DfuService `connectedDevice`。
Activity 声明 `configChanges`（orientation/screenSize/screenLayout/smallestScreenSize）。

## 13. 数据持久化（DataStore：`motobsd_settings`）

| Key | 内容 |
|-----|------|
| last_mac | 上次连接的 MAC |
| onboarding_complete | 引导完成标记 |
| overlay_style / overlay_size / overlay_alpha | 样式 / 大小 / 透明度 |
| overlay_swap / overlay_orientation | 左右反转 / 光带方向 |
| overlay_enabled | 悬浮窗开关偏好（默认开启） |
| left_x / left_y / right_x / right_y | 图标位置（像素） |
| left_screen_w/h / right_screen_w/h | 保存位置时的屏幕尺寸（旋转比例换算用） |
| sound_volume / sound_left_freq / sound_right_freq | 声音设置 |
| style_migrated_v1 | 旧版 Dot → LightBar 默认值迁移标记 |

配置变更通过 `OverlayRepository.configFlow`（内存 StateFlow）实时直通 OverlayService，不经过 DataStore 中转。

## 14. 已知缺口（半成品状态）

- **DFU**：仅选包 + Nordic 传输，无进度 UI、无中断恢复流程
- **通知**：持久通知无电量/电压，告警通知无目标详情，文案固定
- **Onboarding**：4 屏纯展示，无实际权限请求；无"连接设备"步骤
- **折叠屏**：androidx.window 已引入，避让折叠区域未实现
- **设备页**：无"恢复出厂设置"
- **声音**：无多目标/连续告警去重之外的策略；循环间隔固定
- **代码遗留**：`OverlayConfig` 的 fraction 字段未使用（位置按像素+屏幕尺寸存储）；`DeviceStatus.batteryRaw` 未使用；`USE_EXACT_ALARM` 权限未使用
- **显示细节**：断线呼吸状态目前仅限悬浮窗，Dashboard 卡片无独立断线态；告警仅按"有无目标"两级显示，无距离/速度阈值决策

## 15. 项目结构

```
moto-bsd-app/
├── app/build.gradle.kts            # Compose / Hilt / KSP / Nordic
└── app/src/main/
    ├── AndroidManifest.xml
    ├── res/values/                 # strings / colors / themes
    └── java/com/motobsd/
        ├── App.kt                  # Application：通知通道创建
        ├── MainActivity.kt         # 入口：引导门控 / 权限兜底 / DFU 选择
        ├── ble/Protocol.kt         # UUID + 字节流解析
        ├── model/                  # AlertLevel / DeviceStatus / TargetObject
        │                           # / OverlayStyle / BleConnectionState
        ├── data/
        │   ├── ble/                # BleRepository / Impl / ConnectionManager / Scanner
        │   ├── overlay/OverlayRepository.kt
        │   └── settings/SettingsRepository.kt
        ├── di/                     # DataModule / BleModule / OverlayModule
        ├── service/                # BleService / OverlayService / DfuService
        ├── overlay/                # OverlayWindow / BsdIndicatorView
        │                           # / AlertAnimator / OverlayWindowHolder
        ├── audio/SoundManager.kt   # AudioTrack 告警音
        └── ui/
            ├── navigation/NavGraph.kt
            ├── dashboard/          # 状态页 + ViewModel
            ├── device/             # 设备页 + ViewModel
            ├── devicelist/         # 扫描列表页 + ViewModel
            ├── overlay/            # 图标设置页 + ViewModel
            ├── onboarding/         # 首次引导
            ├── components/         # BlindSpotCard / BatteryGauge / StyleSelector
            └── theme/Theme.kt
```

## 16. 后续可扩展（未实现）

- [ ] DFU 进度显示与中断恢复续传
- [ ] 通知栏电量/电压 + 告警目标详情
- [ ] Onboarding 分步实际请求权限 + 连接设备
- [ ] 折叠屏避让折叠区域
- [ ] 骑行数据记录（里程、告警次数、GPS 位置）
- [ ] 语音播报
- [ ] 多设备支持
