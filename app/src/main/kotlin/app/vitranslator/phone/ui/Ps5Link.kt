package app.vitranslator.phone.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.vitranslator.phone.Prefs
import app.vitranslator.phone.R
import app.vitranslator.phone.ps.Ps5
import app.vitranslator.phone.ps.Psn
import app.vitranslator.phone.ps.chiaki.Chiaki
import app.vitranslator.phone.ps.chiaki.DiscoveryHost
import app.vitranslator.phone.ps.chiaki.DiscoveryService
import app.vitranslator.phone.ps.chiaki.Regist

/**
 * Linking a PS5, once, in three steps, each done before the next opens: sign in to PSN with a second
 * account (in the app, or in a browser when Sony's check refuses the app), find it on the home network
 * (searched at once; or its address typed), then the PIN the PS5 shows, and Link. Linked: the
 * console, with Wake, the stream's quality and Unlink.
 */
class Ps5Link(private val h: Host, private val redraw: () -> Unit) {
    private val k get() = h.k
    private var discovery: DiscoveryService? = null
    private var regist: Regist? = null
    private var searched = false
    private var hosts: List<DiscoveryHost> = emptyList()
    private var searching = false

    /** the address chosen (a console found, or typed) */
    private var address: String
        get() = h.act.getSharedPreferences("ps5", Activity.MODE_PRIVATE).getString("address", "") ?: ""
        set(v) = h.act.getSharedPreferences("ps5", Activity.MODE_PRIVATE).edit().putString("address", v.trim()).apply()

    /** the console's name, when it was found */
    private var name: String
        get() = h.act.getSharedPreferences("ps5", Activity.MODE_PRIVATE).getString("name", "") ?: ""
        set(v) = h.act.getSharedPreferences("ps5", Activity.MODE_PRIVATE).edit().putString("name", v).apply()

