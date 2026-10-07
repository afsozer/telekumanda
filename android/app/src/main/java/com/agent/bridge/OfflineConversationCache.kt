package com.agent.bridge

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

// OfflineConversation / OfflineConversationKey / offlineConversationKey /
// OfflineConversationCodec shared modülünde (OfflineConversation.kt). Burada
// yalnız Android'e özgü Room gerçeklemesi kalır (plan 4. madde: ConversationCache).

@Entity(tableName = "offline_conversations", primaryKeys = ["backend", "sessionId"])
internal data class OfflineConversationEntity(
    val backend: String,
    val sessionId: String,
    val transcript: String,
    val messagesJson: String,
    val updatedAt: Long,
)

@Dao
internal interface OfflineConversationDao {
    @Query("SELECT * FROM offline_conversations WHERE backend = :backend AND sessionId = :sessionId LIMIT 1")
    suspend fun find(backend: String, sessionId: String): OfflineConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OfflineConversationEntity)

    @Query("DELETE FROM offline_conversations WHERE sessionId IN (:sessionIds)")
    suspend fun deleteBySessionIds(sessionIds: Collection<String>)
}

@Database(entities = [OfflineConversationEntity::class], version = 1, exportSchema = false)
internal abstract class OfflineConversationDatabase : RoomDatabase() {
    abstract fun conversations(): OfflineConversationDao
}

internal class OfflineConversationCache(context: Context) : ConversationCache {
    private val dao = Room.databaseBuilder(
        context.applicationContext,
        OfflineConversationDatabase::class.java,
        "offline-conversations.db",
    ).fallbackToDestructiveMigration().build().conversations()

    override suspend fun load(backend: String, sessionId: String): OfflineConversation? =
        dao.find(backend, sessionId)?.let { OfflineConversation(it.transcript, OfflineConversationCodec.decode(it.messagesJson)) }

    override suspend fun save(backend: String, sessionId: String, result: ConversationResult) {
        if (backend.isBlank() || sessionId.isBlank() || result.messages.isEmpty()) return
        dao.upsert(OfflineConversationEntity(
            backend = backend,
            sessionId = sessionId,
            transcript = result.text,
            messagesJson = OfflineConversationCodec.encode(result.messages),
            updatedAt = System.currentTimeMillis(),
        ))
    }

    override suspend fun delete(sessionIds: Set<String>) {
        val temiz = sessionIds.filter(String::isNotBlank)
        if (temiz.isEmpty()) return
        dao.deleteBySessionIds(temiz)
    }
}
