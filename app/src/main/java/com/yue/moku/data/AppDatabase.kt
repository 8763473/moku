package com.yue.moku.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, KnowledgeEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun knowledgeDao(): KnowledgeDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN generationMs INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN stopReason TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 注意：Room 的 ForeignKey 注解已通过 @Entity 声明，
                // SQLite 层面的 ALTER TABLE 不要加 REFERENCES，否则 Room 验证
                // 时会检测到实际外键与预期不一致而崩溃。
                db.execSQL("ALTER TABLE conversations ADD COLUMN parentBranchId INTEGER")
                db.execSQL("ALTER TABLE conversations ADD COLUMN forkMessageId INTEGER")
            }
        }
        /**
         * v3 → v4：修复 MIGRATION_2_3 在 SQLite 层创建了 REFERENCES 外键，
         * 导致 Room 的 schema 验证因外键不匹配而崩溃。此迁移将错误的列删除并重建为纯 INTEGER。
         * 注意：SQLite 不支持 DROP COLUMN（API < 35），因此需要重建表。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 先检查旧表是否已经有 parentBranchId / forkMessageId 列
                val cursor = db.query("PRAGMA table_info(conversations)")
                val columns = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                cursor.close()
                val hasBranchColumns = columns.contains("parentBranchId") && columns.contains("forkMessageId")

                if (hasBranchColumns) {
                    // 旧表已有分支列：保留数据，重建表以移除 SQLite 层 REFERENCES 外键
                    db.execSQL("""
                        CREATE TABLE conversations_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            title TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            parentBranchId INTEGER,
                            forkMessageId INTEGER
                        )
                    """.trimIndent())
                    db.execSQL("INSERT INTO conversations_new (id, title, createdAt, updatedAt, parentBranchId, forkMessageId) SELECT id, title, createdAt, updatedAt, parentBranchId, forkMessageId FROM conversations")
                    db.execSQL("DROP TABLE conversations")
                    db.execSQL("ALTER TABLE conversations_new RENAME TO conversations")
                } else {
                    // 旧表无分支列（从 v1/v2 升级）：只需添加列
                    db.execSQL("ALTER TABLE conversations ADD COLUMN parentBranchId INTEGER")
                    db.execSQL("ALTER TABLE conversations ADD COLUMN forkMessageId INTEGER")
                }
            }
        }
    }
}
