package com.cameng.app

import android.app.Application
import com.cameng.app.drive.UploadScheduler

class CamEngApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        UploadScheduler.schedulePeriodic(this)
        UploadScheduler.enqueueNow(this)
    }
}
