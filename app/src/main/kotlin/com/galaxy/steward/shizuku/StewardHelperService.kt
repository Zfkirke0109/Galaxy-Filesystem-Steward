package com.galaxy.steward.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import com.galaxy.steward.BuildConfig
import com.galaxy.steward.core.appdata.AppDataHelper
import com.galaxy.steward.core.appdata.AppDataWire
import com.galaxy.steward.core.appdata.AppPolicy
import com.galaxy.steward.diagnostics.LogcatDump
import kotlinx.coroutines.runBlocking
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The privileged half of the steward. Shizuku starts it in a separate process running as Android's shell user
 * (uid 2000), from this APK. It only exposes the fixed operations in [IStewardHelper]: the app-data scanner and
 * executor from `core` (with all their run-time checks), a read-only folder listing for the app folder browser, a
 * cache-only clear or a full data clear for one validated package name, granting the app usage access, and a fixed
 * dump of the device log. There is no generic command runner.
 */
@Keep
class StewardHelperService : IStewardHelper.Stub {
    constructor() : super()

    @Suppress("unused")
    constructor(context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun uid(): Int = Process.myUid()

    override fun scanAppData(request: ParcelFileDescriptor): ParcelFileDescriptor {
        val text = readAll(request)
        return stream { out -> AppDataHelper.Helper.scan(text, out) }
    }

    override fun listAppFolder(request: ParcelFileDescriptor): ParcelFileDescriptor {
        val text = readAll(request)
        return stream { out -> AppDataHelper.Helper.list(text, out) }
    }

    override fun applyAppData(request: ParcelFileDescriptor): ParcelFileDescriptor {
        val text = readAll(request)
        return stream { out -> runBlocking { AppDataHelper.Helper.apply(text, out) } }
    }

    override fun rollbackAppData(rootPath: String, entries: ParcelFileDescriptor): ParcelFileDescriptor {
        val text = readAll(entries)
        return stream { out -> runBlocking { AppDataHelper.Helper.rollback(rootPath, text, out) } }
    }

    override fun clearAppCache(packageName: String, userId: Int, forceStop: Boolean, timeoutMs: Long): String {
        if (!AppPolicy.isPackageName(packageName) || userId < 0) return "error=invalid package"
        if (packageName in AppPolicy.STRICT_NO_TOUCH) return "error=protected app"
        val user = userId.toString()
        if (forceStop && AppPolicy.mayForceStop(packageName)) {
            exec(listOf("/system/bin/am", "force-stop", "--user", user, packageName), 10_000)
        }
        // On Samsung Android 16 this call clears the cache but its completion callback can hang (observed by the
        // Termux steward), so success is judged by the app from live storage stats, never from the exit code.
        val code = exec(listOf("/system/bin/cmd", "package", "clear", "--user", user, "--cache-only", packageName), timeoutMs.coerceIn(3_000, 60_000))
        return "exit=$code"
    }

    override fun clearAppData(packageName: String, userId: Int, timeoutMs: Long): String {
        if (!AppPolicy.isPackageName(packageName) || userId < 0) return "error=invalid package"
        AppPolicy.clearDataBlock(packageName, BuildConfig.APPLICATION_ID)?.let { return "error=$it" }
        val user = userId.toString()
        // The name rules can't see every preinstalled app; ask the package manager, and refuse when it can't say.
        val (listed, system) = capture(listOf("/system/bin/cmd", "package", "list", "packages", "-s", "--user", user, packageName), 15_000)
        if (listed != 0) return "error=could not check the package"
        if (system.lineSequence().any { it.trim() == "package:$packageName" }) return "error=System app"
        return "exit=${exec(listOf("/system/bin/pm", "clear", "--user", user, packageName), timeoutMs.coerceIn(5_000, 120_000))}"
    }

    override fun grantUsageAccess(packageName: String): Boolean {
        if (!AppPolicy.isPackageName(packageName)) return false
        return exec(listOf("/system/bin/appops", "set", packageName, "GET_USAGE_STATS", "allow"), 10_000) == 0
    }

    override fun dumpLogcat(): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                out.write("--------- Galaxy Steward helper: uid ${Process.myUid()}, pid ${Process.myPid()}\n".toByteArray())
                var logcat: java.lang.Process? = null
                try {
                    logcat = ProcessBuilder(LogcatDump.COMMAND).redirectErrorStream(true).start()
                    logcat.inputStream.use { it.copyTo(out, 64 * 1024) }
                    logcat.waitFor()
                } catch (e: Exception) {
                    // The app stopped reading, or logcat could not start: say why if the pipe is still open.
                    runCatching { out.write("--------- logcat failed: ${e.message ?: e.javaClass.simpleName}\n".toByteArray()) }
                } finally {
                    logcat?.destroy()
                }
            }
        }.apply { name = "steward-logcat" }.start()
        return read
    }

    /** Runs a fixed command without a shell. Returns the exit code, 124 on timeout (like `timeout`), -1 on error. */
    private fun exec(command: List<String>, timeoutMs: Long): Int = try {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        Thread { runCatching { p.inputStream.readBytes() } }.start()
        if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.exitValue()
        } else {
            p.destroy()
            124
        }
    } catch (_: Exception) {
        -1
    }

    /** Like [exec], and also returns what the command printed (at most 1 MiB). */
    private fun capture(command: List<String>, timeoutMs: Long): Pair<Int, String> = try {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> synchronized(output) { if (output.length < 1 shl 20) output.appendLine(line) } }
                }
            }
        }.apply { start() }
        if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            reader.join(2_000)
            p.exitValue() to synchronized(output) { output.toString() }
        } else {
            p.destroy()
            124 to ""
        }
    } catch (_: Exception) {
        -1 to ""
    }

    private fun readAll(fd: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }

    /** Streams lines produced by [producer] on a worker thread through a pipe the app reads as they arrive. */
    private fun stream(producer: ((String) -> Unit) -> Unit): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread {
            BufferedWriter(OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(write), Charsets.UTF_8)).use { out ->
                try {
                    producer { line ->
                        out.write(line)
                        out.write("\n")
                        if (line.startsWith("J\t") || line.startsWith("P\t")) out.flush()
                    }
                } catch (e: Exception) {
                    out.write(AppDataWire.error(e.message ?: e.javaClass.simpleName))
                    out.write("\n")
                    out.write(AppDataWire.END)
                    out.write("\n")
                }
            }
        }.apply { name = "steward-helper" }.start()
        return read
    }
}
