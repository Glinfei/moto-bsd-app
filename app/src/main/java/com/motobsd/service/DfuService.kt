package com.motobsd.service

import android.app.Activity
import com.motobsd.MainActivity
import no.nordicsemi.android.dfu.DfuBaseService

/**
 * MotoBSD DFU 升级服务。
 * 继承 Nordic DfuBaseService，Android Manifest 中注册此类。
 */
class DfuService : DfuBaseService() {
    override fun getNotificationTarget(): Class<out Activity> = MainActivity::class.java
}
