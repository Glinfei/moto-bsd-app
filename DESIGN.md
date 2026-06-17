# MotoBSD Android App — 设计文档

## 项目背景

MotoBSD 是摩托车盲区检测（BSD）固件项目，运行于 nRF52840 + 60GHz 毫米波雷达（AT6010）。
通过 BLE 将告警状态、目标详情、设备状态上报给手机。

本 App 是配套的手机端应用——**只做 Android**。

**iOS 不做**，原因：[见第3节](#3-为什么只做-android)。

---

## 核心场景

```
用户骑车出行：
  1. 发动摩托车 → MotoBSD 设备上电 → BLE 开始广播
  2. 手机自动连接 MotoBSD（后台 Service）
  3. 用户打开高德地图全屏导航
  4. 屏幕两侧各出现一个半透明圆点（叠在地图之上）
     左侧 = 左盲区状态   右侧 = 右盲区状态
     灰色 = 安全          黄色 = Warning
     红色闪烁 = Critical
  5. 用户余光感知圆点颜色变化，无需切换 App
```

**关键体验**：不遮挡、不打扰导航；需要关注时才进入视线。

---

## 架构

```
┌─────────────────────────────────────────────────┐
│ MainActivity (Jetpack Compose)                  │
│  ┌─────────┐ ┌──────────┐ ┌──────────┐         │
│  │ 状态 Tab │ │ 设备 Tab  │ │ 图标设置  │         │
│  │ 盲区卡片 │ │ 版本/DFU │ │ 样式/大小 │         │
│  │ 电量/温度│ │ 雷达开关 │ │ 位置预览  │         │
│  │ [收起]  │ │ 系统复位 │ │ 重置位置  │         │
│  └─────────┘ └──────────┘ └──────────┘         │
└─────────────────────────────────────────────────┘
            ↕ Intent
┌─────────────────────────────────────────────────┐
│ BleService (Foreground Service)                 │
│  - BLE 扫描/连接/重连                           │
│  - 订阅 alert_status / target_details /         │
│    device_status notify                         │
│  - 解析数据 → LiveData / Flow                   │
│  - 持久通知：显示当前盲区状态                    │
└─────────────────────────────────────────────────┘
            ↕ AlertState
┌─────────────────────────────────────────────────┐
│ OverlayService                                  │
│  - WindowManager 叠加两个 BsdIndicatorView      │
│  - 监听 AlertState → 更新颜色/闪烁              │
│  - 拖拽 → 保存位置到 SharedPreferences           │
│  - 横竖屏切换 → 自动调整默认位置                 │
│  - 点击圆点 → 打开 MainActivity                 │
└─────────────────────────────────────────────────┘
```

---

## 技术栈

| 组件 | 技术 | 说明 |
|------|------|------|
| 语言 | Kotlin | Android 官方语言 |
| UI | Jetpack Compose | 声明式 UI，Material 3 |
| BLE | Android BLE API (android.bluetooth.le) | 系统原生，无第三方封装 |
| 悬浮窗 | WindowManager (TYPE_APPLICATION_OVERLAY) | 系统级叠加层 |
| 前台服务 | Service.startForeground() | 后台保活 |
| 状态管理 | StateFlow + SharedFlow (Kotlin Coroutines) | 响应式数据流 |
| 持久化 | SharedPreferences / DataStore | 位置、偏好设置 |
| DFU | Nordic DFU Library | OTA 固件升级 |

---

## BLE 数据映射

MotoBSD 自定义服务 UUID: `b1d30000-9e3f-4b1e-8a3e-7f2b1c3d5e7f`

| 特征值 | UUID 后缀 | 操作 | 字节 | 解析 |
|--------|----------|------|------|------|
| alert_status | 0001 | read + notify | 1B | `hi_nibble=left, lo_nibble=right` (0=Safe,1=Warning,2=Critical) |
| target_details | 0002 | notify | ≤48B | `[count, (range,angle,vel,id,level,side)*N]` |
| device_status | 0003 | read + notify | 5B | `[batt_lo, batt_hi, temp_lo, temp_hi, flags]` |
| radar_power | 0005 | read + write | 1B | 0=off, 1=on |
| dfu_trigger | 0007 | write | 1B | 0x01 = 进入 DFU |
| system_reset | 0008 | write | 1B | 任意值 = 系统复位 |

DIS 标准服务（0x180A）：

| 特征值 | UUID | 操作 | 类型 |
|--------|------|------|------|
| manufacturer_name | 2A29 | read | String |
| model_number (PN) | 2A24 | read | String |
| serial_number (SN) | 2A25 | read | String |
| hardware_revision | 2A27 | read | String |
| firmware_revision | 2A26 | read | String |

---

## 悬浮图标设计

### 默认位置

```
竖屏（w < h）：
┌──────────────┐        ● = left_dot（左下，距边缘 16dp）
│              │        ● = right_dot（右下，距边缘 16dp）
│     地图     │
│              │
│  ●        ● │
└──────────────┘

横屏（w > h）：
┌────────────────────┐
│●                ●  │   ● = left_dot（左边缘中，距边缘 16dp）
│      地图          │   ● = right_dot（右边缘中，距边缘 16dp）
└────────────────────┘
```

### 拖拽交互

```
按住圆点 → 显示标签（"左"/"右"）→ 跟随手指 → 松手 → 防重叠检测 → 吸附最近边缘
                                                              ↓
                                              保存位置到 SharedPreferences
```

#### 点击交互

| 操作 | 行为 | 原因 |
|------|------|------|
| 单击 | **不响应** | 用户正在导航中，误触不应切换 App |
| 双击 | 打开 MainActivity | 有意操作 |
| 长按（300ms） | 弹出悬浮菜单 | 快速查看状态 + 快捷操作 |

长按弹出菜单：
```
       ┌──────────────┐
       │ ⚡ MotoBSD   │
       │ 左: 安全     │
       │ 右: Critical │
       │ 电量: 82%    │
       │ ─────────── │
       │ 打开 App     │
       └──────────────┘
```

#### 防重叠机制

松手时检测两圆点距离：
```
if distance(leftDot, rightDot) < threshold:
    → 左圆点自动弹到最近左边缘
    → 右圆点自动弹到最近右边缘
    → 小幅度振动反馈
    → Toast 提示："图标已自动分开"
```

保存的坐标也做合法性检查：
```kotlin
fun validatePosition(x: Float, y: Float, side: Side): PointF {
    // 限制在屏幕范围内
    val boundedX = x.coerceIn(0f, screenW - dotSize)
    val boundedY = y.coerceIn(statusBarH, screenH - navBarH - dotSize)
    
    // 防重叠：如果两圆点距离 < dotSize * 3，弹回默认位置
    if (distanceTo(otherDot) < dotSize * 3) {
        return defaultPosition(side)
    }
    return PointF(boundedX, boundedY)
}
```

### 图标样式（3 种可选）

| 样式 | 名称 | 不适配时 | 适配时 |
|------|------|---------|--------|
| `Dot` | 圆点 | ● 灰色 | 🔴 红色闪烁 |
| `Bar` | 竖条 | ▎ 灰色 | ▎ 红色 + 脉冲动画 |
| `Arrow` | 箭头 | ←灰色→ | ←红色→ 脉冲 |

### 属性

| 属性 | 默认值 | 范围 |
|------|--------|------|
| 大小 | 中 (20dp) | 12/20/28dp |
| 透明度 | 60% | 20%-100% |
| 位置 | 默认（横竖屏自适应） | 用户拖拽保存 |

---

## 界面设计

### 标签一：状态（Dashboard）

```
┌──────────────────────────────────┐
│            MotoBSD               │
│           ⚡ 已连接              │   ← 绿色 / 灰色（断开）
│                                  │
│      ┌────────┐  ┌────────┐     │
│      │   ●    │  │   ●    │     │   ← 大号圆点
│      │  左    │  │  右    │     │   ← 标签
│      │  安全  │  │ Critical│     │   ← 文字 + 底色
│      │        │  │  ⚡     │     │   ← Critical 显示闪电图标
│      └────────┘  └────────┘     │
│                                  │
│  ┌────────────────────────────┐  │
│  │ 电池            82%        │  │
│  │ ████████████████████░░░░░ │  │   ← 渐变：绿→黄→红
│  │ 3.85V                     │  │
│  │ 🌡 25°C        USB ✓     │  │
│  │ 雷达 ● 开启              │  │
│  └────────────────────────────┘  │
│                                  │
│  [ 收起后台 ]                    │   ← 启动 OverlayService
└──────────────────────────────────┘
```

盲区卡片状态：

| 级别 | 底色 | 圆点颜色 | 文字 |
|------|------|---------|------|
| NoAlert (0) | 灰 #F5F5F5 | 灰 #9E9E9E | 安全 |
| Warning (1) | 黄 #FFF8E1 | 黄 #FFC107 | Warning |
| Critical (2) | 红 #FFEBEE | 红 #F44336 | Critical ⚡ |

### 标签二：设备（Device）

```
┌──────────────────────────────────┐
│  设备信息                        │
│  ─────────────────────────────── │
│  产品型号      MS60-3015S80M4    │
│  序列号        00000001          │
│  硬件版本      1.0.0             │
│  固件版本      1.1.2             │
│                                  │
│  固件升级                        │
│  ┌────────────────────────────┐  │
│  │ 📦  选择升级包             │  │
│  │     当前: v1.1.2           │  │
│  └────────────────────────────┘  │
│                                  │
│  雷达设置                        │
│  ┌────────────────────────────┐  │
│  │ 雷达电源      [ ●●● 开启 ]│  │
│  └────────────────────────────┘  │
│                                  │
│  系统                            │
│  ┌────────────────────────────┐  │
│  │ ⟳  重启设备                │  │
│  │ ⚠  恢复出厂设置            │  │
│  └────────────────────────────┘  │
└──────────────────────────────────┘
```

DFU 升级流程：

```
正常流程：
  选 zip → 确认版本 → 写 dfu_trigger 0x01
  → 设备重启为 DfuTarg → Nordic DFU 传输
  → 设备再次重启 → 重新搜到 MotoBSD → 提示升级完成

中断恢复：
  传输中断（蓝牙断/走远/来电/切App）
  → 设备停留在 DfuTarg 模式（不会自动退出）
  → App 检测到 DFU 中断 → 保存进度
  → 弹窗："升级中断，设备处于升级模式。是否继续？"
     [继续升级] → 重新扫描 DfuTarg → 续传
     [稍后再说] → 通知栏持续提醒："设备等待升级"
                   → 下次打开 App 自动提示续传
```

DFU 状态机：

```
选择文件 → 写入 dfu_trigger → 等待设备重启
    → 扫描 DfuTarg (30s 超时)
       ├─ 发现 → 发送固件 → 完成 → 扫描 MotoBSD
       │                                ├─ 发现 → 升级成功
       │                                └─ 超时 → 提示用户手动重启设备
       ├─ 超时 → 提示手动进入 DFU 模式
       └─ 中断 → 保存状态 → 通知提醒 → 等待用户操作
```

### 标签三：图标设置（Overlay）

```
┌──────────────────────────────────┐
│  悬浮图标设置                     │
│  ─────────────────────────────── │
│                                  │
│  图标样式                        │
│  ┌──────┐ ┌──────┐ ┌──────┐    │
│  │  ●   │ │  ▎   │ │  ←→  │    │
│  │ 圆点 │ │ 竖条 │ │ 箭头 │    │
│  └──────┘ └──────┘ └──────┘    │
│    (选中边框高亮)                │
│                                  │
│  图标大小                        │
│  ○  ────●───  ○                │
│  小   (中)   大                  │
│                                  │
│  透明度                          │
│  ▓ ─────●─── ░                  │
│  (60%)                           │
│                                  │
│  预览                            │
│  ┌────────────────────────────┐  │
│  │                            │  │
│  │   ●                 ●     │  │  ← 实时反映当前设置
│  │                            │  │
│  └────────────────────────────┘  │
│                                  │
│  [ 重置为默认位置 ]             │
└──────────────────────────────────┘
```

---

## 通知设计

### 持久通知（Foreground Service 必须）

```
┌──────────────────────────────────┐
│ MotoBSD                       ▼  │
│ 左: 安全  |  右: Critical       │
│ 电量 82%   3.85V                 │
└──────────────────────────────────┘
```

- 展开显示盲区详情 + 最近目标
- 点击通知 → 打开 MainActivity
- 通知通道：IMPORTANCE_LOW（不发出声音）

### 告警通知（Critical 时触发）

```
┌──────────────────────────────────┐
│ ⚠ 右盲区告警！                   │
│ 目标: 1m  20°  6m/s (快速靠近)   │
└──────────────────────────────────┘
```

- `IMPORTANCE_HIGH` + `AudioAttributes.USAGE_ALARM` → 绕过勿扰模式
- 振动 + 急促短音

---

## BLE 连接策略

```
启动 → 尝试连接已配对的 MAC
       ├─ 成功 → 订阅通知 → 持久连接
       ├─ 失败 → 扫描 "MotoBSD"（10s 超时）
       │         ├─ 发现 → 连接 + 保存 MAC
       │         └─ 未发现 → 等待 5s → 重试（最多 3 次）
       └─ 3 次失败 → 通知用户检查设备电源

连接断开 → 自动重连（指数退避: 1s→2s→4s→8s→...→30s max）
```

### BLE 连接状态机

```
                    ┌─────────┐
                    │ 空闲     │ ← App 启动 / 无已知设备
                    └────┬────┘
                         ↓
                    ┌─────────┐
                    │ 扫描中   │ ← 10s 超时
                    └────┬────┘
                  发现 /   \ 超时
                   ↓        ↓
              ┌─────────┐  ┌──────────┐
              │ 连接中   │  │ 扫描失败  │ → 5s 后重试（×3）
              └────┬────┘  └──────────┘
             成功 /  \ 失败        3 次全败 → 通知用户
              ↓      ↓
         ┌────────┐ ┌──────────┐
         │ 订阅中  │ │ 连接失败  │ → 回退到扫描
         └───┬────┘ └──────────┘
             ↓
        ┌─────────┐
        │ 已就绪   │ ← BLE 数据流通
        └────┬────┘
             ↓ 断开
        ┌─────────┐
        │ 重连中   │ ← 指数退避: 1s→2s→4s→8s→16s→30s
        └────┬────┘
        成功 /  \ 30s 超时 → 回退到扫描
         ↓      ↓
      已就绪   扫描失败
```

```kotlin
enum class ConnectionState {
    Idle,           // 未启动连接
    Scanning,       // 扫描中
    Connecting,     // 正在连接
    Subscribing,    // 连接成功，正在订阅特征值
    Ready,          // 就绪
    Reconnecting,   // 断线重连中
    Failed,         // 失败（需用户干预）
}
```

UI 和通知都绑定此状态：
- Scanning/Connecting/Subscribing → UI 显示加载动画
- Ready → UI 正常显示
- Reconnecting → UI 显示重连进度（第 N 次，间隔 Xs）
- Failed → 通知用户检查设备

---

## Android 权限

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />     <!-- Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />  <!-- Android 12+ -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" /> <!-- BLE 扫描必需 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />  <!-- 悬浮窗 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />   <!-- Android 13+ -->
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />      <!-- Critical 告警 -->

<!-- Android 14+ foreground service type 必须声明 -->
<!-- connectedDevice: 与附近设备（BLE）持续通信 — 符合条件 -->
<!-- 如果 Android 14+ 未正确声明，Service 会在 6 小时后被系统杀死 -->

<!-- 服务声明 -->
<service android:name=".service.BleService"
    android:foregroundServiceType="connectedDevice"
    android:exported="false" />
<service android:name=".service.OverlayService"
    android:exported="false" />
```

### Android 14+ 注意事项

- `foregroundServiceType="connectedDevice"` 必须显式声明，否则 6 小时后被系统杀死
- `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` 是运行时权限，需动态申请
- `POST_NOTIFICATIONS` (Android 13+) 首次启动时弹窗，拒绝后通知功能不可用

### 厂商 ROM 兼容性

`SYSTEM_ALERT_WINDOW` 在以下 ROM 上默认禁止，需引导用户手动开启：

| ROM | 设置路径 |
|-----|---------|
| MIUI (小米) | 设置 → 应用 → MotoBSD → 权限 → 显示悬浮窗 → 始终允许 |
| ColorOS (OPPO) | 手机管家 → 权限管理 → 悬浮窗管理 → MotoBSD → 允许 |
| EMUI (华为) | 设置 → 应用 → 权限 → 悬浮窗 → MotoBSD → 允许 |
| OneUI (三星) | 设置 → 应用程序 → MotoBSD → 在其他应用上层显示 → 开启 |
| 原生 Android | 设置 → 应用 → 特殊应用权限 → 在其他应用上层显示 → MotoBSD → 允许 |

**处理方案**：

```kotlin
fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else true
}

