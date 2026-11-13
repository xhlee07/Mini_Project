package com.ctfly.app.data

import android.content.Context
import android.util.Base64
import com.ctfly.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CtflyApi(
    context: Context,
    private val transport: (suspend (String, JSONObject?, String?, String) -> JSONObject)? = null,
) : ClubGateway {
    private val vault = SessionVault(context)
    @Volatile private var session: JSONObject? = vault.read()
    private val sessionLock = Any()
    @Volatile private var sessionGeneration = 0
    private val authMutex = Mutex()
    override val signedIn
        get() = session != null

    override val accountId
        get() = session?.optJSONObject("user")?.text("id").orEmpty()

    override val accountEmail
        get() = session?.optJSONObject("user")?.text("email").orEmpty()

    override val cachedSnapshot
        get() =
            session?.optJSONObject("cached_snapshot")?.let { JSONObject(it.toString()) }
                ?: JSONObject()

    private suspend fun request(
        path: String,
        body: JSONObject?,
        token: String? = null,
        method: String = "POST",
    ): JSONObject =
        if (transport != null) transport.invoke(path, body, token, method)
        else
            withContext(Dispatchers.IO) {
                val connection =
                    URL(BuildConfig.SUPABASE_URL + path).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = method
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
                    connection.setRequestProperty("Content-Type", "application/json")
                    if (token != null)
                        connection.setRequestProperty("Authorization", "Bearer $token")
                    if (body != null) {
                        connection.doOutput = true
                        connection.outputStream.use { it.write(body.toString().toByteArray()) }
                    }
                    val status = connection.responseCode
                    val raw =
                        (if (status in 200..299) connection.inputStream else connection.errorStream)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            .orEmpty()
                    val result = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                    if (status !in 200..299) {
                        val message =
                            result.text(
                                "msg",
                                result.text(
                                    "message",
                                    result.text(
                                        "error_description",
                                        "Connection failed ($status). Please try again.",
                                    ),
                                ),
                            )
                        throw ApiFailure(
                            status,
                            if (
                                message.contains("ctfly_", true) &&
                                    (status == 404 || message.contains("schema cache"))
                            )
                                "Cloud setup is pending. Run supabase/001_ctfly.sql in your Supabase SQL Editor."
                            else message,
                            result.text("error_code", result.text("code")),
                        )
                    }
                    result
                } finally {
                    connection.disconnect()
                }
            }

    private fun saveSession(data: JSONObject, generation: Int, replacing: Boolean = false) =
        synchronized(sessionLock) {
            if (generation != sessionGeneration)
                throw kotlinx.coroutines.CancellationException("Account changed")
            if (data.has("access_token")) {
                // An account switch must never inherit another customer's cached private data.
                if (accountId.isNotBlank() && accountId == data.optJSONObject("user")?.text("id"))
                    session?.optJSONObject("cached_snapshot")?.let {
                        data.put("cached_snapshot", it)
                    }
                data.put(
                    "expires_at",
                    System.currentTimeMillis() / 1000 + data.optLong("expires_in", 3600),
                )
                session = data
                if (replacing) sessionGeneration++
                vault.save(data)
            }
        }

    private suspend fun accessToken(
        rejectedToken: String? = null,
        expectedGeneration: Int = sessionGeneration,
    ): String =
        authMutex.withLock {
            if (expectedGeneration != sessionGeneration)
                throw kotlinx.coroutines.CancellationException("Account changed")
            val current = session ?: throw ApiFailure(401, "Please sign in.")
            val generation = sessionGeneration
            if (
                (rejectedToken != null && rejectedToken == current.text("access_token")) ||
                    current.optLong("expires_at") < System.currentTimeMillis() / 1000 + 60
            ) {
                try {
                    saveSession(
                        request(
                            "/auth/v1/token?grant_type=refresh_token",
                            obj("refresh_token" to current.text("refresh_token")),
                        ),
                        generation,
                    )
                } catch (e: ApiFailure) {
                    // Transport errors and transient authorization failures must not erase login.
                    if (
                        generation == sessionGeneration &&
                            e.code in
                                setOf(
                                    "refresh_token_not_found",
                                    "refresh_token_already_used",
                                    "session_not_found",
                                    "user_banned",
                                    "user_not_found",
                                    "invalid_refresh_token",
                                )
                    )
                        localLogout()
                    throw e
                }
            }
            synchronized(sessionLock) {
                if (expectedGeneration != sessionGeneration)
                    throw kotlinx.coroutines.CancellationException("Account changed")
                session?.text("access_token") ?: throw ApiFailure(401, "Please sign in.")
            }
        }

    override suspend fun rpc(name: String, body: JSONObject, authenticated: Boolean): JSONObject {
        if (!authenticated) return request("/rest/v1/rpc/$name", body)
        val generation = sessionGeneration
        val token = accessToken(expectedGeneration = generation)
        val response =
            try {
                request("/rest/v1/rpc/$name", body, token)
            } catch (e: ApiFailure) {
                if (e.status != 401) throw e
                request("/rest/v1/rpc/$name", body, accessToken(token, generation))
            }
        synchronized(sessionLock) {
            if (generation != sessionGeneration)
                throw kotlinx.coroutines.CancellationException("Account changed")
            if (name == "ctfly_snapshot") {
                session?.let {
                    it.put("cached_snapshot", JSONObject(response.toString()))
                    vault.save(it)
                }
            }
        }
        return response
    }

    override suspend fun service(body: JSONObject): JSONObject {
        val generation = sessionGeneration
        val token = accessToken(expectedGeneration = generation)
        val response =
            try {
                request("/functions/v1/club-services", body, token)
            } catch (e: ApiFailure) {
                if (e.status != 401) throw e
                request("/functions/v1/club-services", body, accessToken(token, generation))
            }
        if (generation != sessionGeneration)
            throw kotlinx.coroutines.CancellationException("Account changed")
        return response
    }

    override suspend fun uploadPoster(bytes: ByteArray): String =
        service(
                obj(
                    "operation" to "poster",
                    "base64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
            )
            .text("url")

    override suspend fun login(email: String, password: String) =
        authMutex.withLock {
            val generation = sessionGeneration
            saveSession(
                request(
                    "/auth/v1/token?grant_type=password",
                    obj("email" to email.trim(), "password" to password),
                ),
                generation,
                replacing = true,
            )
        }

    override suspend fun staffDevice(body: JSONObject, authenticated: Boolean): JSONObject {
        if (!authenticated) return request("/functions/v1/staff-device-auth", body)
        val generation = sessionGeneration
        val token = accessToken(expectedGeneration = generation)
        val response =
            try {
                request("/functions/v1/staff-device-auth", body, token)
            } catch (e: ApiFailure) {
                if (e.status != 401) throw e
                request("/functions/v1/staff-device-auth", body, accessToken(token, generation))
            }
        if (generation != sessionGeneration)
            throw kotlinx.coroutines.CancellationException("Account changed")
        return response
    }

    override suspend fun loginDevice(body: JSONObject) =
        authMutex.withLock {
            val generation = sessionGeneration
            val result = request("/functions/v1/staff-device-auth", body)
            require(result.has("access_token") && result.has("refresh_token")) {
                "Device sign-in failed."
            }
            saveSession(result, generation, replacing = true)
        }

    override suspend fun signup(
        email: String,
        password: String,
        name: String,
        dob: String,
        phone: String,
    ): Boolean =
        authMutex.withLock {
            val generation = sessionGeneration
            val result =
                request(
                    "/auth/v1/signup",
                    obj(
                        "email" to email.trim(),
                        "password" to password,
                        "data" to obj("name" to name, "dob" to dob, "phone" to phone),
                    ),
                )
            saveSession(result, generation, replacing = true)
            result.has("access_token")
        }

    override suspend fun recover(email: String) {
        request("/auth/v1/recover", obj("email" to email.trim()))
    }

    override suspend fun resend(email: String) {
        request("/auth/v1/resend", obj("email" to email.trim(), "type" to "signup"))
    }

    override suspend fun verify(email: String, code: String, type: String) =
        authMutex.withLock {
            val generation = sessionGeneration
            saveSession(
                request(
                    "/auth/v1/verify",
                    obj("email" to email.trim(), "token" to code.trim(), "type" to type),
                ),
                generation,
                replacing = true,
            )
        }

    override suspend fun newPassword(password: String) {
        request("/auth/v1/user", obj("password" to password), accessToken(), "PUT")
    }

    override suspend fun logout() {
        try {
            if (signedIn) request("/auth/v1/logout?scope=local", null, accessToken())
        } catch (_: Exception) {
            // Explicit logout clears this device even when the network is unavailable.
        } finally {
            localLogout()
        }
    }

    override fun localLogout() =
        synchronized(sessionLock) {
            sessionGeneration++
            session = null
            vault.clear()
        }
}
