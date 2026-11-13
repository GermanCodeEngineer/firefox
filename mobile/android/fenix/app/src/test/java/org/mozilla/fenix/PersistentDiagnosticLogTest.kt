/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix

import android.content.Context
import android.os.StrictMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mozilla.components.support.base.log.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersistentDiagnosticLogTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val context: Context = mockk()
    private lateinit var directory: File
    private lateinit var originalPolicy: StrictMode.ThreadPolicy
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    private val file
        get() = File(directory, "fenix-diagnostic-log.txt")

    @Before
    fun setup() {
        directory = temporaryFolder.newFolder()
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        originalPolicy = StrictMode.getThreadPolicy()
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory
        PersistentDiagnosticLog.initialize(context)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        StrictMode.setThreadPolicy(originalPolicy)
        runBlocking { PersistentDiagnosticLog.read() }
    }

    @Test
    fun `initialization and warning logging do not access files on the calling thread`() = runTest {
        val caller = Thread.currentThread()
        val fileAccessThreads = CopyOnWriteArrayList<Thread>()
        every { context.filesDir } answers
            {
                fileAccessThreads.add(Thread.currentThread())
                directory
            }

        PersistentDiagnosticLog.initialize(context)
        log("first warning")
        log("second warning")
        val saved = PersistentDiagnosticLog.read()

        assertTrue(saved.contains("first warning"))
        assertTrue(saved.indexOf("first warning") < saved.indexOf("second warning"))
        assertFalse(fileAccessThreads.isEmpty())
        fileAccessThreads.forEach { assertNotSame(caller, it) }
        assertEquals(1, fileAccessThreads.distinct().size)
    }

    @Test
    fun `clear runs after pending writes and before subsequent writes`() = runTest {
        log("before clear")

        PersistentDiagnosticLog.clear()
        assertEquals("", PersistentDiagnosticLog.read())
        log("after clear")
        val saved = PersistentDiagnosticLog.read()

        assertFalse(saved.contains("before clear"))
        assertTrue(saved.contains("after clear"))
    }

    @Test
    fun `messages below warning level are not saved`() = runTest {
        PersistentDiagnosticLog.sink.log(Log.Priority.INFO, "test", null, "information")

        assertEquals("", PersistentDiagnosticLog.read())
        assertFalse(file.exists())
    }

    @Test
    fun `a worker file error is caught and later messages are still saved`() = runTest {
        every { context.filesDir } returns temporaryFolder.newFile() andThen directory

        log("failed warning")
        log("next warning")
        val saved = PersistentDiagnosticLog.read()

        assertFalse(saved.contains("failed warning"))
        assertTrue(saved.contains("next warning"))
    }

    @Test
    fun `file errors when reading and clearing do not escape the worker`() = runTest {
        every { context.filesDir } throws IOException("cannot access log")

        assertEquals("", PersistentDiagnosticLog.read())
        PersistentDiagnosticLog.clear()

        every { context.filesDir } returns directory
        log("worker still available")
        assertTrue(PersistentDiagnosticLog.read().contains("worker still available"))
    }

    @Test
    fun `a stack trace formatting failure does not escape the log sink`() = runTest {
        val failure = unprintableFailure()

        PersistentDiagnosticLog.sink.log(Log.Priority.ERROR, "test", failure, "failed to format")
        log("next warning")

        assertTrue(PersistentDiagnosticLog.read().contains("next warning"))
    }

    @Test
    fun `rotation bounds the log and preserves the most recent message`() = runTest {
        repeat(20) { index -> log("entry-$index ${"x".repeat(40 * 1024)} end-$index") }

        val saved = PersistentDiagnosticLog.read()

        assertTrue(file.length() <= 512 * 1024)
        assertFalse(saved.contains("entry-0 "))
        assertTrue(saved.contains("end-19"))
    }

    @Test
    fun `fatal error is saved synchronously before delegating and the policy is restored`() {
        val previous = mockk<Thread.UncaughtExceptionHandler>()
        val failure = IllegalStateException("original failure")
        val thread = Thread.currentThread()
        val policy = strictDiskPolicy()
        var writePolicy: String? = null
        every { context.filesDir } answers
            {
                writePolicy = StrictMode.getThreadPolicy().toString()
                directory
            }
        every { previous.uncaughtException(thread, failure) } answers
            {
                assertTrue(file.readText().contains("FATAL uncaught exception"))
                assertTrue(file.readText().contains("original failure"))
                assertEquals(policy.toString(), StrictMode.getThreadPolicy().toString())
            }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        PersistentDiagnosticLog.installUncaughtExceptionRecorder()

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(thread, failure)

        assertEquals(
            StrictMode.ThreadPolicy.Builder(policy).permitDiskReads().permitDiskWrites().build().toString(),
            writePolicy,
        )
        verify(exactly = 1) { previous.uncaughtException(thread, failure) }
    }

    @Test
    fun `fatal file failure still restores the policy and delegates the original exception`() {
        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        val failure = IllegalStateException("original failure")
        val thread = Thread.currentThread()
        val policy = strictDiskPolicy()
        every { context.filesDir } throws IOException("cannot save fatal error")
        Thread.setDefaultUncaughtExceptionHandler(previous)
        PersistentDiagnosticLog.installUncaughtExceptionRecorder()

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(thread, failure)

        assertEquals(policy.toString(), StrictMode.getThreadPolicy().toString())
        verify(exactly = 1) { previous.uncaughtException(thread, failure) }
    }

    @Test
    fun `fatal formatting failure still restores the policy and delegates the original exception`() {
        val previous = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        val failure = unprintableFailure()
        val thread = Thread.currentThread()
        val policy = strictDiskPolicy()
        Thread.setDefaultUncaughtExceptionHandler(previous)
        PersistentDiagnosticLog.installUncaughtExceptionRecorder()

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(thread, failure)

        assertEquals(policy.toString(), StrictMode.getThreadPolicy().toString())
        verify(exactly = 1) { previous.uncaughtException(thread, failure) }
    }

    @Test
    fun `installing the recorder twice does not wrap the crash handler again`() {
        val previous = mockk<Thread.UncaughtExceptionHandler>()
        Thread.setDefaultUncaughtExceptionHandler(previous)
        PersistentDiagnosticLog.installUncaughtExceptionRecorder()
        val recorder = Thread.getDefaultUncaughtExceptionHandler()

        PersistentDiagnosticLog.installUncaughtExceptionRecorder()

        assertNotSame(previous, recorder)
        assertSame(recorder, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `without an existing crash handler no recursive fallback is installed`() {
        Thread.setDefaultUncaughtExceptionHandler(null)

        PersistentDiagnosticLog.installUncaughtExceptionRecorder()

        assertNull(Thread.getDefaultUncaughtExceptionHandler())
    }

    private fun log(message: String) = PersistentDiagnosticLog.sink.log(Log.Priority.WARN, "test", null, message)

    private fun unprintableFailure() =
        object : Throwable("original failure") {
            override fun printStackTrace(writer: PrintWriter) {
                throw IllegalStateException("cannot format stack trace")
            }
        }

    private fun strictDiskPolicy(): StrictMode.ThreadPolicy {
        val policy = StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().build()
        StrictMode.setThreadPolicy(policy)
        assertFalse(policy.toString() == StrictMode.ThreadPolicy.LAX.toString())
        return policy
    }
}
