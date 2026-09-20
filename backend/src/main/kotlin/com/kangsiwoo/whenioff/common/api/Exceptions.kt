package com.kangsiwoo.whenioff.common.api

class NotFoundException(
    message: String,
) : RuntimeException(message)

class BadRequestException(
    message: String,
) : RuntimeException(message)

class ConflictException(
    message: String,
) : RuntimeException(message)
