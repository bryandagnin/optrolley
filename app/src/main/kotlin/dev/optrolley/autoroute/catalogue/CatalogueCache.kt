package dev.optrolley.autoroute.catalogue

import dev.optrolley.core.Barcode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** What the catalogue said of a code, and when. */
@Serializable
sealed interface Known {
    val at: Long

    @Serializable
    @SerialName("found")
    data class Found(val name: String?, val brand: String?, val picture: String?, override val at: Long) : Known

    /** It answered and did not know the code. */
    @Serializable
    @SerialName("none")
    data class NotKnown(override val at: Long) : Known
}

/**
 * The catalogue's answers and pictures, and whether the user has turned
 * looking codes up on (tier 4 §7.3). All of it is in the app's cache
 * directory, apart from state, the record and the export: Android or the
 * user may clear it, and that loses nothing but what can be fetched again
 * (O47). The option lives here too, so it is never exported and a fresh
 * install starts with it off; if the cache is cleared it is off again,
 * which errs towards sending nothing.
 *
 * With no [dir] it is kept in memory only. Not thread-safe; [Lookups]
 * calls it from one coroutine at a time.
 */
class CatalogueCache(private val dir: File?) {
    private val answersFile = dir?.let { File(it, ANSWERS) }
    private val enabledFile = dir?.let { File(it, ENABLED) }
    private val picturesDir = dir?.let { File(it, PICTURES) }

    private var answers: MutableMap<String, Known>? = null
    private var enabledInMemory = false

    var enabled: Boolean
        get() = enabledFile?.isFile ?: enabledInMemory
        set(value) {
            enabledInMemory = value
            val file = enabledFile ?: return
            try {
                if (value) {
                    file.parentFile?.mkdirs()
                    file.writeText("on\n")
                } else {
                    file.delete()
                }
            } catch (e: IOException) {
                // Unwritten, it is off again next time: the safe way to fail.
            }
        }

    fun answers(): Map<Barcode, Known> = loaded().mapKeys { Barcode(it.key) }

    fun remember(code: Barcode, known: Known) {
        loaded()[code.value] = known
        val file = answersFile ?: return
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "$ANSWERS.new")
            temp.writeText(json.encodeToString(Answers(loaded())))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: IOException) {
            // A cache that cannot be written is only fetched again.
        }
    }

    /** Where [code]'s picture is kept, if it is. */
    fun picture(code: Barcode): File? = picturesDir?.let { File(it, code.value) }?.takeIf { it.isFile }

    fun keepPicture(code: Barcode, bytes: ByteArray) {
        val folder = picturesDir ?: return
        try {
            folder.mkdirs()
            File(folder, code.value).writeBytes(bytes)
        } catch (e: IOException) {
            // As above.
        }
    }

    /** Forgets every answer and picture; the option is the user's to change, not this. */
    fun clear() {
        answers = mutableMapOf()
        answersFile?.delete()
        picturesDir?.deleteRecursively()
    }

    private fun loaded(): MutableMap<String, Known> = answers ?: read().toMutableMap().also { answers = it }

    private fun read(): Map<String, Known> {
        val file = answersFile?.takeIf { it.isFile } ?: return emptyMap()
        return try {
            json.decodeFromString<Answers>(file.readText()).answers
        } catch (e: IOException) {
            emptyMap()
        } catch (e: SerializationException) {
            emptyMap()
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    @Serializable
    private class Answers(val answers: Map<String, Known>)

    companion object {
        private const val ANSWERS = "answers.json"
        private const val ENABLED = "look-ups-on"
        private const val PICTURES = "pictures"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
