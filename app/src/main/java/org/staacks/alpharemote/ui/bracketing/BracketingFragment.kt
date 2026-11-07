package org.staacks.alpharemote.ui.bracketing

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.camera.CameraActionPreset
import org.staacks.alpharemote.service.AlphaRemoteService
import kotlin.math.abs
import kotlin.math.floor

class BracketingFragment : Fragment() {
    private var pos = 0f            // geschaetzte Fokusposition in Sekunden Fahrt (+ = fern)
    private var start: Float? = null
    private var end: Float? = null
    private var step = 0.3f
    private var settle = 0.6f
    private lateinit var info: TextView
    private lateinit var go: Button

    private fun count(): Int {
        val s = start ?: return 0; val e = end ?: return 0
        if (abs(e - s) < 0.01f) return 0
        return floor(abs(e - s) / step).toInt() + 1
    }

    private fun refresh() {
        val n = count()
        info.text = "Position ≈ %.2f s\nStart: %s   Ende: %s\nSchritt: %.2f s  →  %s Aufnahmen"
            .format(pos, start?.let { "%.2f".format(it) } ?: "–", end?.let { "%.2f".format(it) } ?: "–",
                step, if (n > 0) n.toString() else "–")
        go.isEnabled = n >= 2
    }

    private fun holdButton(label: String, preset: CameraActionPreset, dir: Int): Button {
        val action = CameraAction(false, null, null, 1f, preset) // volle Geschwindigkeit
        var t0 = 0L
        return Button(requireContext()).apply {
            text = label
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        t0 = SystemClock.elapsedRealtime()
                        AlphaRemoteService.sendCameraAction(requireContext(), action, down = true, up = false)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        AlphaRemoteService.sendCameraAction(requireContext(), action, down = false, up = true)
                        pos += dir * (SystemClock.elapsedRealtime() - t0) / 1000f
                        refresh(); v.performClick()
                    }
                }
                true
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext(); val dp = resources.displayMetrics.density
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt()) }
        fun row(vararg v: View) = LinearLayout(ctx).apply { v.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) } }

        col.addView(TextView(ctx).apply { text = "Fokus-Bracketing"; textSize = 22f })
        col.addView(TextView(ctx).apply { text = "Objektiv auf MF. Fokus mit den Tasten fahren (gedrückt halten), Start und Ende markieren."; textSize = 13f })
        col.addView(row(holdButton("◀ Nah", CameraActionPreset.FOCUS_NEAR, -1), holdButton("Fern ▶", CameraActionPreset.FOCUS_FAR, 1)))
        col.addView(row(
            Button(ctx).apply { text = "Start setzen"; setOnClickListener { start = pos; refresh() } },
            Button(ctx).apply { text = "Ende setzen"; setOnClickListener { end = pos; refresh() } }))

        val dial = DialView(ctx).apply { onChange = { step = it; if (::info.isInitialized) refresh() } }
        col.addView(TextView(ctx).apply { text = "Schrittweite (Drehrad)"; textSize = 13f; setPadding(0, (12 * dp).toInt(), 0, 0) })
        col.addView(dial, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val settleLabel = TextView(ctx).apply { text = "Wartezeit nach Schritt: 0.6 s" }
        col.addView(settleLabel)
        col.addView(SeekBar(ctx).apply {
            max = 29; progress = 4
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { settle = (p + 2) / 10f; settleLabel.text = "Wartezeit nach Schritt: %.1f s".format(settle) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        info = TextView(ctx).apply { textSize = 15f; setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt()) }
        col.addView(info)
        go = Button(ctx).apply {
            text = "Serie starten (nochmal tippen = abbrechen)"
            setOnClickListener {
                val s = start ?: return@setOnClickListener; val e = end ?: return@setOnClickListener
                val i = Intent(ctx, AlphaRemoteService::class.java).apply {
                    action = AlphaRemoteService.BRACKET_SEQUENCE_INTENT_ACTION
                    putExtra(AlphaRemoteService.BRACKET_COUNT_EXTRA, count())
                    putExtra(AlphaRemoteService.BRACKET_STEP_EXTRA, step)
                    putExtra(AlphaRemoteService.BRACKET_SETTLE_EXTRA, settle)
                    putExtra(AlphaRemoteService.BRACKET_DIR_EXTRA, if (e > s) 1 else -1)
                    putExtra(AlphaRemoteService.BRACKET_APPROACH_EXTRA, s - pos)
                }
                ctx.startService(i)
                pos = s + count().minus(1) * step * (if (e > s) 1 else -1) // Position nach der Serie
                refresh()
            }
        }
        col.addView(go)
        col.addView(Button(ctx).apply { text = "Aktuelle Position = 0 setzen"; setOnClickListener { pos = 0f; start = null; end = null; refresh() } })
        refresh()
        return ScrollView(ctx).apply { addView(col) }
    }
}
