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
  4. 屏幕两侧出现弧形盲区灯带（`|)` / `(|`，叠加在地图之上）
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
│  · 状态 Tab：连接状态 / 盲区卡片 / 电量 / 详情折叠区      │
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

App 端由 target_details 计算**左右威胁度（0~1）**驱动悬浮窗连续显示：
威胁度 = 距离贡献（0m→1，30m→0）+ 接近速度加分（velocity 正=靠近，语义待真机确认）；
alert_status 仅作 presence 下限兜底（有目标但详情缺失时威胁度 ≥ 0.3）。
告警级别（Safe/Warning/Alert/Critical）保留，用于通知/声音/测试模式。

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

| 通道 | 表现 | 说明 |
|------|------|------|
| 颜色 | 黄 `#FFC107` → 橙 `#FF9800` → 红 `#F44336` 连续渐变 | 随威胁度连续插值，不引入蓝绿（余光识别 + 避免"绿色=安全"歧义） |
| 亮度 | 威胁越高越亮 | 亮度系数 0.5~1.0 × 用户透明度，余光最敏感通道 |
| 弧长 | 弧从中间一小段向两端延伸 | 威胁 0.3 → 1.0 覆盖，与"充电弧"隐喻一致 |
| 断线 | 灰色慢速呼吸（2s） | 与 Safe 全透明明确区分 |

告警级别文案（安全/警告/警惕/危险）保留给通知、声音与测试模式：
Critical 才触发告警通知 + 震动 + 声音（同侧 30s 防抖）。

> 测试告警模式将四级映射到固定威胁度（Safe=0 / Warning=0.45 / Alert=0.75 / Critical=1），
> 便于无设备时预览灯带效果。

**告警不自动超时消失**：灯带持续显示直到收到安全通知或 BLE 断线；
数据新鲜度由 Dashboard「周围目标」卡的"实时 / 更新于 Ns 前"提示兜底。

**断线状态**：断线时悬浮指示切换为**灰色慢速呼吸**（2s 周期），与"安全"的恒亮灰
（光带模式下为全透明）明确区分；重连后恢复当前级别显示。
同时 BleRepositoryImpl 把告警/目标/威胁度复位为 0（Safe）。

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
- 重连：每轮等待退避延迟后直连上次 MAC，15s 内未就绪视为失败
  - 骑行中意外断线（自动重连）：最多 10 次，可能只是暂时超出范围，给足机会
  - 手动「重连上次设备」：最多 3 次，失败提示"未找到设备：请确认设备已开机并在附近"
  - 连接/重连/扫描中，Dashboard 底部操作栏提供「取消/停止」，随时终止重试
- 用户主动断开后不会自动重连
- 左右反转（`swapLeftRight`）在仓库层对告警左右交换，适配雷达安装方向

## 8. 悬浮窗设计

### 8.1 形态（单一弧形灯带）

左右各一条"充电弧"式灯带（`|)` / `(|`）：外缘贴屏幕边缘为直线，内缘是向屏幕中心
凸起的弧线，中间最宽、向两端收窄。由威胁度（0~1）连续驱动颜色（黄→橙→红）、
亮度与弧长。Dot/Bar/Arrow 三种图标样式已删除（效果不如灯带）。
断线时灯带切换为灰色慢速呼吸。

### 8.2 属性

| 属性 | 默认值 | 范围 |
|------|--------|------|
| 粗细 | 中 40dp | 细 28 / 中 40 / 粗 56dp |
| 透明度 | 60% | 35%-100%（下限 35%，保证户外可见） |
| 光带位置 | 左右边缘 | 左右边缘 / 上下边缘 |
| 左右反转 | 关 | 开/关 |

### 8.3 弧形灯带

- 左右边缘模式：外缘贴屏幕左右边缘，内缘弧向屏幕中心凸起；上下边缘模式：贴上下边缘，内缘弧向屏内凸起
- 颜色：黄 → 橙 → 红按威胁度连续插值（t<0.5 黄→橙，t≥0.5 橙→红）
- 亮度：`用户透明度 × (0.5 + 0.5×威胁度)`，威胁越高越亮
- 弧长：覆盖范围 `0.3 + 0.7×威胁度`（威胁越大越接近整条边）；弧度 `(0.8 + 0.7×威胁度)×厚度`
- Safe（无目标）全透明（不遮挡）；断线灰色慢速呼吸、整幅覆盖
- 灯带框架使用系统最大窗口区域定位（Android 11+），自动避开不透明状态栏/导航栏、适配透明栏与挖孔，不同手机上均贴屏幕边缘
- 光带不可触摸（`FLAG_NOT_TOUCHABLE`），不拦截导航手势

### 8.4 位置与交互

- 灯带固定贴屏幕边缘，不可拖动（原 Dot/Bar/Arrow 的拖拽/双击/长按交互随图标样式一并移除）
- 旋转/横竖屏切换时按系统最大窗口区域重算位置

### 8.5 权限处理

