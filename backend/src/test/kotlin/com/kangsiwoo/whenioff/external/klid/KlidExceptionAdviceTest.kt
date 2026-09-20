package com.kangsiwoo.whenioff.external.klid

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KlidExceptionAdviceTest {
    private val advice = KlidExceptionAdvice()

    @Test
    fun `missing service key maps to 503`() {
        val problem = advice.handleNotConfigured(KlidNotConfiguredException("https://apis.data.go.kr/B551982/rte"))

        assertEquals(503, problem.status)
        assertTrue(problem.detail!!.contains("service key"))
        assertEquals(null, problem.properties?.get("klidResultCode"))
    }

    @Test
    fun `KLID errors map to 502 and expose klidResultCode`() {
        val api = advice.handleKlid(KlidApiException("K30", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"))
        assertEquals(502, api.status)
        assertEquals("K30", api.properties?.get("klidResultCode"))

        val gateway = advice.handleKlid(KlidGatewayException(403, "Forbidden"))
        assertEquals(502, gateway.status)
        assertEquals("HTTP403", gateway.properties?.get("klidResultCode"))
    }
}
