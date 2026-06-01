package com.sad25kag.cinemax21

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

@PublishedApi
internal val cinemaxJsonMapper = jacksonObjectMapper().apply {
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}

internal inline fun <reified T> parseCinemaxJson(text: String): T? {
    return runCatching { cinemaxJsonMapper.readValue<T>(text) }.getOrNull()
}
