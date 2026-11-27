package io.github.evilgenius79.shh.ssh

import java.io.IOException

/** One server to log in to: where it is and how to authenticate. */
class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String,
    /** Private keys to offer, in order. */
    val keys: List<LoadedKey> = emptyList(),
    /** Stored password, or null to ask the user when the server wants one. */
    val password: CharArray? = null,
    /** Offer legacy algorithms (SHA-1 key exchange, CBC ciphers, ssh-rsa) for old devices. */
    val allowLegacyAlgorithms: Boolean = false,
    /** Which of the host name's addresses to connect to. */
    val addressFamily: AddressFamily = AddressFamily.ANY,
    /**
     * Keys to lend to programs on the server (agent forwarding), or null for none. The list is
     * only asked for when the server first wants it.
     */
    val agentKeys: (() -> List<LoadedKey>)? = null,
) {
    override fun toString(): String = "$username@$host:$port"
}

/** Which kind of address to use for a host name that has several. */
enum class AddressFamily {
    /** Whatever the name resolves to, trying each address in turn. */
    ANY,
    IPV4,
    IPV6,
}

/** How to reach and keep up a connection. */
class ConnectOptions(
    val connectTimeoutMs: Int = 15_000,
    /** Seconds between keep-alive probes; 0 turns them off. */
    val keepAliveSeconds: Int = 30,
    /** Unanswered probes after which the connection is considered dead. */
    val keepAliveMaxMissed: Int = 3,
    val compression: Boolean = false,
)

/** A single question the server asks during keyboard-interactive authentication. */
class InteractivePrompt(val text: String, val echo: Boolean)

/** One round of keyboard-interactive authentication (one-time codes, PAM prompts, ...). */
class InteractiveChallenge(
    val host: String,
    val username: String,
    val name: String,
    val instruction: String,
    val prompts: List<InteractivePrompt>,
)

/**
 * Questions that only the user can answer. All methods are called on the thread that is
 * connecting and may block for as long as the user needs.
 */
interface SshPrompts {
    /** The server presented a key that is not trusted yet. */
    fun confirmHostKey(request: HostKeyRequest): HostKeyDecision

    /**
     * The server wants a password and none is stored, or the stored one was refused.
     *
     * @param attempt 1 for the first question, 2 after the first wrong answer and so on.
     * @return the password, or null to give up.
     */
    fun requestPassword(target: SshTarget, attempt: Int): CharArray?

    /** @return one answer per prompt, or null to give up. */
    fun answerChallenge(challenge: InteractiveChallenge): List<String>?

    /**
     * A program on [target] asks to sign something with [key] through the forwarded agent, which
     * amounts to logging in somewhere as the user. Called once per request and may block.
     *
     * @return true to let it. The default refuses, so that nothing is lent by oversight.
     */
    fun confirmAgentUse(target: SshTarget, key: SshPublicKey): Boolean = false

    /**
     * [target] would accept [key], which lives on a security key, and wants the signature that
     * proves it is at hand. Called once per login and may block for as long as it takes the
     * user to find the key and touch it.
     *
     * The default passes the key over, for callers that cannot reach such hardware.
     */
    fun signWithSecurityKey(target: SshTarget, key: SshPublicKey, request: SecurityKeyRequest): SecurityKeyAnswer = SecurityKeyAnswer.Skip

    /** The server sent a banner to show before logging in. */
    fun onBanner(target: SshTarget, banner: String) {}

    /** Progress messages suitable for a "connecting" screen. */
    fun onProgress(message: String) {}
}

/** Why a connection attempt failed. */
enum class SshFailure {
    /** The host name could not be resolved. */
    UNKNOWN_HOST,

    /** Nothing answered: wrong address, firewall, or no network. */
    UNREACHABLE,

    /** The host answered but nothing is listening on that port. */
    REFUSED,
    TIMEOUT,

    /** The user did not accept the server's host key. */
    HOST_KEY_REJECTED,

    /** None of the offered credentials were accepted. */
    AUTH_FAILED,

    /** The user dismissed a prompt. */
    CANCELLED,

    /** Client and server could not agree on algorithms, or the peer is not an SSH server. */
    PROTOCOL,

    /** The connection broke for some other reason. */
    IO,
}

/** Thrown by [SshConnector.connect]. [failure] says what went wrong in terms a UI can act on. */
class SshConnectException(
    val failure: SshFailure,
    message: String,
    cause: Throwable? = null,
    /** For [SshFailure.AUTH_FAILED]: the methods the server would have accepted. */
    val serverAuthMethods: List<String> = emptyList(),
) : IOException(message, cause)

/** Used internally to unwind when the user dismisses a prompt. */
internal class PromptCancelledException : RuntimeException("Cancelled by user")
