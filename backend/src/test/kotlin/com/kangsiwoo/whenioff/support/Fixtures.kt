package com.kangsiwoo.whenioff.support

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.ConcurrentHashMap

object Fixtures {
    fun read(name: String): String =
        checkNotNull(
            Fixtures::class.java.classLoader.getResource("fixtures/$name"),
        ) { "fixture $name missing" }.readText()

    fun json(name: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(read(name))
}

/** TAGO(버스)와 KLID(신호등) 응답을 오퍼레이션 이름으로 구분해 돌려주는 공용 디스패처. */
class PublicDataFixtureDispatcher : Dispatcher() {
    val responses = ConcurrentHashMap<String, () -> MockResponse>()

    init {
        reset()
    }

    fun reset() {
        responses.clear()
        responses["getRouteNoList"] = { Fixtures.json("tago/getRouteNoList_ok.json") }
        responses["getRouteAcctoThrghSttnList"] = { Fixtures.json("tago/getRouteAcctoThrghSttnList_ok.json") }
        responses["getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList"] = {
            Fixtures.json("tago/getSttnAcctoSpecifyRouteBusArvlPrearngeInfoList_ok.json")
        }
        responses["crsrd_map_info"] = { Fixtures.json("klid/crsrd_map_info_ok.json") }
        responses["tl_drct_info"] = { Fixtures.json("klid/tl_drct_info_ok.json") }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val op = request.requestUrl?.pathSegments?.lastOrNull() ?: return MockResponse().setResponseCode(404)
        return responses[op]?.invoke() ?: MockResponse().setResponseCode(404).setBody("API not found")
    }
}
