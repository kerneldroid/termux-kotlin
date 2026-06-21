package com.termux.shared.android

import android.annotation.SuppressLint
import android.content.Context
import com.termux.shared.logger.Logger
import com.termux.shared.reflection.ReflectionUtils
import java.lang.reflect.Method

/**
 * Utils for Developer Options -> Feature Flags. The page won't show in user/production builds and
 * is only shown in userdebug builds.
 * https://cs.android.com/android/_/android/platform/frameworks/base/+/09dcdad5ebc159861920f090e07da60fac71ac0a:core/java/android/util/FeatureFlagUtils.java
 * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r31:packages/apps/Settings/src/com/android/settings/development/featureflags/FeatureFlagsPreferenceController.java;l=42
 *
 * The feature flags value can be modified in two ways.
 *
 * 1. sysprops with `setprop` command with root. Will be unset by default.
 *  Set value: `setprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs false`
 *  Get value: `getprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs`
 *  Unset value: `setprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs ""`
 * Running `setprop` command requires root and even adb `shell` user cannot modify the values
 * since selinux will not allow it by default. Some props like `settings_dynamic_system` can be
 * set since they are exempted for `shell` in sepolicy.
 *
 * init: Unable to set property 'persist.sys.fflag.override.settings_enable_monitor_phantom_procs' from uid:2000 gid:2000 pid:9576: SELinux permission check failed
 * [ 1034.877067] type=1107 audit(1644436809.637:34): uid=0 auid=4294967295 ses=4294967295 subj=u:r:init:s0 msg='avc: denied { set } for property=persist.sys.fflag.override.settings_enable_monitor_phantom_procs pid=9576 uid=2000 gid=2000 scontext=u:r:shell:s0 tcontext=u:object_r:system_prop:s0 tclass=property_service permissive=0'
 *
 * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r4:system/sepolicy/private/property_contexts;l=71
 * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r4:system/sepolicy/private/shell.te;l=149
 *
 * 2. settings global list with adb or root. Will be unset by default. This takes precedence over
 * sysprop value since `FeatureFlagUtils.isEnabled()`
 * checks its value first. Override precedence: Settings.Global -> sys.fflag.override.* -> static list.
 * Set value: `adb shell settings put global settings_enable_monitor_phantom_procs false`
 * Get value: adb shell settings get global settings_enable_monitor_phantom_procs`
 * Unset value: `adb shell settings delete global settings_enable_monitor_phantom_procs`
 *
 * https://cs.android.com/android/_/android/platform/frameworks/base/+/refs/tags/android-12.0.0_r31:core/java/android/util/FeatureFlagUtils.java;l=113
 *
 * The feature flag values can be modified in user builds with settings global list, but since the
 * developer options feature flags page is not shown and considering that getprop values for features
 * will be unset by default and settings global list will not be set either and there is no shell API,
 * it will require an android app process to check if feature is supported on a device and what its
 * default value is with reflection after bypassing hidden api restrictions since {@link #FEATURE_FLAGS_CLASS}
 * is annotated as `@hide`.
 */
class FeatureFlagUtils {

    enum class FeatureFlagValue(private val valueName: String) {
        /** Unknown like due to exception raised while getting value. */
        UNKNOWN("<unknown>"),

        /** Flag is unsupported on current android build. */
        UNSUPPORTED("<unsupported>"),

        /** Flag is enabled. */
        TRUE("true"),

        /** Flag is not enabled. */
        FALSE("false");

        fun getName(): String {
            return valueName
        }
    }

    companion object {
        const val FEATURE_FLAGS_CLASS = "android.util.FeatureFlagUtils"

        private const val LOG_TAG = "FeatureFlagUtils"

        /**
         * Get all feature flags in their raw form.
         */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun getAllFeatureFlags(): Map<String, String>? {
            ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
            return try {
                @SuppressLint("PrivateApi")
                val clazz = Class.forName(FEATURE_FLAGS_CLASS)
                val getAllFeatureFlagsMethod = ReflectionUtils.getDeclaredMethod(clazz, "getAllFeatureFlags")
                    ?: return null
                ReflectionUtils.invokeMethod(getAllFeatureFlagsMethod, null).value as? Map<String, String>
            } catch (e: Exception) {
                // ClassCastException may be thrown
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get all feature flags", e)
                null
            }
        }

        /**
         * Check if a feature flag exists.
         *
         * @return Returns `true` if flag exists, otherwise `false`. This will be
         * `null` if an exception is raised.
         */
        @JvmStatic
        fun featureFlagExists(feature: String): Boolean? {
            val featureFlags = getAllFeatureFlags() ?: return null
            return featureFlags.containsKey(feature)
        }

        /**
         * Get [FeatureFlagValue] for a feature.
         *
         * @param context The [Context] for operations.
         * @param feature The [String] name for feature.
         * @return Returns [FeatureFlagValue].
         */
        @JvmStatic
        fun getFeatureFlagValueString(context: Context, feature: String): FeatureFlagValue {
            val featureExists = featureFlagExists(feature)
            if (featureExists == null) {
                Logger.logError(LOG_TAG, "Failed to get feature flags \"$feature\" value")
                return FeatureFlagValue.UNKNOWN
            } else if (!featureExists) {
                return FeatureFlagValue.UNSUPPORTED
            }

            val featureFlagValue = isFeatureEnabled(context, feature)
            return if (featureFlagValue == null) {
                Logger.logError(LOG_TAG, "Failed to get feature flags \"$feature\" value")
                FeatureFlagValue.UNKNOWN
            } else {
                if (featureFlagValue) FeatureFlagValue.TRUE else FeatureFlagValue.FALSE
            }
        }

        /**
         * Check if a feature flag is enabled.
         *
         * @param context The [Context] for operations.
         * @param feature The [String] name for feature.
         * @return Returns `true` if flag is enabled, otherwise `false`. This will be
         * `null` if an exception is raised.
         */
        @JvmStatic
        fun isFeatureEnabled(context: Context, feature: String): Boolean? {
            ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
            return try {
                @SuppressLint("PrivateApi")
                val clazz = Class.forName(FEATURE_FLAGS_CLASS)
                val isFeatureEnabledMethod = ReflectionUtils.getDeclaredMethod(clazz, "isEnabled", Context::class.java, String::class.java)
                if (isFeatureEnabledMethod == null) {
                    Logger.logError(LOG_TAG, "Failed to check if feature flag \"$feature\" is enabled")
                    return null
                }
                ReflectionUtils.invokeMethod(isFeatureEnabledMethod, null, context, feature).value as? Boolean
            } catch (e: Exception) {
                // ClassCastException may be thrown
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to check if feature flag \"$feature\" is enabled", e)
                null
            }
        }
    }
}
