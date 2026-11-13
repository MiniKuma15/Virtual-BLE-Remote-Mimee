# make_x4remote.py — สร้างโปรเจกต์แอป X4 Remote (Kotlin, BLE) ในโฟลเดอร์ปัจจุบัน
# ต้องมีไฟล์ชีตปุ่ม 1000023103.webp อยู่ที่โฟลเดอร์เดียวกัน แล้วรัน: python3 make_x4remote.py
import os, sys, glob, shutil
webp = [p for p in glob.glob('*.webp')]
if not webp:
    sys.exit('ไม่เจอไฟล์ .webp ที่โฟลเดอร์นี้ — อัปโหลด 1000023103.webp (ชีตปุ่มรีโมท) เข้า repo ก่อน แล้วรันใหม่')
src = '1000023103.webp' if '1000023103.webp' in webp else webp[0]

FILES = {}
FILES['settings.gradle.kts'] = r'''pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "X4Remote"
include(":app")
'''
FILES['build.gradle.kts'] = r'''plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
'''
FILES['gradle.properties'] = '''org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
'''
FILES['.gitignore'] = '''.gradle/
build/
local.properties
.idea/
*.apk
*.bak*
'''
FILES['.github/workflows/build.yml'] = r'''name: Build APK
on:
  push:
    branches: [ main ]
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.9
      - run: gradle assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: x4-remote-apk
          path: app/build/outputs/apk/debug/app-debug.apk
'''
FILES['app/build.gradle.kts'] = r'''plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mimee.x4remote"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mimee.x4remote"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
'''
FILES['app/src/main/AndroidManifest.xml'] = r'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

    <application
        android:allowBackup="false"
        android:label="X4 Remote"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
'''
K = 'app/src/main/java/com/mimee/x4remote/'
FILES[K + 'BleClient.kt'] = r'''package com.mimee.x4remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

// รหัสคำสั่ง (ไบต์เดียว) ต้องตรงกับ ble_remote.h ในเฟิร์มแวร์
// 0-5 เฟิร์มแวร์รองรับแล้ว ; 16+ จองไว้ (ยังไม่รองรับ)
object Cmd {
    const val BACK = 0
    const val OK = 1
    const val LEFT = 2
    const val RIGHT = 3
    const val UP = 4
    const val DOWN = 5
    const val SUPPORTED_MAX = 5   // เฟิร์มแวร์รับได้ถึงรหัสนี้ ; เพิ่มเมื่อเฟิร์มแวร์รองรับปุ่มอื่น
    const val HOME = 16
    const val MENU = 17
    const val FWD = 18
    const val POWER = 19
    const val VOL_UP = 20
    const val VOL_DN = 21
    const val BRI_UP = 22
    const val BRI_DN = 23
    const val PREV = 24
    const val PLAY = 25
    const val NEXT = 26
    const val APPS = 27
    const val SLEEP = 28
}

