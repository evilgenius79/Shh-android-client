package io.github.evilgenius79.shh.ssh

import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.transport.compression.DelayedZlibCompression
import net.schmizz.sshj.transport.compression.NoneCompression
import net.schmizz.sshj.transport.compression.ZlibCompression

/**
 * The algorithms offered to servers, strongest first.
 *
 * sshj's own defaults still include SHA-1 key exchange, MD5 MACs, CBC mode and RC4. Those are
 * left out here unless the user explicitly enables legacy algorithms for a host, which is
 * sometimes necessary for old routers and embedded devices.
 */
internal object SshAlgorithms {
    private val KEX = listOf(
        "curve25519-sha256",
        "curve25519-sha256@libssh.org",
        "ecdh-sha2-nistp521",
        "ecdh-sha2-nistp384",
        "ecdh-sha2-nistp256",
        "diffie-hellman-group-exchange-sha256",
        "diffie-hellman-group18-sha512",
        "diffie-hellman-group16-sha512",
        "diffie-hellman-group14-sha256",
    )
    private val KEX_LEGACY = listOf(
        "diffie-hellman-group14-sha1",
        "diffie-hellman-group-exchange-sha1",
        "diffie-hellman-group1-sha1",
    )

    /** Announces support for the server-sig-algs extension; not a real key exchange. */
    private const val EXT_INFO = "ext-info-c"

    private val HOST_KEYS = listOf(
        "ssh-ed25519-cert-v01@openssh.com",
        "ssh-ed25519",
        "ecdsa-sha2-nistp521-cert-v01@openssh.com",
        "ecdsa-sha2-nistp521",
        "ecdsa-sha2-nistp384-cert-v01@openssh.com",
        "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp256-cert-v01@openssh.com",
        "ecdsa-sha2-nistp256",
        "rsa-sha2-512",
        "rsa-sha2-256",
    )
    private val HOST_KEYS_LEGACY = listOf("ssh-rsa-cert-v01@openssh.com", "ssh-rsa", "ssh-dss")

    private val CIPHERS = listOf(
        "chacha20-poly1305@openssh.com",
        "aes256-gcm@openssh.com",
        "aes128-gcm@openssh.com",
        "aes256-ctr",
        "aes192-ctr",
        "aes128-ctr",
    )
    private val CIPHERS_LEGACY = listOf("aes256-cbc", "aes192-cbc", "aes128-cbc", "3des-cbc")

    private val MACS = listOf(
        "hmac-sha2-256-etm@openssh.com",
        "hmac-sha2-512-etm@openssh.com",
        "hmac-sha2-256",
        "hmac-sha2-512",
    )
    private val MACS_LEGACY = listOf("hmac-sha1-etm@openssh.com", "hmac-sha1")

    fun config(allowLegacy: Boolean, compression: Boolean): DefaultConfig {
        SshSecurity.install()
        val config = DefaultConfig()
        config.keyExchangeFactories = pick(config.keyExchangeFactories, KEX + (if (allowLegacy) KEX_LEGACY else emptyList()) + EXT_INFO)
        config.keyAlgorithms = pick(config.keyAlgorithms, HOST_KEYS + if (allowLegacy) HOST_KEYS_LEGACY else emptyList())
        config.cipherFactories = pick(config.cipherFactories, CIPHERS + if (allowLegacy) CIPHERS_LEGACY else emptyList())
        config.macFactories = pick(config.macFactories, MACS + if (allowLegacy) MACS_LEGACY else emptyList())
        config.compressionFactories = if (compression) {
            listOf(DelayedZlibCompression.Factory(), ZlibCompression.Factory(), NoneCompression.Factory())
        } else {
            listOf(NoneCompression.Factory())
        }
        // Real keep-alive requests that notice a dead peer, rather than one-way heartbeats.
        config.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        return config
    }

    /** Keeps the factories named in [names], in that order. Unknown names are skipped. */
    private fun <T> pick(available: List<Factory.Named<T>>, names: List<String>): List<Factory.Named<T>> =
        names.mapNotNull { name -> available.firstOrNull { it.name == name } }
}
