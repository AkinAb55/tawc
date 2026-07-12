package me.phie.tawc.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {
    private const val TAG = "TAWC"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        val logsDir = File(context.filesDir, "logs")
        logsDir.mkdirs()
        logFile = File(logsDir, "tawc.log")
        
        // Очистка старого лога при запуске (не более 5 МБ)
        if (logFile!!.length() > 5 * 1024 * 1024) {
            logFile!!.writeText("")
        }
    }

    private fun writeToFile(level: String, message: String, throwable: Throwable? = null) {
        scope.launch {
            try {
                val time = dateFormat.format(Date())
                val logLine = "[$time] $level: $message\n"
                
                FileWriter(logFile, true).use { writer ->
                    writer.append(logLine)
                    throwable?.let {
                        writer.append(Log.getStackTraceString(it) + "\n")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log", e)
            }
        }
    }

    fun d(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
        writeToFile("DEBUG", "[$tag] $message")
    }

    fun i(tag: String, message: String) {
        Log.i(TAG, "[$tag] $message")
        writeToFile("INFO", "[$tag] $message")
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(TAG, "[$tag] $message", throwable)
        writeToFile("WARN", "[$tag] $message", throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(TAG, "[$tag] $message", throwable)
        writeToFile("ERROR", "[$tag] $message", throwable)
    }

    fun getLogFile(): File? = logFile

    fun clearLog() {
        logFile?.writeText("")
    }
}
