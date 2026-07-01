package com.termux.shared.termux.models

enum class UserAction(private val actionName: String) {
    CRASH_REPORT("crash report"),
    PLUGIN_EXECUTION_COMMAND("plugin execution command");

    fun getName(): String {
        return actionName
    }
}
