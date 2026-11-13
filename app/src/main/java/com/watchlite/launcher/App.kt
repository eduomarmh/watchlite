package com.watchlite.launcher

import android.app.Application
import android.content.Context
import com.watchlite.launcher.chime.ChimeScheduler
import com.watchlite.launcher.drawer.AppRepository
import com.watchlite.launcher.link.LinkManager
import com.watchlite.launcher.notifications.NotificationStore
import com.watchlite.launcher.safety.CrashGuard
import com.watchlite.launcher.steps.StepTracker
import com.watchlite.launcher.theme.ThemeRepository
import com.watchlite.launcher.util.DeviceState
import com.watchlite.launcher.util.LocaleHelper
import com.watchlite.launcher.util.Log

class App : Application() {

    companion object {
        lateinit var I: App
            private set
        val prefs: Prefs get() = I.prefsInstance
    }

    lateinit var prefsInstance: Prefs
        private set
    lateinit var themes: ThemeRepository
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var steps: StepTracker
        private set
    val notifications = NotificationStore()
    val state = DeviceState()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        I = this
        prefsInstance = Prefs(this)
        CrashGuard.install(this)
        Log.i("App", "start v${BuildConfig.VERSION_NAME}")
        themes = ThemeRepository(this)
        apps = AppRepository(this)
        steps = StepTracker(this)
        LinkManager.init(this)
        ChimeScheduler.schedule(this)
        com.watchlite.launcher.system.Permissions.autoEnable(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) apps.trimIcons()
        themes.trimMemory()
    }
}
