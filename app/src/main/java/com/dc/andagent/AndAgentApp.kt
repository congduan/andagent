package com.dc.andagent

import android.app.Application
import com.dc.andagent.cron.CronScheduler
import com.dc.andagent.i18n.Lang

class AndAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { Lang.attach(this) }
            .onFailure { android.util.Log.e("AndAgentApp", "Language load failed", it) }
        runCatching { CronScheduler.ensure(this) }
            .onFailure { android.util.Log.e("AndAgentApp", "Cron recovery failed", it) }
    }
}
