package dev.rusteq.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal sealed interface SelectedSessionSnapshot {
    data class Success(val sessions: Map<Int, String>) : SelectedSessionSnapshot
    data class Failure(val reason: DiscoveryFailure) : SelectedSessionSnapshot
}

/** ADB grants DUMP to this app; dumpsys runs as this app, never as shell/root. */
internal class AndroidSessionDiscovery(private val context: Context) {
    fun hasPermission(): Boolean = context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    suspend fun scan(selected: Set<String>): SelectedSessionSnapshot = runInterruptible(Dispatchers.IO) {
        if (!hasPermission()) return@runInterruptible SelectedSessionSnapshot.Failure(DiscoveryFailure.PERMISSION)
        val output = when (val result = BoundedDumpProcess.read(listOf("/system/bin/dumpsys", "-t", "2", "audio"))) {
            is ProcessDumpResult.Success -> result.output
            is ProcessDumpResult.Failure -> return@runInterruptible SelectedSessionSnapshot.Failure(result.reason)
        }
        val sessions = when (val result = AudioSessionDumpParser.parse(output)) {
            is SessionDumpResult.Success -> result.sessions
            is SessionDumpResult.Failure -> return@runInterruptible SelectedSessionSnapshot.Failure(result.reason)
        }
        val manager = context.packageManager
        val identities = selected.mapNotNull { name ->
            runCatching {
                val info = manager.getPackageInfo(name, 0)
                // getPackagesForUid is visibility-filtered. The manifest's shared
                // UID declaration catches ambiguity even when a sibling is hidden.
                if (info.sharedUserId != null) null else info.applicationInfo?.uid?.let { name to it }
            }.getOrNull()
        }.toMap()
        val owners = identities.values.distinct().associateWith { runCatching { manager.getPackagesForUid(it).orEmpty().toSet() }.getOrDefault(emptySet()) }
        SelectedSessionSnapshot.Success(SelectedAudioSessions.resolve(sessions, identities, owners, Process.myUid()).entries.take(8).associate { it.toPair() })
    }
}
