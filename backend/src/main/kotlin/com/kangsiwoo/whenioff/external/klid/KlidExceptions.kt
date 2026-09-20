package com.kangsiwoo.whenioff.external.klid

sealed class KlidException(
    message: String,
) : RuntimeException(message)

class KlidApiException(
    val resultCode: String,
    val resultMsg: String,
) : KlidException("KLID $resultCode: $resultMsg")

class KlidGatewayException(
    val status: Int,
    val body: String,
) : KlidException("KLID gateway HTTP $status: ${body.take(200)}")
