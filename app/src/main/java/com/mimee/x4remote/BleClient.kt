package com.mimee.x4remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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

/** อุปกรณ์ BLE ที่สแกนเจอ */
class Found(val dev: BluetoothDevice, var name: String, var rssi: Int, var clover: Boolean) {
    val address: String get() = dev.address
}

@SuppressLint("MissingPermission")
class BleClient(
    private val ctx: Context,
    private val onState: (State, String) -> Unit,
    private val onFound: (List<Found>, Int) -> Unit   // (รายการที่แสดง, จำนวนอุปกรณ์ไม่มีชื่อที่ซ่อนไว้)
) {
    enum class State { IDLE, SCANNING, CONNECTING, READY }

    companion object {
        val SVC: UUID = UUID.fromString("c10e0001-5a7e-4b1d-9c3a-0d1e7f00a001")
        val CMD: UUID = UUID.fromString("c10e0002-5a7e-4b1d-9c3a-0d1e7f00a001")
        const val DEV_NAME = "Clover-X4"
        const val MIN_GAP_MS = 170L   // เฟิร์มแวร์ใช้ ~140 ms ต่อคำสั่ง
        const val MAX_QUEUE = 4
        const val SCAN_MS = 25000L    // สแกนนานสุดกี่ ms แล้วหยุดเอง (กินแบตน้อยลง)
        const val CONNECT_MS = 12000L // รอเชื่อมต่อนานสุดกี่ ms
        const val MAX_ROWS = 8
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefs = ctx.getSharedPreferences("x4remote", Context.MODE_PRIVATE)

    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var curName = ""
    private var scanning = false
    private var wanted = false
    private var autoAddr: String? = null      // ถ้าตั้งไว้ จะเชื่อมต่ออัตโนมัติเมื่อสแกนเจอที่อยู่นี้
    private val found = LinkedHashMap<String, Found>()
    private var notifyPending = false
    private val queue = ArrayDeque<Int>()
    private var timerOn = false
    private var lastSend = 0L

    private val scanRun = Runnable { if (wanted && gatt == null) startScan() }
    private val pumpRun = Runnable { timerOn = false; pump() }
    private val scanStopRun = Runnable {
        if (scanning) {
            stopScan()
            val n = found.size
            onState(State.IDLE, "หยุดค้นหาแล้ว (พบอุปกรณ์ $n เครื่อง) — กด \"สแกนหาเครื่อง\" เพื่อค้นหาอีกครั้ง")
        }
    }
    private val connTimeout = Runnable {
        if (gatt != null && cmdChar == null) {
            closeGatt()
            onState(State.IDLE, "เชื่อมต่อไม่สำเร็จ (หมดเวลา) — ลองสแกนใหม่")
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (gatt != null) return
            val rec = result.scanRecord
            val name = rec?.deviceName ?: result.device.name ?: ""
            val hasSvc = rec?.serviceUuids?.any { it.uuid == SVC } == true
            val clover = hasSvc || name.startsWith("Clover")
            val addr = result.device.address
            val f = found[addr]
            if (f == null) {
                found[addr] = Found(result.device, name, result.rssi, clover)
            } else {
                if (name.isNotEmpty()) f.name = name
                f.rssi = result.rssi
                f.clover = f.clover || clover
            }
            notifyFound()
            if (autoAddr != null && addr == autoAddr) connect(result.device, name)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            val why = when (errorCode) {
                1 -> "กำลังสแกนซ้ำอยู่"
                2 -> "ลงทะเบียนสแกนไม่ได้ ลองปิด-เปิด Bluetooth"
                3 -> "ไม่รองรับ"
                4 -> "ภายในผิดพลาด"
                5 -> "ฟีเจอร์ไม่รองรับ"
                6 -> "สแกนถี่เกินไป รอ 30 วินาทีแล้วลองใหม่"
                else -> "รหัส $errorCode"
            }
            onState(State.IDLE, "สแกนไม่สำเร็จ: $why")
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { if (gatt === g) onState(State.CONNECTING, "กำลังเชื่อมต่อ…") }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post { dropGatt(g, status) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(SVC)?.getCharacteristic(CMD)
            main.post {
                if (gatt !== g) return@post
                if (c != null) {
                    main.removeCallbacks(connTimeout)
                    cmdChar = c
                    prefs.edit().putString("last", g.device.address).apply()
                    onState(State.READY, "เชื่อมต่อแล้ว: " + curName.ifEmpty { g.device.address })
                } else {
                    closeGatt()
                    onState(State.IDLE, "อุปกรณ์นี้ไม่ใช่ X4 (ไม่พบบริการรีโมท)")
                }
            }
        }
    }

    // ---------- สั่งงานจาก UI ----------

    /** เริ่มสแกน (เรียกตอนเปิดแอป): ถ้าเคยเชื่อมเครื่องไหนไว้ จะต่อเครื่องนั้นให้อัตโนมัติเมื่อเจอ */
    fun start() {
        wanted = true
        if (!btOk()) return
        if (gatt != null || scanning) return
        autoAddr = prefs.getString("last", null)
        startScan()
    }

    /** ปุ่ม "สแกนหาเครื่อง": เริ่มสแกนใหม่ทั้งหมด ไม่ต่ออัตโนมัติ ให้ผู้ใช้เลือกจากรายการเอง */
    fun rescan() {
        wanted = true
        if (!btOk()) return
        autoAddr = null
        main.removeCallbacks(scanRun)
        closeGatt()
        stopScan()
        startScan()
    }

    /** แตะอุปกรณ์ในรายการ */
    fun connectTo(address: String) {
        val f = found[address] ?: return
        autoAddr = null
        connect(f.dev, f.name)
    }

    /** ปุ่ม "ตัดการเชื่อมต่อ" */
    fun disconnect() {
        stop()
        onState(State.IDLE, "ตัดการเชื่อมต่อแล้ว — กด \"สแกนหาเครื่อง\" เพื่อเชื่อมใหม่")
    }

    fun stop() {
        wanted = false
        main.removeCallbacks(scanRun)
        main.removeCallbacks(scanStopRun)
        stopScan()
        closeGatt()
    }

    fun send(code: Int) {
        main.post {
            if (cmdChar == null) return@post
            if (queue.size >= MAX_QUEUE) return@post
            queue.addLast(code)
            pump()
        }
    }

    // ---------- ภายใน ----------

    private fun btOk(): Boolean {
        if (adapter == null || !adapter.isEnabled) {
            onState(State.IDLE, "Bluetooth ปิดอยู่ — เปิดแล้วกลับมาที่แอปนี้")
            return false
        }
        return true
    }

    private fun notifyFound() {
        if (notifyPending) return
        notifyPending = true
        main.postDelayed({
            notifyPending = false
            val all = found.values.toList()
            val shown = all
                .filter { it.clover || it.name.isNotEmpty() }
                .sortedWith(compareByDescending<Found> { it.clover }.thenByDescending { it.rssi })
                .take(MAX_ROWS)
            val hidden = all.count { !it.clover && it.name.isEmpty() }
            onFound(shown, hidden)
        }, 400)
    }

    private fun startScan() {
        val sc = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        found.clear()
        onFound(emptyList(), 0)
        // สแกนแบบไม่ใส่ตัวกรอง: เห็นทุกอุปกรณ์ แล้วแยก Clover ในโค้ดเอง
        // (ตัวกรอง UUID ทำให้ไม่เห็นอะไรเลยถ้าเครื่องไม่ได้ส่ง UUID ในแพ็กเก็ตโฆษณา)
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        onState(State.SCANNING, "กำลังค้นหา… (เปิด เพิ่มเติม > รีโมท BLE ที่เครื่อง)")
        try {
            sc.startScan(null, settings, scanCb)
            main.removeCallbacks(scanStopRun)
            main.postDelayed(scanStopRun, SCAN_MS)
        } catch (e: Exception) {
            scanning = false
            onState(State.IDLE, "สแกนไม่สำเร็จ: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopScan() {
        main.removeCallbacks(scanStopRun)
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) { }
    }

    private fun connect(dev: BluetoothDevice, name: String) {
        if (gatt != null) return
        stopScan()
        main.removeCallbacks(scanRun)
        curName = name
        onState(State.CONNECTING, "กำลังเชื่อมต่อกับ " + name.ifEmpty { dev.address } + "…")
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        main.removeCallbacks(connTimeout)
        main.postDelayed(connTimeout, CONNECT_MS)
    }

    /** ปิด GATT ปัจจุบันเงียบ ๆ (ไม่สแกนใหม่เอง) */
    private fun closeGatt() {
        main.removeCallbacks(pumpRun)
        main.removeCallbacks(connTimeout)
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

    private fun dropGatt(g: BluetoothGatt, status: Int) {
        try { g.close() } catch (e: Exception) { }
        if (gatt !== g) return
        val wasReady = cmdChar != null
        main.removeCallbacks(connTimeout)
        gatt = null
        cmdChar = null
        queue.clear()
        main.removeCallbacks(pumpRun)
        timerOn = false
        if (!wanted) return
        if (wasReady) {
            // หลุดหลังต่อติดแล้ว: สแกนหาเครื่องเดิมและต่อให้อัตโนมัติ
            autoAddr = prefs.getString("last", null)
            onState(State.SCANNING, "หลุดการเชื่อมต่อ กำลังค้นหาใหม่…")
            main.postDelayed(scanRun, 1500)
        } else {
            onState(State.IDLE, "เชื่อมต่อไม่สำเร็จ (รหัส $status) — ลองกด \"สแกนหาเครื่อง\" อีกครั้ง")
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
