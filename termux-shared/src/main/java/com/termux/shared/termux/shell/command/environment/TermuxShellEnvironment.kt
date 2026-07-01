package com.termux.shared.termux.shell.command.environment

import android.content.Context
import com.termux.shared.errors.Error
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.shell.command.environment.AndroidShellEnvironment
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils
import com.termux.shared.termux.TermuxBootstrap
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.shell.TermuxShellUtils
import java.io.File
import java.nio.charset.Charset
import java.util.HashMap

/**
 * Environment for Termux.
 */
open class TermuxShellEnvironment : AndroidShellEnvironment() {

    init {
        shellCommandShellEnvironment = TermuxShellCommandShellEnvironment()
    }

    /** Get shell environment for Termux. */
    override fun getEnvironment(currentPackageContext: Context, isFailSafe: Boolean): HashMap<String, String> {
        // Termux environment builds upon the Android environment
        val environment = super.getEnvironment(currentPackageContext, isFailSafe)

        val termuxAppEnvironment = TermuxAppShellEnvironment.getEnvironment(currentPackageContext)
        if (termuxAppEnvironment != null) {
            environment.putAll(termuxAppEnvironment)
        }

        val termuxApiAppEnvironment = TermuxAPIShellEnvironment.getEnvironment(currentPackageContext)
        if (termuxApiAppEnvironment != null) {
            environment.putAll(termuxApiAppEnvironment)
        }

        environment[ENV_HOME] = TermuxConstants.TERMUX_HOME_DIR_PATH
        environment[ENV_PREFIX] = TermuxConstants.TERMUX_PREFIX_DIR_PATH

        // If failsafe is not enabled, then we keep default PATH and TMPDIR so that system binaries can be used
        if (!isFailSafe) {
            environment[ENV_TMPDIR] = TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH
            if (TermuxBootstrap.isAppPackageVariantAPTAndroid5()) {
                environment[ENV_PATH] = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets" + ":/system/bin"
                environment[ENV_LD_LIBRARY_PATH] = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH
            } else {
                environment[ENV_PATH] = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin"
                environment.remove(ENV_LD_LIBRARY_PATH)
            }
            installTapiFromStaging(currentPackageContext)
        }

        return environment
    }

    override fun getDefaultWorkingDirectoryPath(): String {
        return TermuxConstants.TERMUX_HOME_DIR_PATH
    }

    override fun getDefaultBinPath(): String {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
    }

    override fun setupShellCommandArguments(fileToExecute: String, arguments: Array<String>?): Array<String> {
        return TermuxShellUtils.setupShellCommandArguments(fileToExecute, arguments)
    }

    companion object {
        private const val LOG_TAG = "TermuxShellEnvironment"
        const val TAPI_STAGING_BIN_DIR_PATH = "/data/local/tmp/tapi_staging/bin"
        const val TAPI_SCRIPT_NAME = "tapi"
        const val TAPI_VERSION_NAME = "version"

        fun installTapiFromStaging(currentPackageContext: Context) {
            android.util.Log.e("TAPI_DEBUG", "installTapiFromStaging called")
            try {
                val stagingScript = File(TAPI_STAGING_BIN_DIR_PATH, TAPI_SCRIPT_NAME)
                android.util.Log.e("TAPI_DEBUG", "staging exists=" + stagingScript.exists())
                if (!stagingScript.exists()) return

                val destDir = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH)
                val destScript = File(destDir, TAPI_SCRIPT_NAME)
                destDir.mkdirs()
                destScript.delete()
                stagingScript.copyTo(destScript)
                destScript.setExecutable(true)
                android.util.Log.e("TAPI_DEBUG", "script copied, size=" + destScript.length())

                val tapiDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".tapi")
                val destDex = File(tapiDir, "rish_shizuku.dex")
                try {
                    File(tapiDir, "tapi.dex").delete()
                } catch (_: Throwable) {}

                var nightzukuApkFile: File? = null
                try {
                    val pm = currentPackageContext.packageManager
                    val appInfo = pm.getApplicationInfo("kerneldroid.nightzuku", 0)
                    nightzukuApkFile = File(appInfo.sourceDir)
                } catch (_: Throwable) {}

                val needExtract = !destDex.exists() || 
                    (nightzukuApkFile != null && nightzukuApkFile.exists() && nightzukuApkFile.lastModified() > destDex.lastModified())

                if (needExtract) {
                    tapiDir.mkdirs()
                    var copied = false

                    // 1. Try copying staging dex first
                    val stagingDex = File("/data/local/tmp/tapi_staging/tapi.dex")
                    if (stagingDex.exists() && stagingDex.length() > 30000) {
                        try {
                            destDex.delete()
                            stagingDex.copyTo(destDex)
                            destDex.setLastModified(stagingDex.lastModified())
                            android.util.Log.e("TAPI_DEBUG", "dex copied from staging, size=" + destDex.length())
                            copied = true
                        } catch (e: Throwable) {
                            android.util.Log.e("TAPI_DEBUG", "failed to copy from staging: ${e.message}")
                        }
                    }

                    // 2. Fall back to extracting directly from Nightzuku APK
                    if (!copied && nightzukuApkFile != null && nightzukuApkFile.exists()) {
                        try {
                            destDex.delete()
                            java.util.zip.ZipFile(nightzukuApkFile).use { zip ->
                                val entry = zip.getEntry("assets/rish_shizuku.dex")
                                if (entry != null) {
                                    zip.getInputStream(entry).use { input ->
                                        destDex.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    destDex.setLastModified(nightzukuApkFile.lastModified())
                                    android.util.Log.e("TAPI_DEBUG", "dex extracted from APK, size=" + destDex.length())
                                } else {
                                    android.util.Log.e("TAPI_DEBUG", "rish_shizuku.dex not found in APK assets")
                                }
                            }
                        } catch (e: Throwable) {
                            android.util.Log.e("TAPI_DEBUG", "failed to extract dex from APK: ${e.message}")
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("TAPI_DEBUG", "FAILED: ${e.message}")
                Logger.logErrorExtended(LOG_TAG, "Failed to install TAPI from staging: ${e.message}")
            }
        }

        /** Environment variable for the termux [TermuxConstants.TERMUX_PREFIX_DIR_PATH]. */
        const val ENV_PREFIX = "PREFIX"

        /** Init [TermuxShellEnvironment] constants and caches. */
        @JvmStatic
        @Synchronized
        fun init(currentPackageContext: Context) {
            TermuxAppShellEnvironment.setTermuxAppEnvironment(currentPackageContext)
        }

        /** Init [TermuxShellEnvironment] constants and caches. */
        @JvmStatic
        @Synchronized
        fun writeEnvironmentToFile(currentPackageContext: Context) {
            val environmentMap = TermuxShellEnvironment().getEnvironment(currentPackageContext, false)
            val environmentString = ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(environmentMap)

            // Write environment string to temp file and then move to final location since otherwise
            // writing may happen while file is being sourced/read
            var error = FileUtils.writeTextToFile(
                "termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH,
                Charset.defaultCharset(), environmentString, false
            )
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, error.toString())
                return
            }

            error = FileUtils.moveRegularFile(
                "termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH,
                TermuxConstants.TERMUX_ENV_FILE_PATH, true
            )
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, error.toString())
            }
        }
    }
}