- OverlayService 启动时检查 `Settings.canDrawOverlays`；未授权不创建窗口，前台通知提示"需要悬浮窗权限"，不崩溃
- MainActivity 每次进程只提示一次跳转悬浮窗设置页（0.5.0 修复反复弹跳）
- 「灯带设置」页提供「悬浮窗指示」开关；偏好持久化且**默认开启**——首次进入自动启动，手动关闭后保持关闭

## 9. 界面设计

### 9.1 状态（Dashboard）

```
┌──────────────────────────────────┐
│ MotoBSD              [●已连接]   │
│ [ 重连上次设备 ]（仅断线/失败时） │
│ ┌────────────────────────────┐  │
│ │ 周围目标            [实时]  │  │
│ │     ╱ 5m 10m 15m ╲        │  │
│ │   ●    前方       ●       │  │
│ │   左         右           │  │
│ │    ●    后方              │  │
│ │   3.2m · 6m/s 靠近        │  │
│ └────────────────────────────┘  │
│ ┌────────────────────────────┐  │
│ │ 82%  3.85V                 │  │
│ │ ████████░░░░（<20红/<50黄/else绿）│
│ └────────────────────────────┘  │
│ ▸ 设备与目标详情（可折叠）       │
│   最近目标 / 温度 / USB / 雷达   │
└──────────────────────────────────┘
（底部固定操作栏：开始骑行 / 最小化 / 断开）
```

- 连接徽标：右侧胶囊形状态指示；未连接=彩色圆点，已连接=4 格信号条 + 文案
  （信号条：≥-60 绿 / ≥-75 黄 / ≥-85 橙 / 其余红；RSSI < -80 时胶囊变橙显示"信号弱"）
  RSSI 在连接就绪后每 5s 由 `readRssi()` 轮询一次
- 忙碌态（扫描/连接/重连）用蓝色，避免与告警黄混淆
- 雷达视图（替换原左右盲区卡）：后向扇形（默认 ±75°，前方无探测，弱化标注），
  器件位于画布顶部中央，扇区向下张开、弧线撑满画布宽度（前方死区只剩窄边）；
  俯视显示全部目标（最多 8 个），角度→方向、距离→半径；越界目标钳制到扇区边界；
  距离刻度自适应：无远目标时 15m，按最远目标扩展到 50m（对应 AT6010 汽车 50m 探测规格）；
  颜色语义与告警一致（靠近且 ≤5m=红 / ≤10m=橙 / 靠近=黄 / 远离与静止=灰），
  最近目标带距离/速度标签；扇区左右边界内侧小圆点 = alert_status 有无目标（presence 与详情互补）
- 「周围目标」卡右上角显示数据新鲜度：实时 / 更新于 Ns 前（>5s 变红，防静默失效）
- 目标事件记录：雷达图下方左/右两列（负角度→左列、正角度→右列，每侧最多 4 条），
  格式 `HH:mm:ss 距离 角度`；以 obj_id 为单位（进入创建、持续刷新、消失保留 60 秒后清除），
  消失记录置灰；正后方（0°）不进列表
- 电量卡：百分比 + 电压 + 进度条（绿/黄/红，与连接蓝解耦），常驻首屏
- 「设备与目标详情」折叠区（默认收起）：最近目标（按距离取前 2）+ 温度 + USB + 雷达在线状态
- 断线/失败且存在上次设备时，显示「重连上次设备」一键入口
- 底部固定操作栏（不随内容滚动）：主按钮全宽（52dp）+ 次行小按钮
  - 未连接/错误→主按钮"扫描设备/重试扫描"；连接/重连/扫描中→"取消重连/停止扫描"
  - 已连接→主按钮"开始骑行/退出骑行"（绿色=进行中），次行"最小化 / 断开连接"
- 「开始骑行」（骑行模式）：要求 BLE 已就绪；启动 BleService + OverlayService，悬浮窗窗口加 `FLAG_KEEP_SCREEN_ON` 保持屏幕常亮，随后 moveTaskToBack；状态持久化（重启进程后由 OverlayService 恢复），手动断开连接时自动退出
- 「最小化」：启动 BleService + OverlayService 并 moveTaskToBack（不开启屏幕常亮）

### 9.2 设备（Device）

```
设备信息       产品型号 / 序列号 / 硬件版本 / 固件版本 / 制造商
设备名称       当前名称 / 刷新 / 修改（≤20 字节，重连后生效）
固件升级       当前版本 + [选择升级包]（zip → Nordic DFU）
雷达设置       雷达电源 [开关]（绑定 radarOnline 状态）
系统           [重启设备]（确认对话框）
```

- 设备名称：写入后延时回读，提示"名称已更新（重连后生效）"
- DFU：文件选择（application/zip）→ 写 `dfu_trigger`(0x01) 进入 bootloader（期间抑制自动重连）→ `DfuServiceInitiator`（packet receipt 8、重试 5）

### 9.3 灯带设置（Overlay）

