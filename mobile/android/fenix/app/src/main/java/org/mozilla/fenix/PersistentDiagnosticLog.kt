/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix

import android.content.Context
import android.os.StrictMode
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.sink.LogSink

internal object PersistentDiagnosticLog {
    private const val MAX_LOG_BYTES = 512 * 1024
    private const val MAX_ENTRY_CHARS = 64 * 1024
    private val lock = Any()
    private val dispatcher =
        Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "FenixDiagnosticLog").apply { isDaemon = true }
            }
            .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile private var applicationContext: Context? = null
    private var installedHandler: Thread.UncaughtExceptionHandler? = null

    val sink =
        object : LogSink {
            override fun log(priority: Log.Priority, tag: String?, throwable: Throwable?, message: String) {
                if (priority.value < Log.Priority.WARN.value) return

                ignoreFailure(Unit) {
                    val stack = throwable?.let(::stackTrace).orEmpty()
                    val entry =
                        "${Instant.now()} ${priority.name} ${tag.orEmpty()}: $message\n$stack".takeLast(MAX_ENTRY_CHARS)
                    scope.launch { append(entry) }
                    Unit
                }
            }
        }

    fun initialize(context: Context) {
        applicationContext = ignoreFailure(null) { context.applicationContext }
    }

    suspend fun read(): String =
        withContext(dispatcher) {
            ignoreFailure("") {
                synchronized(lock) {
                    logFile()?.takeIf { it.exists() }?.readText().orEmpty()
                }
            }
        }

    suspend fun clear() =
        withContext(dispatcher) {
            ignoreFailure(Unit) {
                synchronized(lock) { logFile()?.delete() }
                Unit
            }
        }

    // Crash recording must not initialize components or log through StrictModeManager.
    @Suppress("MozillaStrictModeSuppression")
    fun installUncaughtExceptionRecorder() {
        ignoreFailure(Unit) {
            synchronized(lock) {
                val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
                if (previous === installedHandler) return
                val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
                    try {
                        ignoreFailure(Unit) {
                            val policy = StrictMode.allowThreadDiskWrites()
                            try {
                                val entry =
                                    "${Instant.now()} FATAL uncaught exception on ${thread.name}\n" +
                                        stackTrace(throwable)
                                append(entry)
                            } finally {
                                StrictMode.setThreadPolicy(policy)
                            }
                        }
                    } finally {
                        previous.uncaughtException(thread, throwable)
                    }
                }
                Thread.setDefaultUncaughtExceptionHandler(handler)
                installedHandler = handler
            }
        }
    }

    private fun logFile() = applicationContext?.let { File(it.filesDir, "fenix-diagnostic-log.txt") }

    private fun stackTrace(throwable: Throwable) =
        StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()

    private fun append(entry: String) {
        ignoreFailure(Unit) {
            synchronized(lock) {
                val file = logFile() ?: return
                file.parentFile?.mkdirs()
                val bytes = (entry.takeLast(MAX_ENTRY_CHARS) + "\n").toByteArray(Charsets.UTF_8)
                file.appendBytes(bytes)
                if (file.length() > MAX_LOG_BYTES) {
                    val existing = file.readBytes()
                    val tail = existing.copyOfRange(existing.size - MAX_LOG_BYTES / 2, existing.size)
                    val firstNewline = tail.indexOf('\n'.code.toByte())
                    file.writeBytes(if (firstNewline >= 0) tail.copyOfRange(firstNewline + 1, tail.size) else tail)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> ignoreFailure(fallback: T, operation: () -> T): T =
        try {
            operation()
        } catch (_: Throwable) {
            fallback
        }
}
