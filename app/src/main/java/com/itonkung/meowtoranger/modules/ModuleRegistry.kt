package com.itonkung.meowtoranger.modules

/**
 * Feature-module registry. New workflows can be added without creating
 * another app, changing the update endpoint, or duplicating the launcher.
 */
data class RangerModule(
    val id: String,
    val title: String,
    val subtitle: String,
    val available: Boolean
)

object ModuleRegistry {
    val modules = listOf(
        RangerModule("friend_points", "คะแนนมิตรภาพ", "ส่งคะแนนตามโควต้าประจำวัน", true)
    )
}
