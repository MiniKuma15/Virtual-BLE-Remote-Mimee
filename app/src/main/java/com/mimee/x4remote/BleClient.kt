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
