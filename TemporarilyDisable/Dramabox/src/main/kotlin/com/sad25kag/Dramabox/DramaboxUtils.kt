package com.sad25kag.Dramabox
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import kotlin.random.Random

// ============================================
// REGION 1: CONSTANTS
// ============================================

object AutoUsedConstants {
    const val DEFAULT_TIMEOUT = 10000L
}

// ============================================
// REGION 2: UTILITY FUNCTIONS
// ============================================

suspend fun rateLimitDelay(moduleName: String = "default") {
    val waitTime = 100L + Random.nextLong(0, 400L)
    delay(waitTime)
}

suspend fun <T> executeWithRetry(
    maxRetries: Int = 3,
    initialDelay: Long = 1000L,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    repeat(maxRetries) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            if (attempt < maxRetries - 1) {
                delay(initialDelay * (attempt + 1))
            }
        }
    }
    throw lastException ?: Exception("Unknown error")
}

fun logDebug(tag: String, message: String) = Log.d(tag, message)
fun logError(tag: String, message: String, error: Throwable? = null) {
    Log.e(tag, message)
    error?.let { Log.e(tag, "Cause: ${it.message}") }
}

// FIX #4: Removed unused imports (MainAPI, Element were imported but never used).

// FIX #2: Renamed from getQualityFromName to dramaboxGetQuality to avoid shadowing
// CloudStream3's built-in getQualityFromName from com.lagradost.cloudstream3.utils.
// Having the same function name in the same package causes ambiguity and silently
// overrides CloudStream3's version whenever the built-in is called from this package.
// FIX #3: Default return changed from hardcoded 480 to Qualities.Unknown.value.
// Unknown quality should not be assumed to be 480p — the player should auto-detect it.
fun dramaboxGetQuality(name: String?): Int {
    if (name == null) return Qualities.Unknown.value
    return when {
        name.contains("2160") || name.lowercase().contains("4k") -> Qualities.P2160.value
        // FIX: Qualities.P1440 does not exist in CloudStream3 enum.
        // Available values: Unknown, P144, P240, P360, P480, P720, P1080, P1080Hdr, P2160, P2160Hdr.
        // 1440p/2K content is mapped to P1080 as the closest supported quality level.
        name.contains("1440") || name.lowercase().contains("2k") -> Qualities.P1080.value
        name.contains("1080") -> Qualities.P1080.value
        name.contains("720") -> Qualities.P720.value
        name.contains("480") -> Qualities.P480.value
        name.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

// FIX #2: Removed duplicate base64Decode — CloudStream3 already provides
// com.lagradost.cloudstream3.utils.base64Decode with identical behavior.
// Having a local copy with the same name shadows the imported one silently.
