package com.termux.shared.shell.command.environment

class ShellEnvironmentVariable @JvmOverloads constructor(
    /** The name for environment variable */
    @JvmField var name: String?,

    /** The value for environment variable */
    @JvmField var value: String?,

    /** If environment variable [value] is already escaped. */
    @JvmField var escaped: Boolean = false
) : Comparable<ShellEnvironmentVariable> {

    override fun compareTo(other: ShellEnvironmentVariable): Int {
        val thisName = name
        val otherName = other.name
        return when {
            thisName === otherName -> 0
            thisName == null -> -1
            otherName == null -> 1
            else -> thisName.compareTo(otherName)
        }
    }
}
