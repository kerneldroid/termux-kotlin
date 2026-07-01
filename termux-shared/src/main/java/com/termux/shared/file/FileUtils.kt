package com.termux.shared.file

import android.os.Build
import android.system.Os
import com.google.common.io.RecursiveDeleteOption
import com.termux.shared.data.DataUtils
import com.termux.shared.errors.Errno
import com.termux.shared.errors.Error
import com.termux.shared.errors.FunctionErrno
import com.termux.shared.file.filesystem.FileType
import com.termux.shared.file.filesystem.FileTypes
import com.termux.shared.logger.Logger
import org.apache.commons.io.filefilter.AgeFileFilter
import org.apache.commons.io.filefilter.IOFileFilter
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStreamWriter
import java.io.Serializable
import java.nio.charset.Charset
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Calendar
import java.util.regex.Pattern

object FileUtils {

    /** Required file permissions for the executable file for app usage. Executable file must have read and execute permissions */
    const val APP_EXECUTABLE_FILE_PERMISSIONS = "r-x" // Default: "r-x"

    /** Required file permissions for the working directory for app usage. Working directory must have read and write permissions.
     * Execute permissions should be attempted to be set, but ignored if they are missing */
    const val APP_WORKING_DIRECTORY_PERMISSIONS = "rwx" // Default: "rwx"

    private const val LOG_TAG = "FileUtils"

    /**
     * Get canonical path.
     *
     * If path is already an absolute path, then it is used as is to get canonical path.
     * If path is not an absolute path and {code prefixForNonAbsolutePath} is not {@code null}, then
     * {code prefixForNonAbsolutePath} + "/" is prefixed before path before getting canonical path.
     * If path is not an absolute path and {code prefixForNonAbsolutePath} is {@code null}, then
     * "/" is prefixed before path before getting canonical path.
     *
     * If an exception is raised to get the canonical path, then absolute path is returned.
     *
     * @param path The {@code path} to convert.
     * @param prefixForNonAbsolutePath Optional prefix path to prefix before non-absolute paths. This
     *                                 can be set to {@code null} if non-absolute paths should
     *                                 be prefixed with "/". The call to {@link File#getCanonicalPath()}
     *                                 will automatically do this anyways.
     * @return Returns the {@code canonical path}.
     */
    @JvmStatic
    fun getCanonicalPath(path: String?, prefixForNonAbsolutePath: String?): String {
        val p = path ?: ""

        val absolutePath = if (p.startsWith("/")) {
            p
        } else {
            if (prefixForNonAbsolutePath != null) {
                "$prefixForNonAbsolutePath/$p"
            } else {
                "/$p"
            }
        }

        try {
            return File(absolutePath).canonicalPath
        } catch (e: Exception) {
            // ignore
        }

        return absolutePath
    }

    /**
     * Removes one or more forward slashes "//" with single slash "/"
     * Removes "./"
     * Removes trailing forward slash "/"
     *
     * @param path The {@code path} to convert.
     * @return Returns the {@code normalized path}.
     */
    @JvmStatic
    fun normalizePath(path: String?): String? {
        if (path == null) return null

        var p = path.replace(Regex("/+"), "/")
        p = p.replace(Regex("\\./"), "")

        if (p.endsWith("/")) {
            p = p.replace(Regex("/+$"), "")
        }

        return p
    }

    /**
     * Convert special characters `\/:*?"<>|` to underscore.
     *
     * @param fileName The name to sanitize.
     * @param sanitizeWhitespaces If set to {@code true}, then white space characters ` \t\n` will be
     *                            converted.
     * @param toLower If set to {@code true}, then file name will be converted to lower case.
     * @return Returns the {@code sanitized name}.
     */
    @JvmStatic
    fun sanitizeFileName(fileName: String?, sanitizeWhitespaces: Boolean, toLower: Boolean): String? {
        if (fileName == null) return null

        var name = fileName
        name = if (sanitizeWhitespaces) {
            name.replace(Regex("[\\\\/:*?\"<>| \t\n]"), "_")
        } else {
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        }

        return if (toLower) name.lowercase() else name
    }

    /**
     * Determines whether path is in {@code dirPath}. The {@code dirPath} is not canonicalized and
     * only normalized.
     *
     * @param path The {@code path} to check.
     * @param dirPath The {@code directory path} to check in.
     * @param ensureUnder If set to {@code true}, then it will be ensured that {@code path} is
     *                    under the directory and does not equal it.
     * @return Returns {@code true} if path in {@code dirPath}, otherwise returns {@code false}.
     */
    @JvmStatic
    fun isPathInDirPath(path: String?, dirPath: String?, ensureUnder: Boolean): Boolean {
        return isPathInDirPaths(path, listOf(dirPath), ensureUnder)
    }

    /**
     * Determines whether path is in one of the {@code dirPaths}. The {@code dirPaths} are not
     * canonicalized and only normalized.
     *
     * @param path The {@code path} to check.
     * @param dirPaths The {@code directory paths} to check in.
     * @param ensureUnder If set to {@code true}, then it will be ensured that {@code path} is
     *                    under the directories and does not equal it.
     * @return Returns {@code true} if path in {@code dirPaths}, otherwise returns {@code false}.
     */
    @JvmStatic
    fun isPathInDirPaths(path: String?, dirPaths: List<String?>?, ensureUnder: Boolean): Boolean {
        if (path.isNullOrEmpty() || dirPaths.isNullOrEmpty()) return false

        val canonicalPath = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            return false
        }

        for (dirPath in dirPaths) {
            val normalizedDirPath = normalizePath(dirPath) ?: continue

            val isPathInDirPaths = if (ensureUnder) {
                canonicalPath != normalizedDirPath && canonicalPath.startsWith("$normalizedDirPath/")
            } else {
                canonicalPath.startsWith("$normalizedDirPath/")
            }

            if (isPathInDirPaths) return true
        }

