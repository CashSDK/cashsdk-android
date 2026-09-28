package com.cashsdk.net

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

internal class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

internal class HttpResponse(
    val status: Int,
    val body: String,
    headers: Map<String, String> = emptyMap(),
) {
    private val headers = headers.mapKeys { it.key.lowercase(Locale.ROOT) }

    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
}

/**
 * One blocking HTTP exchange; [ApiClient] calls it on `Dispatchers.IO`. An interface so the
 * client's request, retry and identity rules run in plain JVM tests with a fake. Throws an
 * `IOException` (or anything else) for a transport failure; any HTTP status is a response.
 */
internal fun interface HttpTransport {
    fun send(request: HttpRequest): HttpResponse
}

/** The production transport: [HttpURLConnection], no OkHttp. */
internal object UrlConnectionTransport : HttpTransport {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** The only response headers the client reads. */
    private val READ_HEADERS = listOf("ETag", "Retry-After", "Date")

    override fun send(request: HttpRequest): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            request.headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            if (request.body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val headers = READ_HEADERS.mapNotNull { name -> conn.getHeaderField(name)?.let { name to it } }.toMap()
            return HttpResponse(status, text, headers)
        } finally {
            conn.disconnect()
        }
    }
}
