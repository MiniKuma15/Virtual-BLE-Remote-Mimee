package com.mimee.x4remote

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val SKIN_DY = 0   // 0 = ชุดครีม-ชมพู ; 653 = ชุดครีม-ฟ้า (ในชีตเดียวกัน)

    private lateinit var ble: BleClient
    private lateinit var dot: View
    private lateinit var statusTv: TextView
    private var toast: Toast? = null
    private var ready = false
    private var asked = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), 0xFFE8E4F2.toInt())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ble = BleClient(this) { st, text -> runOnUiThread { onBleState(st, text) } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // ---- การ์ดสถานะเครื่อง ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card(Color.WHITE, 22)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { ble.restart() }
        }
        val devIcon = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFFEDEDF3.toInt())
                cornerRadius = dp(7).toFloat()
                setStroke(dp(3), 0xFF3C3C46.toInt())
            }
        }
        header.addView(devIcon, LinearLayout.LayoutParams(dp(34), dp(46)))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = "เครื่องของคุณ"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF2B2B35.toInt())
        })
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFB0B0B8.toInt())
            }
        }
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusTv = TextView(this).apply {
            text = "เริ่มต้น…"
            textSize = 13f
            setTextColor(0xFF6A6A78.toInt())
            setPadding(dp(8), 0, 0, 0)
        }
        statusRow.addView(statusTv)
        texts.addView(statusRow)
        header.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- การ์ดรีโมท ----
        val sheet = BitmapFactory.decodeResource(
            resources, R.drawable.remote_sheet,
            BitmapFactory.Options().apply { inScaled = false }
        ) ?: throw IllegalStateException("อ่านรูป remote_sheet ไม่ได้")
        val padView = RemotePad(this, sheet, SKIN_DY) { code -> onKey(code) }
        val remoteCard = FrameLayout(this).apply { background = card(Color.WHITE, 28) }
        remoteCard.addView(padView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(12)
        root.addView(remoteCard, lp)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF4F2FB.toInt())
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
    }

    private fun onBleState(st: BleClient.State, text: String) {
        ready = st == BleClient.State.READY
        statusTv.text = text
        val c = when (st) {
            BleClient.State.READY -> 0xFF4CAF50.toInt()
            BleClient.State.IDLE -> 0xFFB0B0B8.toInt()
            else -> 0xFFFFA726.toInt()
        }
        (dot.background as GradientDrawable).setColor(c)
    }

    private fun onKey(code: Int) {
        when {
            code > Cmd.SUPPORTED_MAX -> say("ปุ่มนี้ยังไม่รองรับในเฟิร์มแวร์")
            !ready -> say("ยังไม่ได้เชื่อมต่อกับ X4")
            else -> ble.send(code)
        }
    }

    private fun say(t: String) {
        toast?.cancel()
        toast = Toast.makeText(this, t, Toast.LENGTH_SHORT)
        toast?.show()
    }

    private fun needed(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms() = needed().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startBle() {
        if (hasPerms()) {
            ble.start()
        } else if (!asked) {
            asked = true
            requestPermissions(needed(), 1)
        } else {
            onBleState(BleClient.State.IDLE, "ต้องอนุญาตสิทธิ์ Bluetooth ในตั้งค่าแอป")
        }
    }

    override fun onResume() {
        super.onResume()
        startBle()
    }

    override fun onPause() {
        super.onPause()
        ble.stop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startBle()
    }
}
