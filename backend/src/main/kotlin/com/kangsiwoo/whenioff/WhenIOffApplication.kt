package com.kangsiwoo.whenioff

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class WhenIOffApplication

fun main(args: Array<String>) {
    runApplication<WhenIOffApplication>(*args)
}
