package com.termux.shared.file.filesystem

import android.os.Build
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.HashSet
import java.util.concurrent.TimeUnit

/**
 * Unix implementation of PosixFileAttributes.
 * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:libcore/ojluni/src/main/java/sun/nio/fs/UnixFileAttributes.java
 */
class FileAttributes private constructor(
    private val filePath: String?,
    private val fileDescriptor: FileDescriptor?
) {

    private var st_mode = 0
    private var st_ino: Long = 0
    private var st_dev: Long = 0
    private var st_rdev: Long = 0
    private var st_nlink: Long = 0
    private var st_uid = 0
    private var st_gid = 0
    private var st_size: Long = 0
    private var st_blksize: Long = 0
    private var st_blocks: Long = 0
    private var st_atime_sec: Long = 0
    private var st_atime_nsec: Long = 0
    private var st_mtime_sec: Long = 0
    private var st_mtime_nsec: Long = 0
    private var st_ctime_sec: Long = 0
    private var st_ctime_nsec: Long = 0

    // created lazily
    @Volatile
    private var owner: String? = null

    @Volatile
    private var group: String? = null

    @Volatile
    private var key: FileKey? = null

    fun file(): String? {
        return if (filePath != null) {
            filePath
        } else if (fileDescriptor != null) {
            fileDescriptor.toString()
        } else {
            null
        }
    }

    fun isSameFile(attrs: FileAttributes): Boolean {
        return st_ino == attrs.st_ino && st_dev == attrs.st_dev
    }

    fun mode(): Int {
        return st_mode
    }

    fun blksize(): Long {
        return st_blksize
    }

    fun blocks(): Long {
        return st_blocks
    }

    fun ino(): Long {
        return st_ino
    }

    fun dev(): Long {
        return st_dev
    }

    fun rdev(): Long {
        return st_rdev
    }

    fun nlink(): Long {
        return st_nlink
    }

    fun uid(): Int {
        return st_uid
    }

    fun gid(): Int {
        return st_gid
    }

    fun lastAccessTime(): FileTime {
        return toFileTime(st_atime_sec, st_atime_nsec)
    }

    fun lastModifiedTime(): FileTime {
        return toFileTime(st_mtime_sec, st_mtime_nsec)
    }

    fun lastChangeTime(): FileTime {
        return toFileTime(st_ctime_sec, st_ctime_nsec)
    }

    fun creationTime(): FileTime {
        return lastModifiedTime()
    }

    val isRegularFile: Boolean
        get() = (st_mode and UnixConstants.S_IFMT) == UnixConstants.S_IFREG

    val isDirectory: Boolean
        get() = (st_mode and UnixConstants.S_IFMT) == UnixConstants.S_IFDIR

    val isSymbolicLink: Boolean
        get() = (st_mode and UnixConstants.S_IFMT) == UnixConstants.S_IFLNK

    val isCharacter: Boolean
        get() = (st_mode and UnixConstants.S_IFMT) == UnixConstants.S_IFCHR

    val isFifo: Boolean
        get() = (st_mode and UnixConstants.S_IFMT) == UnixConstants.S_IFIFO

    val isSocket: Boolean
        get() = (st_mode and UnixConstants.S_IFMT) == UnixConstants.S_IFSOCK

    val isBlock: Boolean
        get() = (st_mode and UnixConstants.S_IFMT) == UnixConstants.S_IFBLK

    val isOther: Boolean
        get() {
            val type = st_mode and UnixConstants.S_IFMT
            return (type != UnixConstants.S_IFREG &&
                    type != UnixConstants.S_IFDIR &&
                    type != UnixConstants.S_IFLNK)
        }

    val isDevice: Boolean
        get() {
            val type = st_mode and UnixConstants.S_IFMT
            return (type == UnixConstants.S_IFCHR ||
                    type == UnixConstants.S_IFBLK ||
                    type == UnixConstants.S_IFIFO)
        }

    fun size(): Long {
        return st_size
    }

    fun fileKey(): FileKey {
        var result = key
        if (result == null) {
            synchronized(this) {
                result = key
                if (result == null) {
                    result = FileKey(st_dev, st_ino)
                    key = result
                }
            }
        }
        return result!!
    }

    fun owner(): String {
        var result = owner
        if (result == null) {
            synchronized(this) {
                result = owner
                if (result == null) {
                    result = st_uid.toString()
                    owner = result
                }
            }
        }
        return result!!
    }

    fun group(): String {
        var result = group
        if (result == null) {
            synchronized(this) {
                result = group
                if (result == null) {
                    result = st_gid.toString()
                    group = result
                }
            }
        }
        return result!!
    }

    fun permissions(): Set<FilePermission> {
        val bits = st_mode and UnixConstants.S_IAMB
        val perms = HashSet<FilePermission>()

        if ((bits and UnixConstants.S_IRUSR) > 0) perms.add(FilePermission.OWNER_READ)
        if ((bits and UnixConstants.S_IWUSR) > 0) perms.add(FilePermission.OWNER_WRITE)
        if ((bits and UnixConstants.S_IXUSR) > 0) perms.add(FilePermission.OWNER_EXECUTE)

        if ((bits and UnixConstants.S_IRGRP) > 0) perms.add(FilePermission.GROUP_READ)
        if ((bits and UnixConstants.S_IWGRP) > 0) perms.add(FilePermission.GROUP_WRITE)
        if ((bits and UnixConstants.S_IXGRP) > 0) perms.add(FilePermission.GROUP_EXECUTE)

        if ((bits and UnixConstants.S_IROTH) > 0) perms.add(FilePermission.OTHERS_READ)
        if ((bits and UnixConstants.S_IWOTH) > 0) perms.add(FilePermission.OTHERS_WRITE)
        if ((bits and UnixConstants.S_IXOTH) > 0) perms.add(FilePermission.OTHERS_EXECUTE)

        return perms
    }

    fun loadFromStructStat(structStat: StructStat) {
        this.st_mode = structStat.st_mode
        this.st_ino = structStat.st_ino
        this.st_dev = structStat.st_dev
        this.st_rdev = structStat.st_rdev
        this.st_nlink = structStat.st_nlink
        this.st_uid = structStat.st_uid
        this.st_gid = structStat.st_gid
        this.st_size = structStat.st_size
        this.st_blksize = structStat.st_blksize
        this.st_blocks = structStat.st_blocks

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            this.st_atime_sec = structStat.st_atim.tv_sec
            this.st_atime_nsec = structStat.st_atim.tv_nsec
            this.st_mtime_sec = structStat.st_mtim.tv_sec
            this.st_mtime_nsec = structStat.st_mtim.tv_nsec
            this.st_ctime_sec = structStat.st_ctim.tv_sec
            this.st_ctime_nsec = structStat.st_ctim.tv_nsec
        } else {
            this.st_atime_sec = structStat.st_atime
            this.st_atime_nsec = 0
            this.st_mtime_sec = structStat.st_mtime
            this.st_mtime_nsec = 0
            this.st_ctime_sec = structStat.st_ctime
            this.st_ctime_nsec = 0
        }
    }

    fun getFileString(): String {
        return "File: `" + file() + "`"
    }

    fun getTypeString(): String {
        return "Type: `" + FileTypes.getFileType(this).name + "`"
    }

    fun getSizeString(): String {
        return "Size: `" + size() + "`"
    }

    fun getBlocksString(): String {
        return "Blocks: `" + blocks() + "`"
    }

    fun getIOBlockString(): String {
        return "IO Block: `" + blksize() + "`"
    }

    fun getDeviceString(): String {
        return "Device: `" + java.lang.Long.toHexString(st_dev) + "`"
    }

    fun getInodeString(): String {
        return "Inode: `" + st_ino + "`"
    }

    fun getLinksString(): String {
        return "Links: `" + nlink() + "`"
    }

    fun getDeviceTypeString(): String {
        return "Device Type: `" + rdev() + "`"
    }

    fun getOwnerString(): String {
        return "Owner: `" + owner() + "`"
    }

    fun getGroupString(): String {
        return "Group: `" + group() + "`"
    }

    fun getPermissionString(): String {
        return "Permissions: `" + FilePermissions.toString(permissions()) + "`"
    }

    fun getAccessTimeString(): String {
        return "Access Time: `" + lastAccessTime() + "`"
    }

    fun getModifiedTimeString(): String {
        return "Modified Time: `" + lastModifiedTime() + "`"
    }

    fun getChangeTimeString(): String {
        return "Change Time: `" + lastChangeTime() + "`"
    }

    override fun toString(): String {
        return getFileAttributesLogString(this)
    }

    companion object {
        // get the FileAttributes for a given file
        @JvmStatic
        @Throws(IOException::class)
        fun get(filePath: String?, followLinks: Boolean): FileAttributes {
            val fileAttributes = if (filePath.isNullOrEmpty()) {
                FileAttributes(null, null)
            } else {
                FileAttributes(File(filePath).absolutePath, null)
            }

            if (followLinks) {
                NativeDispatcher.stat(filePath, fileAttributes)
            } else {
                NativeDispatcher.lstat(filePath, fileAttributes)
            }

            return fileAttributes
        }

        // get the FileAttributes for an open file
        @JvmStatic
        @Throws(IOException::class)
        fun get(fileDescriptor: FileDescriptor?): FileAttributes {
            val fileAttributes = FileAttributes(null, fileDescriptor)
            NativeDispatcher.fstat(fileDescriptor, fileAttributes)
            return fileAttributes
        }

        private fun toFileTime(sec: Long, nsec: Long): FileTime {
            return if (nsec == 0L) {
                FileTime.from(sec, TimeUnit.SECONDS)
            } else {
                // truncate to microseconds to avoid overflow with timestamps
                // way out into the future. We can re-visit this if FileTime
                // is updated to define a from(secs,nsecs) method.
                val micro = sec * 1000000L + nsec / 1000L
                FileTime.from(micro, TimeUnit.MICROSECONDS)
            }
        }

        @JvmStatic
        fun getFileAttributesLogString(fileAttributes: FileAttributes?): String {
            if (fileAttributes == null) return "null"

            val logString = StringBuilder()

            logString.append(fileAttributes.getFileString())

            logString.append("\n").append(fileAttributes.getTypeString())

            logString.append("\n").append(fileAttributes.getSizeString())
            logString.append("\n").append(fileAttributes.getBlocksString())
            logString.append("\n").append(fileAttributes.getIOBlockString())

            logString.append("\n").append(fileAttributes.getDeviceString())
            logString.append("\n").append(fileAttributes.getInodeString())
            logString.append("\n").append(fileAttributes.getLinksString())

            if (fileAttributes.isBlock || fileAttributes.isCharacter) {
                logString.append("\n").append(fileAttributes.getDeviceTypeString())
            }

            logString.append("\n").append(fileAttributes.getOwnerString())
            logString.append("\n").append(fileAttributes.getGroupString())
            logString.append("\n").append(fileAttributes.getPermissionString())

            logString.append("\n").append(fileAttributes.getAccessTimeString())
            logString.append("\n").append(fileAttributes.getModifiedTimeString())
            logString.append("\n").append(fileAttributes.getChangeTimeString())

            return logString.toString()
        }
    }
}
