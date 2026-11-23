package dev.optrolley.autoroute

import java.io.File
import java.io.IOException

/**
 * Options that belong to this phone, not to the list (F14): how it feels to
 * use, never what it shows or learns. Kept beside the state but not in it,
 * so they are never exported and a fresh install starts with each off.
 * Each is a file that exists while it is on.
 *
 * With no [dir] they are kept in memory only.
 */
class DeviceOptions(private val dir: File?) {
    private val inMemory = mutableSetOf<String>()

    fun isOn(name: String): Boolean = dir?.let { File(it, name).isFile } ?: (name in inMemory)

    fun set(name: String, on: Boolean) {
        if (on) inMemory += name else inMemory -= name
        val file = dir?.let { File(it, name) } ?: return
        try {
            if (on) {
                file.parentFile?.mkdirs()
                file.writeText("on\n")
            } else {
                file.delete()
            }
        } catch (e: IOException) {
            // Unwritten, it is off again next time; nothing depends on it.
        }
    }

    companion object {
        const val BUZZ_ON_ADD = "buzz-on-add"
        const val BUZZ_ON_TICK = "buzz-on-tick"
    }
}
