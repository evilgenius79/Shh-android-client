package io.github.evilgenius79.shh.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshKeysTest {
    private val fastAlgorithms = listOf(KeyAlgorithm.ED25519, KeyAlgorithm.ECDSA_256, KeyAlgorithm.ECDSA_384, KeyAlgorithm.ECDSA_521)

    /**
     * Two key pairs are the same key when their public halves are identical and a signature made
     * with one private half verifies against the other public half. (Comparing the private key
     * objects directly is not reliable: BouncyCastle treats differently encoded curve parameters
     * as unequal.)
     */
    private fun assertSameKey(expected: LoadedKey, actual: LoadedKey, label: String) {
        assertEquals(expected.publicKey, actual.publicKey, label)
        val algorithm = when {
            expected.publicKey.type == "ssh-ed25519" -> "Ed25519"
            expected.publicKey.type == "ssh-rsa" -> "SHA256withRSA"
            else -> "SHA256withECDSA"
        }
        val message = "the quick brown fox".toByteArray()
        val signer = java.security.Signature.getInstance(algorithm, "BC")
        signer.initSign(actual.keyPair.private)
        signer.update(message)
        val signature = signer.sign()
        val verifier = java.security.Signature.getInstance(algorithm, "BC")
        verifier.initVerify(expected.keyPair.public)
        verifier.update(message)
        assertTrue(verifier.verify(signature), "$label: signature made with the reloaded key must verify")
    }

    @Test
    fun generatedKeysHaveTheExpectedTypes() {
        val expected = mapOf(
            KeyAlgorithm.ED25519 to ("ssh-ed25519" to "Ed25519"),
            KeyAlgorithm.ECDSA_256 to ("ecdsa-sha2-nistp256" to "ECDSA P-256"),
            KeyAlgorithm.ECDSA_384 to ("ecdsa-sha2-nistp384" to "ECDSA P-384"),
            KeyAlgorithm.ECDSA_521 to ("ecdsa-sha2-nistp521" to "ECDSA P-521"),
        )
        for ((algorithm, names) in expected) {
            val key = SshKeys.generate(algorithm)
            assertEquals(names.first, key.publicKey.type)
            assertEquals(names.second, key.description)
        }
    }

    @Test
    fun rsaKeyGeneration() {
        val key = SshKeys.generate(KeyAlgorithm.RSA_3072)
        assertEquals("ssh-rsa", key.publicKey.type)
        assertEquals("RSA 3072", key.description)
        val reloaded = SshKeys.load(SshKeys.toOpenSshPrivateKey(key, "rsa@test"))
        assertSameKey(key, reloaded, "RSA")
    }

    @Test
    fun openSshFormatRoundTrip() {
        for (algorithm in fastAlgorithms) {
            val key = SshKeys.generate(algorithm)
            val text = SshKeys.toOpenSshPrivateKey(key, "someone@phone")
            assertTrue(text.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"), algorithm.name)
            assertTrue(text.endsWith("-----END OPENSSH PRIVATE KEY-----\n"), algorithm.name)
            assertTrue(text.lines().all { it.length <= 70 }, "lines are wrapped at 70 characters")
            assertFalse(SshKeys.isEncrypted(text))
            val reloaded = SshKeys.load(text)
            assertSameKey(key, reloaded, algorithm.name)
        }
    }

    @Test
    fun encryptedOpenSshFormatRoundTrip() {
        for (algorithm in fastAlgorithms) {
            val key = SshKeys.generate(algorithm)
            val text = SshKeys.toOpenSshPrivateKey(key, "c", "correct horse".toCharArray())
            assertTrue(SshKeys.isEncrypted(text), algorithm.name)
            assertFailsWith<PassphraseRequiredException> { SshKeys.load(text) }
            assertFailsWith<WrongPassphraseException> { SshKeys.load(text, "wrong".toCharArray()) }
            val reloaded = SshKeys.load(text, "correct horse".toCharArray())
            assertSameKey(key, reloaded, algorithm.name)
        }
    }

    @Test
    fun passphraseIsNotWipedByLoading() {
        val key = SshKeys.generate(KeyAlgorithm.ED25519)
        val passphrase = "secret".toCharArray()
        val text = SshKeys.toOpenSshPrivateKey(key, "", passphrase)
        SshKeys.load(text, passphrase)
        assertEquals("secret", String(passphrase))
    }

    @Test
    fun twoGeneratedKeysDiffer() {
        assertNotEquals(SshKeys.generate(KeyAlgorithm.ED25519).publicKey, SshKeys.generate(KeyAlgorithm.ED25519).publicKey)
    }

    @Test
    fun garbageIsRejected() {
        assertFailsWith<UnsupportedKeyException> { SshKeys.load("this is not a key") }
        assertFailsWith<UnsupportedKeyException> { SshKeys.load("") }
        assertFailsWith<UnsupportedKeyException> {
            SshKeys.load("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n")
        }
    }

    @Test
    fun publicKeyFormatting() {
        val key = SshKeys.generate(KeyAlgorithm.ED25519).publicKey
        val line = key.toAuthorizedKey("me@phone")
        val parts = line.split(' ')
        assertEquals("ssh-ed25519", parts[0])
        assertEquals("me@phone", parts[2])
        // An Ed25519 public key blob is always 51 bytes, i.e. 68 base64 characters.
        assertEquals(68, parts[1].length)
        assertEquals("${key.type} ${key.base64}", key.toAuthorizedKey())
        assertTrue(Regex("SHA256:[A-Za-z0-9+/]{43}").matches(key.fingerprint), key.fingerprint)
        assertTrue(Regex("([0-9a-f]{2}:){15}[0-9a-f]{2}").matches(key.md5Fingerprint), key.md5Fingerprint)
    }

    @Test
    fun parsingAuthorizedKeyLines() {
        val key = SshKeys.generate(KeyAlgorithm.ECDSA_256).publicKey
        assertEquals(key, SshPublicKey.parse(key.toAuthorizedKey("a comment with spaces")))
        assertEquals(key, SshPublicKey.parse("  " + key.toAuthorizedKey() + "  \n"))
        assertNull(SshPublicKey.parse("ssh-ed25519"))
        assertNull(SshPublicKey.parse("ssh-ed25519 not-base64!"))
        // The type named on the line has to match the type inside the key.
        assertNull(SshPublicKey.parse("ssh-rsa ${key.base64}"))
    }

    /** A fixed key from OpenSSH's own documentation style examples would need network access, so use known answers. */
    @Test
    fun fingerprintMatchesKnownAnswer() {
        // Public key and fingerprint produced by: ssh-keygen -t ed25519 -N '' && ssh-keygen -lf key.pub
        val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGXLQfyH6YrCXHL1wd5HkbGnpEqBJB6X6DlmDqNcYQ3r test@example"
        val key = assertNotNull(SshPublicKey.parse(line))
        assertEquals("ssh-ed25519", key.type)
        assertEquals(51, key.blob.size)
        // SHA-256 over the decoded blob, independently computed in the test.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(line.split(' ')[1]))
        assertEquals("SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest), key.fingerprint)
    }

    @Test
    fun hostKeyClassification() {
        val a = SshKeys.generate(KeyAlgorithm.ED25519).publicKey
        val b = SshKeys.generate(KeyAlgorithm.ED25519).publicKey
        val c = SshKeys.generate(KeyAlgorithm.ECDSA_256).publicKey
        assertEquals(HostKeyStatus.UNKNOWN, classifyHostKey(a, emptyList()))
        assertEquals(HostKeyStatus.TRUSTED, classifyHostKey(a, listOf(c, a)))
        assertEquals(HostKeyStatus.CHANGED, classifyHostKey(a, listOf(b)))
        assertEquals(HostKeyStatus.CHANGED, classifyHostKey(a, listOf(c, b)))
        assertEquals(HostKeyStatus.NEW_KEY_TYPE, classifyHostKey(a, listOf(c)))
    }

    @Test
    fun inMemoryStoreReplacesKeysOfTheSameType() {
        val store = InMemoryHostKeyStore()
        val a = SshKeys.generate(KeyAlgorithm.ED25519).publicKey
        val b = SshKeys.generate(KeyAlgorithm.ED25519).publicKey
        val c = SshKeys.generate(KeyAlgorithm.ECDSA_256).publicKey
        store.remember("h", 22, a)
        store.remember("h", 22, c)
        store.remember("h", 22, b)
        assertEquals(setOf(b, c), store.find("h", 22).toSet())
        assertTrue(store.find("h", 2222).isEmpty())
        assertTrue(store.find("other", 22).isEmpty())
    }
}
