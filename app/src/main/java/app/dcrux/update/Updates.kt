package app.dcrux.update

import kotlinx.coroutines.flow.StateFlow

data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    /** Android has not yet been told that DCrux may install its own updates. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    /** The system's own "Update this app?" prompt is showing (or about to). */
    data class Confirming(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Over-the-air updates from the project's GitHub releases.
 *
 * The newest release's APK is streamed straight into Android's package installer: the app
 * never writes an APK file of its own, so there is nothing to clean up afterwards, and the
 * system replaces the old build with the new one. Android only accepts the update when it is
 * signed with the same key as the installed app, so a tampered download cannot be installed.
 */

/** Finding and installing newer versions of the app. How a new version is installed differs by platform. */
interface Updates {
    val state: StateFlow<UpdateState>
    suspend fun check(force: Boolean = false)
    fun install(info: UpdateInfo)
    fun onAppForeground()
    fun cleanUp()

    companion object {
        /** True when version [candidate] is newer than [current] ("1.10.0" beats "1.9.3"). */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.trim().split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
