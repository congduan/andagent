package com.dc.andagent

import android.app.Application
import com.dc.andagent.cron.CronScheduler
import com.dc.andagent.i18n.Lang
import com.dc.andagent.tools.KeepAliveRefs

class AndAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { Lang.attach(this) }
            .onFailure { android.util.Log.e("AndAgentApp", "Language load failed", it) }
        runCatching { CronScheduler.ensure(this) }
            .onFailure { android.util.Log.e("AndAgentApp", "Cron recovery failed", it) }
        // R124 b（Codex review 必修 3）：尽早安装保活服务的 start/stop 动作与应用上下文，
        // 避免 SSH/ADB 首次 acquire 时动作未装导致服务拉不起来（静默无保活）。
        runCatching { KeepAliveRefs.installActions(this) }
            .onFailure { android.util.Log.e("AndAgentApp", "KeepAlive actions install failed", it) }
    }
}
