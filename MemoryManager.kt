package com.lanie.workspace

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

data class AgentMemoryRecord(
    val id: Long,
    val category: String,
    val key: String,
    val content: String,
    val timestamp: Long
)

class MemoryManager(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "lannie_agent_memory.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_MEMORY = "memory_store"

        private const val COL_ID = "id"
        private const val COL_CATEGORY = "category"
        private const val COL_KEY = "key"
        private const val COL_CONTENT = "content"
        private const val COL_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_MEMORY (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CATEGORY TEXT,
                $COL_KEY TEXT UNIQUE,
                $COL_CONTENT TEXT,
                $COL_TIMESTAMP INTEGER
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MEMORY")
        onCreate(db)
    }

    /**
     * Inserts or updates an agent memory record or fact.
     */
    fun saveMemory(category: String, key: String, content: String): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_CATEGORY, category)
            put(COL_KEY, key)
            put(COL_CONTENT, content)
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }
        return db.insertWithOnConflict(
            TABLE_MEMORY,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Retrieves a stored memory or task trace by its unique key.
     */
    fun getMemory(key: String): AgentMemoryRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_MEMORY,
            arrayOf(COL_ID, COL_CATEGORY, COL_KEY, COL_CONTENT, COL_TIMESTAMP),
            "$COL_KEY = ?",
            arrayOf(key),
            null, null, null
        )

        cursor.use {
            if (it.moveToFirst()) {
                return AgentMemoryRecord(
                    id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                    category = it.getString(it.getColumnIndexOrThrow(COL_CATEGORY)),
                    key = it.getString(it.getColumnIndexOrThrow(COL_KEY)),
                    content = it.getString(it.getColumnIndexOrThrow(COL_CONTENT)),
                    timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP))
                )
            }
        }
        return null
    }

    /**
     * Clears all memory records matching a specific category.
     */
    fun clearCategory(category: String): Int {
        val db = writableDatabase
        return db.delete(TABLE_MEMORY, "$COL_CATEGORY = ?", arrayOf(category))
    }
}
