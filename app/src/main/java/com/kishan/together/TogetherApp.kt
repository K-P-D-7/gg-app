package com.kishan.together

import android.app.Application

class TogetherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        KeepAlive.schedule(this)
    }
}