        return false
    }

    /**
     * Validate that directory is empty or contains only files in {@code ignoredSubFilePaths}.
     *
     * If parent path of an ignored file exists, but ignored file itself does not exist, then directory
     * is not considered empty.
     *
     * @param label The optional label for directory to check. This can optionally be {@code null}.
     * @param filePath The {@code path} for directory to check.
     * @param ignoredSubFilePaths The list of absolute file paths under {@code filePath} dir.
     *                            Validation is done for the paths.
     * @param ignoreNonExistentFile The {@code boolean} that decides if it should be considered an
     *                              error if file to be checked doesn't exist.
     * @return Returns {@code null} if directory is empty or contains only files in {@code ignoredSubFilePaths}.
     * Returns {@code FileUtilsErrno#ERRNO_NON_EMPTY_DIRECTORY_FILE} if a file was found that did not
     * exist in the {@code ignoredSubFilePaths}, otherwise returns an appropriate {@code error} if
     * checking was not successful.
     */
    @JvmStatic
    fun validateDirectoryFileEmptyOrOnlyContainsSpecificFiles(
        label: String?,
        filePath: String?,
        ignoredSubFilePaths: List<String>?,
        ignoreNonExistentFile: Boolean
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(
                "${lbl}file path",
                "isDirectoryFileEmptyOrOnlyContainsSpecificFiles"
            )
        }

        try {
            val file = File(filePath)
            val fileType = getFileType(filePath, false)

            // If file exists but not a directory file
            if (fileType != FileType.NO_EXIST && fileType != FileType.DIRECTORY) {
                return FileUtilsErrno.ERRNO_NON_DIRECTORY_FILE_FOUND.getError("${lbl}directory", filePath)
                    .setLabel("${lbl}directory")
            }

            // If file does not exist
            if (fileType == FileType.NO_EXIST) {
                // If checking is to be ignored if file does not exist
                return if (ignoreNonExistentFile) {
                    null
                } else {
                    val finalLbl = "${lbl}directory to check if is empty or only contains specific files"
                    FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(finalLbl, filePath).setLabel(finalLbl)
                }
            }

            val subFiles = file.listFiles()
            if (subFiles == null || subFiles.isEmpty()) {
                return null
            }

            // If sub files exists but no file should be ignored
            if (ignoredSubFilePaths.isNullOrEmpty()) {
                return FileUtilsErrno.ERRNO_NON_EMPTY_DIRECTORY_FILE.getError(lbl, filePath)
            }

            // If a sub file does not exist in ignored file path
            if (nonIgnoredSubFileExists(subFiles, ignoredSubFilePaths)) {
                return FileUtilsErrno.ERRNO_NON_EMPTY_DIRECTORY_FILE.getError(lbl, filePath)
            }

        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_VALIDATE_DIRECTORY_EMPTY_OR_ONLY_CONTAINS_SPECIFIC_FILES_FAILED_WITH_EXCEPTION
                .getError(e, "${lbl}directory", filePath, e.message)
        }

        return null
    }

    /**
     * Check if {@code subFiles} contains contains a file not in {@code ignoredSubFilePaths}.
     *
     * If parent path of an ignored file exists, but ignored file itself does not exist, then directory
     * is not considered empty.
     *
     * This function should ideally not be called by itself but through
     * {@link #validateDirectoryFileEmptyOrOnlyContainsSpecificFiles(String, String, List, boolean)}.
     *
     * @param subFiles The list of files of a directory to check.
     * @param ignoredSubFilePaths The list of absolute file paths under {@code filePath} dir.
     *                            Validation is done for the paths.
     * @return Returns {@code true} if a file was found that did not exist in the {@code ignoredSubFilePaths},
     * otherwise  {@code false}.
     */
    @JvmStatic
    fun nonIgnoredSubFileExists(subFiles: Array<File>?, ignoredSubFilePaths: List<String>): Boolean {
        if (subFiles == null || subFiles.isEmpty()) return false

        for (subFile in subFiles) {
            val subFilePath = subFile.absolutePath
            // If sub file does not exist in ignored sub file paths
            if (!ignoredSubFilePaths.contains(subFilePath)) {
                var isParentPath = false
                for (ignoredSubFilePath in ignoredSubFilePaths) {
                    if (ignoredSubFilePath.startsWith("$subFilePath/") && fileExists(ignoredSubFilePath, false)) {
                        isParentPath = true
                        break
                    }
                }
                // If sub file is not a parent of any existing ignored sub file paths
                if (!isParentPath) {
                    return true
                }
            }

            if (getFileType(subFilePath, false) == FileType.DIRECTORY) {
                // If non ignored sub file found, then early exit, otherwise continue looking
                if (nonIgnoredSubFileExists(subFile.listFiles(), ignoredSubFilePaths)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Checks whether a regular file exists at {@code filePath}.
     *
     * @param filePath The {@code path} for regular file to check.
     * @param followLinks The {@code boolean} that decides if symlinks will be followed while
     *                       finding if file exists. Check {@link #getFileType(String, boolean)}
     *                       for details.
     * @return Returns {@code true} if regular file exists, otherwise {@code false}.
     */
    @JvmStatic
    fun regularFileExists(filePath: String?, followLinks: Boolean): Boolean {
        return getFileType(filePath, followLinks) == FileType.REGULAR
    }

    /**
     * Checks whether a directory file exists at {@code filePath}.
     *
     * @param filePath The {@code path} for directory file to check.
     * @param followLinks The {@code boolean} that decides if symlinks will be followed while
     *                       finding if file exists. Check {@link #getFileType(String, boolean)}
     *                       for details.
     * @return Returns {@code true} if directory file exists, otherwise {@code false}.
     */
    @JvmStatic
    fun directoryFileExists(filePath: String?, followLinks: Boolean): Boolean {
        return getFileType(filePath, followLinks) == FileType.DIRECTORY
    }

    /**
     * Checks whether a symlink file exists at {@code filePath}.
     *
     * @param filePath The {@code path} for symlink file to check.
     * @return Returns {@code true} if symlink file exists, otherwise {@code false}.
     */
    @JvmStatic
    fun symlinkFileExists(filePath: String?): Boolean {
        return getFileType(filePath, false) == FileType.SYMLINK
    }

    /**
     * Checks whether a regular or directory file exists at {@code filePath}.
     *
     * @param filePath The {@code path} for regular file to check.
     * @param followLinks The {@code boolean} that decides if symlinks will be followed while
     *                       finding if file exists. Check {@link #getFileType(String, boolean)}
     *                       for details.
     * @return Returns {@code true} if regular or directory file exists, otherwise {@code false}.
     */
    @JvmStatic
    fun regularOrDirectoryFileExists(filePath: String?, followLinks: Boolean): Boolean {
        val fileType = getFileType(filePath, followLinks)
        return fileType == FileType.REGULAR || fileType == FileType.DIRECTORY
    }

    /**
     * Checks whether any file exists at {@code filePath}.
     *
     * @param filePath The {@code path} for file to check.
     * @param followLinks The {@code boolean} that decides if symlinks will be followed while
     *                       finding if file exists. Check {@link #getFileType(String, boolean)}
     *                       for details.
     * @return Returns {@code true} if file exists, otherwise {@code false}.
     */
    @JvmStatic
    fun fileExists(filePath: String?, followLinks: Boolean): Boolean {
        return getFileType(filePath, followLinks) != FileType.NO_EXIST
    }

    /**
     * Get the type of file that exists at {@code filePath}.
     *
     * This function is a wrapper for
     * {@link FileTypes#getFileType(String, boolean)}
     *
     * @param filePath The {@code path} for file to check.
     * @param followLinks The {@code boolean} that decides if symlinks will be followed while
     *                       finding type. If set to {@code true}, then type of symlink target will
     *                       be returned if file at {@code filePath} is a symlink. If set to
     *                       {@code false}, then type of file at {@code filePath} itself will be
     *                       returned.
     * @return Returns the {@link FileType} of file.
     */
    @JvmStatic
    fun getFileType(filePath: String?, followLinks: Boolean): FileType {
        return FileTypes.getFileType(filePath, followLinks)
    }

    /**
     * Validate the existence and permissions of regular file at path.
     *
     * If the {@code parentDirPath} is not {@code null}, then setting of missing permissions will
     * only be done if {@code path} is under {@code parentDirPath}.
     *
     * @param label The optional label for the regular file. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to validate. Symlinks will not be followed.
     * @param parentDirPath The optional {@code parent directory path} to restrict operations to.
     *                      This can optionally be {@code null}. It is not canonicalized and only normalized.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param setPermissions The {@code boolean} that decides if permissions are to be
     *                              automatically set defined by {@code permissionsToCheck}.
     * @param setMissingPermissionsOnly The {@code boolean} that decides if only missing permissions
     *                                  are to be set or if they should be overridden.
     * @param ignoreErrorsIfPathIsUnderParentDirPath The {@code boolean} that decides if permission
     *                                               errors are to be ignored if path is under
     *                                               {@code parentDirPath}.
     * @return Returns the {@code error} if path is not a regular file, or validating permissions
     * failed, otherwise {@code null}.
     */
    @JvmStatic
    fun validateRegularFileExistenceAndPermissions(
        label: String?,
        filePath: String?,
        parentDirPath: String?,
        permissionsToCheck: String?,
        setPermissions: Boolean,
        setMissingPermissionsOnly: Boolean,
        ignoreErrorsIfPathIsUnderParentDirPath: Boolean
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(
                "${lbl}regular file path",
                "validateRegularFileExistenceAndPermissions"
            )
        }

        try {
            val fileType = getFileType(filePath, false)

            // If file exists but not a regular file
            if (fileType != FileType.NO_EXIST && fileType != FileType.REGULAR) {
                return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError("${lbl}file", filePath)
                    .setLabel("${lbl}file")
            }

            var isPathUnderParentDirPath = false
            if (parentDirPath != null) {
                // The path can only be under parent directory path
                isPathUnderParentDirPath = isPathInDirPath(filePath, parentDirPath, true)
            }

            // If setPermissions is enabled and path is a regular file
            if (setPermissions && permissionsToCheck != null && fileType == FileType.REGULAR) {
                // If there is not parentDirPath restriction or path is under parentDirPath
                if (parentDirPath == null || (isPathUnderParentDirPath && getFileType(parentDirPath, false) == FileType.DIRECTORY)) {
                    if (setMissingPermissionsOnly) {
                        setMissingFilePermissions("${lbl}file", filePath, permissionsToCheck)
                    } else {
                        setFilePermissions("${lbl}file", filePath, permissionsToCheck)
                    }
                }
            }

            // If path is not a regular file
            // Regular files cannot be automatically created so we do not ignore if missing
            if (fileType != FileType.REGULAR) {
                val finalLbl = "${lbl}regular file"
                return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(finalLbl, filePath).setLabel(finalLbl)
            }

            // If there is not parentDirPath restriction or path is not under parentDirPath or
            // if permission errors must not be ignored for paths under parentDirPath
            if (parentDirPath == null || !isPathUnderParentDirPath || !ignoreErrorsIfPathIsUnderParentDirPath) {
                if (permissionsToCheck != null) {
                    // Check if permissions are missing
                    return checkMissingFilePermissions("${lbl}regular", filePath, permissionsToCheck, false)
                }
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_VALIDATE_FILE_EXISTENCE_AND_PERMISSIONS_FAILED_WITH_EXCEPTION
                .getError(e, "${lbl}file", filePath, e.message)
        }

        return null
    }

    /**
     * Validate the existence and permissions of directory file at path.
     *
     * If the {@code parentDirPath} is not {@code null}, then creation of missing directory and
     * setting of missing permissions will only be done if {@code path} is under
     * {@code parentDirPath} or equals {@code parentDirPath}.
     *
     * @param label The optional label for the directory file. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to validate or create. Symlinks will not be followed.
     * @param parentDirPath The optional {@code parent directory path} to restrict operations to.
     *                      This can optionally be {@code null}. It is not canonicalized and only normalized.
     * @param createDirectoryIfMissing The {@code boolean} that decides if directory file
     *                                 should be created if its missing.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param setPermissions The {@code boolean} that decides if permissions are to be
     *                              automatically set defined by {@code permissionsToCheck}.
     * @param setMissingPermissionsOnly The {@code boolean} that decides if only missing permissions
     *                                  are to be set or if they should be overridden.
     * @param ignoreErrorsIfPathIsInParentDirPath The {@code boolean} that decides if existence
     *                                  and permission errors are to be ignored if path is
     *                                  in {@code parentDirPath}.
     * @param ignoreIfNotExecutable The {@code boolean} that decides if missing executable permission
     *                              error is to be ignored. This allows making an attempt to set
     *                              executable permissions, but ignoring if it fails.
     * @return Returns the {@code error} if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise {@code null}.
     */
    @JvmStatic
    fun validateDirectoryFileExistenceAndPermissions(
        label: String?,
        filePath: String?,
        parentDirPath: String?,
        createDirectoryIfMissing: Boolean,
        permissionsToCheck: String?,
        setPermissions: Boolean,
        setMissingPermissionsOnly: Boolean,
        ignoreErrorsIfPathIsInParentDirPath: Boolean,
        ignoreIfNotExecutable: Boolean
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(
                "${lbl}directory file path",
                "validateDirectoryExistenceAndPermissions"
            )
        }

        try {
            val file = File(filePath)
            var fileType = getFileType(filePath, false)

            // If file exists but not a directory file
            if (fileType != FileType.NO_EXIST && fileType != FileType.DIRECTORY) {
                return FileUtilsErrno.ERRNO_NON_DIRECTORY_FILE_FOUND.getError("${lbl}directory", filePath)
                    .setLabel("${lbl}directory")
            }

            var isPathInParentDirPath = false
            if (parentDirPath != null) {
                // The path can be equal to parent directory path or under it
                isPathInParentDirPath = isPathInDirPath(filePath, parentDirPath, false)
            }

            if (createDirectoryIfMissing || setPermissions) {
                // If there is not parentDirPath restriction or path is in parentDirPath
                if (parentDirPath == null || (isPathInParentDirPath && getFileType(parentDirPath, false) == FileType.DIRECTORY)) {
                    // If createDirectoryIfMissing is enabled and no file exists at path, then create directory
                    if (createDirectoryIfMissing && fileType == FileType.NO_EXIST) {
                        Logger.logVerbose(LOG_TAG, "Creating ${lbl}directory file at path \"$filePath\"")
                        // Create directory and update fileType if successful, otherwise return with error
                        // It "might" be possible that mkdirs returns false even though directory was created
                        val result = file.mkdirs()
                        fileType = getFileType(filePath, false)
                        if (!result && fileType != FileType.DIRECTORY) {
                            return FileUtilsErrno.ERRNO_CREATING_FILE_FAILED.getError("${lbl}directory file", filePath)
                        }
                    }

                    // If setPermissions is enabled and path is a directory
                    if (setPermissions && permissionsToCheck != null && fileType == FileType.DIRECTORY) {
                        if (setMissingPermissionsOnly) {
                            setMissingFilePermissions("${lbl}directory", filePath, permissionsToCheck)
                        } else {
                            setFilePermissions("${lbl}directory", filePath, permissionsToCheck)
                        }
                    }
                }
            }

            // If there is not parentDirPath restriction or path is not in parentDirPath or
            // if existence or permission errors must not be ignored for paths in parentDirPath
            if (parentDirPath == null || !isPathInParentDirPath || !ignoreErrorsIfPathIsInParentDirPath) {
                // If path is not a directory
                // Directories can be automatically created so we can ignore if missing with above check
                if (fileType != FileType.DIRECTORY) {
                    val finalLbl = "${lbl}directory"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(finalLbl, filePath).setLabel(finalLbl)
                }

                if (permissionsToCheck != null) {
                    // Check if permissions are missing
                    return checkMissingFilePermissions("${lbl}directory", filePath, permissionsToCheck, ignoreIfNotExecutable)
                }
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_VALIDATE_DIRECTORY_EXISTENCE_AND_PERMISSIONS_FAILED_WITH_EXCEPTION
                .getError(e, "${lbl}directory file", filePath, e.message)
        }

        return null
    }

    /**
     * Create a regular file at path.
     *
     * This function is a wrapper for
     * {@link #validateDirectoryFileExistenceAndPermissions(String, String, String, boolean, String, boolean, boolean, boolean, boolean)}.
     *
     * @param filePath The {@code path} for regular file to create.
     * @return Returns the {@code error} if path is not a regular file or failed to create it,
     * otherwise {@code null}.
     */
    @JvmStatic
    fun createRegularFile(filePath: String?): Error? {
        return createRegularFile(null, filePath)
    }

    /**
     * Create a regular file at path.
     *
     * This function is a wrapper for
     * {@link #validateDirectoryFileExistenceAndPermissions(String, String, String, boolean, String, boolean, boolean, boolean, boolean)}.
     *
     * @param label The optional label for the regular file. This can optionally be {@code null}.
     * @param filePath The {@code path} for regular file to create.
     * @return Returns the {@code error} if path is not a regular file or failed to create it,
     * otherwise {@code null}.
     */
    @JvmStatic
    fun createRegularFile(label: String?, filePath: String?): Error? {
        return createRegularFile(label, filePath, null, false, false)
    }

    /**
     * Create a regular file at path.
     *
     * This function is a wrapper for
     * {@link #validateRegularFileExistenceAndPermissions(String, String, String, String, boolean, boolean, boolean)}.
     *
     * @param label The optional label for the regular file. This can optionally be {@code null}.
     * @param filePath The {@code path} for regular file to create.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param setPermissions The {@code boolean} that decides if permissions are to be
     *                              automatically set defined by {@code permissionsToCheck}.
     * @param setMissingPermissionsOnly The {@code boolean} that decides if only missing permissions
     *                                  are to be set or if they should be overridden.
     * @return Returns the {@code error} if path is not a regular file, failed to create it,
     * or validating permissions failed, otherwise {@code null}.
     */
    @JvmStatic
    fun createRegularFile(
        label: String?,
        filePath: String?,
        permissionsToCheck: String?,
        setPermissions: Boolean,
        setMissingPermissionsOnly: Boolean
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}file path", "createRegularFile")
        }

        val file = File(filePath)
        val fileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType != FileType.NO_EXIST && fileType != FileType.REGULAR) {
            return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError("${lbl}file", filePath).setLabel("${lbl}file")
        }

        // If regular file already exists
        if (fileType == FileType.REGULAR) {
            return null
        }

        // Create the file parent directory
        val error = createParentDirectoryFile("${lbl}regular file parent", filePath)
        if (error != null) return error

        try {
            Logger.logVerbose(LOG_TAG, "Creating ${lbl}regular file at path \"$filePath\"")
            if (!file.createNewFile()) {
                return FileUtilsErrno.ERRNO_CREATING_FILE_FAILED.getError("${lbl}regular file", filePath)
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_CREATING_FILE_FAILED_WITH_EXCEPTION.getError(e, "${lbl}regular file", filePath, e.message)
        }

        return validateRegularFileExistenceAndPermissions(
            label, filePath,
            null,
            permissionsToCheck, setPermissions, setMissingPermissionsOnly,
            false
        )
    }

    /**
     * Create parent directory of file at path.
     *
     * This function is a wrapper for
     * {@link #validateDirectoryFileExistenceAndPermissions(String, String, String, boolean, String, boolean, boolean, boolean, boolean)}.
     *
     * @param label The optional label for the parent directory file. This can optionally be {@code null}.
     * @param filePath The {@code path} for file whose parent needs to be created.
     * @return Returns the {@code error} if parent path is not a directory file or failed to create it,
     * otherwise {@code null}.
     */
    @JvmStatic
    fun createParentDirectoryFile(label: String?, filePath: String?): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}file path", "createParentDirectoryFile")
        }

        val file = File(filePath)
        val fileParentPath = file.parent

        return if (fileParentPath != null) {
            createDirectoryFile(label, fileParentPath, null, false, false)
        } else {
            null
        }
    }

    /**
     * Create a directory file at path.
     *
     * This function is a wrapper for
     * {@link #validateDirectoryFileExistenceAndPermissions(String, String, String, boolean, String, boolean, boolean, boolean, boolean)}.
     *
     * @param filePath The {@code path} for directory file to create.
     * @return Returns the {@code error} if path is not a directory file or failed to create it,
     * otherwise {@code null}.
     */
    @JvmStatic
    fun createDirectoryFile(filePath: String?): Error? {
        return createDirectoryFile(null, filePath)
    }

    /**
     * Create a directory file at path.
     *
     * This function is a wrapper for
     * {@link #validateDirectoryFileExistenceAndPermissions(String, String, String, boolean, String, boolean, boolean, boolean, boolean)}.
     *
     * @param label The optional label for the directory file. This can optionally be {@code null}.
     * @param filePath The {@code path} for directory file to create.
     * @return Returns the {@code error} if path is not a directory file or failed to create it,
     * otherwise {@code null}.
     */
    @JvmStatic
    fun createDirectoryFile(label: String?, filePath: String?): Error? {
        return createDirectoryFile(label, filePath, null, false, false)
    }

    /**
     * Create a directory file at path.
     *
     * This function is a wrapper for
     * {@link #validateDirectoryFileExistenceAndPermissions(String, String, String, boolean, String, boolean, boolean, boolean, boolean)}.
     *
     * @param label The optional label for the directory file. This can optionally be {@code null}.
     * @param filePath The {@code path} for directory file to create.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param setPermissions The {@code boolean} that decides if permissions are to be
     *                              automatically set defined by {@code permissionsToCheck}.
     * @param setMissingPermissionsOnly The {@code boolean} that decides if only missing permissions
     *                                  are to be set or if they should be overridden.
     * @return Returns the {@code error} if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise {@code null}.
     */
    @JvmStatic
    fun createDirectoryFile(
        label: String?,
        filePath: String?,
        permissionsToCheck: String?,
        setPermissions: Boolean,
        setMissingPermissionsOnly: Boolean
    ): Error? {
        return validateDirectoryFileExistenceAndPermissions(
            label, filePath,
            null, true,
            permissionsToCheck, setPermissions, setMissingPermissionsOnly,
            false, false
        )
    }

    /**
     * Create a symlink file at path.
     *
     * This function is a wrapper for
     * {@link #createSymlinkFile(String, String, String, boolean, boolean, boolean)}.
     *
     * Dangling symlinks will be allowed.
     * Symlink destination will be overwritten if it already exists but only if its a symlink.
     *
     * @param targetFilePath The {@code path} TO which the symlink file will be created.
     * @param destFilePath The {@code path} AT which the symlink file will be created.
     * @return Returns the {@code error} if path is not a symlink file, failed to create it,
     * otherwise {@code null}.
     */
    @JvmStatic
    fun createSymlinkFile(targetFilePath: String?, destFilePath: String?): Error? {
        return createSymlinkFile(null, targetFilePath, destFilePath, true, true, true)
    }

    /**
     * Create a symlink file at path.
     *
     * This function is a wrapper for
     * {@link #createSymlinkFile(String, String, String, boolean, boolean, boolean)}.
     *
     * Dangling symlinks will be allowed.
     * Symlink destination will be overwritten if it already exists but only if its a symlink.
     *
     * @param label The optional label for the symlink file. This can optionally be {@code null}.
     * @param targetFilePath The {@code path} TO which the symlink file will be created.
     * @param destFilePath The {@code path} AT which the symlink file will be created.
     * @return Returns the {@code error} if path is not a symlink file, failed to create it,
     * otherwise {@code null}.
     */
    @JvmStatic
    fun createSymlinkFile(label: String?, targetFilePath: String?, destFilePath: String?): Error? {
        return createSymlinkFile(label, targetFilePath, destFilePath, true, true, true)
    }

    /**
     * Create a symlink file at path.
     *
     * @param label The optional label for the symlink file. This can optionally be {@code null}.
     * @param targetFilePath The {@code path} TO which the symlink file will be created.
     * @param destFilePath The {@code path} AT which the symlink file will be created.
     * @param allowDangling The {@code boolean} that decides if it should be considered an
     *                              error if source file doesn't exist.
     * @param overwrite The {@code boolean} that decides if destination file should be overwritten if
     *                  it already exists. If set to {@code true}, then destination file will be
     *                  deleted before symlink is created.
     * @param overwriteOnlyIfDestIsASymlink The {@code boolean} that decides if overwrite should
     *                                         only be done if destination file is also a symlink.
     * @return Returns the {@code error} if path is not a symlink file, failed to create it,
     * or validating permissions failed, otherwise {@code null}.
     */
    @JvmStatic
    fun createSymlinkFile(
        label: String?,
        targetFilePath: String?,
        destFilePath: String?,
        allowDangling: Boolean,
        overwrite: Boolean,
        overwriteOnlyIfDestIsASymlink: Boolean
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (targetFilePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}target file path", "createSymlinkFile")
        }
        if (destFilePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}destination file path", "createSymlinkFile")
        }

        try {
            val destFile = File(destFilePath)

            var targetFileAbsolutePath = targetFilePath
            // If target path is relative instead of absolute
            if (!targetFilePath.startsWith("/")) {
                val destFileParentPath = destFile.parent
                if (destFileParentPath != null) {
                    targetFileAbsolutePath = "$destFileParentPath/$targetFilePath"
                }
            }

            val targetFileType = getFileType(targetFileAbsolutePath, false)
            val destFileType = getFileType(destFilePath, false)

            // If target file does not exist
            if (targetFileType == FileType.NO_EXIST) {
                // If dangling symlink should not be allowed, then return with error
                if (!allowDangling) {
                    val finalLbl = "${lbl}symlink target file"
                    return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(finalLbl, targetFileAbsolutePath).setLabel(finalLbl)
                }
            }

            // If destination exists
            if (destFileType != FileType.NO_EXIST) {
                // If destination must not be overwritten
                if (!overwrite) {
                    return null
                }

                // If overwriteOnlyIfDestIsASymlink is enabled but destination file is not a symlink
                if (overwriteOnlyIfDestIsASymlink && destFileType != FileType.SYMLINK) {
                    return FileUtilsErrno.ERRNO_CANNOT_OVERWRITE_A_NON_SYMLINK_FILE_TYPE.getError(
                        "${lbl}file",
                        destFilePath,
                        targetFilePath,
                        destFileType.getName()
                    )
                }

                // Delete the destination file
                val error = deleteFile("${lbl}symlink destination", destFilePath, true)
                if (error != null) return error
            } else {
                // Create the destination file parent directory
                val error = createParentDirectoryFile("${lbl}symlink destination file parent", destFilePath)
                if (error != null) return error
            }

            // create a symlink at destFilePath to targetFilePath
            Logger.logVerbose(LOG_TAG, "Creating ${lbl}symlink file at path \"$destFilePath\" to \"$targetFilePath\"")
            Os.symlink(targetFilePath, destFilePath)
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_CREATING_SYMLINK_FILE_FAILED_WITH_EXCEPTION
                .getError(e, "${lbl}symlink file", destFilePath, targetFilePath, e.message)
        }

        return null
    }

    /**
     * Copy a regular file from {@code sourceFilePath} to {@code destFilePath}.
     *
     * This function is a wrapper for
     * {@link #copyOrMoveFile(String, String, String, boolean, boolean, int, boolean, boolean)}.
     *
     * If destination file already exists, then it will be overwritten, but only if its a regular
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to copy. This can optionally be {@code null}.
     * @param srcFilePath The {@code source path} for file to copy.
     * @param destFilePath The {@code destination path} for file to copy.
     * @param ignoreNonExistentSrcFile The {@code boolean} that decides if it should be considered an
     *                              error if source file to copied doesn't exist.
     * @return Returns the {@code error} if copy was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun copyRegularFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(
            label, srcFilePath, destFilePath,
            false, ignoreNonExistentSrcFile, FileType.REGULAR.getValue(),
            true, true
        )
    }

    /**
     * Move a regular file from {@code sourceFilePath} to {@code destFilePath}.
     *
     * This function is a wrapper for
     * {@link #copyOrMoveFile(String, String, String, boolean, boolean, int, boolean, boolean)}.
     *
     * If destination file already exists, then it will be overwritten, but only if its a regular
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to move. This can optionally be {@code null}.
     * @param srcFilePath The {@code source path} for file to move.
     * @param destFilePath The {@code destination path} for file to move.
     * @param ignoreNonExistentSrcFile The {@code boolean} that decides if it should be considered an
     *                              error if source file to moved doesn't exist.
     * @return Returns the {@code error} if move was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun moveRegularFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(
            label, srcFilePath, destFilePath,
            true, ignoreNonExistentSrcFile, FileType.REGULAR.getValue(),
            true, true
        )
    }

    /**
     * Copy a directory file from {@code sourceFilePath} to {@code destFilePath}.
     *
     * This function is a wrapper for
     * {@link #copyOrMoveFile(String, String, String, boolean, boolean, int, boolean, boolean)}.
     *
     * If destination file already exists, then it will be overwritten, but only if its a directory
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to copy. This can optionally be {@code null}.
     * @param srcFilePath The {@code source path} for file to copy.
     * @param destFilePath The {@code destination path} for file to copy.
     * @param ignoreNonExistentSrcFile The {@code boolean} that decides if it should be considered an
     *                              error if source file to copied doesn't exist.
     * @return Returns the {@code error} if copy was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun copyDirectoryFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(
            label, srcFilePath, destFilePath,
            false, ignoreNonExistentSrcFile, FileType.DIRECTORY.getValue(),
            true, true
        )
    }

    /**
     * Move a directory file from {@code sourceFilePath} to {@code destFilePath}.
     *
     * This function is a wrapper for
     * {@link #copyOrMoveFile(String, String, String, boolean, boolean, int, boolean, boolean)}.
     *
     * If destination file already exists, then it will be overwritten, but only if its a directory
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to move. This can optionally be {@code null}.
     * @param srcFilePath The {@code source path} for file to move.
     * @param destFilePath The {@code destination path} for file to move.
     * @param ignoreNonExistentSrcFile The {@code boolean} that decides if it should be considered an
     *                              error if source file to moved doesn't exist.
     * @return Returns the {@code error} if move was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun moveDirectoryFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(
            label, srcFilePath, destFilePath,
            true, ignoreNonExistentSrcFile, FileType.DIRECTORY.getValue(),
            true, true
        )
    }

    /**
     * Copy a symlink file from {@code sourceFilePath} to {@code destFilePath}.
     *
     * This function is a wrapper for
     * {@link #copyOrMoveFile(String, String, String, boolean, boolean, int, boolean, boolean)}.
     *
     * If destination file already exists, then it will be overwritten, but only if its a symlink
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to copy. This can optionally be {@code null}.
     * @param srcFilePath The {@code source path} for file to copy.
     * @param destFilePath The {@code destination path} for file to copy.
     * @param ignoreNonExistentSrcFile The {@code boolean} that decides if it should be considered an
     *                              error if source file to copied doesn't exist.
     * @return Returns the {@code error} if copy was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun copySymlinkFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(
            label, srcFilePath, destFilePath,
            false, ignoreNonExistentSrcFile, FileType.SYMLINK.getValue(),
            true, true
        )
    }

    /**
     * Move a symlink file from {@code sourceFilePath} to {@code destFilePath}.
     *
     * This function is a wrapper for
     * {@link #copyOrMoveFile(String, String, String, boolean, boolean, int, boolean, boolean)}.
     *
     * If destination file already exists, then it will be overwritten, but only if its a symlink
     * file, otherwise an error will be returned.
     *
     * @param label The optional label for file to move. This can optionally be {@code null}.
     * @param srcFilePath The {@code source path} for file to move.
     * @param destFilePath The {@code destination path} for file to move.
     * @param ignoreNonExistentSrcFile The {@code boolean} that decides if it should be considered an
     *                              error if source file to moved doesn't exist.
     * @return Returns the {@code error} if move was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun moveSymlinkFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(
            label, srcFilePath, destFilePath,
            true, ignoreNonExistentSrcFile, FileType.SYMLINK.getValue(),
            true, true
        )
    }

    /**
     * Copy a file from {@code sourceFilePath} to {@code destFilePath}.
     *
     * This function is a wrapper for
     * {@link #copyOrMoveFile(String, String, String, boolean, boolean, int, boolean, boolean)}.
     *
     * If destination file already exists, then it will be overwritten, but only if its the same file
     * type as the source, otherwise an error will be returned.
     *
     * @param label The optional label for file to copy. This can optionally be {@code null}.
     * @param srcFilePath The {@code source path} for file to copy.
     * @param destFilePath The {@code destination path} for file to copy.
     * @param ignoreNonExistentSrcFile The {@code boolean} that decides if it should be considered an
     *                              error if source file to copied doesn't exist.
     * @return Returns the {@code error} if copy was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun copyFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(
            label, srcFilePath, destFilePath,
            false, ignoreNonExistentSrcFile, FileTypes.FILE_TYPE_NORMAL_FLAGS,
            true, true
        )
    }

    /**
     * Move a file from {@code sourceFilePath} to {@code destFilePath}.
     *
     * This function is a wrapper for
     * {@link #copyOrMoveFile(String, String, String, boolean, boolean, int, boolean, boolean)}.
     *
     * If destination file already exists, then it will be overwritten, but only if its the same file
     * type as the source, otherwise an error will be returned.
     *
     * @param label The optional label for file to move. This can optionally be {@code null}.
     * @param srcFilePath The {@code source path} for file to move.
     * @param destFilePath The {@code destination path} for file to move.
     * @param ignoreNonExistentSrcFile The {@code boolean} that decides if it should be considered an
     *                              error if source file to moved doesn't exist.
     * @return Returns the {@code error} if move was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun moveFile(label: String?, srcFilePath: String?, destFilePath: String?, ignoreNonExistentSrcFile: Boolean): Error? {
        return copyOrMoveFile(
            label, srcFilePath, destFilePath,
            true, ignoreNonExistentSrcFile, FileTypes.FILE_TYPE_NORMAL_FLAGS,
            true, true
        )
    }

    /**
     * Copy or move a file from {@code sourceFilePath} to {@code destFilePath}.
     *
     * The {@code sourceFilePath} and {@code destFilePath} must be the canonical path to the source
     * and destination since symlinks will not be followed.
     *
     * If the {@code sourceFilePath} or {@code destFilePath} is a canonical path to a directory,
     * then any symlink files found under the directory will be deleted, but not their targets when
     * deleting source after move and deleting destination before copy/move.
     *
     * @param label The optional label for file to copy or move. This can optionally be {@code null}.
     * @param srcFilePath The {@code source path} for file to copy or move.
     * @param destFilePath The {@code destination path} for file to copy or move.
     * @param moveFile The {@code boolean} that decides if source file needs to be copied or moved.
     *                 If set to {@code true}, then source file will be moved, otherwise it will be
     *                 copied.
     * @param ignoreNonExistentSrcFile The {@code boolean} that decides if it should be considered an
     *                              error if source file to copied or moved doesn't exist.
     * @param allowedFileTypeFlags The flags that are matched against the source file's {@link FileType}
     *                             to see if it should be copied/moved or not. This is a safety measure
     *                             to prevent accidental copy/move/delete of the wrong type of file,
     *                             like a directory instead of a regular file. You can pass
     *                             {@link FileTypes#FILE_TYPE_ANY_FLAGS} to allow copy/move of any file type.
     * @param overwrite The {@code boolean} that decides if destination file should be overwritten if
     *                  it already exists. If set to {@code true}, then destination file will be
     *                  deleted before source is copied or moved.
     * @param overwriteOnlyIfDestSameFileTypeAsSrc The {@code boolean} that decides if overwrite should
     *                                         only be done if destination file is also the same file
     *                                          type as the source file.
     * @return Returns the {@code error} if copy or move was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun copyOrMoveFile(
        label: String?,
        srcFilePath: String?,
        destFilePath: String?,
        moveFile: Boolean,
        ignoreNonExistentSrcFile: Boolean,
        allowedFileTypeFlags: Int,
        overwrite: Boolean,
        overwriteOnlyIfDestSameFileTypeAsSrc: Boolean
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (srcFilePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}source file path", "copyOrMoveFile")
        }
        if (destFilePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}destination file path", "copyOrMoveFile")
        }

        val mode = if (moveFile) "Moving" else "Copying"
        val modePast = if (moveFile) "moved" else "copied"

        try {
            Logger.logVerbose(LOG_TAG, "$mode ${lbl}source file from \"$srcFilePath\" to destination \"$destFilePath\"")

            val srcFile = File(srcFilePath)
            val destFile = File(destFilePath)

            val srcFileType = getFileType(srcFilePath, false)
            val destFileType = getFileType(destFilePath, false)

            val srcFileCanonicalPath = srcFile.canonicalPath
            val destFileCanonicalPath = destFile.canonicalPath

            // If source file does not exist
            if (srcFileType == FileType.NO_EXIST) {
                // If copy or move is to be ignored if source file is not found
                return if (ignoreNonExistentSrcFile) {
                    null
                } else {
                    val finalLbl = "${lbl}source file"
                    FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(finalLbl, srcFilePath).setLabel(finalLbl)
                }
            }

            // If the file type of the source file does not exist in the allowedFileTypeFlags, then return with error
            if ((allowedFileTypeFlags and srcFileType.getValue()) <= 0) {
                return FileUtilsErrno.ERRNO_FILE_NOT_AN_ALLOWED_FILE_TYPE.getError(
                    "${lbl}source file meant to be $modePast",
                    srcFilePath,
                    FileTypes.convertFileTypeFlagsToNamesString(allowedFileTypeFlags)
                )
            }

            // If source and destination file path are the same
            if (srcFileCanonicalPath == destFileCanonicalPath) {
                return FileUtilsErrno.ERRNO_COPYING_OR_MOVING_FILE_TO_SAME_PATH.getError(
                    "$mode ${lbl}source file",
                    srcFilePath,
                    destFilePath
                )
            }

            // If destination exists
            if (destFileType != FileType.NO_EXIST) {
                // If destination must not be overwritten
                if (!overwrite) {
                    return null
                }

                // If overwriteOnlyIfDestSameFileTypeAsSrc is enabled but destination file does not match source file type
                if (overwriteOnlyIfDestSameFileTypeAsSrc && destFileType != srcFileType) {
                    return FileUtilsErrno.ERRNO_CANNOT_OVERWRITE_A_DIFFERENT_FILE_TYPE.getError(
                        "${lbl}source file",
                        mode.lowercase(),
                        srcFilePath,
                        destFilePath,
                        destFileType.getName(),
                        srcFileType.getName()
                    )
                }

                // Delete the destination file
                val error = deleteFile("${lbl}destination", destFilePath, true)
                if (error != null) return error
            }

            // Copy or move source file to dest
            var copyFile = !moveFile

            // If moveFile is true
            if (moveFile) {
                // We first try to rename source file to destination file to save a copy operation in case both source and destination are on the same filesystem
                Logger.logVerbose(LOG_TAG, "Attempting to rename source to destination.")

                if (!srcFile.renameTo(destFile)) {
                    // If destination directory is a subdirectory of the source directory
                    // Copying is still allowed by copyDirectory() by excluding destination directory files
                    if (srcFileType == FileType.DIRECTORY && destFileCanonicalPath.startsWith(srcFileCanonicalPath + File.separator)) {
                        return FileUtilsErrno.ERRNO_CANNOT_MOVE_DIRECTORY_TO_SUB_DIRECTORY_OF_ITSELF.getError(
                            "${lbl}source directory",
                            srcFilePath,
                            destFilePath
                        )
                    }

                    // If rename failed, then we copy
                    Logger.logVerbose(LOG_TAG, "Renaming ${lbl}source file to destination failed, attempting to copy.")
                    copyFile = true
                }
            }

            // If moveFile is false or renameTo failed while moving
            if (copyFile) {
                Logger.logVerbose(LOG_TAG, "Attempting to copy source to destination.")

                // Create the dest file parent directory
                val error = createParentDirectoryFile("${lbl}dest file parent", destFilePath)
                if (error != null) return error

                if (srcFileType == FileType.DIRECTORY) {
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    org.apache.commons.io.FileUtils.copyDirectory(srcFile, destFile, true)
                } else if (srcFileType == FileType.SYMLINK) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        java.nio.file.Files.copy(
                            srcFile.toPath(),
                            destFile.toPath(),
                            LinkOption.NOFOLLOW_LINKS,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } else {
                        // read the target for the source file and create a symlink at dest
                        // source file metadata will be lost
                        val errorSymlink = createSymlinkFile("${lbl}dest", Os.readlink(srcFilePath), destFilePath)
                        if (errorSymlink != null) return errorSymlink
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        java.nio.file.Files.copy(
                            srcFile.toPath(),
                            destFile.toPath(),
                            LinkOption.NOFOLLOW_LINKS,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } else {
                        // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                        org.apache.commons.io.FileUtils.copyFile(srcFile, destFile, true)
                    }
                }
            }

            // If source file had to be moved
            if (moveFile) {
                // Delete the source file since copying would have succeeded
                val error = deleteFile("${lbl}source", srcFilePath, true)
                if (error != null) return error
            }

            Logger.logVerbose(LOG_TAG, "$mode successful.")
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_COPYING_OR_MOVING_FILE_FAILED_WITH_EXCEPTION
                .getError(e, "$mode ${lbl}file", srcFilePath, destFilePath, e.message)
        }

        return null
    }

    /**
     * Delete regular file at path.
     *
     * This function is a wrapper for {@link #deleteFile(String, String, boolean, boolean, int)}.
     *
     * @param label The optional label for file to delete. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to delete.
     * @param ignoreNonExistentFile The {@code boolean} that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @return Returns the {@code error} if deletion was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun deleteRegularFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileType.REGULAR.getValue())
    }

    /**
     * Delete directory file at path.
     *
     * This function is a wrapper for {@link #deleteFile(String, String, boolean, boolean, int)}.
     *
     * @param label The optional label for file to delete. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to delete.
     * @param ignoreNonExistentFile The {@code boolean} that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @return Returns the {@code error} if deletion was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun deleteDirectoryFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileType.DIRECTORY.getValue())
    }

    /**
     * Delete symlink file at path.
     *
     * This function is a wrapper for {@link #deleteFile(String, String, boolean, boolean, int)}.
     *
     * @param label The optional label for file to delete. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to delete.
     * @param ignoreNonExistentFile The {@code boolean} that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @return Returns the {@code error} if deletion was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun deleteSymlinkFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileType.SYMLINK.getValue())
    }

    /**
     * Delete socket file at path.
     *
     * This function is a wrapper for {@link #deleteFile(String, String, boolean, boolean, int)}.
     *
     * @param label The optional label for file to delete. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to delete.
     * @param ignoreNonExistentFile The {@code boolean} that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @return Returns the {@code error} if deletion was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun deleteSocketFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileType.SOCKET.getValue())
    }

    /**
     * Delete regular, directory or symlink file at path.
     *
     * This function is a wrapper for {@link #deleteFile(String, String, boolean, boolean, int)}.
     *
     * @param label The optional label for file to delete. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to delete.
     * @param ignoreNonExistentFile The {@code boolean} that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @return Returns the {@code error} if deletion was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun deleteFile(label: String?, filePath: String?, ignoreNonExistentFile: Boolean): Error? {
        return deleteFile(label, filePath, ignoreNonExistentFile, false, FileTypes.FILE_TYPE_NORMAL_FLAGS)
    }

    /**
     * Delete file at path.
     *
     * The {@code filePath} must be the canonical path to the file to be deleted since symlinks will
     * not be followed.
     * If the {@code filePath} is a canonical path to a directory, then any symlink files found under
     * the directory will be deleted, but not their targets.
     *
     * @param label The optional label for file to delete. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to delete.
     * @param ignoreNonExistentFile The {@code boolean} that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @param ignoreWrongFileType The {@code boolean} that decides if it should be considered an
     *                              error if file type is not one from {@code allowedFileTypeFlags}.
     * @param allowedFileTypeFlags The flags that are matched against the file's {@link FileType} to
     *                             see if it should be deleted or not. This is a safety measure to
     *                             prevent accidental deletion of the wrong type of file, like a
     *                             directory instead of a regular file. You can pass
     *                             {@link FileTypes#FILE_TYPE_ANY_FLAGS} to allow deletion of any file type.
     * @return Returns the {@code error} if deletion was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun deleteFile(
        label: String?,
        filePath: String?,
        ignoreNonExistentFile: Boolean,
        ignoreWrongFileType: Boolean,
        allowedFileTypeFlags: Int
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}file path", "deleteFile")
        }

        try {
            val file = File(filePath)
            var fileType = getFileType(filePath, false)

            Logger.logVerbose(LOG_TAG, "Processing delete of ${lbl}file at path \"$filePath\" of type \"${fileType.getName()}\"")

            // If file does not exist
            if (fileType == FileType.NO_EXIST) {
                // If delete is to be ignored if file does not exist
                return if (ignoreNonExistentFile) {
                    null
                } else {
                    val finalLbl = "${lbl}file meant to be deleted"
                    FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(finalLbl, filePath).setLabel(finalLbl)
                }
            }

            // If the file type of the file does not exist in the allowedFileTypeFlags
            if ((allowedFileTypeFlags and fileType.getValue()) <= 0) {
                // If wrong file type is to be ignored
                if (ignoreWrongFileType) {
                    Logger.logVerbose(
                        LOG_TAG,
                        "Ignoring deletion of ${lbl}file at path \"$filePath\" of type \"${fileType.getName()}\" not matching allowed file types: " +
                                FileTypes.convertFileTypeFlagsToNamesString(allowedFileTypeFlags)
                    )
                    return null
                }

                // Else return with error
                return FileUtilsErrno.ERRNO_FILE_NOT_AN_ALLOWED_FILE_TYPE.getError(
                    "${lbl}file meant to be deleted",
                    filePath,
                    fileType.getName(),
                    FileTypes.convertFileTypeFlagsToNamesString(allowedFileTypeFlags)
                )
            }

            Logger.logVerbose(LOG_TAG, "Deleting ${lbl}file at path \"$filePath\"")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                /*
                 * Try to use {@link SecureDirectoryStream} if available for safer directory
                 * deletion, it should be available for android >= 8.0
                 * https://guava.dev/releases/24.1-jre/api/docs/com/google/common/io/MoreFiles.html#deleteRecursively-java.nio.file.Path-com.google.common.io.RecursiveDeleteOption...-
                 * https://github.com/google/guava/issues/365
                 * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:libcore/ojluni/src/main/java/sun/nio/fs/UnixSecureDirectoryStream.java
                 *
                 * MoreUtils is marked with the @Beta annotation so the API may be removed in
                 * future but has been there for a few years now.
                 *
                 * If an exception is thrown, the exception message might not contain the full errors.
                 * Individual failures get added to suppressed throwables which can be extracted
                 * from the exception object by calling `Throwable[] getSuppressed()`. So just logging
                 * the exception message and stacktrace may not be enough, the suppressed throwables
                 * need to be logged as well, which the Logger class does if they are found in the
                 * exception added to the Error that's returned by this function.
                 * https://github.com/google/guava/blob/v30.1.1/guava/src/com/google/common/io/MoreFiles.java#L775
                 */
                //noinspection UnstableApiUsage
                com.google.common.io.MoreFiles.deleteRecursively(file.toPath(), RecursiveDeleteOption.ALLOW_INSECURE)
            } else {
                if (fileType == FileType.DIRECTORY) {
                    // deleteDirectory() instead of forceDelete() gets the files list first instead of walking directory tree, so seems safer
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    org.apache.commons.io.FileUtils.deleteDirectory(file)
                } else {
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    org.apache.commons.io.FileUtils.forceDelete(file)
                }
            }

            // If file still exists after deleting it
            fileType = getFileType(filePath, false)
            if (fileType != FileType.NO_EXIST) {
                return FileUtilsErrno.ERRNO_FILE_STILL_EXISTS_AFTER_DELETING.getError(
                    "${lbl}file meant to be deleted",
                    filePath
                )
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_DELETING_FILE_FAILED_WITH_EXCEPTION.getError(e, "${lbl}file", filePath, e.message)
        }

        return null
    }

    /**
     * Clear contents of directory at path without deleting the directory. If directory does not exist
     * it will be created automatically.
     *
     * This function is a wrapper for
     * {@link #clearDirectory(String, String)}.
     *
     * @param filePath The {@code path} for directory to clear.
     * @return Returns the {@code error} if clearing was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun clearDirectory(filePath: String?): Error? {
        return clearDirectory(null, filePath)
    }

    /**
     * Clear contents of directory at path without deleting the directory. If directory does not exist
     * it will be created automatically.
     *
     * The {@code filePath} must be the canonical path to a directory since symlinks will not be followed.
     * Any symlink files found under the directory will be deleted, but not their targets.
     *
     * @param label The optional label for directory to clear. This can optionally be {@code null}.
     * @param filePath The {@code path} for directory to clear.
     * @return Returns the {@code error} if clearing was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun clearDirectory(label: String?, filePath: String?): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}file path", "clearDirectory")
        }

        try {
            Logger.logVerbose(LOG_TAG, "Clearing ${lbl}directory at path \"$filePath\"")

            val file = File(filePath)
            val fileType = getFileType(filePath, false)

            // If file exists but not a directory file
            if (fileType != FileType.NO_EXIST && fileType != FileType.DIRECTORY) {
                return FileUtilsErrno.ERRNO_NON_DIRECTORY_FILE_FOUND.getError("${lbl}directory", filePath)
                    .setLabel("${lbl}directory")
            }

            // If directory exists, clear its contents
            if (fileType == FileType.DIRECTORY) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    /* If an exception is thrown, the exception message might not contain the full errors.
                     * Individual failures get added to suppressed throwables. */
                    //noinspection UnstableApiUsage
                    com.google.common.io.MoreFiles.deleteDirectoryContents(file.toPath(), RecursiveDeleteOption.ALLOW_INSECURE)
                } else {
                    // Will give runtime exceptions on android < 8 due to missing classes like java.nio.file.Path if org.apache.commons.io version > 2.5
                    org.apache.commons.io.FileUtils.cleanDirectory(File(filePath))
                }
            } else {
                // Else create it
                val error = createDirectoryFile(label, filePath)
                if (error != null) return error
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_CLEARING_DIRECTORY_FAILED_WITH_EXCEPTION.getError(e, "${lbl}directory", filePath, e.message)
        }

        return null
    }

    /**
     * Delete files under a directory older than x days.
     *
     * The {@code filePath} must be the canonical path to a directory since symlinks will not be followed.
     * Any symlink files found under the directory will be deleted, but not their targets.
     *
     * @param label The optional label for directory to clear. This can optionally be {@code null}.
     * @param filePath The {@code path} for directory to clear.
     * @param dirFilter  The optional filter to apply when finding subdirectories.
     *                   If this parameter is {@code null}, subdirectories will not be included in the
     *                   search. Use TrueFileFilter.INSTANCE to match all directories.
     * @param days The x amount of days before which files should be deleted. This must be `>=0`.
     * @param ignoreNonExistentFile The {@code boolean} that decides if it should be considered an
     *                              error if file to deleted doesn't exist.
     * @param allowedFileTypeFlags The flags that are matched against the file's {@link FileType} to
     *                             see if it should be deleted or not. This is a safety measure to
     *                             prevent accidental deletion of the wrong type of file, like a
     *                             directory instead of a regular file. You can pass
     *                             {@link FileTypes#FILE_TYPE_ANY_FLAGS} to allow deletion of any file type.
     * @return Returns the {@code error} if deleting was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun deleteFilesOlderThanXDays(
        label: String?,
        filePath: String?,
        dirFilter: IOFileFilter?,
        days: Int,
        ignoreNonExistentFile: Boolean,
        allowedFileTypeFlags: Int
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}file path", "deleteFilesOlderThanXDays")
        }
        if (days < 0) {
            return FunctionErrno.ERRNO_INVALID_PARAMETER.getError("${lbl}days", "deleteFilesOlderThanXDays", " It must be >= 0.")
        }

        try {
            Logger.logVerbose(LOG_TAG, "Deleting files under ${lbl}directory at path \"$filePath\" older than $days days")

            val file = File(filePath)
            val fileType = getFileType(filePath, false)

            // If file exists but not a directory file
            if (fileType != FileType.NO_EXIST && fileType != FileType.DIRECTORY) {
                return FileUtilsErrno.ERRNO_NON_DIRECTORY_FILE_FOUND.getError("${lbl}directory", filePath)
                    .setLabel("${lbl}directory")
            }

            // If file does not exist
            if (fileType == FileType.NO_EXIST) {
                // If delete is to be ignored if file does not exist
                return if (ignoreNonExistentFile) {
                    null
                } else {
                    val finalLbl = "${lbl}directory under which files had to be deleted"
                    FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(finalLbl, filePath).setLabel(finalLbl)
                }
            }

            // TODO: Use FileAttributes with support for atime (default), mtime, ctime. Add regex for ignoring file and dir absolute paths.
            // FIXME: iterateFiles() does not return subdirectories even with TrueFileFilter for file and dir.
            // FIXME: Empty directories remain

            // If directory exists, delete its contents
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DATE, -days)
            // AgeFileFilter seems to apply to symlink destination timestamp instead of symlink file itself
            val filesToDelete = org.apache.commons.io.FileUtils.iterateFiles(file, AgeFileFilter(calendar.time), dirFilter)
            while (filesToDelete.hasNext()) {
                val subFile = filesToDelete.next()
                val error = deleteFile("${lbl}directory sub", subFile.absolutePath, true, true, allowedFileTypeFlags)
                if (error != null) return error
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_DELETING_FILES_OLDER_THAN_X_DAYS_FAILED_WITH_EXCEPTION
                .getError(e, "${lbl}directory", filePath, days, e.message)
        }

        return null
    }

    /**
     * Read a text {@link String} from file at path with a specific {@link Charset} into {@code dataString}.
     *
     * @param label The optional label for file to read. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to read.
     * @param charset The {@link Charset} of the file. If this is {@code null},
     *                then default {@link Charset} will be used.
     * @param dataStringBuilder The {@code StringBuilder} to read data into.
     * @param ignoreNonExistentFile The {@code boolean} that decides if it should be considered an
     *                              error if file to read doesn't exist.
     * @return Returns the {@code error} if reading was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun readTextFromFile(
        label: String?,
        filePath: String?,
        charset: Charset?,
        dataStringBuilder: StringBuilder,
        ignoreNonExistentFile: Boolean
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}file path", "readStringFromFile")
        }

        Logger.logVerbose(LOG_TAG, "Reading text from ${lbl}file at path \"$filePath\"")

        val fileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType != FileType.NO_EXIST && fileType != FileType.REGULAR) {
            return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError("${lbl}file", filePath).setLabel("${lbl}file")
        }

        // If file does not exist
        if (fileType == FileType.NO_EXIST) {
            // If reading is to be ignored if file does not exist
            return if (ignoreNonExistentFile) {
                null
            } else {
                val finalLbl = "${lbl}file meant to be read"
                FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(finalLbl, filePath).setLabel(finalLbl)
            }
        }

        val cs = charset ?: Charset.defaultCharset()

        // Check if charset is supported
        val error = isCharsetSupported(cs)
        if (error != null) return error

        var fileInputStream: FileInputStream? = null
        var bufferedReader: BufferedReader? = null
        try {
            // Read text from file
            fileInputStream = FileInputStream(filePath)
            bufferedReader = BufferedReader(InputStreamReader(fileInputStream, cs))

            var receiveString: String?
            var firstLine = true
            while (bufferedReader.readLine().also { receiveString = it } != null) {
                if (!firstLine) {
                    dataStringBuilder.append("\n")
                } else {
                    firstLine = false
                }
                dataStringBuilder.append(receiveString)
            }

            Logger.logVerbose(
                LOG_TAG,
                Logger.getMultiLineLogStringEntry(
                    "String",
                    DataUtils.getTruncatedCommandOutput(
                        dataStringBuilder.toString(),
                        Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD,
                        true,
                        false,
                        true
                    ),
                    "-"
                )
            )
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_READING_TEXT_FROM_FILE_FAILED_WITH_EXCEPTION
                .getError(e, "${lbl}file", filePath, e.message)
        } finally {
            closeCloseable(fileInputStream)
            closeCloseable(bufferedReader)
        }

        return null
    }

    class ReadSerializableObjectResult(
        @JvmField val error: Error?,
        @JvmField val serializableObject: Serializable?
    )

    /**
     * Read a {@link Serializable} object from file at path.
     *
     * @param label The optional label for file to read. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to read.
     * @param readObjectType The {@link Class} of the object.
     * @param ignoreNonExistentFile The {@code boolean} that decides if it should be considered an
     *                              error if file to read doesn't exist.
     * @return Returns the {@code error} if reading was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun <T : Serializable> readSerializableObjectFromFile(
        label: String?,
        filePath: String?,
        readObjectType: Class<T>,
        ignoreNonExistentFile: Boolean
    ): ReadSerializableObjectResult {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return ReadSerializableObjectResult(
                FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError(
                    "${lbl}file path",
                    "readSerializableObjectFromFile"
                ), null
            )
        }

        Logger.logVerbose(LOG_TAG, "Reading serializable object from ${lbl}file at path \"$filePath\"")

        val serializableObject: T

        val fileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType != FileType.NO_EXIST && fileType != FileType.REGULAR) {
            return ReadSerializableObjectResult(
                FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError("${lbl}file", filePath)
                    .setLabel("${lbl}file"), null
            )
        }

        // If file does not exist
        if (fileType == FileType.NO_EXIST) {
            // If reading is to be ignored if file does not exist
            return if (ignoreNonExistentFile) {
                ReadSerializableObjectResult(null, null)
            } else {
                val finalLbl = "${lbl}file meant to be read"
                ReadSerializableObjectResult(
                    FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError(finalLbl, filePath).setLabel(finalLbl), null
                )
            }
        }

        var fileInputStream: FileInputStream? = null
        var objectInputStream: ObjectInputStream? = null
        try {
            // Read serializable object from file
            fileInputStream = FileInputStream(filePath)
            objectInputStream = ObjectInputStream(fileInputStream)
            serializableObject = readObjectType.cast(objectInputStream.readObject())!!
        } catch (e: Exception) {
            return ReadSerializableObjectResult(
                FileUtilsErrno.ERRNO_READING_SERIALIZABLE_OBJECT_TO_FILE_FAILED_WITH_EXCEPTION
                    .getError(e, "${lbl}file", filePath, e.message), null
            )
        } finally {
            closeCloseable(fileInputStream)
            closeCloseable(objectInputStream)
        }

        return ReadSerializableObjectResult(null, serializableObject)
    }

    /**
     * Write text {@code dataString} with a specific {@link Charset} to file at path.
     *
     * @param label The optional label for file to write. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to write.
     * @param charset The {@link Charset} of the {@code dataString}. If this is {@code null},
     *                then default {@link Charset} will be used.
     * @param dataString The data to write to file.
     * @param append The {@code boolean} that decides if file should be appended to or not.
     * @return Returns the {@code error} if writing was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun writeTextToFile(
        label: String?,
        filePath: String?,
        charset: Charset?,
        dataString: String?,
        append: Boolean
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}file path", "writeStringToFile")
        }

        Logger.logVerbose(
            LOG_TAG,
            Logger.getMultiLineLogStringEntry(
                "Writing text to ${lbl}file at path \"$filePath\"",
                DataUtils.getTruncatedCommandOutput(
                    dataString,
                    Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD,
                    true,
                    false,
                    true
                ),
                "-"
            )
        )

        val error = preWriteToFile(label, filePath)
        if (error != null) return error

        val cs = charset ?: Charset.defaultCharset()

        // Check if charset is supported
        val errorCharset = isCharsetSupported(cs)
        if (errorCharset != null) return errorCharset

        var fileOutputStream: FileOutputStream? = null
        var bufferedWriter: BufferedWriter? = null
        try {
            // Write text to file
            fileOutputStream = FileOutputStream(filePath, append)
            bufferedWriter = BufferedWriter(OutputStreamWriter(fileOutputStream, cs))

            bufferedWriter.write(dataString ?: "")
            bufferedWriter.flush()
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_WRITING_TEXT_TO_FILE_FAILED_WITH_EXCEPTION
                .getError(e, "${lbl}file", filePath, e.message)
        } finally {
            closeCloseable(fileOutputStream)
            closeCloseable(bufferedWriter)
        }

        return null
    }

    /**
     * Write the {@link Serializable} {@code serializableObject} to file at path.
     *
     * @param label The optional label for file to write. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to write.
     * @param serializableObject The object to write to file.
     * @return Returns the {@code error} if writing was not successful, otherwise {@code null}.
     */
    @JvmStatic
    fun <T : Serializable> writeSerializableObjectToFile(
        label: String?,
        filePath: String?,
        serializableObject: T?
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}file path", "writeSerializableObjectToFile")
        }

        Logger.logVerbose(LOG_TAG, "Writing serializable object to ${lbl}file at path \"$filePath\"")

        val error = preWriteToFile(label, filePath)
        if (error != null) return error

        var fileOutputStream: FileOutputStream? = null
        var objectOutputStream: ObjectOutputStream? = null
        try {
            // Write serializable object to file
            fileOutputStream = FileOutputStream(filePath)
            objectOutputStream = ObjectOutputStream(fileOutputStream)

            objectOutputStream.writeObject(serializableObject)
            objectOutputStream.flush()
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_WRITING_SERIALIZABLE_OBJECT_TO_FILE_FAILED_WITH_EXCEPTION
                .getError(e, "${lbl}file", filePath, e.message)
        } finally {
            closeCloseable(fileOutputStream)
            closeCloseable(objectOutputStream)
        }

        return null
    }

    private fun preWriteToFile(label: String?, filePath: String?): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        val fileType = getFileType(filePath, false)

        // If file exists but not a regular file
        if (fileType != FileType.NO_EXIST && fileType != FileType.REGULAR) {
            return FileUtilsErrno.ERRNO_NON_REGULAR_FILE_FOUND.getError("${lbl}file", filePath).setLabel("${lbl}file")
        }

        // Create the file parent directory
        val error = createParentDirectoryFile("${lbl}file parent", filePath)
        if (error != null) return error

        return null
    }

    /**
     * Check if a specific {@link Charset} is supported.
     *
     * @param charset The {@link Charset} to check.
     * @return Returns the {@code error} if charset is not supported or failed to check it, otherwise {@code null}.
     */
    @JvmStatic
    fun isCharsetSupported(charset: Charset?): Error? {
        if (charset == null) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("charset", "isCharsetSupported")
        }

        try {
            if (!Charset.isSupported(charset.name())) {
                return FileUtilsErrno.ERRNO_UNSUPPORTED_CHARSET.getError(charset.name())
            }
        } catch (e: Exception) {
            return FileUtilsErrno.ERRNO_CHECKING_IF_CHARSET_SUPPORTED_FAILED.getError(e, charset.name(), e.message)
        }

        return null
    }

    /**
     * Close a {@link Closeable} object if not {@code null} and ignore any exceptions raised.
     *
     * @param closeable The {@link Closeable} object to close.
     */
    @JvmStatic
    fun closeCloseable(closeable: Closeable?) {
        if (closeable != null) {
            try {
                closeable.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }

    /**
     * Set permissions for file at path. Existing permission outside the {@code permissionsToSet}
     * will be removed.
     *
     * @param filePath The {@code path} for file to set permissions to.
     * @param permissionsToSet The 3 character string that contains the "r", "w", "x" or "-" in-order.
     */
    @JvmStatic
    fun setFilePermissions(filePath: String?, permissionsToSet: String?) {
        setFilePermissions(null, filePath, permissionsToSet)
    }

    /**
     * Set permissions for file at path. Existing permission outside the {@code permissionsToSet}
     * will be removed.
     *
     * @param label The optional label for the file. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to set permissions to.
     * @param permissionsToSet The 3 character string that contains the "r", "w", "x" or "-" in-order.
     */
    @JvmStatic
    fun setFilePermissions(label: String?, filePath: String?, permissionsToSet: String?) {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) return

        if (!isValidPermissionString(permissionsToSet)) {
            Logger.logError(LOG_TAG, "Invalid permissionsToSet passed to setFilePermissions: \"$permissionsToSet\"")
            return
        }

        val file = File(filePath)

        if (permissionsToSet!!.contains("r")) {
            if (!file.canRead()) {
                Logger.logVerbose(LOG_TAG, "Setting read permissions for ${lbl}file at path \"$filePath\"")
                file.setReadable(true)
            }
        } else {
            if (file.canRead()) {
                Logger.logVerbose(LOG_TAG, "Removing read permissions for ${lbl}file at path \"$filePath\"")
                file.setReadable(false)
            }
        }

        if (permissionsToSet.contains("w")) {
            if (!file.canWrite()) {
                Logger.logVerbose(LOG_TAG, "Setting write permissions for ${lbl}file at path \"$filePath\"")
                file.setWritable(true)
            }
        } else {
            if (file.canWrite()) {
                Logger.logVerbose(LOG_TAG, "Removing write permissions for ${lbl}file at path \"$filePath\"")
                file.setWritable(false)
            }
        }

        if (permissionsToSet.contains("x")) {
            if (!file.canExecute()) {
                Logger.logVerbose(LOG_TAG, "Setting execute permissions for ${lbl}file at path \"$filePath\"")
                file.setExecutable(true)
            }
        } else {
            if (file.canExecute()) {
                Logger.logVerbose(LOG_TAG, "Removing execute permissions for ${lbl}file at path \"$filePath\"")
                file.setExecutable(false)
            }
        }
    }

    /**
     * Set missing permissions for file at path. Existing permission outside the {@code permissionsToSet}
     * will not be removed.
     *
     * @param filePath The {@code path} for file to set permissions to.
     * @param permissionsToSet The 3 character string that contains the "r", "w", "x" or "-" in-order.
     */
    @JvmStatic
    fun setMissingFilePermissions(filePath: String?, permissionsToSet: String?) {
        setMissingFilePermissions(null, filePath, permissionsToSet)
    }

    /**
     * Set missing permissions for file at path. Existing permission outside the {@code permissionsToSet}
     * will not be removed.
     *
     * @param label The optional label for the file. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to set permissions to.
     * @param permissionsToSet The 3 character string that contains the "r", "w", "x" or "-" in-order.
     */
    @JvmStatic
    fun setMissingFilePermissions(label: String?, filePath: String?, permissionsToSet: String?) {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) return

        if (!isValidPermissionString(permissionsToSet)) {
            Logger.logError(LOG_TAG, "Invalid permissionsToSet passed to setMissingFilePermissions: \"$permissionsToSet\"")
            return
        }

        val file = File(filePath)

        if (permissionsToSet!!.contains("r") && !file.canRead()) {
            Logger.logVerbose(LOG_TAG, "Setting missing read permissions for ${lbl}file at path \"$filePath\"")
            file.setReadable(true)
        }

        if (permissionsToSet.contains("w") && !file.canWrite()) {
            Logger.logVerbose(LOG_TAG, "Setting missing write permissions for ${lbl}file at path \"$filePath\"")
            file.setWritable(true)
        }

        if (permissionsToSet.contains("x") && !file.canExecute()) {
            Logger.logVerbose(LOG_TAG, "Setting missing execute permissions for ${lbl}file at path \"$filePath\"")
            file.setExecutable(true)
        }
    }

    /**
     * Checking missing permissions for file at path.
     *
     * @param filePath The {@code path} for file to check permissions for.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param ignoreIfNotExecutable The {@code boolean} that decides if missing executable permission
     *                              error is to be ignored.
     * @return Returns the {@code error} if validating permissions failed, otherwise {@code null}.
     */
    @JvmStatic
    fun checkMissingFilePermissions(filePath: String?, permissionsToCheck: String?, ignoreIfNotExecutable: Boolean): Error? {
        return checkMissingFilePermissions(null, filePath, permissionsToCheck, ignoreIfNotExecutable)
    }

    /**
     * Checking missing permissions for file at path.
     *
     * @param label The optional label for the file. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to check permissions for.
     * @param permissionsToCheck The 3 character string that contains the "r", "w", "x" or "-" in-order.
     * @param ignoreIfNotExecutable The {@code boolean} that decides if missing executable permission
     *                              error is to be ignored.
     * @return Returns the {@code error} if validating permissions failed, otherwise {@code null}.
     */
    @JvmStatic
    fun checkMissingFilePermissions(
        label: String?,
        filePath: String?,
        permissionsToCheck: String?,
        ignoreIfNotExecutable: Boolean
    ): Error? {
        val lbl = if (label.isNullOrEmpty()) "" else "$label "
        if (filePath.isNullOrEmpty()) {
            return FunctionErrno.ERRNO_NULL_OR_EMPTY_PARAMETER.getError("${lbl}file path", "checkMissingFilePermissions")
        }

        if (!isValidPermissionString(permissionsToCheck)) {
            Logger.logError(LOG_TAG, "Invalid permissionsToCheck passed to checkMissingFilePermissions: \"$permissionsToCheck\"")
            return FileUtilsErrno.ERRNO_INVALID_FILE_PERMISSIONS_STRING_TO_CHECK.getError()
        }

        val file = File(filePath)

        // If file is not readable
        if (permissionsToCheck!!.contains("r") && !file.canRead()) {
            return FileUtilsErrno.ERRNO_FILE_NOT_READABLE.getError("${lbl}file", filePath).setLabel("${lbl}file")
        }

        // If file is not writable
        if (permissionsToCheck.contains("w") && !file.canWrite()) {
            return FileUtilsErrno.ERRNO_FILE_NOT_WRITABLE.getError("${lbl}file", filePath).setLabel("${lbl}file")
        } else if (permissionsToCheck.contains("x") && !file.canExecute() && !ignoreIfNotExecutable) {
            // If file is not executable
            // This canExecute() will give "avc: granted { execute }" warnings for target sdk 29
            return FileUtilsErrno.ERRNO_FILE_NOT_EXECUTABLE.getError("${lbl}file", filePath).setLabel("${lbl}file")
        }

        return null
    }

    /**
     * Checks whether string exactly matches the 3 character permission string that
     * contains the "r", "w", "x" or "-" in-order.
     *
     * @param string The {@link String} to check.
     * @return Returns {@code true} if string exactly matches a permission string, otherwise {@code false}.
     */
    @JvmStatic
    fun isValidPermissionString(string: String?): Boolean {
        if (string.isNullOrEmpty()) return false
        return Pattern.compile("^([r-])[w-][x-]$", 0).matcher(string).matches()
    }

    /**
     * Get a {@link Error} that contains a shorter version of {@link Errno} message.
     *
     * @param error The original {@link Error} returned by one of the {@link FileUtils} functions.
     * @return Returns the shorter {@link Error} if one exists, otherwise original {@code error}.
     */
    @JvmStatic
    fun getShortFileUtilsError(error: Error): Error {
        val type = error.getType()
        if (FileUtilsErrno.TYPE != type) return error

        val shortErrno = FileUtilsErrno.ERRNO_SHORT_MAPPING[Errno.valueOf(type, error.getCode())] ?: return error

        val throwables = error.getThrowablesList()
        return if (throwables.isEmpty()) {
            shortErrno.getError(DataUtils.getDefaultIfNull(error.getLabel(), "file"))
        } else {
            shortErrno.getError(throwables, error.getLabel(), "file")
        }
    }

    /**
     * Get file dirname for file at {@code filePath}.
     *
     * @param filePath The {@code path} for file.
     * @return Returns the file dirname if not {@code null}.
     */
    @JvmStatic
    fun getFileDirname(filePath: String?): String? {
        if (DataUtils.isNullOrEmpty(filePath)) return null
        val lastSlash = filePath!!.lastIndexOf('/')
        return if (lastSlash == -1) null else filePath.substring(0, lastSlash)
    }

    /**
     * Get file basename for file at {@code filePath}.
     *
     * @param filePath The {@code path} for file.
     * @return Returns the file basename if not {@code null}.
     */
    @JvmStatic
    fun getFileBasename(filePath: String?): String? {
        if (DataUtils.isNullOrEmpty(filePath)) return null
        val lastSlash = filePath!!.lastIndexOf('/')
        return if (lastSlash == -1) filePath else filePath.substring(lastSlash + 1)
    }

    /**
     * Get file basename for file at {@code filePath} without extension.
     *
     * @param filePath The {@code path} for file.
     * @return Returns the file basename without extension if not {@code null}.
     */
    @JvmStatic
    fun getFileBasenameWithoutExtension(filePath: String?): String? {
        val fileBasename = getFileBasename(filePath)
        if (DataUtils.isNullOrEmpty(fileBasename)) return null
        val lastDot = fileBasename!!.lastIndexOf('.')
        return if (lastDot == -1) fileBasename else fileBasename.substring(0, lastDot)
    }

}
