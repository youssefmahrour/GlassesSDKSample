package com.sdk.glassessdksample.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.fragment.app.Fragment
import java.io.Serializable

inline fun <reified T : Activity> Activity.startKtxActivity(
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) {
    val list = ArrayList<Pair<String, Any>?>()
    value?.let { list.add(it) }
    values?.let { list.addAll(it) }
    startActivity(getIntent<T>(flags, extra, list))
}

inline fun <reified T : Activity> Fragment.startKtxActivity(
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) =
    activity?.let {
        val list = ArrayList<Pair<String, Any>?>()
        value?.let { v -> list.add(v) }
        values?.let { v -> list.addAll(v) }
        startActivity(it.getIntent<T>(flags, extra, list))
    }

inline fun <reified T : Activity> Context.startKtxActivity(
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) {
    val list = ArrayList<Pair<String, Any>?>()
    value?.let { v -> list.add(v) }
    values?.let { v -> list.addAll(v) }
    startActivity(getIntent<T>(flags, extra, list))
}

inline fun <reified T : Activity> Activity.startKtxActivityForResult(
    requestCode: Int,
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) {
    val list = ArrayList<Pair<String, Any>?>()
    value?.let { list.add(it) }
    values?.let { list.addAll(it) }
    startActivityForResult(getIntent<T>(flags, extra, list), requestCode)
}

inline fun <reified T : Activity> Fragment.startKtxActivityForResult(
    requestCode: Int,
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) =
    activity?.let {
        val list = ArrayList<Pair<String, Any>?>()
        value?.let { list.add(it) }
        values?.let { list.addAll(it) }
        startActivityForResult(it.getIntent<T>(flags, extra, list), requestCode)
    }

// Fixed: Reified T should be Activity, not Context
inline fun <reified T : Activity> Context.getIntent(
    flags: Int? = null,
    extra: Bundle? = null,
    pairs: List<Pair<String, Any>?>? = null
): Intent {
    val intent = Intent(this, T::class.java)
    flags?.let { intent.flags = it }
    extra?.let { intent.putExtras(extra) }
    pairs?.let {
        for (pair in pairs) {
            pair?.let {
                val name = pair.first
                when (val value = pair.second) {
                    is Int -> intent.putExtra(name, value)
                    is Byte -> intent.putExtra(name, value)
                    is Char -> intent.putExtra(name, value)
                    is Short -> intent.putExtra(name, value)
                    is Boolean -> intent.putExtra(name, value)
                    is Long -> intent.putExtra(name, value)
                    is Float -> intent.putExtra(name, value)
                    is Double -> intent.putExtra(name, value)
                    is String -> intent.putExtra(name, value)
                    is CharSequence -> intent.putExtra(name, value)
                    is Parcelable -> intent.putExtra(name, value)
                    is Array<*> -> intent.putExtra(name, value)
                    is ArrayList<*> -> intent.putExtra(name, value)
                    is Serializable -> intent.putExtra(name, value)
                    is BooleanArray -> intent.putExtra(name, value)
                    is ByteArray -> intent.putExtra(name, value)
                    is ShortArray -> intent.putExtra(name, value)
                    is CharArray -> intent.putExtra(name, value)
                    is IntArray -> intent.putExtra(name, value)
                    is LongArray -> intent.putExtra(name, value)
                    is FloatArray -> intent.putExtra(name, value)
                    is DoubleArray -> intent.putExtra(name, value)
                    is Bundle -> intent.putExtra(name, value)
                    is Intent -> intent.putExtra(name, value)
                    else -> { /* Ignore unsupported types */ }
                }
            }
        }
    }
    return intent
}
