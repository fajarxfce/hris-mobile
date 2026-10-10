package dev.fajar.starter.identity.data.sso.datasources

import java.awt.Desktop
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runInterruptible

/** Raw browser launch; interrupting authorization also interrupts the fallback process wait. */
internal suspend fun openDesktopAuthorizationBrowser(uri: URI) = runInterruptible {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    if (desktop?.isSupported(Desktop.Action.BROWSE) == true) desktop.browse(uri)
    else if (System.getProperty("os.name").lowercase().contains("linux")) {
        val process =
            ProcessBuilder("xdg-open", uri.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw UnsupportedOperationException("A browser could not be opened.")
        } finally {
            process.destroy()
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    } else throw UnsupportedOperationException("A browser is not available.")
}