fun requestOverlayPermission(context: Context) {
    // 跳转到系统设置页（ACTION_MANAGE_OVERLAY_PERMISSION）
    // + 弹窗教学截图引导（不同 ROM 路径不同）
    // + 注册 ActivityResult 回调检测授权结果
}
```

如果用户拒绝悬浮窗权限，不影响 BLE 连接和通知功能，仅悬浮图标不可用。

---

## 首次引导流程（Onboarding）

首次安装 App 后，分步引导。6 项权限分 3 组申请，避免一次性弹出太多对话框：

```
第 1 屏：欢迎
  ┌──────────────────────────┐
  │    🏍️  MotoBSD           │
  │    摩托车盲区检测助手     │
  │                          │
  │  骑行中屏幕显示盲区指示   │
  │  连接 MotoBSD 设备开始   │
  │                          │
  │      [ 开始设置 ]        │
  └──────────────────────────┘

第 2 屏：蓝牙权限
  "MotoBSD 通过蓝牙与设备通信"
  → 请求 BLUETOOTH_SCAN + BLUETOOTH_CONNECT + 定位权限
  → 打开蓝牙

第 3 屏：悬浮窗权限
  "骑行时在屏幕边缘显示盲区指示"
  → 请求 SYSTEM_ALERT_WINDOW
  → 跳转系统设置页
  → 示范拖拽操作

