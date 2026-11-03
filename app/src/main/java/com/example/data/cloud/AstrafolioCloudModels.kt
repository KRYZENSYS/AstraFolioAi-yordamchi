package com.example.data.cloud

data class JarvisCloudMission(
    val id: String = "",
    val userId: String = "local_user",
    val prompt: String = "",
    val mode: String = "VOICE_COUNCIL",
    val finalSpeech: String = "",
    val architectReport: String = "",
    val strategistPlan: String = "",
    val sentinelSecurityNote: String = "",
    val creativeSpark: String = "",
    val executedDeviceAction: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class AutoCallReplyLog(
    val id: String = "",
    val userId: String = "local_user",
    val callerNumber: String = "",
    val callerLabel: String = "",
    val statusPreset: String = "Uchrashuvdaman",
    val aiGeneratedReply: String = "",
    val autoAnswered: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class VoiceNoteItem(
    val id: String = "",
    val userId: String = "local_user",
    val title: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
