package com.motobsd.model

/**
 * BLE 连接状态枚举，UI 和通知均绑定此状态。
 */
enum class ConnectionState(val label: String) {
    Idle("未启动"),
    Scanning("扫描中"),
    Connecting("连接中"),
    Subscribing("订阅中"),
    Ready("已就绪"),
    Reconnecting("重连中"),
    Failed("连接失败"),
}
