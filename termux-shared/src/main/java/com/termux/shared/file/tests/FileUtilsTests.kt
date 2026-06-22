package com.termux.shared.file.tests

import android.content.Context
import com.termux.shared.errors.Errno
import com.termux.shared.errors.Error
import com.termux.shared.file.FileUtils
import com.termux.shared.file.FileUtilsErrno
import com.termux.shared.logger.Logger
import java.io.File
import java.nio.charset.Charset

class FileUtilsTests {

    companion object {

        private const val LOG_TAG = "FileUtilsTests"

        /**
         * Run basic tests for {@link FileUtils} class.
         *
         * Move tests need to be written, specially for failures.
         *
         * The log level must be set to verbose.
         *
         * Run at app startup like in an activity
         * FileUtilsTests.runTests(this, TermuxConstants.TERMUX_HOME_DIR_PATH + "/FileUtilsTests");
         *
         * @param context The {@link Context} for operations.
         */
        @JvmStatic
        fun runTests(context: Context, testRootDirectoryPath: String) {
            try {
                Logger.logInfo(LOG_TAG, "Running tests")
                Logger.logInfo(LOG_TAG, "testRootDirectoryPath: \"$testRootDirectoryPath\"")

                val fileUtilsTestsDirectoryCanonicalPath = FileUtils.getCanonicalPath(testRootDirectoryPath, null)
                assertEqual("FileUtilsTests directory path is not a canonical path", testRootDirectoryPath, fileUtilsTestsDirectoryCanonicalPath)

                runTestsInner(testRootDirectoryPath)
                Logger.logInfo(LOG_TAG, "All tests successful")
            } catch (e: Exception) {
                Logger.logErrorExtended(LOG_TAG, e.message)
                Logger.showToast(context, if (e.message != null) e.message!!.replace("(?s)\nFull Error:\n.*".toRegex(), "") else null, true)
            }
        }

        @Throws(Exception::class)
        private fun runTestsInner(testRootDirectoryPath: String) {
            var error: Error?
            var label: String
            var path: String

            /*
             * - dir1
             *  - sub_dir1
             *  - sub_reg1
             *  - sub_sym1 (absolute symlink to dir2)
             *  - sub_sym2 (copy of sub_sym1 for symlink to dir2)
             *  - sub_sym3 (relative symlink to dir4)
             * - dir2
             *  - sub_reg1
             *  - sub_reg2 (copy of dir2/sub_reg1)
             * - dir3 (copy of dir1)
             * - dir4 (moved from dir3)
             */

            val dir1Label = "dir1"
            val dir1Path = "$testRootDirectoryPath/dir1"

            val dir1SubDir1Label = "dir1/sub_dir1"
            val dir1SubDir1Path = "$dir1Path/sub_dir1"

            val dir1SubDir2Label = "dir1/sub_dir2"
            val dir1SubDir2Path = "$dir1Path/sub_dir2"

            val dir1SubDir3Label = "dir1/sub_dir3"
            val dir1SubDir3Path = "$dir1Path/sub_dir3"

            val dir1SubDir3SubReg1Label = "dir1/sub_dir3/sub_reg1"
            val dir1SubDir3SubReg1Path = "$dir1SubDir3Path/sub_reg1"

            val dir1SubReg1Label = "dir1/sub_reg1"
            val dir1SubReg1Path = "$dir1Path/sub_reg1"

            val dir1SubSym1Label = "dir1/sub_sym1"
            val dir1SubSym1Path = "$dir1Path/sub_sym1"

            val dir1SubSym2Label = "dir1/sub_sym2"
            val dir1SubSym2Path = "$dir1Path/sub_sym2"

            val dir1SubSym3Label = "dir1/sub_sym3"
            val dir1SubSym3Path = "$dir1Path/sub_sym3"


            val dir2Label = "dir2"
            val dir2Path = "$testRootDirectoryPath/dir2"

            val dir2SubReg1Label = "dir2/sub_reg1"
            val dir2SubReg1Path = "$dir2Path/sub_reg1"

            val dir2SubReg2Label = "dir2/sub_reg2"
            val dir2SubReg2Path = "$dir2Path/sub_reg2"


            val dir3Label = "dir3"
            val dir3Path = "$testRootDirectoryPath/dir3"

            val dir4Label = "dir4"
            val dir4Path = "$testRootDirectoryPath/dir4"


            // Create or clear test root directory file
            label = "testRootDirectoryPath"
            error = FileUtils.clearDirectory(label, testRootDirectoryPath)
            assertEqual("Failed to create $label directory file", null, error)

            if (!FileUtils.directoryFileExists(testRootDirectoryPath, false))
                throwException("The $label directory file does not exist as expected after creation")


            // Create dir1 directory file
            error = FileUtils.createDirectoryFile(dir1Label, dir1Path)
            assertEqual("Failed to create $dir1Label directory file", null, error)

            // Create dir2 directory file
            error = FileUtils.createDirectoryFile(dir2Label, dir2Path)
            assertEqual("Failed to create $dir2Label directory file", null, error)


            // Create dir1/sub_dir1 directory file
            label = dir1SubDir1Label
            path = dir1SubDir1Path
            error = FileUtils.createDirectoryFile(label, path)
            assertEqual("Failed to create $label directory file", null, error)
            if (!FileUtils.directoryFileExists(path, false))
                throwException("The $label directory file does not exist as expected after creation")

            // Create dir1/sub_reg1 regular file
            label = dir1SubReg1Label
            path = dir1SubReg1Path
            error = FileUtils.createRegularFile(label, path)
            assertEqual("Failed to create $label regular file", null, error)
            if (!FileUtils.regularFileExists(path, false))
                throwException("The $label regular file does not exist as expected after creation")

            // Create dir1/sub_sym1 -> dir2 absolute symlink file
            label = dir1SubSym1Label
            path = dir1SubSym1Path
            error = FileUtils.createSymlinkFile(label, dir2Path, path)
            assertEqual("Failed to create $label symlink file", null, error)
            if (!FileUtils.symlinkFileExists(path))
                throwException("The $label symlink file does not exist as expected after creation")

            // Copy dir1/sub_sym1 symlink file to dir1/sub_sym2
            label = dir1SubSym2Label
            path = dir1SubSym2Path
            error = FileUtils.copySymlinkFile(label, dir1SubSym1Path, path, false)
            assertEqual("Failed to copy $dir1SubSym1Label symlink file to $label", null, error)
            if (!FileUtils.symlinkFileExists(path))
                throwException("The $label symlink file does not exist as expected after copying it from $dir1SubSym1Label")
            if (File(path).canonicalPath != dir2Path)
                throwException("The $label symlink file does not point to $dir2Label")


            // Write "line1" to dir2/sub_reg1 regular file
            label = dir2SubReg1Label
            path = dir2SubReg1Path
            error = FileUtils.writeTextToFile(label, path, Charset.defaultCharset(), "line1", false)
            assertEqual("Failed to write string to $label file with append mode false", null, error)
            if (!FileUtils.regularFileExists(path, false))
                throwException("The $label file does not exist as expected after writing to it with append mode false")

            // Write "line2" to dir2/sub_reg1 regular file
            error = FileUtils.writeTextToFile(label, path, Charset.defaultCharset(), "\nline2", true)
            assertEqual("Failed to write string to $label file with append mode true", null, error)

            // Read dir2/sub_reg1 regular file
            val dataStringBuilder = java.lang.StringBuilder()
            error = FileUtils.readTextFromFile(label, path, Charset.defaultCharset(), dataStringBuilder, false)
            assertEqual("Failed to read from $label file", null, error)
            assertEqual("The data read from $label file in not as expected", "line1\nline2", dataStringBuilder.toString())

            // Copy dir2/sub_reg1 regular file to dir2/sub_reg2 file
            label = dir2SubReg2Label
            path = dir2SubReg2Path
            error = FileUtils.copyRegularFile(label, dir2SubReg1Path, path, false)
            assertEqual("Failed to copy $dir2SubReg1Label regular file to $label", null, error)
            if (!FileUtils.regularFileExists(path, false))
                throwException("The $label regular file does not exist as expected after copying it from $dir2SubReg1Label")


            // Copy dir1 directory file to dir3
            label = dir3Label
            path = dir3Path
            error = FileUtils.copyDirectoryFile(label, dir2Path, path, false)
            assertEqual("Failed to copy $dir2Label directory file to $label", null, error)
            if (!FileUtils.directoryFileExists(path, false))
                throwException("The $label directory file does not exist as expected after copying it from $dir2Label")

            // Copy dir1 directory file to dir3 again to test overwrite
            label = dir3Label
            path = dir3Path
            error = FileUtils.copyDirectoryFile(label, dir2Path, path, false)
            assertEqual("Failed to copy $dir2Label directory file to $label", null, error)
            if (!FileUtils.directoryFileExists(path, false))
                throwException("The $label directory file does not exist as expected after copying it from $dir2Label")

            // Move dir3 directory file to dir4
            label = dir4Label
            path = dir4Path
            error = FileUtils.moveDirectoryFile(label, dir3Path, path, false)
            assertEqual("Failed to move $dir3Label directory file to $label", null, error)
            if (!FileUtils.directoryFileExists(path, false))
                throwException("The $label directory file does not exist as expected after copying it from $dir3Label")


            // Create dir1/sub_sym3 -> dir4 relative symlink file
            label = dir1SubSym3Label
            path = dir1SubSym3Path
            error = FileUtils.createSymlinkFile(label, "../dir4", path)
            assertEqual("Failed to create $label symlink file", null, error)
            if (!FileUtils.symlinkFileExists(path))
                throwException("The $label symlink file does not exist as expected after creation")

            // Create dir1/sub_sym3 -> dirX relative dangling symlink file
            // This is to ensure that symlinkFileExists returns true if a symlink file exists but is dangling
            label = dir1SubSym3Label
            path = dir1SubSym3Path
            error = FileUtils.createSymlinkFile(label, "../dirX", path)
            assertEqual("Failed to create $label symlink file", null, error)
            if (!FileUtils.symlinkFileExists(path))
                throwException("The $label dangling symlink file does not exist as expected after creation")


            // Delete dir1/sub_sym2 symlink file
            label = dir1SubSym2Label
            path = dir1SubSym2Path
            error = FileUtils.deleteSymlinkFile(label, path, false)
            assertEqual("Failed to delete $label symlink file", null, error)
            if (FileUtils.fileExists(path, false))
                throwException("The $label symlink file still exist after deletion")

            // Check if dir2 directory file still exists after deletion of dir1/sub_sym2 since it was a symlink to dir2
            // When deleting a symlink file, its target must not be deleted
            label = dir2Label
            path = dir2Path
            if (!FileUtils.directoryFileExists(path, false))
                throwException("The $label directory file has unexpectedly been deleted after deletion of $dir1SubSym2Label")


            // Delete dir1 directory file
            label = dir1Label
            path = dir1Path
            error = FileUtils.deleteDirectoryFile(label, path, false)
            assertEqual("Failed to delete $label directory file", null, error)
            if (FileUtils.fileExists(path, false))
                throwException("The $label directory file still exist after deletion")


            // Check if dir2 directory file and dir2/sub_reg1 regular file still exist after deletion of
            // dir1 since there was a dir1/sub_sym1 symlink to dir2 in it
            // When deleting a directory, any targets of symlinks must not be deleted when deleting symlink files
            label = dir2Label
            path = dir2Path
            if (!FileUtils.directoryFileExists(path, false))
                throwException("The $label directory file has unexpectedly been deleted after deletion of $dir1Label")
            label = dir2SubReg1Label
            path = dir2SubReg1Path
            if (!FileUtils.fileExists(path, false))
                throwException("The $label regular file has unexpectedly been deleted after deletion of $dir1Label")


            // Delete dir2/sub_reg1 regular file
            label = dir2SubReg1Label
            path = dir2SubReg1Path
            error = FileUtils.deleteRegularFile(label, path, false)
            assertEqual("Failed to delete $label regular file", null, error)
            if (FileUtils.fileExists(path, false))
                throwException("The $label regular file still exist after deletion")


            val ignoredSubFilePaths = listOf(dir1SubDir2Path, dir1SubDir3SubReg1Path)

            // Create dir1 directory file
            error = FileUtils.createDirectoryFile(dir1Label, dir1Path)
            assertEqual("Failed to create $dir1Label directory file", null, error)

            // Test empty dir
            error = FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles(dir1Label, dir1Path, ignoredSubFilePaths, false)
            assertEqual("Failed to validate if $dir1Label directory file is empty", null, error)


            // Create dir1/sub_dir3 directory file
            label = dir1SubDir3Label
            path = dir1SubDir3Path
            error = FileUtils.createDirectoryFile(label, path)
            assertEqual("Failed to create $label directory file", null, error)
            if (!FileUtils.directoryFileExists(path, false))
                throwException("The $label directory file does not exist as expected after creation")

            // Test parent dir existing of non existing ignored regular file
            error = FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles(dir1Label, dir1Path, ignoredSubFilePaths, false)
            assertErrnoEqual("Failed to validate if $dir1Label directory file is empty with parent dir existing of non existing ignored regular file", FileUtilsErrno.ERRNO_NON_EMPTY_DIRECTORY_FILE, error)


            // Write "line1" to dir1/sub_dir3/sub_reg1 regular file
            label = dir1SubDir3SubReg1Label
            path = dir1SubDir3SubReg1Path
            error = FileUtils.writeTextToFile(label, path, Charset.defaultCharset(), "line1", false)
            assertEqual("Failed to write string to $label file with append mode false", null, error)
            if (!FileUtils.regularFileExists(path, false))
                throwException("The $label file does not exist as expected after writing to it with append mode false")

            // Test ignored regular file existing
            error = FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles(dir1Label, dir1Path, ignoredSubFilePaths, false)
            assertEqual("Failed to validate if $dir1Label directory file is empty with ignored regular file existing", null, error)


            // Create dir1/sub_dir2 directory file
            label = dir1SubDir2Label
            path = dir1SubDir2Path
            error = FileUtils.createDirectoryFile(label, path)
            assertEqual("Failed to create $label directory file", null, error)
            if (!FileUtils.directoryFileExists(path, false))
                throwException("The $label directory file does not exist as expected after creation")

            // Test ignored dir file existing
            error = FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles(dir1Label, dir1Path, ignoredSubFilePaths, false)
            assertEqual("Failed to validate if $dir1Label directory file is empty with ignored dir file existing", null, error)


            // Create dir1/sub_dir1 directory file
            label = dir1SubDir1Label
            path = dir1SubDir1Path
            error = FileUtils.createDirectoryFile(label, path)
            assertEqual("Failed to create $label directory file", null, error)
            if (!FileUtils.directoryFileExists(path, false))
                throwException("The $label directory file does not exist as expected after creation")

            // Test non ignored dir file existing
            error = FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles(dir1Label, dir1Path, ignoredSubFilePaths, false)
            assertErrnoEqual("Failed to validate if $dir1Label directory file is empty with non ignored dir file existing", FileUtilsErrno.ERRNO_NON_EMPTY_DIRECTORY_FILE, error)


            // Delete dir1 directory file
            label = dir1Label
            path = dir1Path
            error = FileUtils.deleteDirectoryFile(label, path, false)
            assertEqual("Failed to delete $label directory file", null, error)


            FileUtils.getFileType("/dev/ptmx", false)
            FileUtils.getFileType("/dev/null", false)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun assertEqual(message: String, expected: String?, actual: Error?) {
            val actualString = actual?.getMessage()
            if (!equalsRegardingNull(expected, actualString)) {
                throwException(message + "\nexpected: \"" + expected + "\"\nactual: \"" + actualString + "\"\nFull Error:\n" + (actual?.toString() ?: ""))
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun assertEqual(message: String, expected: String?, actual: String?) {
            if (!equalsRegardingNull(expected, actual)) {
                throwException("$message\nexpected: \"$expected\"\nactual: \"$actual\"")
            }
        }

        private fun equalsRegardingNull(expected: String?, actual: String?): Boolean {
            return expected == actual
        }

        @JvmStatic
        @Throws(Exception::class)
        fun assertErrnoEqual(message: String, expected: Errno?, actual: Error?) {
            if ((expected == null && actual != null) || (expected != null && !expected.equalsErrorTypeAndCode(actual))) {
                throwException(message + "\nexpected: \"" + expected + "\"\nactual: \"" + actual + "\"\nFull Error:\n" + (actual?.toString() ?: ""))
            }
        }

        private fun isEquals(expected: String, actual: String): Boolean {
            return expected == actual
        }

        @JvmStatic
        @Throws(Exception::class)
        fun throwException(message: String) {
            throw Exception(message)
        }
    }
}
