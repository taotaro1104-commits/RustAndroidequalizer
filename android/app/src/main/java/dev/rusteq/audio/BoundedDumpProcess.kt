package dev.rusteq.audio

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** No shell interpolation or privileged identity: the child retains this app's UID. */
internal object BoundedDumpProcess {
    private class OutputLimit : IOException()

    @Throws(InterruptedException::class)
    fun read(command: List<String>, timeoutMs: Long = 2500, maxBytes: Int = 1024 * 1024): ProcessDumpResult {
        require(timeoutMs > 0 && maxBytes > 0)
        val process = try { ProcessBuilder(command).redirectErrorStream(true).start() }
        catch (_: IOException) { return ProcessDumpResult.Failure(DiscoveryFailure.PROCESS) }
        val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "EQ-dump-reader").apply { isDaemon = true } }
        val started = System.nanoTime()
        val result = reader.submit<String> {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > maxBytes) throw OutputLimit()
                bytes.write(buffer, 0, count)
            }
            bytes.toString("UTF-8")
        }
        try {
            val output = result.get(timeoutMs, TimeUnit.MILLISECONDS)
            val remaining = (timeoutMs - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
            if (!process.waitFor(remaining, TimeUnit.MILLISECONDS)) return ProcessDumpResult.Failure(DiscoveryFailure.TIMEOUT)
            if (output.contains("Permission Denial", ignoreCase = true) || output.contains("Permission denied", ignoreCase = true)) {
                return ProcessDumpResult.Failure(DiscoveryFailure.PERMISSION)
            }
            return if (process.exitValue() == 0) ProcessDumpResult.Success(output) else ProcessDumpResult.Failure(DiscoveryFailure.PROCESS)
        } catch (_: TimeoutException) {
            return ProcessDumpResult.Failure(DiscoveryFailure.TIMEOUT)
        } catch (e: ExecutionException) {
            return ProcessDumpResult.Failure(if (e.cause is OutputLimit) DiscoveryFailure.OUTPUT_LIMIT else DiscoveryFailure.PROCESS)
        } finally {
            // Kill before closing the pipe: a reader may hold its stream lock.
            process.destroyForcibly()
            result.cancel(true)
            reader.shutdownNow()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}
