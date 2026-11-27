package io.github.evilgenius79.shh

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.KeystoreSecretBox
import io.github.evilgenius79.shh.data.KnownHostsAdapter
import io.github.evilgenius79.shh.data.SecretBox
import io.github.evilgenius79.shh.session.SessionManager
import io.github.evilgenius79.shh.ssh.SshSecurity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** The app's long lived objects, created once and shared by hand rather than through a framework. */
class AppContainer(
    val store: AppStore,
    val secrets: SecretBox,
    val sessions: SessionManager,
) {
    /** What the app was last started to do, until the UI has done it. */
    val launch = MutableStateFlow<Launch?>(null)
}

class ShhApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Must happen before anything touches SSH: replaces Android's cut down "BC" provider.
        SshSecurity.install()
        val store = AppStore(File(filesDir, "shh.json"))
        val secrets = KeystoreSecretBox()
        val sessions = SessionManager(this, store, secrets, KnownHostsAdapter(store))
        container = AppContainer(store, secrets, sessions)

        // Sessions want to know whether anybody is looking: a bell is only worth a notification
        // when nobody is.
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                private var started = 0

                override fun onActivityStarted(activity: Activity) {
                    started++
                    sessions.appInForeground = true
                }

                override fun onActivityStopped(activity: Activity) {
                    started--
                    sessions.appInForeground = started > 0
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )

        // The home screen shortcuts follow the hosts that were used last.
        MainScope().launch {
            store.data.map { HostShortcuts.choose(it) }.distinctUntilChanged().collect { HostShortcuts.publish(this@ShhApp, it) }
        }
    }
}
