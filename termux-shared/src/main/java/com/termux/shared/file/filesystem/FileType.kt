package com.termux.shared.file.filesystem

/** The [Enum] that defines file types. */
enum class FileType(private val typeName: String, private val typeValue: Int) {

    NO_EXIST("no exist", 0),    // 00000000
    REGULAR("regular", 1),      // 00000001
    DIRECTORY("directory", 2),  // 00000010
    SYMLINK("symlink", 4),      // 00000100
    SOCKET("socket", 8),        // 00001000
    CHARACTER("character", 16), // 00010000
    FIFO("fifo", 32),           // 00100000
    BLOCK("block", 64),         // 01000000
    UNKNOWN("unknown", 128);    // 10000000

    fun getName(): String {
        return typeName
    }

    fun getValue(): Int {
        return typeValue
    }

}
