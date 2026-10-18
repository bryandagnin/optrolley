package dev.optrolley.autoroute

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * On a device an exception escaping the view model's work kills the app;
 * under Robolectric it is only logged. This makes it fail the test instead,
 * as the cause, rather than as whatever assertion it made fail later.
 * Outermost, so it sees what the other rules report.
 */
class RethrowUncaught : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val caught = mutableListOf<Throwable>()
            val thread = Thread.currentThread()
            val before = thread.uncaughtExceptionHandler
            val beforeDefault = Thread.getDefaultUncaughtExceptionHandler()
            val handler = Thread.UncaughtExceptionHandler { _, e -> synchronized(caught) { caught += e } }
            thread.uncaughtExceptionHandler = handler
            Thread.setDefaultUncaughtExceptionHandler(handler)
            var failure: Throwable? = null
            try {
                base.evaluate()
            } catch (e: Throwable) {
                failure = e
            } finally {
                thread.uncaughtExceptionHandler = before
                Thread.setDefaultUncaughtExceptionHandler(beforeDefault)
            }
            // The Compose test rule reports what it catches as suppressed by
            // the next failure; bring it to the front the same way.
            val uncaught = synchronized(caught) { caught.firstOrNull() }
            if (uncaught != null) {
                failure?.let { uncaught.addSuppressed(it) }
                throw uncaught
            }
            failure?.suppressed?.firstOrNull { it !is AssertionError }?.let { throw it }
            failure?.let { throw it }
        }
    }
}
