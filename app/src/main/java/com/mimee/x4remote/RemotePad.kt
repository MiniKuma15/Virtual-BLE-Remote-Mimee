package com.mimee.x4remote

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
