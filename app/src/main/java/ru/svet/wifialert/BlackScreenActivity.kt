package ru.svet.wifialert

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.random.Random

/**
 * «Чёрный экран»: экран формально включён (минимальная яркость, всё чёрное),
 * поэтому прошивка не усыпляет Wi-Fi сканирование. Выход — двойное касание.
 */
class BlackScreenActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        setContentView(R.layout.activity_black)
        hint = findViewById(R.id.hint)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                finish()
                return true
            }
        })
        findViewById<View>(R.id.root).setOnTouchListener { v, ev ->
            detector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
        moveHint()
    }

    /** Подсказку двигаем раз в минуту, чтобы ничего не «выжигалось» на экране. */
    private fun moveHint() {
        val root = findViewById<View>(R.id.root)
        root.post {
            hint.translationX = Random.nextInt(0, (root.width - hint.width).coerceAtLeast(1)).toFloat()
            hint.translationY = Random.nextInt(0, (root.height - hint.height).coerceAtLeast(1)).toFloat()
        }
        handler.postDelayed({ moveHint() }, 60_000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
