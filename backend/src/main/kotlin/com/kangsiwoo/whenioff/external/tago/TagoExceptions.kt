package com.kangsiwoo.whenioff.external.tago

sealed class TagoException(
    message: String,
) : RuntimeException(message)

class TagoApiException(
    val resultCode: String,
    val resultMsg: String,
) : TagoException("TAGO $resultCode: $resultMsg")

class TagoGatewayException(
    val status: Int,
    val body: String,
) : TagoException("TAGO gateway HTTP $status: ${body.take(200)}")

class TagoNotConfiguredException(
    baseUrl: String,
) : TagoException("TAGO service key is not configured for $baseUrl")
