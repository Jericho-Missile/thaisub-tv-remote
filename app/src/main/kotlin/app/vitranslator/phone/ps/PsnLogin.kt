package app.vitranslator.phone.ps

import java.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The PSN account ID, which registering with a PS5 needs: the user signs in on Sony's own page
 * in their own browser, never inside this app (this edition has no sign-in screen), and pastes
 * the address the browser ends on, which carries a one-time code; the code gives a token,
 * and the token's details give the account's number (8 bytes, little-endian, in base64). The app
 * keeps only the account ID: no password, and the token is dropped.
 */
object Psn {
    // Sony's Remote Play app (the PC one), as used by chiaki-ng (gui/include/psnaccountid.h)
    private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
    private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
    const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
    private const val SCOPE = "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
    private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"

    val loginUrl: String
        get() = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize?service_entity=urn:service-entity:psn" +
            "&response_type=code&client_id=$CLIENT_ID&redirect_uri=${enc(REDIRECT)}&scope=${enc(SCOPE)}" +
            "&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private val basic = "Basic " + Base64.getEncoder().encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray())

    /** The account ID (base64) for the code Sony's page redirected with. Blocking. */
    fun accountId(code: String): String {
        val body = "grant_type=authorization_code&code=${enc(code)}&scope=${enc(SCOPE)}&redirect_uri=${enc(REDIRECT)}"
        val token = JSONObject(http(TOKEN_URL, body)).getString("access_token")
        val info = JSONObject(http("$TOKEN_URL/${enc(token)}", null))
        return accountIdOf(info.getString("user_id"))
    }

    /** The account's number (decimal) as Remote Play's account ID: 8 bytes little-endian, base64. */
    fun accountIdOf(userId: String): String {
        var n = userId.trim().toULong()
        val b = ByteArray(8)
        for (i in 0 until 8) {
            b[i] = (n and 0xffUL).toByte()
            n = n shr 8
        }
        return Base64.getEncoder().encodeToString(b)
    }

    private fun http(url: String, form: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("Authorization", basic)
            if (form != null) {
                val bytes = form.toByteArray()
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(bytes) }
            }
            val ok = c.responseCode in 200..299
            val text = (if (ok) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (!ok) throw IOException("PSN answered ${c.responseCode}: ${text.take(200)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
