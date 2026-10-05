package io.github.dgproman.pihome.hub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The hub scripts/contract-test.sh started for this run, as it describes it.
 *
 * A new hub each run: a fresh database holding one admin, the house in
 * src/contractTest/hub, and nothing else. Tests may count on that much and on
 * nothing another test leaves behind.
 *
 * The hub locks an address out after ten failed authentications in five
 * minutes, and every test starts by signing in, which would then be refused.
 * The few refusals the tests ask for on purpose stay well under it, and each
 * success clears the count.
 */
class ContractHub private constructor(
    origin: String,
    val admin: String,
    val password: String,
    private val sensorKey: String,
) {
    val address: HubAddress =
        when (val parsed = HubAddress.parse(origin)) {
            is Parsed.Valid -> parsed.value
            is Parsed.Invalid -> fail("PIHOME_CONTRACT_HUB is not an address the client takes: ${parsed.problem}")
        }

    /** One for the whole run, as the app has. */
    private val http = OkHttpClient()

    fun client(token: SessionToken?): HubClient = HubClient(address, token, http)

    fun anonymous(): HubClient = client(token = null)

    /** A client signed in as the admin. */
    suspend fun signIn(): HubClient = client(anonymous().logIn(admin, password).token)

    /**
     * Report a reading as a sensor would, with the sensor key.
     *
     * The one thing done here that the app never does: the house needs a reading
     * in it before the app's view of one can be checked.
     */
    suspend fun pushReading(
        sensor: String,
        json: String,
    ) {
        val request =
            Request
                .Builder()
                .url("${address.origin}/v1/sensors/$sensor/readings")
                .header("X-API-Key", sensorKey)
                .post(json.toRequestBody(JSON))
                .build()
        send(request).use { assertEquals(202, it.code, "the hub took no reading for $sensor") }
    }

    /** A request exactly as written, without anything the client would add. */
    suspend fun send(request: Request): Response = withContext(Dispatchers.IO) { http.newCall(request).execute() }

    companion object {
        val JSON = "application/json".toMediaType()

        /** Read once, so that each test does not ask the environment again. */
        val current: ContractHub by lazy {
            ContractHub(
                origin = required("PIHOME_CONTRACT_HUB"),
                admin = required("PIHOME_CONTRACT_ADMIN"),
                password = required("PIHOME_CONTRACT_PASSWORD"),
                sensorKey = required("PIHOME_CONTRACT_SENSOR_KEY"),
            )
        }

        private fun required(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: fail("$name is not set. These tests need a running hub: start them with scripts/contract-test.sh")
    }
}