    private var editingAddress = false
    private var editingAccount = false
    private var linkMsg: TextView? = null
    private var pinInput: EditText? = null
    private var linking = false

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(top) }

    fun build(into: LinearLayout) {
        if (!Chiaki.available) {
            into.addView(k.callout(T("PS Remote Play isn't in this build of the app."), k.err).apply { layoutParams = lp(12) })
            return
        }
        val saved = Ps5.saved(h.act)
        if (saved != null && !showSteps) {
            linked(into, saved)
            return
        }
        if (!searched) search()
        val addressOk = validAddress(address)
        val accountOk = validAccount(Ps5.accountId(h.act))
        // (PSN first, as Sony's own app: the step that may take a few tries; finding the PS5 next, then
        // its PIN at once, which lasts only about 5 minutes)
        into.addView(step(1, T("Sign in to PSN"), accountOk && !editingAccount, true, signInStep(accountOk)))
        into.addView(step(2, T("Find your PS5"), addressOk && !editingAddress, accountOk && !editingAccount, findStep(addressOk)))
        into.addView(step(3, T("Enter the PIN from the PS5"), false, addressOk && accountOk && !editingAddress && !editingAccount, pinStep()))
    }

    // ---- the frame of a step: its number (or ✓), its title, then what it asks

    private fun step(n: Int, title: String, done: Boolean, open: Boolean, content: View): View {
        val badge = k.text(if (done) "✓" else n.toString(), k.base, if (done || open) Color.WHITE else k.muted, k.bold).apply {
            gravity = Gravity.CENTER
            background = when {
                done -> k.round(k.ok, 999)
                open -> k.sunset(999)
                else -> k.round(k.panel3, 999)
            }
            layoutParams = LinearLayout.LayoutParams(k.dp(30), k.dp(30))
        }
        val head = k.row(badge, k.grow(k.text(title, k.base + 1f, if (open || done) k.text else k.muted, k.bold)), gap = 12)
        val card = k.card(14, head)
        card.layoutParams = lp(12)
        if (!open && !done) {
            card.alpha = 0.6f
            return card
        }
        card.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) })
        if (open && !done) card.background = k.round(k.panel, 14, k.accentUi, 1.5)
        return card
    }

    // ---- 1. find it

    private fun findStep(addressOk: Boolean): View {
        val c = k.column()
        if (addressOk && !editingAddress) {
            c.addView(k.row(
                k.icon(R.drawable.ic_source, k.ok, 20),
                k.grow(k.column(
                    k.text(name.ifEmpty { "PS5" }, k.base, k.text, k.bold),
                    k.muted(address, small = true).apply { textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START },
                )),
                k.link(T("Change")) {
                    editingAddress = true
                    redraw()
                },
                gap = 10,
            ))
            return c
        }
        c.addView(k.muted(T("The PS5 must be on (or in rest mode) and on the same network as this phone."), small = true))
        // the consoles found
        if (searching) c.addView(k.row(
            android.widget.ProgressBar(h.act).apply { layoutParams = LinearLayout.LayoutParams(k.dp(20), k.dp(20)) },
            k.muted(T("Looking for PlayStation consoles…"), small = true),
            gap = 10,
        ).apply { layoutParams = lp(10) })
        for (x in hosts) {
            val addr = x.hostAddr ?: continue
            val standby = x.state == DiscoveryHost.State.STANDBY
            c.addView(k.row(
                k.icon(R.drawable.ic_source, k.accentUi, 22),
                k.grow(k.column(
                    k.text(x.hostName ?: "PS5", k.base, k.text, k.bold),
                    k.muted("$addr · " + T(if (standby) "rest mode" else "on"), small = true).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START },
                )),
                k.button(T("Choose"), primary = true) {
                    address = addr
                    name = x.hostName ?: "PS5"
                    editingAddress = false
                    redraw()
                },
                gap = 10,
            ).apply {
                background = k.round(k.panel2, 12, k.line)
                setPadding(k.dp(12), k.dp(8), k.dp(8), k.dp(8))
                layoutParams = lp(8)
            })
        }
        if (!searching && hosts.isEmpty()) c.addView(k.callout(T("No PS5 found yet. Is it on, with Remote Play allowed (Settings › System › Remote Play)? You can also type its address below.")).apply { layoutParams = lp(10) })
        if (!searching) c.addView(k.link(T("Search again")) { search() })
        // or typed
        val typed = k.input(address, "192.168.1.20", ltr = true) {}
        c.addView(k.field(T("Or type its address"), typed, T("On the PS5: Settings › Network › Connection Status › View Connection Status, IPv4 address.")))
        c.addView(k.button(T("Use this address")) {
            val a = typed.text.toString().trim()
            if (!validAddress(a)) {
                android.widget.Toast.makeText(h.act, T("That isn't an address like 192.168.1.20."), android.widget.Toast.LENGTH_LONG).show()
                return@button
            }
            address = a
            name = hosts.firstOrNull { it.hostAddr == a }?.hostName ?: ""
            editingAddress = false
            redraw()
        }.apply { layoutParams = lp(10) })
        return c
    }

    private fun search() {
        searched = true
        stop()
        searching = true
        hosts = emptyList()
        discovery = Ps5.discover { found -> h.ui { hosts = found; if (found.isNotEmpty()) { searching = false; stop() }; redraw() } }
        h.act.window.decorView.postDelayed({
            if (searching) {
                searching = false
                stop()
                redraw()
            }
        }, 8000)
    }

    // ---- 2. PSN

    private fun signInStep(accountOk: Boolean): View {
        val c = k.column()
        if (accountOk && !editingAccount) {
            c.addView(k.row(
                k.icon(R.drawable.ic_check, k.ok, 20),
                k.grow(k.column(
                    k.text(T("Signed in: the account ID is ready"), k.base, k.text, k.bold),
                    k.muted(Ps5.accountId(h.act), small = true).apply { textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START },
                )),
                k.link(T("Change")) {
                    editingAccount = true
                    redraw()
                },
                gap = 10,
            ))
            return c
        }
        c.addView(k.callout(T("Sign in with a second PSN account, not the one you play with: Remote Play signs it in on the PS5. Only its account ID is kept."), k.warn))
        // This edition has no PSN sign-in inside the app: the password is only ever typed in the
        // user's own browser, on Sony's page. The app gets the one-time code from the address the
        // browser ends on, or the account ID typed below.
        c.addView(k.muted(T("Your PSN password is never typed in this app: you sign in on Sony's page in your own browser."), small = true).apply { setPadding(0, k.dp(8), 0, 0) })
        c.addView(k.button(T("Sign in with a browser"), primary = true) { pasteAddress() }.apply { layoutParams = lp(10) })
        // (an account ID already known: typed or pasted)
        val typed = k.input(Ps5.accountId(h.act), "", ltr = true) {}
        c.addView(k.field(T("Or type the account ID"), typed))
        c.addView(k.button(T("Use this account ID")) {
            val v = typed.text.toString().trim()
            if (!validAccount(v)) {
                android.widget.Toast.makeText(h.act, T("That isn't an account ID (12 letters and digits, ending in =)."), android.widget.Toast.LENGTH_LONG).show()
                return@button
            }
            Ps5.saveAccountId(h.act, v)
            editingAccount = false
            redraw()
        }.apply { layoutParams = lp(8) })
        return c
    }

    fun result(requestCode: Int, resultCode: Int, data: Intent?) {
        // (nothing comes back here in this edition: there is no sign-in screen inside the app)
    }

    /**
     * Sony's sign-in in a browser instead of in the app (its bot check sometimes refuses the app's):
     * the browser ends on remoteplay.dl.playstation.net/remoteplay/redirect?code=…; that address
     * pasted here gives the account ID (the code is used once, within a few minutes).
     */
    private fun pasteAddress() {
        val e = k.input("", "https://remoteplay.dl.playstation.net/remoteplay/redirect?code=…", ltr = true) {}
        val msg = k.muted("", small = true)
        val link = Psn.loginUrl
        var d: android.app.Dialog? = null
        d = k.dialog(k.card(18,
            k.text(T("Sign in with a browser"), k.base + 0.5f, k.text, k.bold),
            k.muted(T("1. Open Sony's sign-in in a browser (on this phone or a PC) and sign in with your second PSN account."), small = true).apply { setPadding(0, k.dp(8), 0, 0) },
            k.row(
                k.grow(k.button(T("Open in the browser")) {
                    try {
                        h.act.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
                    } catch (ex: Exception) {
                        msg.text = ex.message
                    }
                }),
                k.grow(k.button(T("Copy the link")) {
                    h.act.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("PSN", link))
                    msg.text = T("Copied.")
                }),
            ).apply { setPadding(0, k.dp(8), 0, 0) },
            k.muted(T("2. It ends on a page whose address starts with https://remoteplay.dl.playstation.net (the page itself may be blank). Copy that address and paste it here:"), small = true).apply { setPadding(0, k.dp(12), 0, k.dp(6)) },
            e, msg, k.gap(10),
            k.button(T("Get the account ID"), primary = true) {
                val code = Regex("[?&]code=([^&#\\s]+)").find(e.text.toString().trim())?.groupValues?.get(1)
                if (code == null) {
                    msg.text = T("That address has no sign-in code: copy the whole address of the page you ended on.")
                    return@button
                }
                msg.text = T("Getting the account ID…")
                h.io {
                    val r = try {
                        Psn.accountId(android.net.Uri.decode(code)) to null
                    } catch (ex: Exception) {
                        null to (ex.message ?: ex.toString())
                    }
                    h.ui {
                        val id = r.first
                        if (id != null) {
                            Ps5.saveAccountId(h.act, id)
                            editingAccount = false
                            d?.dismiss()
                            redraw()
                        } else {
                            msg.text = T("PSN sign-in didn't work: {e}", "e" to (r.second ?: "")) + "\n" + T("The code works once and only for a few minutes: sign in again and paste the new address.")
                        }
                    }
                }
            },
        ))
        d.show()
    }

    // ---- 3. the PIN, and Link

    private fun pinStep(): View {
        val c = k.column()
        c.addView(k.muted(T("On the PS5: Settings › System › Remote Play › Link Device. It shows an 8-digit PIN, for about 5 minutes."), small = true))
        val p = k.input("", "0000 0000", number = true, ltr = true) {}.apply {
            textSize = k.base + 12f
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            typeface = k.bold
            filters = arrayOf(InputFilter.LengthFilter(8))
        }
        pinInput = p
        c.addView(p, LinearLayout.LayoutParams(-1, -2).apply { topMargin = k.dp(10) })
        val msg = k.text("", k.base - 0.5f, k.muted).apply { setPadding(0, k.dp(8), 0, 0) }
        linkMsg = msg
        c.addView(msg)
        c.addView(k.button(T("Link"), primary = true) { link() }.apply { layoutParams = lp(8) })
        return c
    }

    private fun link() {
        if (linking) return
        val p = pinInput?.text?.toString()?.filter { it.isDigit() } ?: ""
        val msg = linkMsg ?: return
        if (p.length != 8) {
            msg.text = T("The PIN has 8 digits.")
            msg.setTextColor(k.err)
            return
        }
        linking = true
        msg.text = T("Linking…")
        msg.setTextColor(k.muted)
        regist?.dispose()
        regist = Ps5.register(address, Ps5.accountId(h.act), p.toInt(), { line -> android.util.Log.i("Regist", line) }) { console ->
            h.ui {
                linking = false
                if (console != null) {
                    Ps5.save(h.act, console)
                    redraw()
                } else {
                    msg.text = T("The PS5 refused: check the PIN (it changes every few minutes) and that Remote Play is on.")
                    msg.setTextColor(k.err)
                }
            }
        }
        if (regist == null) {
            linking = false
            msg.text = T("The PS5 refused: check the PIN (it changes every few minutes) and that Remote Play is on.")
            msg.setTextColor(k.err)
        }
    }

    // ---- linked

    private fun linked(into: LinearLayout, c: app.vitranslator.phone.ps.PsConsole) {
        val icon = k.icon(R.drawable.ic_source, k.ok, 26).apply {
            background = k.round(0x2228c08a, 12)
            setPadding(k.dp(10), k.dp(10), k.dp(10), k.dp(10))
            layoutParams = LinearLayout.LayoutParams(k.dp(48), k.dp(48))
            scaleType = android.widget.ImageView.ScaleType.CENTER
        }
        val card = k.card(14,
            k.row(icon, k.grow(k.column(
                k.text(c.nickname.ifEmpty { "PS5" }, k.base + 1.5f, k.text, k.bold),
                k.text("✓ " + T("Linked") + " · " + c.host, k.base - 1f, k.ok, k.bold).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START },
            )), gap = 12),
            k.muted(T("Start translating connects to it with Remote Play. If it is in rest mode, Wake turns it on."), small = true).apply { setPadding(0, k.dp(10), 0, 0) },
            k.row(
                k.grow(k.button(T("Wake the PS5")) {
                    Ps5.wakeup(c)
                    android.widget.Toast.makeText(h.act, T("Waking {name}…", "name" to c.nickname.ifEmpty { "PS5" }), android.widget.Toast.LENGTH_SHORT).show()
                }),
                k.grow(k.button(T("Unlink"), danger = true) {
                    k.ask(T("Unlink {name}? To use it again, link it with a new PIN.", "name" to c.nickname.ifEmpty { "PS5" }), T("Unlink")) {
                        Ps5.save(h.act, null)
                        searched = false
                        redraw()
                    }
                }),
            ).apply { layoutParams = lp(12) },
        )
        card.layoutParams = lp(12)
        into.addView(card)
        val prefs = Prefs(h.act)
        into.addView(k.field(T("Stream quality"), k.select(listOf("1080" to "1080p", "720" to "720p"), prefs.psResolution.toString()) { prefs.psResolution = it.toInt() },
            T("1080p reads small text best; 720p for a slower home network.")))
    }

    private fun stop() {
        discovery?.dispose()
        discovery = null
    }

    fun close() {
        stop()
        regist?.dispose()
    }

    companion object {
        const val REQ_PSN = 11

        /** debug builds: the linking steps shown even with a PS5 linked (to look at them) */
        @Volatile
        var showSteps = false

        fun validAddress(a: String) = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(a.trim())?.groupValues?.drop(1)?.all { it.toInt() <= 255 } == true

        /** a PSN account ID: 8 bytes in base64 (12 characters) */
        fun validAccount(a: String) = try {
            java.util.Base64.getDecoder().decode(a.trim()).size == 8
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
