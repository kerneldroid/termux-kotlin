package com.termux.shared.reflection

import android.os.Build
import com.termux.shared.logger.Logger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

object ReflectionUtils {

    private var HIDDEN_API_REFLECTION_RESTRICTIONS_BYPASSED = Build.VERSION.SDK_INT < Build.VERSION_CODES.P

    private const val LOG_TAG = "ReflectionUtils"

    /**
     * Bypass android hidden API reflection restrictions.
     * https://github.com/LSPosed/AndroidHiddenApiBypass
     * https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces
     */
    @JvmStatic
    fun bypassHiddenAPIReflectionRestrictions() {
        if (!HIDDEN_API_REFLECTION_RESTRICTIONS_BYPASSED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Logger.logDebug(LOG_TAG, "Bypassing android hidden api reflection restrictions")
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (t: Throwable) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to bypass hidden API reflection restrictions", t)
            }

            HIDDEN_API_REFLECTION_RESTRICTIONS_BYPASSED = true
        }
    }

    /** Check if android hidden API reflection restrictions are bypassed. */
    @JvmStatic
    fun areHiddenAPIReflectionRestrictionsBypassed(): Boolean {
        return HIDDEN_API_REFLECTION_RESTRICTIONS_BYPASSED
    }

    /**
     * Get a {@link Field} for the specified class.
     *
     * @param clazz The {@link Class} for which to return the field.
     * @param fieldName The name of the {@link Field}.
     * @return Returns the {@link Field} if getting the it was successful, otherwise {@code null}.
     */
    @JvmStatic
    fun getDeclaredField(clazz: Class<*>, fieldName: String): Field? {
        return try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"$fieldName\" field for \"${clazz.name}\" class", e)
            null
        }
    }

    /** Class that represents result of invoking a field. */
    data class FieldInvokeResult(
        @JvmField val success: Boolean,
        @JvmField val value: Any?
    )

    /**
     * Get a value for a {@link Field} of an object for the specified class.
     *
     * Trying to access {@code null} fields will result in {@link NoSuchFieldException}.
     *
     * @param clazz The {@link Class} to which the object belongs to.
     * @param fieldName The name of the {@link Field}.
     * @param object The {@link Object} instance from which to get the field value.
     * @return Returns the {@link FieldInvokeResult} of invoking the field. The
     * {@link FieldInvokeResult#success} will be {@code true} if invoking the field was successful,
     * otherwise {@code false}. The {@link FieldInvokeResult#value} will contain the field
     * {@link Object} value.
     */
    @JvmStatic
    fun <T> invokeField(clazz: Class<out T>, fieldName: String, `object`: T?): FieldInvokeResult {
        return try {
            val field = getDeclaredField(clazz, fieldName) ?: return FieldInvokeResult(false, null)
            FieldInvokeResult(true, field.get(`object`))
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"$fieldName\" field value for \"${clazz.name}\" class", e)
            FieldInvokeResult(false, null)
        }
    }

    /**
     * Wrapper for {@link #getDeclaredMethod(Class, String, Class[])} without parameters.
     */
    @JvmStatic
    fun getDeclaredMethod(clazz: Class<*>, methodName: String): Method? {
        return getDeclaredMethod(clazz, methodName, *emptyArray<Class<*>?>())
    }

    /**
     * Get a {@link Method} for the specified class with the specified parameters.
     *
     * @param clazz The {@link Class} for which to return the method.
     * @param methodName The name of the {@link Method}.
     * @param parameterTypes The parameter types of the method.
     * @return Returns the {@link Method} if getting the it was successful, otherwise {@code null}.
     */
    @JvmStatic
    fun getDeclaredMethod(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>?): Method? {
        return try {
            val method = clazz.getDeclaredMethod(methodName, *parameterTypes)
            method.isAccessible = true
            method
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"$methodName\" method for \"${clazz.name}\" class with parameter types: ${parameterTypes.contentToString()}", e)
            null
        }
    }

    /**
     * Wrapper for {@link #invokeVoidMethod(Method, Object, Object...)} without arguments.
     */
    @JvmStatic
    fun invokeVoidMethod(method: Method, obj: Any?): Boolean {
        return invokeVoidMethod(method, obj, *emptyArray<Any?>())
    }

    /**
     * Invoke a {@link Method} on the specified object with the specified arguments that returns
     * {@code void}.
     *
     * @param method The {@link Method} to invoke.
     * @param obj The {@link Object} the method should be invoked from.
     * @param args The arguments to pass to the method.
     * @return Returns {@code true} if invoking the method was successful, otherwise {@code false}.
     */
    @JvmStatic
    fun invokeVoidMethod(method: Method, obj: Any?, vararg args: Any?): Boolean {
        return try {
            method.isAccessible = true
            method.invoke(obj, *args)
            true
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to invoke \"${method.name}\" method with object \"$obj\" and args: ${args.contentToString()}", e)
            false
        }
    }

    /** Class that represents result of invoking a method that has a non-void return type. */
    data class MethodInvokeResult(
        @JvmField val success: Boolean,
        @JvmField val value: Any?
    )

    /**
     * Wrapper for {@link #invokeMethod(Method, Object, Object...)} without arguments.
     */
    @JvmStatic
    fun invokeMethod(method: Method, obj: Any?): MethodInvokeResult {
        return invokeMethod(method, obj, *emptyArray<Any?>())
    }

    /**
     * Invoke a {@link Method} on the specified object with the specified arguments.
     *
     * @param method The {@link Method} to invoke.
     * @param obj The {@link Object} the method should be invoked from.
     * @param args The arguments to pass to the method.
     * @return Returns the {@link MethodInvokeResult} of invoking the method. The
     * {@link MethodInvokeResult#success} will be {@code true} if invoking the method was successful,
     * otherwise {@code false}. The {@link MethodInvokeResult#value} will contain the {@link Object}
     * returned by the method.
     */
    @JvmStatic
    fun invokeMethod(method: Method, obj: Any?, vararg args: Any?): MethodInvokeResult {
        return try {
            method.isAccessible = true
            MethodInvokeResult(true, method.invoke(obj, *args))
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to invoke \"${method.name}\" method with object \"$obj\" and args: ${args.contentToString()}", e)
            MethodInvokeResult(false, null)
        }
    }

    /**
     * Wrapper for {@link #getConstructor(String, Class[])} without parameters.
     */
    @JvmStatic
    fun getConstructor(className: String): Constructor<*>? {
        return getConstructor(className, *emptyArray<Class<*>?>())
    }

    /**
     * Wrapper for {@link #getConstructor(Class, Class[])} to get a {@link Constructor} for the
     * {@code className}.
     */
    @JvmStatic
    fun getConstructor(className: String, vararg parameterTypes: Class<*>?): Constructor<*>? {
        return try {
            getConstructor(Class.forName(className), *parameterTypes)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get constructor for \"$className\" class with parameter types: ${parameterTypes.contentToString()}", e)
            null
        }
    }

    /**
     * Get a {@link Constructor} for the specified class with the specified parameters.
     *
     * @param clazz The {@link Class} for which to return the constructor.
     * @param parameterTypes The parameter types of the constructor.
     * @return Returns the {@link Constructor} if getting the it was successful, otherwise {@code null}.
     */
    @JvmStatic
    fun getConstructor(clazz: Class<*>, vararg parameterTypes: Class<*>?): Constructor<*>? {
        return try {
            val constructor = clazz.getConstructor(*parameterTypes)
            constructor.isAccessible = true
            constructor
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get constructor for \"${clazz.name}\" class with parameter types: ${parameterTypes.contentToString()}", e)
            null
        }
    }

    /**
     * Wrapper for {@link #invokeConstructor(Constructor, Object...)} without arguments.
     */
    @JvmStatic
    fun invokeConstructor(constructor: Constructor<*>): Any? {
        return invokeConstructor(constructor, *emptyArray<Any?>())
    }

    /**
     * Invoke a {@link Constructor} with the specified arguments.
     *
     * @param constructor The {@link Constructor} to invoke.
     * @param args The arguments to pass to the constructor.
     * @return Returns the new instance if invoking the constructor was successful, otherwise {@code null}.
     */
    @JvmStatic
    fun invokeConstructor(constructor: Constructor<*>, vararg args: Any?): Any? {
        return try {
            constructor.isAccessible = true
            constructor.newInstance(*args)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to invoke \"${constructor.name}\" constructor with args: ${args.contentToString()}", e)
            null
        }
    }
}
