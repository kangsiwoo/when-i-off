package com.kangsiwoo.whenioff.external.klid.signal

data class SignalStateRecord(
    val approachDir: String,
    val signalKind: String,
    val status: String,
    val remainingDs: Int?,
)

object SignalStateExploder {
    val APPROACH_DIRS = listOf("nt", "et", "st", "wt", "ne", "se", "sw", "nw")
    val SIGNAL_KINDS = listOf("Bs", "Bc", "Lt", "Pd", "St", "Ut")
    const val REMAINING_UNKNOWN = "36001"

    fun statusKey(
        dir: String,
        kind: String,
    ): String = "${dir}${kind}sgSttsNm"

    fun remainingKey(
        dir: String,
        kind: String,
    ): String = "${dir}${kind}sgRmndCs"

    fun explode(fields: Map<String, String>): List<SignalStateRecord> =
        APPROACH_DIRS.flatMap { dir ->
            SIGNAL_KINDS.mapNotNull { kind ->
                val status = fields[statusKey(dir, kind)]?.trim().orEmpty()
                if (status.isEmpty()) return@mapNotNull null
                SignalStateRecord(dir, kind, status, parseRemaining(fields[remainingKey(dir, kind)]))
            }
        }

    fun parseRemaining(value: String?): Int? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == REMAINING_UNKNOWN) return null
        return trimmed.toIntOrNull()
    }
}