第 4 屏：通知权限
  "告警时发送通知，绕过勿扰模式"
  → 请求 POST_NOTIFICATIONS
  → 示范通知样式

第 5 屏：连接设备
  "搜索附近的 MotoBSD 设备"
  → 自动开始扫描
  → 发现设备 → 连接
  → 连接成功 → 完成引导
```

引导完成后的状态：
- `SharedPreferences: onboarding_complete = true`（下次启动跳过引导）
- 自动连接已配对设备
- 若用户跳过连接，可在主界面手动触发

---

## 边缘情况处理

### 横竖屏 / 折叠屏切换

```
横竖屏旋转 / 折叠屏展开折叠
  → OverlayService 检测 onConfigurationChanged()
  → 如果用户已自定义位置 → 按比例映射到新屏幕坐标系
  → 如果使用默认位置 → 重新计算默认位置
  → 图标平滑过渡（animateTo）
```

折叠屏额外处理：
- `Jetpack WindowManager` 库获取 `FoldingFeature` 状态
- 折叠状态 → 图标自动避开折叠区域
- 展开状态 → 恢复大屏默认位置

### 电量优化（扫描 vs 直连）

| 场景 | 策略 | 功耗 |
|------|------|------|
| 有已配对 MAC | 直接 `connectGatt()`，不扫描 | 低 |
| 无配对 + 首次连接 | 扫描 10s | 中 |
| 断线重连 | 优先直连，失败后扫描 5s | 中 |
| 后台维持连接 | 降低 BLE 连接参数（增大 interval: 30ms→50ms） | 低 |

Android BLE 扫描白名单限制（Android 7+）：
- 30 秒内最多开始 5 次扫描
- 后台扫描频率受限（约 30 分钟 1 次）
- **我们的策略天然适配**：有配对 MAC 不扫描，只在首次/失败时扫描

### 告警通知防抖

连续 Critical 告警不重复通知：
```kotlin
// 同一侧 Critical 告警，30s 内只发一次通知
private var lastNotifyTime: MutableMap<Side, Long>

