package io.github.evilgenius79.shh.data

import io.github.evilgenius79.shh.ssh.HostKeyStore
import io.github.evilgenius79.shh.ssh.SshPublicKey
import java.util.Base64

/** Exposes the known hosts kept in [AppStore] to the SSH layer. */
class KnownHostsAdapter(private val store: AppStore) : HostKeyStore {
    override fun find(host: String, port: Int): List<SshPublicKey> =
        store.data.value.knownHosts
            .filter { it.host == host && it.port == port }
            .mapNotNull { entry ->
                try {
                    SshPublicKey(entry.keyType, Base64.getDecoder().decode(entry.keyBase64))
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

    override fun remember(host: String, port: Int, key: SshPublicKey) {
        store.rememberHostKey(KnownHost(host, port, key.type, key.base64, System.currentTimeMillis()))
    }
}

/** The fingerprint to show for a stored host key. */
fun KnownHost.fingerprint(): String = try {
    SshPublicKey(keyType, Base64.getDecoder().decode(keyBase64)).fingerprint
} catch (_: IllegalArgumentException) {
    "?"
}
