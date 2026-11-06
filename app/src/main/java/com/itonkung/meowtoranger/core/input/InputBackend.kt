package com.itonkung.meowtoranger.core.input

/** Controls must not act unless the user explicitly started the active game run. */
interface InputBackend {
    val id: String
    fun isReady(): Boolean
    fun tap(x: Int, y: Int, onResult: (Boolean) -> Unit)
    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long,
              onResult: (Boolean) -> Unit)
    fun close()
}

/**
 * Android does not expose arbitrary cross-app touch injection to ordinary apps.
 * Implementations must obtain their respective platform authorization:
 * Accessibility with user permission, or Wireless Debugging with explicit pairing.
 * Never use this interface to control banking applications.
 */
data class GameScope(val packageName: String = "com.linecorp.LGRGS")
