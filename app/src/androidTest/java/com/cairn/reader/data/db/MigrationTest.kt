package com.cairn.reader.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the Room migration chain against the exported schemas. This is the safety net for the
 * app's "your data, forever" promise: a broken migration must fail here in CI, not on a user's
 * device. Runs on an emulator (connected check); the v1 schema JSON was never exported, so the
 * replay starts at v2 and covers MIGRATION_2_3 … MIGRATION_13_14 plus the v14→v15 auto-migration
 * (which drops the legacy items.collectionId column).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    private val allMigrations = arrayOf(
        MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
        MIGRATION_12_13, MIGRATION_13_14,
    )

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CairnDatabase::class.java,
        listOf(CairnDatabase.DropLegacyCollectionId()),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrateAll_fromV2_toLatest() {
        // Create the schema at v2, then apply every migration up to the current version and let
        // Room validate that the resulting schema exactly matches the compiled entities (v14).
        helper.createDatabase(dbName, 2).close()
        val db = helper.runMigrationsAndValidate(dbName, 14, true, *allMigrations)
        assertNotNull(db)
        db.close()
    }
}
