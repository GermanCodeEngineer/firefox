/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix

import android.content.Context
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.sink.LogSink
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

internal object PersistentDiagnosticLog {
    private const val MAX_LOG_BYTES = 512 * 1024
    private const val MAX_ENTRY_CHARS = 64 * 1024
    private val lock = Any()
    private lateinit var file: File
    private var installedHandler: Thread.UncaughtExceptionHandler? = null

    val sink = object : LogSink {
        override fun log(priority: Log.Priority, tag: String?, throwable: Throwable?, message: String) {
            if (priority.value < Log.Priority.WARN.value) return

            val stack = throwable?.let { exception ->
                StringWriter().also { writer -> exception.printStackTrace(PrintWriter(writer)) }.toString()
            }.orEmpty()
            append("${Instant.now()} ${priority.name} ${tag.orEmpty()}: $message\n$stack")
        }
    }

    fun initialize(context: Context) {
        file = File(context.filesDir, "fenix-diagnostic-log.txt")
    }

    fun read(): String = synchronized(lock) {
        runCatching { if (::file.isInitialized && file.exists()) file.readText() else "" }.getOrDefault("")
    }

    fun clear() = synchronized(lock) {
        runCatching { if (::file.isInitialized) file.delete() }
    }

    fun installUncaughtExceptionRecorder() {
        synchronized(lock) {
            val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (installedHandler != null && currentHandler === installedHandler) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            installedHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
                val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
                append("${Instant.now()} FATAL uncaught exception on ${thread.name}\n$stack")
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    thread.threadGroup?.uncaughtException(thread, throwable)
                }
            }
            Thread.setDefaultUncaughtExceptionHandler(installedHandler)
        }
    }

    private fun append(entry: String) = synchronized(lock) {
        if (!::file.isInitialized) return@synchronized
        runCatching {
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