```
灯带粗细        细 / 中 / 粗
透明度          Slider 35%-100%
测试告警        左/右独立「切换」循环 安全→警告→警惕→危险，实时驱动浮窗与声音
声音设置        音量 0-100、左频/右频 100-2000Hz、试听左/右/左急/右急
高级            左右反转 [开关]
[光带位置：左右边缘/上下边缘 切换]
[悬浮窗指示：开/关]（默认开；关闭后需手动开启；无权限时提示先到系统设置授权）
```

测试告警按四级映射威胁度（Safe=0 / Warning=0.45 / Alert=0.75 / Critical=1）实时驱动灯带与声音；
「重置为安全」后恢复真实数据。

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

- AudioTrack 实时生成三角波 PCM；默认 `USAGE_MEDIA`（跟随媒体音量、支持蓝牙耳机），
  可在灯带设置页切换为 `USAGE_ALARM`（闹钟音量、可穿透其他声音）
- Warning：3 声 × 100ms，间隔 500ms；循环间隔 3000ms
- Critical：5 声 × 80ms，间隔 100ms；循环间隔 1500ms
- 默认频率：左 1000Hz / 右 400Hz；Critical 左频 ×1.2（≤2500Hz）
- 优先级：Critical > Warning，左 > 右；全部安全时停止
- 音量/左右频率/音频流持久化到 DataStore，灯带设置页可调

## 12. 权限清单

| 权限 | 用途 | 运行时请求 |
|------|------|-----------|
| BLUETOOTH / BLUETOOTH_ADMIN（maxSdk 30） | Android 8-11 旧版蓝牙 | 否 |
| BLUETOOTH_SCAN（neverForLocation）/ BLUETOOTH_CONNECT | Android 12+ BLE | onResume 一次性请求 |
| ACCESS_FINE_LOCATION（maxSdk 30） | 仅 Android 8-11 BLE 扫描 | 仅 < Android 12 请求 |
| POST_NOTIFICATIONS | Android 13+ 通知 | onResume 一次性请求 |
| SYSTEM_ALERT_WINDOW | 悬浮窗 | 每进程提示一次（跳系统设置） |
| FOREGROUND_SERVICE / CONNECTED_DEVICE / SPECIAL_USE | 前台服务 | 否 |
| VIBRATE | 告警震动 | 否 |

服务声明：BleService `connectedDevice`；OverlayService `specialUse`；DfuService `connectedDevice`。
Activity 声明 `configChanges`（orientation/screenSize/screenLayout/smallestScreenSize）。

## 13. 数据持久化（DataStore：`motobsd_settings`）

| Key | 内容 |
|-----|------|
| last_mac | 上次连接的 MAC |
| onboarding_complete | 引导完成标记 |
| overlay_size / overlay_alpha | 粗细 / 透明度 |
| overlay_swap / overlay_orientation | 左右反转 / 光带方向 |
| overlay_enabled | 悬浮窗开关偏好（默认开启） |
| sound_volume / sound_left_freq / sound_right_freq | 声音设置 |

配置变更通过 `OverlayRepository.configFlow`（内存 StateFlow）实时直通 OverlayService，不经过 DataStore 中转。

## 14. 已知缺口（半成品状态）

- **DFU**：仅选包 + Nordic 传输，无进度 UI、无中断恢复流程
- **通知**：持久通知无电量/电压，告警通知无目标详情，文案固定
- **Onboarding**：4 屏纯展示，无实际权限请求；无"连接设备"步骤
- **折叠屏**：androidx.window 已引入，避让折叠区域未实现
- **设备页**：无"恢复出厂设置"
- **声音**：无多目标/连续告警去重之外的策略；循环间隔固定
- **代码遗留**：`DeviceStatus.batteryRaw` 未使用；`USE_EXACT_ALARM` 权限未使用
- **显示细节**：威胁度的距离/速度权重（THREAT_RANGE_MAX / THREAT_SPEED_WEIGHT / 符号）未真机标定；
  悬浮窗数据新鲜度提示未做（仅在 Dashboard 雷达卡显示）

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
        │                           # / OverlayConfig / BleConnectionState
        ├── data/
        │   ├── ble/                # BleRepository / Impl / ConnectionManager / Scanner
        │   ├── overlay/OverlayRepository.kt
        │   └── settings/SettingsRepository.kt
        ├── di/                     # DataModule / BleModule / OverlayModule
        ├── service/                # BleService / OverlayService / DfuService
        ├── overlay/                # OverlayWindow / BsdIndicatorView
        │                           # / OverlayWindowHolder
        ├── audio/SoundManager.kt   # AudioTrack 告警音
        └── ui/
            ├── navigation/NavGraph.kt
            ├── dashboard/          # 状态页 + ViewModel
            ├── device/             # 设备页 + ViewModel
            ├── devicelist/         # 扫描列表页 + ViewModel
            ├── overlay/            # 灯带设置页 + ViewModel
            ├── onboarding/         # 首次引导
            ├── components/         # RadarView / BatteryGauge
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
