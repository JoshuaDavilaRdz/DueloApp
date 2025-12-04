package com.example.dueloapp.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.dueloapp.model.SpawnData
import kotlin.math.pow
import kotlin.math.sqrt

class GameCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentSpawn: SpawnData? = null
    private var onTargetHitListener: ((String) -> Unit)? = null

    // Paints para dibujar
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF6B35")
    }

    private val targetStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.WHITE
    }

    private val innerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFCC00")
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Animación
    private var pulseAnimator: ValueAnimator? = null
    private var pulseScale = 1f

    // Partículas de explosión
    private val particles = mutableListOf<Particle>()
    private var particleAnimator: ValueAnimator? = null

    data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var alpha: Int,
        var color: Int
    )

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setSpawn(spawn: SpawnData?) {
        currentSpawn = spawn
        particles.clear()

        if (spawn != null) {
            startPulseAnimation()
        } else {
            stopPulseAnimation()
        }

        invalidate()
    }

    fun setOnTargetHitListener(listener: (String) -> Unit) {
        onTargetHitListener = listener
    }

    private fun startPulseAnimation() {
        stopPulseAnimation()

        pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                pulseScale = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseScale = 1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Dibujar partículas
        particles.forEach { particle ->
            particlePaint.color = particle.color
            particlePaint.alpha = particle.alpha
            canvas.drawCircle(particle.x, particle.y, 8f, particlePaint)
        }

        // Dibujar objetivo actual
        currentSpawn?.let { spawn ->
            val cx = spawn.cx.toFloat()
            val cy = spawn.cy.toFloat()
            val r = (spawn.r * pulseScale).toFloat()

            // Círculo principal
            canvas.drawCircle(cx, cy, r, targetPaint)

            // Borde blanco
            canvas.drawCircle(cx, cy, r, targetStrokePaint)

            // Círculo interno amarillo
            canvas.drawCircle(cx, cy, r * 0.4f, innerCirclePaint)

            // Punto central
            val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
            }
            canvas.drawCircle(cx, cy, r * 0.15f, centerPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val spawn = currentSpawn ?: return false

            val touchX = event.x
            val touchY = event.y
            val cx = spawn.cx.toFloat()
            val cy = spawn.cy.toFloat()
            val r = spawn.r.toFloat()

            // Calcular distancia del toque al centro
            val distance = sqrt((touchX - cx).pow(2) + (touchY - cy).pow(2))

            if (distance <= r) {
                // ¡Objetivo tocado!
                onTargetHitListener?.invoke(spawn.spawnId)
                createExplosion(cx, cy)
                currentSpawn = null
                stopPulseAnimation()
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun createExplosion(cx: Float, cy: Float) {
        particles.clear()

        val colors = listOf(
            Color.parseColor("#FF6B35"),
            Color.parseColor("#FFCC00"),
            Color.parseColor("#FF1744"),
            Color.parseColor("#00E676")
        )

        // Crear partículas en círculo
        for (i in 0 until 20) {
            val angle = (i * 360.0 / 20) * Math.PI / 180.0
            val speed = 5f + Math.random().toFloat() * 5f

            particles.add(
                Particle(
                    x = cx,
                    y = cy,
                    vx = (Math.cos(angle) * speed).toFloat(),
                    vy = (Math.sin(angle) * speed).toFloat(),
                    alpha = 255,
                    color = colors.random()
                )
            )
        }

        // Animar partículas
        particleAnimator?.cancel()
        particleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            addUpdateListener {
                val progress = it.animatedValue as Float

                particles.forEach { particle ->
                    particle.x += particle.vx
                    particle.y += particle.vy
                    particle.vy += 0.3f // Gravedad
                    particle.alpha = (255 * (1 - progress)).toInt()
                }

                invalidate()

                if (progress >= 1f) {
                    particles.clear()
                }
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulseAnimation()
        particleAnimator?.cancel()
    }
}