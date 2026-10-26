package com.umar.aireply.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { INFO, SUCCESS, WARNING, ERROR }

data class LogEntry(
    val time: Long,
    val level: LogLevel,
    val message: String
)

/** In-memory live activity log shown on the dashboard. Keeps the 10 newest events. */
object ActivityLog {

    private const val MAX_ENTRIES = 10

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun add(level: LogLevel, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        _entries.update { old -> (listOf(entry) + old).take(MAX_ENTRIES) }
    }

    fun info(message: String) = add(LogLevel.INFO, message)
    fun success(message: String) = add(LogLevel.SUCCESS, message)
    fun warn(message: String) = add(LogLevel.WARNING, message)
    fun error(message: String) = add(LogLevel.ERROR, message)

    fun clear() {
        _entries.value = emptyList()
    }
}
