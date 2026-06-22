package com.termux.shared.termux.file

import android.content.Context
import android.os.Environment
import com.termux.shared.android.AndroidUtils
import com.termux.shared.errors.Error
import com.termux.shared.file.FileUtils
import com.termux.shared.file.FileUtilsErrno
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.runner.app.AppShell
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import java.io.File
import java.util.regex.Pattern

object TermuxFileUtils {
    private const val LOG_TAG = "TermuxFileUtils"

    /**
     * Replace "$PREFIX/" or "~/" prefix with termux absolute paths.
     *
     * @param paths The `paths` to expand.
     * @return Returns the `expand paths`.
     */
    @JvmStatic
    fun getExpandedTermuxPaths(paths: List<String?>?): List<String?>? {
        if (paths == null) return null
        val expandedPaths = ArrayList<String?>()

        for (path in paths) {
            expandedPaths.add(getExpandedTermuxPath(path))
        }

        return expandedPaths
    }

    /**
     * Replace "$PREFIX/" or "~/" prefix with termux absolute paths.
     *
     * @param path The `path` to expand.
     * @return Returns the `expand path`.
     */
    @JvmStatic
    fun getExpandedTermuxPath(path: String?): String? {
        var expandedPath = path
        if (expandedPath != null && expandedPath.isNotEmpty()) {
            expandedPath = expandedPath.replace("^\\\$PREFIX\$".toRegex(), TermuxConstants.TERMUX_PREFIX_DIR_PATH)
            expandedPath = expandedPath.replace("^\\\$PREFIX/".toRegex(), TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/")
            expandedPath = expandedPath.replace("^~/\$".toRegex(), TermuxConstants.TERMUX_HOME_DIR_PATH)
            expandedPath = expandedPath.replace("^~/".toRegex(), TermuxConstants.TERMUX_HOME_DIR_PATH + "/")
        }
        return expandedPath
    }

    /**
     * Replace termux absolute paths with "$PREFIX/" or "~/" prefix.
     *
     * @param paths The `paths` to unexpand.
     * @return Returns the `unexpand paths`.
     */
    @JvmStatic
    fun getUnExpandedTermuxPaths(paths: List<String?>?): List<String?>? {
        if (paths == null) return null
        val unExpandedPaths = ArrayList<String?>()

        for (path in paths) {
            unExpandedPaths.add(getUnExpandedTermuxPath(path))
        }

        return unExpandedPaths
    }

    /**
     * Replace termux absolute paths with "$PREFIX/" or "~/" prefix.
     *
     * @param path The `path` to unexpand.
     * @return Returns the `unexpand path`.
     */
    @JvmStatic
    fun getUnExpandedTermuxPath(path: String?): String? {
        var unExpandedPath = path
        if (unExpandedPath != null && unExpandedPath.isNotEmpty()) {
            unExpandedPath = unExpandedPath.replace(("^" + Pattern.quote(TermuxConstants.TERMUX_PREFIX_DIR_PATH) + "/").toRegex(), "\\\$PREFIX/")
            unExpandedPath = unExpandedPath.replace(("^" + Pattern.quote(TermuxConstants.TERMUX_HOME_DIR_PATH) + "/").toRegex(), "~/")
        }
        return unExpandedPath
    }

    /**
     * Get canonical path.
     *
     * @param path The `path` to convert.
     * @param prefixForNonAbsolutePath Optional prefix path to prefix before non-absolute paths. This
     * can be set to `null` if non-absolute paths should
     * be prefixed with "/". The call to [File.getCanonicalPath]
     * will automatically do this anyways.
     * @param expandPath The `boolean` that decides if input path is first attempted to be expanded by calling
     * [TermuxFileUtils.getExpandedTermuxPath] before its passed to
     * [FileUtils.getCanonicalPath].
     * @return Returns the `canonical path`.
     */
    @JvmStatic
    fun getCanonicalPath(path: String?, prefixForNonAbsolutePath: String?, expandPath: Boolean): String? {
        var resolvedPath = path ?: ""

        if (expandPath) {
            resolvedPath = getExpandedTermuxPath(resolvedPath) ?: ""
        }

        return FileUtils.getCanonicalPath(resolvedPath, prefixForNonAbsolutePath)
    }

    /**
     * Check if `path` is under the allowed termux working directory paths. If it is, then
     * allowed parent path is returned.
     *
     * @param path The `path` to check.
     * @return Returns the allowed path if it `path` is under it, otherwise [TermuxConstants.TERMUX_FILES_DIR_PATH].
     */
    @JvmStatic
    fun getMatchedAllowedTermuxWorkingDirectoryParentPathForPath(path: String?): String {
        if (path.isNullOrEmpty()) return TermuxConstants.TERMUX_FILES_DIR_PATH

        return if (path.startsWith(TermuxConstants.TERMUX_STORAGE_HOME_DIR_PATH + "/")) {
            TermuxConstants.TERMUX_STORAGE_HOME_DIR_PATH
        } else if (path.startsWith(Environment.getExternalStorageDirectory().absolutePath + "/")) {
            Environment.getExternalStorageDirectory().absolutePath
        } else if (path.startsWith("/sdcard/")) {
            "/sdcard"
        } else {
            TermuxConstants.TERMUX_FILES_DIR_PATH
        }
    }

    /**
     * Validate the existence and permissions of directory file at path as a working directory for
     * termux app.
     *
     * The creation of missing directory and setting of missing permissions will only be done if
     * `path` is under paths returned by [getMatchedAllowedTermuxWorkingDirectoryParentPathForPath].
     *
     * The permissions set to directory will be [FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS].
     *
     * @param label The optional label for the directory file. This can optionally be `null`.
     * @param filePath The `path` for file to validate or create. Symlinks will not be followed.
     * @param createDirectoryIfMissing The `boolean` that decides if directory file
     * should be created if its missing.
     * @param setPermissions The `boolean` that decides if permissions are to be
     * automatically set defined by `permissionsToCheck`.
     * @param setMissingPermissionsOnly The `boolean` that decides if only missing permissions
     * are to be set or if they should be overridden.
     * @param ignoreErrorsIfPathIsInParentDirPath The `boolean` that decides if existence
     * and permission errors are to be ignored if path is
     * in `parentDirPath`.
     * @param ignoreIfNotExecutable The `boolean` that decides if missing executable permission
     * error is to be ignored. This allows making an attempt to set
     * executable permissions, but ignoring if it fails.
     * @return Returns the `error` if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise `null`.
     */
    @JvmStatic
    fun validateDirectoryFileExistenceAndPermissions(
        label: String?,
        filePath: String?,
        createDirectoryIfMissing: Boolean,
        setPermissions: Boolean,
        setMissingPermissionsOnly: Boolean,
        ignoreErrorsIfPathIsInParentDirPath: Boolean,
        ignoreIfNotExecutable: Boolean
    ): Error? {
        return FileUtils.validateDirectoryFileExistenceAndPermissions(
            label,
            filePath,
            getMatchedAllowedTermuxWorkingDirectoryParentPathForPath(filePath),
            createDirectoryIfMissing,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS,
            setPermissions,
            setMissingPermissionsOnly,
            ignoreErrorsIfPathIsInParentDirPath,
            ignoreIfNotExecutable
        )
    }

    /**
     * Validate if [TermuxConstants.TERMUX_FILES_DIR_PATH] exists and has
     * [FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS] permissions.
     *
     * This is required because binaries compiled for termux are hard coded with
     * [TermuxConstants.TERMUX_PREFIX_DIR_PATH] and the path must be accessible.
     *
     * The permissions set to directory will be [FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS].
     *
     * This function does not create the directory manually but by calling [Context.getFilesDir]
     * so that android itself creates it. However, the call will not create its parent package
     * data directory `/data/user/0/[package_name]` if it does not already exist and a `logcat`
     * error will be logged by android.
     * `Failed to ensure /data/user/0/<package_name>/files: mkdir failed: ENOENT (No such file or directory)`
     * An android app normally can't create the package data directory since its parent `/data/user/0`
     * is owned by `system` user and is normally created at app install or update time and not at app startup.
     *
     * Note that the path returned by [Context.getFilesDir] may
     * be under `/data/user/[id]/[package_name]` instead of `/data/data/[package_name]`
     * defined by default by [TermuxConstants.TERMUX_FILES_DIR_PATH] where id will be 0 for
     * primary user and a higher number for other users/profiles. If app is running under work profile
     * or secondary user, then [TermuxConstants.TERMUX_FILES_DIR_PATH] will not be accessible
     * and will not be automatically created, unless there is a bind mount from `/data/data` to
     * `/data/user/[id]`, ideally in the right namespace.
     * https://source.android.com/devices/tech/admin/multi-user
     *
     *
     * On Android version `<=10`, the `/data/user/0` is a symlink to `/data/data` directory.
     * https://cs.android.com/android/platform/superproject/+/android-10.0.0_r47:system/core/rootdir/init.rc;l=589
     * `symlink /data/data /data/user/0`
     *
     * `
     * /system/bin/ls -lhd /data/data /data/user/0
     * drwxrwx--x 179 system system 8.0K 2021-xx-xx xx:xx /data/data
     * lrwxrwxrwx   1 root   root     10 2021-xx-xx xx:xx /data/user/0 -> /data/data
     * `
     *
     * On Android version `>=11`, the `/data/data` directory is bind mounted at `/data/user/0`.
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:system/core/rootdir/init.rc;l=705
     * https://cs.android.com/android/_/android/platform/system/core/+/3cca270e95ca8d8bc8b800e2b5d7da1825fd7100
     * `
     * # Unlink /data/user/0 if we previously symlink it to /data/data
     * rm /data/user/0
     *
     * # Bind mount /data/user/0 to /data/data
     * mkdir /data/user/0 0700 system system encryption=None
     * mount none /data/data /data/user/0 bind rec
     * `
     *
     * `
     * /system/bin/grep -E '( /data )|( /data/data )|( /data/user/[0-9]+ )' /proc/self/mountinfo 2>&1 | /system/bin/grep -v '/data_mirror' 2>&1
     * 87 32 253:5 / /data rw,nosuid,nodev,noatime shared:27 - ext4 /dev/block/dm-5 rw,seclabel,resgid=1065,errors=panic
     * 91 87 253:5 /data /data/user/0 rw,nosuid,nodev,noatime shared:27 - ext4 /dev/block/dm-5 rw,seclabel,resgid=1065,errors=panic
     * `
     *
     * The column 4 defines the root of the mount within the filesystem.
     * Basically, `/dev/block/dm-5/` is mounted at `/data` and `/dev/block/dm-5/data` is mounted at
     * `/data/user/0`.
     * https://www.kernel.org/doc/Documentation/filesystems/proc.txt (section 3.5)
     * https://www.kernel.org/doc/Documentation/filesystems/sharedsubtree.txt
     * https://unix.stackexchange.com/a/571959
     *
     *
     * Also note that running `/system/bin/ls -lhd /data/user/0/com.termux` as secondary user will result
     * in `ls: /data/user/0/com.termux: Permission denied` where `0` is primary user id but running
     * `/system/bin/ls -lhd /data/user/10/com.termux` will result in
     * `drwx------ 6 u10_a149 u10_a149 4.0K 2021-xx-xx xx:xx /data/user/10/com.termux` where `10` is
     * secondary user id. So can't stat directory (not contents) of primary user from secondary user
     * but can the other way around. However, this is happening on android 10 avd, but not on android
     * 11 avd.
     *
     * @param context The [Context] for operations.
     * @param createDirectoryIfMissing The `boolean` that decides if directory file
     * should be created if its missing.
     * @param setMissingPermissions The `boolean` that decides if permissions are to be
     * automatically set.
     * @return Returns the `error` if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise `null`.
     */
    @JvmStatic
    fun isTermuxFilesDirectoryAccessible(
        context: Context,
        createDirectoryIfMissing: Boolean,
        setMissingPermissions: Boolean
    ): Error? {
        if (createDirectoryIfMissing) {
            context.filesDir
        }

        if (!FileUtils.directoryFileExists(TermuxConstants.TERMUX_FILES_DIR_PATH, true)) {
            return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(
                "termux files directory",
                TermuxConstants.TERMUX_FILES_DIR_PATH
            )
        }

        if (setMissingPermissions) {
            FileUtils.setMissingFilePermissions(
                "termux files directory",
                TermuxConstants.TERMUX_FILES_DIR_PATH,
                FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS
            )
        }

        return FileUtils.checkMissingFilePermissions(
            "termux files directory",
            TermuxConstants.TERMUX_FILES_DIR_PATH,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS,
            false
        )
    }

    /**
     * Validate if [TermuxConstants.TERMUX_PREFIX_DIR_PATH] exists and has
     * [FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS] permissions.
     * .
     *
     * The [TermuxConstants.TERMUX_PREFIX_DIR_PATH] directory would not exist if termux has
     * not been installed or the bootstrap setup has not been run or if it was deleted by the user.
     *
     * @param createDirectoryIfMissing The `boolean` that decides if directory file
     * should be created if its missing.
     * @param setMissingPermissions The `boolean` that decides if permissions are to be
     * automatically set.
     * @return Returns the `error` if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise `null`.
     */
    @JvmStatic
    fun isTermuxPrefixDirectoryAccessible(
        createDirectoryIfMissing: Boolean,
        setMissingPermissions: Boolean
    ): Error? {
        return FileUtils.validateDirectoryFileExistenceAndPermissions(
            "termux prefix directory",
            TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            null,
            createDirectoryIfMissing,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS,
            setMissingPermissions,
            true,
            false,
            false
        )
    }

    /**
     * Validate if [TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH] exists and has
     * [FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS] permissions.
     *
     * @param createDirectoryIfMissing The `boolean` that decides if directory file
     * should be created if its missing.
     * @param setMissingPermissions The `boolean` that decides if permissions are to be
     * automatically set.
     * @return Returns the `error` if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise `null`.
     */
    @JvmStatic
    fun isTermuxPrefixStagingDirectoryAccessible(
        createDirectoryIfMissing: Boolean,
        setMissingPermissions: Boolean
    ): Error? {
        return FileUtils.validateDirectoryFileExistenceAndPermissions(
            "termux prefix staging directory",
            TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH,
            null,
            createDirectoryIfMissing,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS,
            setMissingPermissions,
            true,
            false,
            false
        )
    }

    /**
     * Validate if [TermuxConstants.TERMUX_APP.APPS_DIR_PATH] exists and has
     * [FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS] permissions.
     *
     * @param createDirectoryIfMissing The `boolean` that decides if directory file
     * should be created if its missing.
     * @param setMissingPermissions The `boolean` that decides if permissions are to be
     * automatically set.
     * @return Returns the `error` if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise `null`.
     */
    @JvmStatic
    fun isAppsTermuxAppDirectoryAccessible(
        createDirectoryIfMissing: Boolean,
        setMissingPermissions: Boolean
    ): Error? {
        return FileUtils.validateDirectoryFileExistenceAndPermissions(
            "apps/termux-app directory",
            TermuxConstants.TERMUX_APP.APPS_DIR_PATH,
            null,
            createDirectoryIfMissing,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS,
            setMissingPermissions,
            true,
            false,
            false
        )
    }

    /**
     * If [TermuxConstants.TERMUX_PREFIX_DIR_PATH] doesn't exist, is empty or only contains
     * files in [TermuxConstants.TERMUX_PREFIX_DIR_IGNORED_SUB_FILES_PATHS_TO_CONSIDER_AS_EMPTY].
     */
    @JvmStatic
    fun isTermuxPrefixDirectoryEmpty(): Boolean {
        val error = FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles(
            "termux prefix",
            TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            TermuxConstants.TERMUX_PREFIX_DIR_IGNORED_SUB_FILES_PATHS_TO_CONSIDER_AS_EMPTY,
            true
        )
        if (error == null) return true

        if (!FileUtilsErrno.ERRNO_NON_EMPTY_DIRECTORY_FILE.equalsErrorTypeAndCode(error)) {
            Logger.logErrorExtended(
                LOG_TAG,
                "Failed to check if termux prefix directory is empty:\n" + error.getErrorLogString()
            )
        }
        return false
    }

    /**
     * Get a markdown [String] for stat output for various Termux app files paths.
     *
     * @param context The context for operations.
     * @return Returns the markdown [String].
     */
    @JvmStatic
    fun getTermuxFilesStatMarkdownString(context: Context): String? {
        val termuxPackageContext = TermuxUtils.getTermuxPackageContext(context) ?: return null

        // Also ensures that termux files directory is created if it does not already exist
        val filesDir = termuxPackageContext.filesDir.absolutePath

        // Build script
        val statScript = StringBuilder()
        statScript
            .append("echo 'ls info:'\n")
            .append("/system/bin/ls -lhdZ")
            .append(" '/data/data'")
            .append(" '/data/user/0'")
            .append(" '" + TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH + "'")
            .append(" '/data/user/0/" + TermuxConstants.TERMUX_PACKAGE_NAME + "'")
            .append(" '" + TermuxConstants.TERMUX_FILES_DIR_PATH + "'")
            .append(" '" + filesDir + "'")
            .append(" '/data/user/0/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files'")
            .append(" '/data/user/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files'")
            .append(" '" + TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH + "'")
            .append(" '" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "'")
            .append(" '" + TermuxConstants.TERMUX_HOME_DIR_PATH + "'")
            .append(" '" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/login'")
            .append(" 2>&1")
            .append("\necho; echo 'mount info:'\n")
            .append("/system/bin/grep -E '( /data )|( /data/data )|( /data/user/[0-9]+ )' /proc/self/mountinfo 2>&1 | /system/bin/grep -v '/data_mirror' 2>&1")

        // Run script
        val executionCommand = ExecutionCommand(
            -1,
            "/system/bin/sh",
            null,
            statScript.toString() + "\n",
            "/",
            ExecutionCommand.Runner.APP_SHELL.name,
            true
        )
        executionCommand.commandLabel = TermuxConstants.TERMUX_APP_NAME + " Files Stat Command"
        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF
        val appShell = AppShell.execute(context, executionCommand, null, TermuxShellEnvironment(), null, true)
        if (appShell == null || !executionCommand.isSuccessful) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            return null
        }

        // Build script output
        val statOutput = StringBuilder()
        statOutput.append("$ ").append(statScript.toString())
        statOutput.append("\n\n").append(executionCommand.resultData.stdout.toString())

        val stderrSet = executionCommand.resultData.stderr.toString().isNotEmpty()
        if (executionCommand.resultData.exitCode != 0 || stderrSet) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            if (stderrSet) {
                statOutput.append("\n").append(executionCommand.resultData.stderr.toString())
            }
            statOutput.append("\n").append("exit code: ").append(executionCommand.resultData.exitCode.toString())
        }

        // Build markdown output
        val markdownString = StringBuilder()
        markdownString.append("## ").append(TermuxConstants.TERMUX_APP_NAME).append(" Files Info\n\n")
        AndroidUtils.appendPropertyToMarkdown(
            markdownString,
            "TERMUX_REQUIRED_FILES_DIR_PATH (\$PREFIX)",
            TermuxConstants.TERMUX_FILES_DIR_PATH
        )
        AndroidUtils.appendPropertyToMarkdown(markdownString, "ANDROID_ASSIGNED_FILES_DIR_PATH", filesDir)
        markdownString.append("\n\n").append(MarkdownUtils.getMarkdownCodeForString(statOutput.toString(), true))
        markdownString.append("\n##\n")

        return markdownString.toString()
    }
}
