package io.github.evilgenius79.shh

import io.github.evilgenius79.shh.ssh.LoadedKey
import io.github.evilgenius79.shh.ssh.SshCertificate
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Makes real OpenSSH certificates for tests by running `ssh-keygen`, the tool that issues them. */
object TestCertificates {
    val available: Boolean by lazy {
        try {
            ProcessBuilder("ssh-keygen", "-?").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
    }

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command.joinToString(" ")} failed: $output" }
    }

    /** A private key file that only its owner can read, which is the only kind ssh-keygen accepts. */
    private fun private(directory: File, text: String): File {
        val file = File(directory, "authority")
        file.writeText(text)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        return file
    }

    /**
     * Has an authority certify [key] for the account [principal]. The authority is the one whose
     * private key is in [authorityKey], or a new one that no server trusts when that is null.
     */
    fun certify(key: LoadedKey, principal: String, authorityKey: File? = null, id: String = "issued-for-the-test"): SshCertificate {
        val directory = Files.createTempDirectory("shh-cert").toFile()
        try {
            val authority = if (authorityKey != null) {
                private(directory, authorityKey.readText())
            } else {
                File(directory, "authority").also { run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", it.path) }
            }
            val publicKey = File(directory, "key.pub")
            publicKey.writeText(key.publicKey.toAuthorizedKey("test") + "\n")
            run("ssh-keygen", "-q", "-s", authority.path, "-I", id, "-n", principal, "-V", "+10m", publicKey.path)
            return SshCertificate.parse(File(directory, "key-cert.pub").readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
