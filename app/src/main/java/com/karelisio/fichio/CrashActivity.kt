package com.karelisio.fichio

import com.karelisio.fichio.core.util.Strings
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.karelisio.fichio.core.util.CrashLog

/**
 * Plain-View crash report (no Compose, no container), shown on the next launch after a fatal
 * crash so the report can be copied or shared even if the UI crashes on every start.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashLog.read(this) ?: run { relaunch(); return }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = Strings.get(R.string.crash_closed_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = Strings.get(R.string.crash_closed_body)
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = Strings.get(R.string.common_copy)
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(Strings.get(R.string.crash_report_clip), report))
                Toast.makeText(this@CrashActivity, Strings.get(R.string.crash_report_copied), Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Button(this).apply {
            text = Strings.get(R.string.common_share)
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(send, Strings.get(R.string.crash_share_report)))
            }
        })
        buttons.addView(Button(this).apply {
            text = Strings.get(R.string.crash_relaunch)
            setOnClickListener { CrashLog.clear(this@CrashActivity); relaunch() }
        })
        root.addView(buttons)
        root.addView(ScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply {
                text = report
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextIsSelectable(true)
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root, android.view.ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun relaunch() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
