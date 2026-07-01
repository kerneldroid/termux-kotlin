package com.termux.shared.shell.command.environment

import com.termux.shared.errors.Error
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.shell.command.environment.UnixShellEnvironment.Companion.ENV_HOME
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap

class ShellEnvironmentUtils {

    companion object {
        private const val LOG_TAG = "ShellEnvironmentUtils"

        /**
         * Convert environment [HashMap] to `environ` [List].
         *
         * The items in the environ will have the format `name=value`.
         *
         * Check [isValidEnvironmentVariableName] and [isValidEnvironmentVariableValue]
         * for valid variable names and values.
         *
         * https://manpages.debian.org/testing/manpages/environ.7.en.html
         * https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap08.html
         */
        @JvmStatic
        fun convertEnvironmentToEnviron(environmentMap: HashMap<String, String>): List<String> {
            val environmentList = ArrayList<String>(environmentMap.size)
            for ((name, value) in environmentMap) {
                if (isValidEnvironmentVariableNameValuePair(name, value, true)) {
                    environmentList.add("$name=$value")
                }
            }
            return environmentList
        }

        /**
         * Convert environment [HashMap] to [String] where each item equals "key=value".
         *
         */
        @JvmStatic
        fun convertEnvironmentToDotEnvFile(environmentMap: HashMap<String, String>): String {
            return convertEnvironmentToDotEnvFile(convertEnvironmentMapToEnvironmentVariableList(environmentMap))
        }

        /**
         * Convert environment [HashMap] to `.env` file [String].
         *
         * The items in the `.env` file have the format `export name="value"`.
         *
         * If the [ShellEnvironmentVariable.escaped] is set to `true`, then
         * [ShellEnvironmentVariable.value] will be considered to be a literal value that has
         * already been escaped by the caller, otherwise all the `"`\$` in the value will be escaped
         * with `a backslash `\`, like `\"`. Note that if `$` is escaped and if its part of variable,
         * then variable expansion will not happen if `.env` file is sourced.
         *
         * The `\` at the end of a value line means line continuation. Value can contain newline characters.
         *
         * Check [isValidEnvironmentVariableName] and [isValidEnvironmentVariableValue]
         * for valid variable names and values.
         *
         * https://github.com/ko1nksm/shdotenv#env-file-syntax
         * https://github.com/ko1nksm/shdotenv/blob/main/docs/specification.md
         */
        @JvmStatic
        fun convertEnvironmentToDotEnvFile(environmentList: List<ShellEnvironmentVariable>): String {
            val environment = StringBuilder()
            Collections.sort(environmentList)
            for (variable in environmentList) {
                val name = variable.name
                val value = variable.value
                if (isValidEnvironmentVariableNameValuePair(name, value, true) && value != null) {
                    val escapedValue = if (variable.escaped) {
                        value
                    } else {
                        value.replace("([\"`\\\\\$])".toRegex(), "\\\\\$1")
                    }
                    environment.append("export ").append(name).append("=\"")
                        .append(escapedValue)
                        .append("\"\n")
                }
            }
            return environment.toString()
        }

        /**
         * Convert environment [HashMap] to [List]. Each item
         * will have its [ShellEnvironmentVariable.escaped] set to `false`.
         */
        @JvmStatic
        fun convertEnvironmentMapToEnvironmentVariableList(environmentMap: HashMap<String, String>): List<ShellEnvironmentVariable> {
            val environmentList = ArrayList<ShellEnvironmentVariable>()
            for ((name, value) in environmentMap) {
                environmentList.add(ShellEnvironmentVariable(name, value, false))
            }
            return environmentList
        }

        /**
         * Check if environment variable name and value pair is valid. Errors will be logged if
         * [logErrors] is `true`.
         *
         * Check [isValidEnvironmentVariableName] and [isValidEnvironmentVariableValue]
         * for valid variable names and values.
         */
        @JvmStatic
        fun isValidEnvironmentVariableNameValuePair(name: String?, value: String?, logErrors: Boolean): Boolean {
            if (!isValidEnvironmentVariableName(name)) {
                if (logErrors) {
                    Logger.logErrorPrivate(LOG_TAG, "Invalid environment variable name. name=`$name`, value=`$value`")
                }
                return false
            }
            if (!isValidEnvironmentVariableValue(value)) {
                if (logErrors) {
                    Logger.logErrorPrivate(LOG_TAG, "Invalid environment variable value. name=`$name`, value=`$value`")
                }
                return false
            }
            return true
        }

        /**
         * Check if environment variable name is valid. It must not be `null` and must not contain
         * the null byte ('\0') and must only contain alphanumeric and underscore characters and must not
         * start with a digit.
         */
        @JvmStatic
        fun isValidEnvironmentVariableName(name: String?): Boolean {
            return name != null && !name.contains("\u0000") && name.matches("[a-zA-Z_][a-zA-Z0-9_]*".toRegex())
        }

        /**
         * Check if environment variable value is valid. It must not be `null` and must not contain
         * the null byte ('\0').
         */
        @JvmStatic
        fun isValidEnvironmentVariableValue(value: String?): Boolean {
            return value != null && !value.contains("\u0000")
        }

        /** Put value in environment if variable exists in [System] environment. */
        @JvmStatic
        fun putToEnvIfInSystemEnv(environment: HashMap<String, String>, name: String) {
            val value = System.getenv(name)
            if (value != null) {
                environment[name] = value
            }
        }

        /** Put [String] value in environment if value set. */
        @JvmStatic
        fun putToEnvIfSet(environment: HashMap<String, String>, name: String, value: String?) {
            if (value != null) {
                environment[name] = value
            }
        }

        /** Put [Boolean] value "true" or "false" in environment if value set. */
        @JvmStatic
        fun putToEnvIfSet(environment: HashMap<String, String>, name: String, value: Boolean?) {
            if (value != null) {
                environment[name] = value.toString()
            }
        }

        /** Create HOME directory in environment [HashMap] if set. */
        @JvmStatic
        fun createHomeDir(environment: HashMap<String, String>) {
            val homeDirectory = environment[ENV_HOME]
            if (homeDirectory != null && homeDirectory.isNotEmpty()) {
                val error = FileUtils.createDirectoryFile("shell home", homeDirectory)
                if (error != null) {
                    Logger.logErrorExtended(LOG_TAG, "Failed to create shell home directory\n$error")
                }
            }
        }
    }
}
