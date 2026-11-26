package io.github.evilgenius79.shh

import android.app.Application
import io.github.evilgenius79.shh.data.AppStore
import io.github.evilgenius79.shh.data.KeystoreSecretBox
import io.github.evilgenius79.shh.data.KnownHostsAdapter
import io.github.evilgenius79.shh.data.SecretBox
import io.github.evilgenius79.shh.session.SessionManager
import io.github.evilgenius79.shh.ssh.SshSecurity
import java.io.File

/** The app's long lived objects, created once and shared by hand rather than through a framework. */
class AppContainer(
    val store: AppStore,
    val secrets: SecretBox,
    val sessions: SessionManager,
)

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
    }
}
