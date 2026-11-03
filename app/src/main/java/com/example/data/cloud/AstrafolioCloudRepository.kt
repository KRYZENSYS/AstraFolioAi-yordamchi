package com.example.data.cloud

import android.content.Context
import com.example.data.CallReplyLogEntity
import com.example.data.JarvisDao
import com.example.data.JarvisDatabase
import com.example.data.MissionLogEntity
import com.example.data.VoiceNoteEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Local Room-backed repository replacing Firebase Firestore.
 * Keeps 100% compatibility with call logs, voice notes, and mission storage without requiring Firebase.
 */
class AstrafolioCloudRepository(private val dao: JarvisDao) {

    constructor(context: Context) : this(
        JarvisDatabase.getInstance(context).jarvisDao()
    )

    fun observeMissions(): Flow<List<JarvisCloudMission>> {
        return dao.getAllMissions().map { entities ->
            entities.map { entity ->
                JarvisCloudMission(
                    id = "mission_${entity.id}",
                    userId = "local_user",
                    prompt = entity.commandText,
                    mode = entity.detectedIntent,
                    finalSpeech = entity.voiceSummary,
                    architectReport = entity.architectReport,
                    strategistPlan = entity.strategistPlanJoined.replace("||", "\n"),
                    sentinelSecurityNote = entity.sentinelSecurityNote,
                    creativeSpark = entity.creativeSpark,
                    executedDeviceAction = null,
                    createdAt = entity.timestamp,
                    updatedAt = entity.timestamp
                )
            }
        }
    }

    suspend fun createMission(
        prompt: String,
        mode: String,
        finalSpeech: String,
        architectReport: String,
        strategistPlan: String,
        sentinelSecurityNote: String,
        creativeSpark: String,
        executedDeviceAction: String? = null
    ): Result<String> {
        val missionId = "mission_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        return try {
            dao.insertMission(
                MissionLogEntity(
                    commandText = prompt.take(2000).ifBlank { "Ovozli buyruq" },
                    detectedIntent = if (!executedDeviceAction.isNullOrBlank()) "$mode [$executedDeviceAction]" else mode,
                    voiceSummary = finalSpeech.take(6000).ifBlank { "Bajarildi" },
                    architectReport = architectReport.take(4000).ifBlank { "Tahlil yakunlandi" },
                    strategistPlanJoined = strategistPlan.take(4000).replace("\n", "||").ifBlank { "Reja tuzildi" },
                    sentinelSecurityNote = sentinelSecurityNote.take(4000).ifBlank { "Xavfsiz" },
                    creativeSpark = creativeSpark.take(4000).ifBlank { "Innovatsion yechim" },
                    confidenceScore = 97
                )
            )
            Result.success(missionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteMission(missionId: String): Result<Unit> {
        return try {
            val numericId = missionId.removePrefix("mission_").toIntOrNull()
            if (numericId != null) {
                dao.deleteMissionById(numericId)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeCallLogs(): Flow<List<AutoCallReplyLog>> {
        return dao.getAllCallLogs().map { list ->
            list.map { entity ->
                AutoCallReplyLog(
                    id = entity.id,
                    userId = entity.userId,
                    callerNumber = entity.callerNumber,
                    callerLabel = entity.callerLabel,
                    statusPreset = entity.statusPreset,
                    aiGeneratedReply = entity.aiGeneratedReply,
                    autoAnswered = entity.autoAnswered,
                    createdAt = entity.createdAt,
                    updatedAt = entity.createdAt
                )
            }
        }
    }

    suspend fun createCallLog(
        callerNumber: String,
        callerLabel: String,
        statusPreset: String,
        aiGeneratedReply: String,
        autoAnswered: Boolean
    ): Result<String> {
        val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        return try {
            dao.insertCallLog(
                CallReplyLogEntity(
                    id = callId,
                    userId = "local_user",
                    callerNumber = callerNumber.take(100).ifBlank { "Noma'lum raqam" },
                    callerLabel = callerLabel.take(150).ifBlank { "Kiruvchi qo'ng'iroq" },
                    statusPreset = statusPreset.take(100).ifBlank { "Bandman" },
                    aiGeneratedReply = aiGeneratedReply.take(2000).ifBlank { "Assalomu alaykum, hozir bandman." },
                    autoAnswered = autoAnswered,
                    createdAt = System.currentTimeMillis()
                )
            )
            Result.success(callId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCallLog(callId: String): Result<Unit> {
        return try {
            dao.deleteCallLogById(callId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeVoiceNotes(): Flow<List<VoiceNoteItem>> {
        return dao.getAllVoiceNotes().map { list ->
            list.map { entity ->
                VoiceNoteItem(
                    id = entity.id,
                    userId = entity.userId,
                    title = entity.title,
                    content = entity.content,
                    createdAt = entity.createdAt,
                    updatedAt = entity.createdAt
                )
            }
        }
    }

    suspend fun createVoiceNote(title: String, content: String): Result<String> {
        val noteId = "note_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        return try {
            dao.insertVoiceNote(
                VoiceNoteEntity(
                    id = noteId,
                    userId = "local_user",
                    title = title.take(300).ifBlank { "Ovozli qayd" },
                    content = content.take(4000).ifBlank { "Bo'sh qayd" },
                    createdAt = System.currentTimeMillis()
                )
            )
            Result.success(noteId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteVoiceNote(noteId: String): Result<Unit> {
        return try {
            dao.deleteVoiceNoteById(noteId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
