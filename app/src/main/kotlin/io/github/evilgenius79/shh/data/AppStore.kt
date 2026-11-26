package io.github.evilgenius79.shh.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The app's persistent state: one JSON file, held in memory as an immutable [AppData] and
 * rewritten atomically after every change. The amount of data (a few dozen hosts and keys) makes
 * a database unnecessary, and a single document makes backup and restore trivial.
 */
class AppStore(
    private val file: File,
    /** Where the file is written. Tests pass a direct executor to make saving synchronous. */
    private val writer: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "shh-store").apply { isDaemon = true }
    },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val state = MutableStateFlow(load())

    /** The current data; collect it to follow changes. */
    val data: StateFlow<AppData> = state.asStateFlow()

    /** Set when the stored file could not be read; the damaged file is kept next to it. */
    @Volatile
    var loadError: String? = null
        private set

    private fun load(): AppData {
        if (!file.exists()) return AppData()
        return try {
            json.decodeFromString(AppData.serializer(), file.readText())
        } catch (e: Exception) {
            // Never overwrite something we could not understand: move it aside for recovery.
            val backup = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
            file.renameTo(backup)
            loadError = "Saved data could not be read (${e.message}). It was moved to ${backup.name}."
            AppData()
        }
    }

    /** Applies [transform] to the current data and saves the result. */
    fun update(transform: (AppData) -> AppData): AppData {
        val updated: AppData
        synchronized(this) {
            updated = transform(state.value)
            if (updated == state.value) return updated
            state.value = updated
        }
        writer.execute { persist() }
        return updated
    }

    /** Replaces everything, used when restoring a backup. */
    fun replaceAll(data: AppData) {
        update { data }
    }

    private fun persist() {
        // Always writes the latest state, so queued writes that were overtaken are harmless.
        val snapshot = state.value
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeText(json.encodeToString(AppData.serializer(), snapshot))
            if (!temp.renameTo(file)) {
                // renameTo does not replace an existing file on every platform.
                file.delete()
                if (!temp.renameTo(file)) throw IOException("Could not replace ${file.name}")
            }
        } catch (e: IOException) {
            temp.delete()
            loadError = "Saving failed: ${e.message}"
        }
    }

    /** The whole store as JSON text, for backups. Secrets inside stay encrypted. */
    fun exportJson(): String = json.encodeToString(AppData.serializer(), state.value)

    /** Parses JSON produced by [exportJson]. */
    fun parse(text: String): AppData = json.decodeFromString(AppData.serializer(), text)

    // ----- convenience mutations -----

    fun saveHost(host: Host) = update { data ->
        val exists = data.hosts.any { it.id == host.id }
        data.copy(hosts = if (exists) data.hosts.map { if (it.id == host.id) host else it } else data.hosts + host)
    }

    fun deleteHost(id: String) = update { data ->
        data.copy(
            // Hosts that jumped through the deleted one fall back to a direct connection.
            hosts = data.hosts.filter { it.id != id }.map { if (it.jumpHostId == id) it.copy(jumpHostId = null) else it },
        )
    }

    fun saveIdentity(identity: Identity) = update { data ->
        val exists = data.identities.any { it.id == identity.id }
        data.copy(
            identities = if (exists) data.identities.map { if (it.id == identity.id) identity else it } else data.identities + identity,
        )
    }

    fun deleteIdentity(id: String) = update { data ->
        data.copy(
            identities = data.identities.filter { it.id != id },
            hosts = data.hosts.map { if (it.identityId == id) it.copy(identityId = null) else it },
        )
    }

    fun saveSnippet(snippet: Snippet) = update { data ->
        val exists = data.snippets.any { it.id == snippet.id }
        data.copy(snippets = if (exists) data.snippets.map { if (it.id == snippet.id) snippet else it } else data.snippets + snippet)
    }

    fun deleteSnippet(id: String) = update { data -> data.copy(snippets = data.snippets.filter { it.id != id }) }

    fun rememberHostKey(entry: KnownHost) = update { data ->
        val others = data.knownHosts.filterNot { it.host == entry.host && it.port == entry.port && it.keyType == entry.keyType }
        data.copy(knownHosts = others + entry)
    }

    fun forgetHostKey(entry: KnownHost) = update { data -> data.copy(knownHosts = data.knownHosts - entry) }

    fun updateSettings(transform: (Settings) -> Settings) = update { it.copy(settings = transform(it.settings)) }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
