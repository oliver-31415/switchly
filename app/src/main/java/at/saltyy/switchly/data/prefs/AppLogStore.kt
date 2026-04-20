package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogStore {
    private const val PREFS_NAME = "switchly_app_logs"
    private const val KEY_LINES = "lines"
    private const val MAX_LINES = 240
    private val SEP = '\u001E'

    fun append(context: Context, tag: String, message: String, error: Throwable? = null) {
        val entry = buildString {
            append(timestamp())
            append(" [")
            append(tag)
            append("] ")
            append(message.trim())
            error?.let {
                val summary = it.javaClass.simpleName + (it.message?.takeIf { msg -> msg.isNotBlank() }?.let { msg -> ": $msg" } ?: "")
                append(" | ")
                append(summary)
            }
        }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_LINES, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?.toMutableList()
            ?: mutableListOf()

        current += entry
        while (current.size > MAX_LINES) current.removeAt(0)

        prefs.edit {
            putString(KEY_LINES, current.joinToString(separator = SEP.toString()))
        }
    }

    fun export(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lines = prefs.getString(KEY_LINES, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (lines.isEmpty()) return "No recent app logs."

        return buildString {
            append("-----\n")
            append("Latest app logs\n")
            append("-----\n")
            lines.forEach { append(it).append('\n') }
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