@SuppressLint("MissingPermission")
class BleClient(
    private val ctx: Context,
    private val onState: (State, String) -> Unit
) {
    enum class State { IDLE, SCANNING, CONNECTING, READY }

    companion object {
        val SVC: UUID = UUID.fromString("c10e0001-5a7e-4b1d-9c3a-0d1e7f00a001")
        val CMD: UUID = UUID.fromString("c10e0002-5a7e-4b1d-9c3a-0d1e7f00a001")
        const val MIN_GAP_MS = 170L   // เฟิร์มแวร์ใช้ ~140 ms ต่อคำสั่ง
        const val MAX_QUEUE = 4
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var wanted = false
    private val queue = ArrayDeque<Int>()
    private var timerOn = false
    private var lastSend = 0L

    private val scanRun = Runnable { if (wanted && gatt == null) startScan() }
    private val pumpRun = Runnable { timerOn = false; pump() }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (gatt != null) return
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            if (wanted) main.postDelayed(scanRun, 3000)
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { onState(State.CONNECTING, "กำลังเชื่อมต่อ…") }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post { dropGatt(g) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(SVC)?.getCharacteristic(CMD)
            main.post {
                if (c != null && gatt === g) {
                    cmdChar = c
                    onState(State.READY, "พร้อมใช้งาน")
                } else {
                    g.disconnect()
                }
            }
        }
    }

    fun start() {
        wanted = true
        if (adapter == null || !adapter.isEnabled) {
            onState(State.IDLE, "Bluetooth ปิดอยู่ — เปิดแล้วกลับมาที่แอปนี้")
            return
        }
        if (gatt != null || scanning) return
        startScan()
    }

    fun stop() {
        wanted = false
        main.removeCallbacks(scanRun)
        main.removeCallbacks(pumpRun)
        stopScan()
        queue.clear()
        timerOn = false
        val g = gatt
        gatt = null
        cmdChar = null
        if (g != null) {
            try { g.disconnect() } catch (e: Exception) { }
            try { g.close() } catch (e: Exception) { }
        }
    }

    fun restart() {
        stop()
        start()
    }

    fun send(code: Int) {
        main.post {
            if (cmdChar == null) return@post
            if (queue.size >= MAX_QUEUE) return@post
            queue.addLast(code)
            pump()
        }
    }

    private fun startScan() {
        val sc = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        onState(State.SCANNING, "กำลังค้นหา X4… (เปิด เพิ่มเติม > รีโมท BLE ที่เครื่อง)")
        try {
            sc.startScan(listOf(filter), settings, scanCb)
        } catch (e: Exception) {
            scanning = false
            main.postDelayed(scanRun, 3000)
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { }
    }

    private fun connect(dev: BluetoothDevice) {
        stopScan()
        onState(State.CONNECTING, "กำลังเชื่อมต่อ…")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun dropGatt(g: BluetoothGatt) {
        try { g.close() } catch (e: Exception) { }
        if (gatt === g) {
            gatt = null
            cmdChar = null
            queue.clear()
            main.removeCallbacks(pumpRun)
            timerOn = false
            if (wanted) {
                onState(State.SCANNING, "หลุดการเชื่อมต่อ กำลังค้นหาใหม่…")
                main.postDelayed(scanRun, 1500)
            }
        }
    }

    private fun pump() {
        if (timerOn || queue.isEmpty()) return
        val g = gatt ?: return
        val c = cmdChar ?: return
        val wait = lastSend + MIN_GAP_MS - SystemClock.uptimeMillis()
        if (wait > 0) {
            timerOn = true
            main.postDelayed(pumpRun, wait)
            return
        }
        writeCmd(g, c, queue.removeFirst())
        lastSend = SystemClock.uptimeMillis()
        if (queue.isNotEmpty()) {
            timerOn = true
            main.postDelayed(pumpRun, MIN_GAP_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCmd(g: BluetoothGatt, c: BluetoothGattCharacteristic, code: Int) {
        val data = byteArrayOf(code.toByte())
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            c.value = data
            g.writeCharacteristic(c)
        }
    }
}
'''
FILES[K + 'RemotePad.kt'] = r'''package com.mimee.x4remote

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

// แผงปุ่มรีโมท: ตัดรูปปุ่มจากชีต remote_sheet.webp ตามพิกัด (ชุดสีชมพู y=0 ; ชุดสีฟ้า skinDy=653)
class RemotePad(
    ctx: Context,
    sheet: Bitmap,
    skinDy: Int,
    private val onKey: (Int) -> Unit
) : FrameLayout(ctx) {

    // x,y,w,h = พิกัดในชีต ; code = คำสั่ง ; code2 = คำสั่งครึ่งล่าง (ปุ่ม VOL/BRI) ; repeat = กดค้างแล้วส่งซ้ำ
    private class Key(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val code: Int, val code2: Int = -1, val repeat: Boolean = false
    )

    companion object {
        private const val OX = 14    // มุมซ้ายบนของพื้นที่ปุ่มทั้งหมดในชีต
        private const val OY = 9
        private const val CW = 1187  // กว้าง/สูงของพื้นที่ปุ่มทั้งหมด
        private const val CH = 630
        private val KEYS = listOf(
            Key(14, 9, 216, 162, Cmd.HOME),
            Key(227, 9, 208, 164, Cmd.MENU),
            Key(434, 9, 207, 163, Cmd.BACK),
            Key(640, 9, 207, 163, Cmd.FWD),
            Key(844, 9, 204, 161, Cmd.POWER),
            Key(15, 208, 209, 198, Cmd.LEFT, repeat = true),
            Key(226, 177, 225, 100, Cmd.UP, repeat = true),
            Key(226, 277, 225, 107, Cmd.OK),
            Key(226, 384, 225, 104, Cmd.DOWN, repeat = true),
            Key(452, 209, 186, 197, Cmd.RIGHT, repeat = true),
            Key(636, 180, 164, 318, Cmd.VOL_UP, Cmd.VOL_DN),
            Key(805, 180, 164, 319, Cmd.BRI_UP, Cmd.BRI_DN),
            Key(967, 177, 234, 458, Cmd.SLEEP),
            Key(14, 486, 216, 153, Cmd.PREV),
            Key(227, 486, 219, 153, Cmd.PLAY),
            Key(442, 486, 222, 153, Cmd.NEXT),
            Key(665, 493, 232, 145, Cmd.APPS)
        )
    }

    private val pad = (10 * ctx.resources.displayMetrics.density).toInt()
    private val views = ArrayList<ImageView>()
    private val main = Handler(Looper.getMainLooper())
    private var repeater: Runnable? = null

    init {
        clipChildren = false
        for (k in KEYS) {
            val bmp = Bitmap.createBitmap(sheet, k.x, k.y + skinDy, k.w, k.h)
            val iv = ImageView(ctx)
            iv.setImageBitmap(bmp)
            iv.scaleType = ImageView.ScaleType.FIT_XY
            iv.setOnTouchListener { v, e -> touch(v, e, k) }
            addView(iv, LayoutParams(0, 0))
            views.add(iv)
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val s = (w - 2 * pad).toFloat() / CW
        val h = (CH * s).toInt() + 2 * pad
        for ((i, k) in KEYS.withIndex()) {
            views[i].measure(
                MeasureSpec.makeMeasureSpec((k.w * s).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((k.h * s).toInt(), MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = (r - l - 2 * pad).toFloat() / CW
        for ((i, k) in KEYS.withIndex()) {
            val x = pad + ((k.x - OX) * s).toInt()
            val y = pad + ((k.y - OY) * s).toInt()
            views[i].layout(x, y, x + views[i].measuredWidth, y + views[i].measuredHeight)
        }
    }

    private fun touch(v: View, e: MotionEvent, k: Key): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val code = if (k.code2 >= 0 && e.y > v.height / 2f) k.code2 else k.code
                v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(60).start()
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onKey(code)
                if (k.repeat) startRepeat(code)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                stopRepeat()
            }
        }
        return true
    }

    private fun startRepeat(code: Int) {
        stopRepeat()
        val r = object : Runnable {
            override fun run() {
                onKey(code)
                main.postDelayed(this, 260)
            }
        }
        repeater = r
        main.postDelayed(r, 450)
    }

    private fun stopRepeat() {
        repeater?.let { main.removeCallbacks(it) }
        repeater = null
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        super.onDetachedFromWindow()
    }
}
'''
FILES[K + 'MainActivity.kt'] = r'''package com.mimee.x4remote

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
'''

if os.path.exists('.gitignore'):
    del FILES['.gitignore']   # repo มี .gitignore อยู่แล้ว ไม่แตะ
clash = [p for p in FILES if os.path.exists(p)]
if clash:
    sys.exit('มีไฟล์เหล่านี้อยู่แล้ว ยังไม่ได้เขียนอะไรเลย: ' + ', '.join(clash))
for p, txt in FILES.items():
    os.makedirs(os.path.dirname(p) or '.', exist_ok=True)
    open(p, 'w', encoding='utf-8').write(txt)
dst = 'app/src/main/res/drawable-nodpi'
os.makedirs(dst, exist_ok=True)
shutil.move(src, dst + '/remote_sheet.webp')
print('สร้างโปรเจกต์เสร็จ (%d ไฟล์) + ย้ายรูปปุ่มไปที่ %s/remote_sheet.webp' % (len(FILES), dst))
