package tools.isekai.cameraclient

import android.app.Application
import android.system.Os
import android.util.Log
import java.io.File

private const val TAG = "IsekaiFFI"

/**
 * Sets up `SSL_CERT_FILE` and `XDG_CONFIG_HOME` before anything else in the
 * process runs.
 *
 * quictls was cross-compiled for Android (see the setup notes' task 2/4
 * write-ups) with an `--openssldir` baked in at compile time that points at
 * the *build machine's* path, which does not exist on Android -- so without
 * this, quictls has no root CAs to validate a server's certificate chain
 * against, and every real (non-`insecureSkipVerify`) TLS handshake fails.
 * OpenSSL/quictls reads `SSL_CERT_FILE` to override that compiled-in
 * default, so pointing it at a real CA bundle here is the fix, not a
 * workaround -- unlike `insecureSkipVerify`, which skips validation
 * entirely and must not ship.
 *
 * `XDG_CONFIG_HOME` fixes a second, unrelated gap found while synthetic
 * testing the new pairing/attestation flow (see the setup notes' 2026-08-15
 * update): `camera_core::privacy`/`camera_core::paired`'s shared
 * `config_dir()` falls into the generic Linux/XDG branch for Android (same
 * `target_os` mismatch as the `epoll_event` cfg-gate bug from task 1/2 --
 * Android runs Linux but reports `target_os = "android"`, not `"linux"`),
 * and neither `XDG_CONFIG_HOME` nor `HOME` is ever set in an Android app's
 * process. Without this, `camera_core::paired::remember()` fails silently
 * on every pairing (surfaced to the user as `Paired.notRemembered`), and
 * every later connection streams *unchecked* rather than *pinned* against
 * the Endpoint it was paired as -- the one thing #122-#126's whole
 * peer-attestation effort was for. Pointing `XDG_CONFIG_HOME` at internal
 * storage is the fix, not a workaround: it is exactly the directory
 * `config_dir()`'s own XDG branch is asking for, the same way iOS points
 * its `HOME`-based branch at `Library/Application Support`.
 */
class IsekaiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Os.setenv("SSL_CERT_FILE", caBundleFile().absolutePath, true)
        } catch (e: Exception) {
            Log.e(TAG, "failed to install CA bundle", e)
        }
        try {
            Os.setenv("XDG_CONFIG_HOME", filesDir.absolutePath, true)
        } catch (e: Exception) {
            Log.e(TAG, "failed to set XDG_CONFIG_HOME", e)
        }
    }

    /** The bundled CA cert, copied to internal storage once and reused after. */
    private fun caBundleFile(): File {
        val out = File(filesDir, "cacert.pem")
        if (!out.exists() || out.length() == 0L) {
            assets.open("cacert.pem").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out
    }
}
