package com.kangsiwoo.whenioff.signal.domain

// KLID tl_drct_info 필드명 접두어와 같은 코드. V1__init_schema.sql의 CHECK 제약과 일치해야 한다.
object SignalCodes {
    val APPROACH_DIRS = setOf("nt", "et", "st", "wt", "ne", "se", "sw", "nw")
    val SIGNAL_KINDS = setOf("Bs", "Bc", "Lt", "Pd", "St", "Ut")
}
