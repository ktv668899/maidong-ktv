package com.local.ktv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CatalogClient {
    fun fetch(url: String): JSONArray {
        val body = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            try {
                val status = responseCode
                check(status in 200..299) { "HTTP $status" }
                inputStream.buffered().reader(Charsets.UTF_8).use { it.readText() }
            } finally {
                disconnect()
            }
        }.trim()
        if (body.startsWith("[")) return JSONArray(body)
        val root = JSONObject(body)
        return root.optJSONArray("songs") ?: root.getJSONArray("data")
    }
}