fun shouldNotify(side: Side): Boolean {
    val last = lastNotifyTime[side] ?: 0
    val now = System.currentTimeMillis()
    return (now - last) > 30_000
}

fun onCriticalAlert(side: Side) {
    if (shouldNotify(side)) {
        sendNotification(side)
        vibrate()
        lastNotifyTime[side] = System.currentTimeMillis()
    }
}
```

但悬浮窗图标**不受防抖影响**——颜色实时变化，闪烁不限制。

### 连接状态栏更新

即使用户不看 App，通知栏的持久通知也实时更新：

```
连接就绪：  "MotoBSD · 左:安全 右:安全 · 82%"
告警中：    "⚠ MotoBSD · 左:安全 右:Critical · 82%"
重连中：    "⟳ MotoBSD · 重连中 (第 3 次) · 82%"
扫描中：    "⟳ MotoBSD · 正在搜索 · ??%"
连接失败：  "✗ MotoBSD · 连接失败 · 点击重试"
DFU 中断：  "⚠ MotoBSD · 设备等待升级 · 点击继续"
```

---

## 项目结构

```
moto-bsd-app/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/motobsd/
│       │   ├── App.kt                   # Application 类
│       │   ├── MainActivity.kt          # 主 Activity（Compose）
│       │   │
│       │   ├── service/
│       │   │   ├── BleService.kt        # 前台 Service：BLE 生命周期
│       │   │   └── OverlayService.kt    # 悬浮窗 Service
│       │   │
│       │   ├── ble/
│       │   │   ├── BleManager.kt        # BLE 扫描/连接/GATT 操作
│       │   │   └── Protocol.kt          # 数据解析（字节 → 模型）
│       │   │
│       │   ├── overlay/
│       │   │   ├── BsdIndicatorView.kt  # 悬浮窗 View（单侧）
│       │   │   ├── OverlayWindow.kt     # 悬浮窗管理（位置/拖拽/保存）
│       │   │   └── AlertAnimator.kt     # 告警动画（颜色渐变/闪烁）
│       │   │
│       │   ├── ui/
│       │   │   ├── screens/
│       │   │   │   ├── DashboardScreen.kt   # 标签一：状态
│       │   │   │   ├── DeviceScreen.kt      # 标签二：设备/DFU
│       │   │   │   └── OverlaySettingsScreen.kt # 标签三：图标设置
│       │   │   ├── components/
│       │   │   │   ├── BlindSpotCard.kt      # 盲区状态卡片
│       │   │   │   ├── BatteryGauge.kt       # 电量进度条
│       │   │   │   └── StyleSelector.kt      # 图标样式选择器
│       │   │   └── theme/
│       │   │       └── Theme.kt              # Material 3 主题（暗色）
│       │   │
│       │   └── model/
│       │       ├── AlertState.kt             # 告警状态（left/right level）
│       │       ├── ConnectionState.kt        # BLE 连接状态枚举
│       │       ├── DeviceStatus.kt           # 设备状态（batt/temp/flags）
│       │       ├── TargetObject.kt           # 单个目标数据
│       │       └── OverlayStyle.kt           # 悬浮窗样式配置
│       │   ├── ui/screens/
│       │   │   └── OnboardingScreen.kt       # 首次引导（权限 + 连接教程）
│       │
│       └── res/
│           ├── values/strings.xml
│           └── drawable/                     # 图标资源
│
├── build.gradle.kts                          # 项目级构建
├── settings.gradle.kts
└── DESIGN.md                                 # 本文档
```

---

## 开发阶段

### 阶段一：BLE 链路（2 天）

- [ ] 扫描 BLE 设备，过滤 "MotoBSD"
- [ ] 连接 + 发现服务 + 订阅 notify
- [ ] alert_status / target_details / device_status 解析
- [ ] DIS 信息读取
- **验证**：控制台打印原始数据 + 解析结果

### 阶段二：主界面（1 天）

- [ ] 底部导航三标签
- [ ] DashboardScreen：盲区卡片 + 电量 + 状态
- [ ] DeviceScreen：版本信息 + 雷达开关 + 复位按钮
- [ ] OverlaySettingsScreen：样式/大小/透明度选择
- **验证**：连接设备后 UI 实时更新

### 阶段三：悬浮窗（2 天）

- [ ] SYSTEM_ALERT_WINDOW 权限处理
- [ ] BsdIndicatorView：圆点渲染 + 颜色变化
- [ ] 拖拽 + 位置保存
- [ ] 横竖屏自适应
- [ ] 闪烁动画
- **验证**：收起 App → 圆点浮在地图上 → 告警时变色

### 阶段四：后台服务（1.5 天）

- [ ] BleService 前台 Service
- [ ] 持久通知
- [ ] 自动重连
- [ ] 告警通知（高优先级）
- **验证**：锁屏后 BLE 不断，告警时收到通知

### 阶段五：DFU 升级（1.5 天）

- [ ] Nordic DFU 库集成
- [ ] 文件选择器
- [ ] dfu_trigger → 设备切换 → DFU 传输
- [ ] 升级结果通知
- **验证**：选 zip → 设备升级 → 重启 → 新版本号

### 阶段六：打磨（1 天）

- [ ] 首次使用引导（权限申请 + 连接教程）
- [ ] 错误状态处理（蓝牙未开、权限拒绝、超时）
- [ ] 真机路测

| 阶段 | 新增任务 | 说明 |
|------|---------|------|
| 一 | 连接状态机 | ConnectionState 枚举 + 状态转换逻辑 |
| 三 | 防重叠机制 | 拖拽松手时检测距离 + 自动弹开 |
| 三 | 长按菜单 | 300ms 长按弹出快捷菜单 |
| 四 | 告警防抖 | 同侧 30s 内不重复通知 |
| 六 | 首次引导 | 4 屏引导 + 6 权限分步申请 |
| 六 | 厂商 ROM 适配 | 6 种 ROM 悬浮窗开启路径 |
| 六 | 折叠屏适配 | onConfigurationChanged + 避让折叠区域 |
| 六 | DFU 中断恢复 | 状态保存 + 续传提示 |

**总计约 12 天**（原 9 天 + 新增 3 天）

---

## 为什么只做 Android

### iOS 的本质障碍

iOS 三条硬限制：

1. **不允许跨 App UI 覆盖**：悬浮窗、屏幕叠加层在 iOS 完全被禁止（系统级安全策略，无例外、无绕行方案）
2. **后台 App 随时被系统挂起**：iOS 允许后台 BLE，但系统基于内存/功耗策略随时杀死 App，不给任何通知
3. **不存在 SYSTEM_ALERT_WINDOW 等价物**：Android 的悬浮窗权限在 iOS 完全没有对应功能

骑车场景的要求是"高德地图导航中，屏幕边缘显示 BSD 告警指示"。iOS 上要实现这个，唯一的办法是自己做一个导航 App——那需要 1000 人年的工作量。

### iOS 上存在的"折中方案"

| 方案 | 导航中可见吗 | 说明 |
|------|:--:|------|
| Live Activity（灵动岛/锁屏） | ❌ | 锁屏时才看得到 |
| 高优先级通知横幅 | ❌ | 2 秒消失 |
| Critical Alert 声音 | ❌ | 无视觉 |
| CarPlay | ❌ | 摩托车不支持 |

**全部不行。放弃 iOS。**

---

## 后续可扩展功能

- [ ] 图标样式：由用户自定义上传图片
- [ ] 自动启动：检测到 MotoBSD 设备 BLE 广播时自动连接（即使 App 未打开）
- [ ] 骑行数据记录：里程、告警次数、告警位置（GPS）→ 骑行报告
- [ ] 语音播报："左侧盲区有车辆靠近"
- [ ] 多设备支持（一辆摩托车前后雷达）
