package io.github.evilgenius79.shh.ssh

import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/** How a host key presented by a server relates to what is already trusted. */
enum class HostKeyStatus {
    /** The key matches one that was accepted before. */
    TRUSTED,

    /** Nothing is known about this host yet. */
    UNKNOWN,

    /** The host is known, but not with a key of this type. */
    NEW_KEY_TYPE,

    /**
     * The host is known with a different key of the same type. Either the server was reinstalled
     * or someone is intercepting the connection.
     */
    CHANGED,
}

/** What the user decided about a host key that is not trusted yet. */
enum class HostKeyDecision { REJECT, ACCEPT_ONCE, ACCEPT_AND_REMEMBER }

/** Everything the user needs to see to decide whether to trust a server. */
class HostKeyRequest(
    val host: String,
    val port: Int,
    val key: SshPublicKey,
    val status: HostKeyStatus,
    /** Keys already trusted for this host; for [HostKeyStatus.CHANGED] this includes the old key. */
    val knownKeys: List<SshPublicKey>,
)

/** Persistent record of the host keys the user has accepted (the app's `known_hosts`). */
interface HostKeyStore {
    /** Keys trusted for the server at [host]:[port]. */
    fun find(host: String, port: Int): List<SshPublicKey>

    /** Remembers [key] for [host]:[port], replacing any stored key of the same type. */
    fun remember(host: String, port: Int, key: SshPublicKey)
}

/** A [HostKeyStore] that only lives in memory, useful for tests and one-off connections. */
class InMemoryHostKeyStore : HostKeyStore {
    private val entries = HashMap<String, MutableList<SshPublicKey>>()

    @Synchronized
    override fun find(host: String, port: Int): List<SshPublicKey> = entries["$host:$port"]?.toList() ?: emptyList()

    @Synchronized
    override fun remember(host: String, port: Int, key: SshPublicKey) {
        val list = entries.getOrPut("$host:$port") { ArrayList() }
        list.removeAll { it.type == key.type }
        list.add(key)
    }
}

/** Classifies [key] against the keys already trusted for a host. */
fun classifyHostKey(key: SshPublicKey, known: List<SshPublicKey>): HostKeyStatus = when {
    known.any { it == key } -> HostKeyStatus.TRUSTED
    known.isEmpty() -> HostKeyStatus.UNKNOWN
    known.any { it.type == key.type } -> HostKeyStatus.CHANGED
    else -> HostKeyStatus.NEW_KEY_TYPE
}

/** Bridges sshj's host key callback to [HostKeyStore] and the user's decision. */
internal class TrustOnFirstUseVerifier(
    private val store: HostKeyStore,
    private val prompts: SshPrompts,
) : HostKeyVerifier {
    /** Set when the user turned a key down, so the failure can be reported as such. */
    @Volatile
    var rejected: HostKeyRequest? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val presented = SshPublicKey.of(key)
        val known = store.find(hostname, port)
        val status = classifyHostKey(presented, known)
        if (status == HostKeyStatus.TRUSTED) return true
        val request = HostKeyRequest(hostname, port, presented, status, known)
        return when (prompts.confirmHostKey(request)) {
            HostKeyDecision.ACCEPT_AND_REMEMBER -> {
                store.remember(hostname, port, presented)
                true
            }
            HostKeyDecision.ACCEPT_ONCE -> true
            HostKeyDecision.REJECT -> {
                rejected = request
                false
            }
        }
    }

    /**
     * Tells sshj which key types we already trust for this host so that it asks the server for
     * one of those; otherwise a server with several host keys could present a different one and
     * trigger a needless warning.
     */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        store.find(hostname, port).map { it.type }.distinct()
}
