package app.vitranslator.phone.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A stand-in for the shared Rust core, until its Android build lands: the same calls, with the
 * real TV connection (TV apps can't tell it from the PC app), but no translation memory: a line is
 * "translated" from a few built-in phrases, else shown as read, marked with the language. Enough to
 * build and test everything around it (capture, text recognition, the TVs, the screens).
 */
class StubCore(context: Context, private val recognizer: Recognizer) : Core {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("stub-core", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private var language = "ar"
    private val lines = ArrayList<Line>()
    private var nextId = 1L
    private val lastText = HashMap<String, String>()

    @Volatile
    private var listener: CoreListener? = null

    @Volatile
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    /** bumped on every change of what the TVs show (their page asks with the last one it saw) */
    @Volatile
    private var version = 0L
    private val changed = Object()

    override val name = "stand-in"

    override fun games() = listOf(Game("gta6", "Grand Theft Auto VI"))

    override fun languages() = LANGUAGES

    override fun prepare(game: String, language: String) {
        if (LANGUAGES.none { it.code == language }) throw CoreException("unknown language $language")
        synchronized(this) {
            this.language = language
            lastText.clear()
        }
        bump()
    }

    override fun processFrame(rgba: ByteArray, width: Int, height: Int): Long {
        val now = System.currentTimeMillis()
        var added = false
        for (a in AREAS) {
            val (l, t, r, b) = boundsOf(a, width, height).toList()
            if (r - l < 8 || b - t < 8) continue
            val text = textOf(recognizer.recognize(crop(rgba, width, l, t, r, b), r - l, b - t))
            synchronized(this) {
                if (text != lastText[a.id]) {
                    lastText[a.id] = text
                    if (text.isNotEmpty()) {
                        lines.add(Line(nextId++, a.id, text, translate(text, language), now))
                        while (lines.size > 200) lines.removeAt(0)
                        added = true
                    }
                }
            }
        }
        if (added) {
            bump()
            listener?.linesChanged()
        }
        return 250
    }

    override fun noPicture() {
        synchronized(this) { lastText.clear() }
        bump()
    }

    override fun configJson(): String = synchronized(this) {
        val c = try {
            JSONObject(prefs.getString("config", "{}"))
        } catch (e: Exception) {
            JSONObject()
        }
        val profile = JSONObject().put("id", "gta6").put("name", "Grand Theft Auto VI").put("game", "gta6")
            .put("areas", JSONArray(AREAS.map {
                JSONObject().put("id", it.id).put("name", it.id).put("enabled", true).put("x", it.left.toDouble()).put("y", it.top.toDouble())
                    .put("w", (it.right - it.left).toDouble()).put("h", (it.bottom - it.top).toDouble()).put("placement", "over").put("font_scale", 1.0)
            }))
            .put("style", JSONObject().put("font_family", "Tajawal").put("font_size", 26.0).put("bold", true).put("text_color", "#ffffff")
                .put("bg_color", "#000000").put("bg_opacity", 0.65).put("outline_color", "#000000").put("outline_width", 0.0).put("shadow", true)
                .put("padding", 10.0).put("radius", 10.0).put("line_height", 1.5).put("keep_colors", true))
            .put("pages", JSONObject()).put("timing", JSONObject())
        val defaults = JSONObject().put("engine", "local").put("target_lang", language).put("overlays_enabled", true).put("live_translations", true)
            .put("theme", "dark").put("ui_lang", "").put("capture_fps", 12).put("preview_fps", 30).put("interval_ms", 250).put("stable_ms", 300)
            .put("clear_after_ms", 800).put("builtins", JSONArray().put(profile)).put("profiles", JSONArray()).put("profile", "gta6")
            .put("areas", profile.getJSONArray("areas")).put("style", profile.getJSONObject("style")).put("profile_opts", JSONObject())
        for (k in c.keys()) defaults.put(k, c.get(k))
        defaults.toString()
    }

    override fun updateConfig(patch: String) {
        synchronized(this) {
            val c = try {
                JSONObject(prefs.getString("config", "{}"))
            } catch (e: Exception) {
                JSONObject()
            }
            val p = JSONObject(patch)
            for (k in p.keys()) c.put(k, p.get(k))
            prefs.edit().putString("config", c.toString()).apply()
            if (p.has("target_lang")) language = p.getString("target_lang")
        }
    }

    override fun service(): Pair<Service, String> {
        val s = Service.entries.firstOrNull { it.name == prefs.getString("service", null) } ?: Service.MEMORY
        return s to (prefs.getString("service_key", "") ?: "")
    }

    override fun setService(service: Service, key: String) {
        prefs.edit().putString("service", service.name).putString("service_key", key).apply()
    }

    override fun lines(max: Int): List<Line> = synchronized(this) { lines.takeLast(max) }

    override fun setListener(listener: CoreListener?) {
        this.listener = listener
    }

    // ---- the TVs

    override fun startServer(): Int {
        synchronized(this) {
            if (server == null) {
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PAGE_PORT))
                }
                pool.execute(::serve)
            }
        }
        return PAGE_PORT
    }

    override fun stopServer() {
        val s = synchronized(this) { server.also { server = null } }
        try {
            s?.close()
        } catch (ignored: IOException) {
            // closing
        }
    }

    override fun findTvs(): List<Tv> = TvLink.scan(ctx)

    override fun pairTv(address: String, code: String): PairResult {
        val to = TvLink.normalize(address) ?: return PairResult.UNREACHABLE
        if (server == null) {
            try {
                startServer()
            } catch (e: java.io.IOException) {
                return PairResult.PORT_BUSY
            }
        }
        val pc = advertise.ifEmpty { TvLink.ownAddress(ctx)?.hostAddress?.let { "$it:$PAGE_PORT" } } ?: return PairResult.UNREACHABLE
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        val (result, tv) = TvLink.pair(to, code.trim(), pc, token, deviceName())
        if (result == PairResult.OK && tv != null) {
            val all = stored().filter { it.getString("id") != tv.id || tv.id.isEmpty() }.toMutableList()
            all.add(JSONObject().put("id", tv.id).put("name", tv.name).put("address", tv.address).put("token", token))
            store(all)
        }
        return result
    }

    /**
     * The address given to the TVs for this phone's page, instead of its own (debug builds, for
     * emulators behind NAT: "10.0.2.2:8795" with adb forwarding). Empty: its own.
     */
    var advertise: String
        get() = prefs.getString("advertise", "") ?: ""
        set(v) = prefs.edit().putString("advertise", v).apply()

    override fun pairedTvs(): List<Tv> = stored().map { Tv(it.optString("id"), it.optString("name"), it.optString("address")) }

    override fun forgetTv(id: String) {
        store(stored().filter { it.optString("id") != id })
        bump()
    }

    private fun stored(): List<JSONObject> {
        val a = try {
            JSONArray(prefs.getString("tvs", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun store(tvs: List<JSONObject>) {
        prefs.edit().putString("tvs", JSONArray(tvs).toString()).apply()
    }

    private fun deviceName(): String {
        val n = try {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: RuntimeException) {
            null
        }
        return (n?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL).take(60)
    }

    private fun bump() {
        synchronized(changed) {
            version++
            changed.notifyAll()
        }
    }

    // ---- the TV page (http://phone:8795/tv?t=key), as the PC app serves it (on its own port here)

    private fun serve() {
        while (true) {
            val s = server ?: return
            val c = try {
                s.accept()
            } catch (e: IOException) {
                return
            }
            pool.execute {
                try {
                    c.use {
                        it.soTimeout = 5000
                        it.tcpNoDelay = true
                        handle(it)
                    }
                } catch (e: IOException) {
                    // the TV went away
                }
            }
        }
    }

    private fun handle(c: Socket) {
        val input = BufferedInputStream(c.getInputStream())
        val request = line(input) ?: return
        while (true) {
            if (line(input).isNullOrEmpty()) break
        }
        val out = c.getOutputStream()
        val parts = request.split(" ")
        if (parts.size < 2 || parts[0] != "GET") return respond(out, 405, "text/plain", "")
        val path = parts[1].substringBefore('?')
        val query = parts[1].substringAfter('?', "")
        val token = param(query, "t")
        val allowed = token.isNotEmpty() && stored().any { it.optString("token") == token }
        when (path) {
            "/tv", "/tv.json", "/tv/forget" -> if (!allowed) return respond(out, 403, "text/plain", "")
            else -> return respond(out, 404, "text/plain", "")
        }
        when (path) {
            "/tv" -> respond(out, 200, "text/html; charset=utf-8", PAGE)
            "/tv/forget" -> {
                store(stored().filter { it.optString("token") != token })
                respond(out, 200, "application/json", "{}")
            }
            else -> {
                // (waits up to a second for a change, as the PC app does)
                val seen = param(query, "h").toLongOrNull()
                val until = SystemClock.elapsedRealtime() + 1000
                synchronized(changed) {
                    while (seen == version && SystemClock.elapsedRealtime() < until) {
                        changed.wait((until - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                    }
                }
                if (seen == version) return respond(out, 204, "text/plain", "")
                val v = version
                respond(out, 200, "application/json; charset=utf-8", state().toString(), "X-State: $v\r\n")
            }
        }
    }

    /** What the TVs draw: the latest line of each area, translated. */
    private fun state(): JSONObject {
        val shown = JSONObject()
        val rtl: Boolean
        synchronized(this) {
            rtl = LANGUAGES.firstOrNull { it.code == language }?.rtl ?: false
            for (a in AREAS) {
                val l = lines.lastOrNull { it.areaId == a.id } ?: continue
                if (lastText[a.id].isNullOrEmpty()) continue
                shown.put(a.id, l.translated.ifEmpty { l.original })
            }
        }
        val areas = JSONArray()
        for (a in AREAS) {
            areas.put(JSONObject().put("id", a.id).put("x", a.left).put("y", a.top).put("w", a.right - a.left).put("h", a.bottom - a.top))
        }
        return JSONObject().put("build", BUILD).put("overlays", true).put("lang", language).put("rtl", rtl)
            .put("areas", areas).put("shown", shown)
    }

    /** A part of the picture to read, in fractions of the frame. */
    data class Area(val id: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

    companion object {
        const val PAGE_PORT = RustCore.PORT

        /** this run (the TV page reloads itself when it changes, as with the PC app) */
        private val BUILD = SecureRandom().nextLong().toString(16)

        val AREAS = listOf(
            // GTA's subtitles: bottom middle
            Area("subtitle", 0.12f, 0.80f, 0.88f, 0.96f),
            // help and objectives: the box at the top left
            Area("help", 0.02f, 0.03f, 0.36f, 0.24f),
        )

        val LANGUAGES = listOf(
            Language("ar", "العربية", true),
            Language("es", "Español", false),
            Language("pt-BR", "Português (Brasil)", false),
            Language("fr", "Français", false),
            Language("de", "Deutsch", false),
            Language("it", "Italiano", false),
            Language("tr", "Türkçe", false),
            Language("ru", "Русский", false),
            Language("pl", "Polski", false),
            Language("uk", "Українська", false),
            Language("nl", "Nederlands", false),
            Language("id", "Bahasa Indonesia", false),
            Language("vi", "Tiếng Việt", false),
            Language("th", "ไทย", false),
            Language("hi", "हिन्दी", false),
            Language("fa", "فارسی", true),
            Language("ur", "اردو", true),
            Language("ja", "日本語", false),
            Language("ko", "한국어", false),
            Language("zh-Hans", "简体中文", false),
        )

        private val PHRASES = mapOf(
            "ar" to mapOf(
                "let's go" to "هيا بنا",
                "get in the car" to "اركب السيارة",
                "go to the marker" to "اذهب إلى العلامة",
                "follow him" to "اتبعه",
                "watch out" to "انتبه",
            ),
        )

        /** The area's rectangle in pixels (left, top, right, bottom), inside the frame. */
        fun boundsOf(a: Area, w: Int, h: Int): IntArray {
            val l = (a.left * w).toInt().coerceIn(0, w - 1)
            val t = (a.top * h).toInt().coerceIn(0, h - 1)
            val r = (a.right * w).toInt().coerceIn(l + 1, w)
            val b = (a.bottom * h).toInt().coerceIn(t + 1, h)
            return intArrayOf(l, t, r, b)
        }

        /** A part of an RGBA picture, made grey (as the core gives its recognizer). */
        fun crop(rgba: ByteArray, width: Int, l: Int, t: Int, r: Int, b: Int): ByteArray {
            val out = ByteArray((r - l) * (b - t) * 4)
            var o = 0
            for (y in t until b) {
                var i = (y * width + l) * 4
                for (x in l until r) {
                    val g = ((rgba[i].toInt() and 255) * 77 + (rgba[i + 1].toInt() and 255) * 150 + (rgba[i + 2].toInt() and 255) * 29) shr 8
                    out[o] = g.toByte()
                    out[o + 1] = g.toByte()
                    out[o + 2] = g.toByte()
                    out[o + 3] = -1
                    o += 4
                    i += 4
                }
            }
            return out
        }

        /** The words of one area as its text: line by line, words left to right. */
        fun textOf(words: List<Word>): String =
            words.groupBy { it.line }.toSortedMap().values
                .joinToString(" ") { l -> l.sortedBy { it.left }.joinToString(" ") { it.text } }
                .replace(Regex("\\s+"), " ").trim()

        fun translate(text: String, language: String): String {
            val key = text.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')
            PHRASES[language]?.get(key)?.let { return it }
            return "[$language] $text"
        }

        private fun param(query: String, name: String): String {
            for (p in query.split('&')) {
                val k = p.substringBefore('=')
                if (k == name) return try {
                    URLDecoder.decode(p.substringAfter('=', ""), "UTF-8")
                } catch (e: IllegalArgumentException) {
                    ""
                }
            }
            return ""
        }

        private fun line(input: InputStream): String? {
            val b = ByteArrayOutputStream()
            var c: Int
            while (true) {
                c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) b.write(c)
                if (b.size() > 8192) throw IOException("line too long")
            }
            if (c < 0 && b.size() == 0) return null
            return b.toString("ISO-8859-1")
        }

        private fun respond(out: OutputStream, status: Int, type: String, body: String, extra: String = "") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val reason = when (status) {
                200 -> "OK"
                204 -> "No Content"
                403 -> "Forbidden"
                405 -> "Method Not Allowed"
                else -> "Not Found"
            }
            val head = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n$extra" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }

        /** The TV page: transparent, the translation of each area drawn over the game where it was read. */
        private val PAGE = """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>VI Translator</title>
<style>
html,body{margin:0;height:100%;background:transparent;overflow:hidden;font-family:sans-serif}
.band{position:fixed;display:flex;align-items:center;justify-content:center}
.box{background:rgba(0,0,0,.72);color:#fff;border-radius:.3em;padding:.15em .5em;text-align:center;
 font-size:calc(100vh/1080*40);line-height:1.35;text-shadow:0 0 .1em #000}
</style></head><body><div id="a"></div>
<script>
const key=new URLSearchParams(location.search).get("t")||"";let v="",build=null;const el=document.getElementById("a");
function alive(){if(window.VITTV)window.VITTV.alive();try{if(window.parent!==window)window.parent.postMessage({vit:"alive"},"*")}catch(e){}}
function draw(s){el.innerHTML="";if(!s||s.overlays===false)return;for(const a of s.areas){const t=s.shown[a.id];if(!t)continue;
 const b=document.createElement("div");b.className="band";Object.assign(b.style,{left:a.x*100+"vw",top:a.y*100+"vh",width:a.w*100+"vw",height:a.h*100+"vh"});
 const x=document.createElement("div");x.className="box";x.dir=s.rtl?"rtl":"ltr";x.textContent=t;b.appendChild(x);el.appendChild(b)}}
const timed=(p,ms)=>Promise.race([p,new Promise((_,n)=>setTimeout(()=>n(new Error("no answer")),ms))]);
async function poll(){let w=0;try{const r=await timed(fetch("tv.json?t="+encodeURIComponent(key)+"&h="+encodeURIComponent(v),{cache:"no-store"}),6000);alive();
 if(r.status===200){const s=await timed(r.json(),6000);v=r.headers.get("X-State")||"";const re=build!==null&&s.build!==build;build=s.build;draw(s);if(re)location.reload()}
 else if(r.status!==204){draw(null);v="";w=2000}}catch(e){draw(null);v="";w=1000}setTimeout(poll,w)}
poll();
</script></body></html>
""".trimIndent()
    }
}
