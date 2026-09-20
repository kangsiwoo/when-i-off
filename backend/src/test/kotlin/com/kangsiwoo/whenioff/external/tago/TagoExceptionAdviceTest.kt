package com.kangsiwoo.whenioff.external.tago

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagoExceptionAdviceTest {
    private val advice = TagoExceptionAdvice()

    @Test
    fun `missing service key maps to 503`() {
        val problem =
            advice.handleNotConfigured(
                TagoNotConfiguredException("https://apis.data.go.kr/1613000/ArvlInfoInqireService"),
            )

        assertEquals(503, problem.status)
        assertTrue(problem.detail!!.contains("service key"))
        assertEquals(null, problem.properties?.get("tagoResultCode"))
    }

    @Test
    fun `TAGO errors map to 502 and expose tagoResultCode`() {
        val api = advice.handleTago(TagoApiException("30", "SERVICE KEY IS NOT REGISTERED ERROR"))
        assertEquals(502, api.status)
        assertEquals("30", api.properties?.get("tagoResultCode"))

        val gateway = advice.handleTago(TagoGatewayException(403, "Forbidden"))
        assertEquals(502, gateway.status)
        assertEquals("HTTP403", gateway.properties?.get("tagoResultCode"))
    }
}
