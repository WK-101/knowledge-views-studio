package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A named, coloured, user-ordered flag (MLO-style). Unlike the ephemeral [TaskEntity.star]
 * ("focus now"), a flag is a persistent label a task carries: it drives grouping and sorting
 * and shows on the row as a coloured marker. A task holds at most one flag ([TaskEntity.flagId]);
 * [TaskEntity.flagColorArgb] caches the chosen flag's colour so rows render without a lookup.
 *
 * Every field is part of the lossless export contract.
 */
@Serializable
@Entity(tableName = "flags")
data class FlagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Long,
    val icon: String = "flag",       // an icon key resolved by FlagIcons
    val sortOrder: Double,           // user-defined order → group/sort rank
    val createdAt: Long = 0L,
) {
    companion object {
        /**
         * The five defaults seeded on first run (and when migrating from the old single-colour
         * flag). IDs are fixed so the v13→v14 migration can back-fill each task's [flagId] from
         * its legacy [TaskEntity.flagColorArgb] deterministically.
         */
        val DEFAULTS: List<FlagEntity> = listOf(
            FlagEntity("flag-red", "Red", 0xFFE5484D, "flag", 1.0),
            FlagEntity("flag-amber", "Amber", 0xFFF59E0B, "flag", 2.0),
            FlagEntity("flag-teal", "Teal", 0xFF12A594, "flag", 3.0),
            FlagEntity("flag-blue", "Blue", 0xFF3E7BFA, "flag", 4.0),
            FlagEntity("flag-purple", "Purple", 0xFF8B5CF6, "flag", 5.0),
        )
    }
}
