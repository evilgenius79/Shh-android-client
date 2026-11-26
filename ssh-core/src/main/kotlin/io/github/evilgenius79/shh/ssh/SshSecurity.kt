package io.github.evilgenius79.shh.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/** One-time setup of the cryptography provider used for everything SSH. */
object SshSecurity {
    private var installed = false

    /**
     * Makes sure the full BouncyCastle provider that ships with the app is the one registered
     * under the name "BC".
     *
     * Android comes with a cut down provider of the same name that lacks, among other things,
     * X25519 and Ed25519 on older releases. sshj asks for algorithms by provider name, so the
     * platform provider has to be replaced rather than merely supplemented. The provider is
     * appended to the end of the provider list so that it never takes precedence for anything
     * that did not ask for it by name (Android Keystore, TLS, ...).
     *
     * Safe to call any number of times and from any thread.
     */
    @JvmStatic
    @Synchronized
    fun install() {
        if (installed) return
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing == null || existing.javaClass.name != BouncyCastleProvider::class.java.name) {
            if (existing != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
        // Have sshj pick the provider up now rather than at some later, less predictable point.
        check(SecurityUtils.getSecurityProvider() == BouncyCastleProvider.PROVIDER_NAME) {
            "sshj did not select the BouncyCastle provider"
        }
        installed = true
    }
}
