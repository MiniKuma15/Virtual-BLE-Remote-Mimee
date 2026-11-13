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
    private lateinit var discBtn: TextView
    private lateinit var listCard: LinearLayout
    private lateinit var listBody: LinearLayout
    private var connected = false
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
        ble = BleClient(
            this,
            { st, text -> runOnUiThread { onBleState(st, text) } },
            { list, hidden -> runOnUiThread { renderList(list, hidden) } }
        )

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
            setOnClickListener { ble.rescan() }
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

        // ---- ปุ่มสแกน / ตัดการเชื่อมต่อ ----
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scanBtn = pill("สแกนหาเครื่อง", 0xFFFFD6E0.toInt()) { ble.rescan() }
        discBtn = pill("ตัดการเชื่อมต่อ", 0xFFEDEAF6.toInt()) { ble.disconnect() }
        discBtn.visibility = View.GONE
        btnRow.addView(scanBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(discBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })
        root.addView(btnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        // ---- รายการอุปกรณ์ที่สแกนเจอ ----
        listCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(Color.WHITE, 22)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        listCard.addView(TextView(this).apply {
            text = "อุปกรณ์ที่พบ — แตะเพื่อเชื่อมต่อ"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF6A6A78.toInt())
        })
        listBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(listBody)
        root.addView(listCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

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

    private fun pill(label: String, fill: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF4A4A58.toInt())
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(22).toFloat() }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { onClick() }
    }

    private fun renderList(list: List<Found>, hidden: Int) {
        listBody.removeAllViews()
        if (connected || (list.isEmpty() && hidden == 0)) { listCard.visibility = View.GONE; return }
        listCard.visibility = View.VISIBLE
        for (f in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(if (f.clover) 0xFFFFE9EF.toInt() else 0xFFF7F6FB.toInt())
                    cornerRadius = dp(14).toFloat()
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { ble.connectTo(f.address) }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = (if (f.clover) "★ " else "") + f.name.ifEmpty { "(ไม่มีชื่อ)" }
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF2B2B35.toInt())
            })
            col.addView(TextView(this).apply {
                text = "${f.address}  ·  ${f.rssi} dBm"
                textSize = 12f
                setTextColor(0xFF6A6A78.toInt())
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "เชื่อมต่อ"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFB0476A.toInt())
            })
            listBody.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        if (hidden > 0) {
            listBody.addView(TextView(this).apply {
                text = "ยังมีอุปกรณ์ที่ไม่มีชื่ออีก $hidden เครื่อง (ซ่อนไว้)"
                textSize = 12f
                setTextColor(0xFF8A8A98.toInt())
                setPadding(0, dp(10), 0, 0)
            })
        }
        if (list.none { it.clover }) {
            listBody.addView(TextView(this).apply {
                text = "ยังไม่เจอ Clover-X4 — ตรวจว่าที่เครื่องเปิด เพิ่มเติม > รีโมท BLE เป็น \"เปิด\" แล้ว"
                textSize = 12f
                setTextColor(0xFFB0476A.toInt())
                setPadding(0, dp(10), 0, 0)
            })
        }
    }

    private fun onBleState(st: BleClient.State, text: String) {
        ready = st == BleClient.State.READY
        connected = st == BleClient.State.READY || st == BleClient.State.CONNECTING
        discBtn.visibility = if (connected) View.VISIBLE else View.GONE
        if (connected) listCard.visibility = View.GONE
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
