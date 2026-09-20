package com.kangsiwoo.whenioff.external.klid

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kangsiwoo.whenioff.external.klid.signal.SignalStateExploder
import com.kangsiwoo.whenioff.external.klid.signal.SignalStateRecord
import com.kangsiwoo.whenioff.support.Fixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalStateExploderTest {
    private val fixtureItem: Map<String, String> =
        jacksonObjectMapper()
            .readTree(Fixtures.read("klid/tl_drct_info_ok.json"))
            .path("body")
            .path("items")
            .path("item")[0]
            .properties()
            .associate { (k, v) -> k to v.asText() }

    @Test
    fun `explodes the 96-field item into one record per populated direction and kind`() {
        val records = SignalStateExploder.explode(fixtureItem)

        assertEquals(
            setOf(
                SignalStateRecord("nt", "Pd", "protected-Movement-Allowed", 150),
                SignalStateRecord("nt", "St", "protected-Movement-Allowed", 21),
                SignalStateRecord("et", "Lt", "permissive-Movement-Allowed", null),
                SignalStateRecord("et", "St", "stop-And-Remain", 261),
                SignalStateRecord("st", "Pd", "stop-And-Remain", null),
                SignalStateRecord("st", "St", "protected-Movement-Allowed", 21),
            ),
            records.toSet(),
        )
        assertEquals(6, records.size)
    }

    @Test
    fun `36001 means unknown remaining and maps to null`() {
        assertNull(SignalStateExploder.parseRemaining("36001"))
        assertNull(SignalStateExploder.parseRemaining(""))
        assertNull(SignalStateExploder.parseRemaining(null))
        assertEquals(12, SignalStateExploder.parseRemaining(" 12 "))
    }

    @Test
    fun `a blank status is skipped even if remaining is present`() {
        val fields =
            mapOf("wtPdsgRmndCs" to "50", "wtPdsgSttsNm" to "", "nwUtsgRmndCs" to "7", "nwUtsgSttsNm" to "dark")

        assertEquals(listOf(SignalStateRecord("nw", "Ut", "dark", 7)), SignalStateExploder.explode(fields))
    }

    @Test
    fun `field names follow the dir kind sg suffix rule`() {
        assertEquals("ntPdsgRmndCs", SignalStateExploder.remainingKey("nt", "Pd"))
        assertEquals("swBcsgSttsNm", SignalStateExploder.statusKey("sw", "Bc"))
    }
}
