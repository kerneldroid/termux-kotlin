package com.termux.app.models

enum class UserAction(val actionName: String) {

    ABOUT("about"),
    REPORT_ISSUE_FROM_TRANSCRIPT("report issue from transcript");

    fun getName(): String = actionName
}
