package com.suseoaa.locationspoofer

import android.app.Application
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.suseoaa.locationspoofer.di.appModule
import com.topjohnwu.superuser.Shell
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class LocationApp : Application() {

    companion object {
        init {
            // 必须在任何 Shell.cmd() 调用之前配置，且只配置一次
            // setTimeout(60)：给用户 60 秒响应 Magisk 授权弹窗
            // FLAG_MOUNT_MASTER：以 mount namespace master 模式运行，兼容更多 root 场景
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(60)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)

        startKoin {
            androidLogger()
            androidContext(this@LocationApp)
            modules(appModule)
        }
    }
}
