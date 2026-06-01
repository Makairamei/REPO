package com.sad25kag.drakor

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

private val drakorJsonMapper = jacksonObjectMapper().configure(
    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
    false
)

inline fun <reified T> parseDrakorJson(json: String): T {
    return drakorJsonMapper.readValue(json, object : TypeReference<T>() {})
}

inline fun <reified T> tryParseDrakorJson(json: String): T? {
    return runCatching { parseDrakorJson<T>(json) }.getOrNull()
}
