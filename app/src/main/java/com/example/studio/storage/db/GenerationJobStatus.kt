package com.example.studio.storage.db

import androidx.room.TypeConverter

enum class GenerationJobKind {
    LOCAL_IMPORT,
    PROVIDER_GENERATION
}

enum class PromotionPhase {
    PREPARED,
    PROMOTED,
    COMMITTED
}

enum class GenerationJobStatus {
    QUEUED,
    SUBMITTING,
    SUBMITTED,
    PROCESSING,
    DOWNLOADING,
    VALIDATING,
    COMPLETED,
    FAILED,
    AMBIGUOUS_SUBMISSION_FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED ||
            this == FAILED ||
            this == AMBIGUOUS_SUBMISSION_FAILED ||
            this == CANCELLED

    val isActiveInFlight: Boolean
        get() = !isTerminal

    companion object {
        fun isValidTransition(
            from: GenerationJobStatus,
            to: GenerationJobStatus,
            jobKind: GenerationJobKind? = null
        ): Boolean {
            if (jobKind == GenerationJobKind.LOCAL_IMPORT) {
                // Local import is never a billable provider POST and must NEVER enter
                // SUBMITTING, SUBMITTED, PROCESSING, or AMBIGUOUS_SUBMISSION_FAILED.
                return when (from) {
                    QUEUED ->
                        to == DOWNLOADING ||
                            to == FAILED ||
                            to == CANCELLED

                    DOWNLOADING ->
                        to == VALIDATING ||
                            to == FAILED ||
                            to == CANCELLED

                    VALIDATING ->
                        to == COMPLETED ||
                            to == FAILED ||
                            to == CANCELLED

                    else -> false
                }
            }

            return when (from) {
                QUEUED ->
                    to == SUBMITTING ||
                        (jobKind == null && to == DOWNLOADING) ||
                        to == CANCELLED

                SUBMITTING ->
                    to == SUBMITTED ||
                        to == QUEUED ||
                        to == AMBIGUOUS_SUBMISSION_FAILED ||
                        to == FAILED

                SUBMITTED ->
                    to == PROCESSING ||
                        to == FAILED ||
                        to == CANCELLED

                PROCESSING ->
                    to == DOWNLOADING ||
                        to == FAILED ||
                        to == CANCELLED

                DOWNLOADING ->
                    to == VALIDATING ||
                        to == FAILED ||
                        to == CANCELLED

                VALIDATING ->
                    to == COMPLETED ||
                        to == FAILED ||
                        to == CANCELLED

                else -> false
            }
        }
    }
}

class GenerationJobStatusConverter {
    @TypeConverter
    fun fromStatus(status: GenerationJobStatus): String = status.name

    @TypeConverter
    fun toStatus(raw: String): GenerationJobStatus =
        GenerationJobStatus.valueOf(raw)

    @TypeConverter
    fun fromJobKind(kind: GenerationJobKind): String = kind.name

    @TypeConverter
    fun toJobKind(raw: String): GenerationJobKind =
        GenerationJobKind.valueOf(raw)

    @TypeConverter
    fun fromPromotionPhase(phase: PromotionPhase): String = phase.name

    @TypeConverter
    fun toPromotionPhase(raw: String): PromotionPhase =
        PromotionPhase.valueOf(raw)
}
