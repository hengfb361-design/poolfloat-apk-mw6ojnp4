package com.example.poolfloatingoverlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Floating aim overlay with real-time screen analysis.
 *
 * Screen frames come from MediaProjection + ImageReader and are processed with plain Kotlin
 * pixel maths (no OpenCV, no .so libraries), so the project builds on any Android SDK only.
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_DATA_INTENT = "DATA_INTENT"
        const val ACTION_STOP = "com.example.poolfloatingoverlay.STOP"
        private const val CHANNEL_ID = "pool_aim_overlay"
        private const val NOTIFICATION_ID = 8108
        private const val FRAME_INTERVAL_MS = 70L
    }

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var panel: View? = null
    private var aimView: AimCanvasView? = null
    private var statusLabel: TextView? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var detector: BallDetector? = null
    private var lastFrameAt = 0L
    private var aimEnabled = true
    private var lastStatus = ""

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        addAimLayer()
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }
        if (resultCode == Activity.RESULT_OK && data != null) {
            startCapture(resultCode, data)
        }
        return START_STICKY
    }

    // region foreground service

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Pool aim overlay", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Keeps the aim overlay and screen analysis running"
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Pool Aim Assistant")
            .setContentText("Analysing the screen in real time")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // endregion

    // region screen capture

    private fun startCapture(resultCode: Int, data: Intent) {
        if (projection != null) return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val created = try {
            manager.getMediaProjection(resultCode, data)
        } catch (error: Exception) {
            null
        } ?: return

        projection = created
        // Android 14 requires the callback to be registered before the virtual display exists.
        created.registerCallback(projectionCallback, mainHandler)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        if (width <= 0 || height <= 0) return

        val thread = HandlerThread("pool-capture")
        thread.start()
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        detector = BallDetector(width, height)
        virtualDisplay = created.createVirtualDisplay(
            "pool-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        reader.setOnImageAvailableListener({ source -> onFrameAvailable(source) }, handler)
        updateStatus("capture started")
    }

    private fun onFrameAvailable(source: ImageReader) {
        val image: Image = try {
            source.acquireLatestImage()
        } catch (error: Exception) {
            null
        } ?: return

        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            lastFrameAt = now
            if (!aimEnabled) return
            val aim = detector?.analyse(image) ?: return
            mainHandler.post {
                aimView?.showAim(aim.cue, aim.target, aim.radius)
                updateStatus("tracking · " + (aim.confidence * 100).toInt() + "%")
            }
        } catch (error: Throwable) {
            // A single broken frame must never stop the capture thread.
        } finally {
            image.close()
        }
    }

    private fun releaseCapture() {
        val reader = imageReader
        val display = virtualDisplay
        val active = projection
        imageReader = null
        virtualDisplay = null
        projection = null
        detector = null

        try {
            reader?.setOnImageAvailableListener(null, null)
        } catch (error: Exception) {
        }
        try {
            display?.release()
        } catch (error: Exception) {
        }
        try {
            reader?.close()
        } catch (error: Exception) {
        }
        try {
            active?.unregisterCallback(projectionCallback)
        } catch (error: Exception) {
        }
        try {
            active?.stop()
        } catch (error: Exception) {
        }
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        aimView?.clearAim()
        updateStatus("capture stopped")
    }

    // endregion

    // region overlay views

    private fun overlayParams(width: Int, height: Int, touchable: Boolean, x: Int, y: Int): WindowManager.LayoutParams {
        val flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun addAimLayer() {
        if (aimView != null) return
        val view = AimCanvasView(this)
        aimView = view
        addViewSafe(
            view,
            overlayParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                touchable = false,
                x = 0,
                y = 0
            )
        )
    }

    private fun addBubble() {
        if (bubble != null) return
        val button = TextView(this).apply {
            text = "POOL"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(25, 110, 190))
            setPadding(18, 14, 18, 14)
            elevation = 12f
        }
        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
            x = 24,
            y = 220
        )
        button.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (abs(dx) > 5 || abs(dy) > 5) moved = true
                        params.x = startX + dx
                        params.y = startY + dy
                        try {
                            windowManager.updateViewLayout(button, params)
                        } catch (error: Exception) {
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) togglePanel()
                        return true
                    }
                }
                return false
            }
        })
        bubble = button
        addViewSafe(button, params)
    }

    private fun togglePanel() {
        val current = panel
        if (current != null) {
            panel = null
            statusLabel = null
            removeViewSafe(current)
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            setBackgroundColor(Color.rgb(28, 32, 42))
            elevation = 16f
        }
        val heading = TextView(this).apply {
            text = "Real-time aim"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(4, 4, 4, 8)
        }
        val status = TextView(this).apply {
            text = lastStatus.ifEmpty { "waiting for frames" }
            textSize = 12f
            setTextColor(Color.rgb(159, 179, 200))
            setPadding(4, 0, 4, 10)
        }
        statusLabel = status
        box.addView(heading)
        box.addView(status)

        val toggle = TextView(this).apply {
            text = if (aimEnabled) "Aim lines: ON" else "Aim lines: OFF"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
            setOnClickListener {
                aimEnabled = !aimEnabled
                text = if (aimEnabled) "Aim lines: ON" else "Aim lines: OFF"
                aimView?.setLinesVisible(aimEnabled)
                if (!aimEnabled) aimView?.clearAim()
                updateStatus(if (aimEnabled) "aim lines on" else "aim lines off")
            }
        }
        box.addView(toggle)
        addAction(box, "Stop screen capture") { releaseCapture() }
        addAction(box, "Close menu") { togglePanel() }

        panel = box
        addViewSafe(
            box,
            overlayParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                touchable = true,
                x = 24,
                y = 290
            )
        )
    }

    private fun addAction(parent: LinearLayout, label: String, action: () -> Unit) {
        val item = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
            setOnClickListener { action() }
        }
        parent.addView(item)
    }

    private fun addViewSafe(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.addView(view, params)
        } catch (error: Exception) {
            // The overlay permission can be revoked while the service runs.
        }
    }

    private fun removeViewSafe(view: View) {
        try {
            windowManager.removeView(view)
        } catch (error: Exception) {
        }
    }

    private fun updateStatus(text: String) {
        lastStatus = text
        val label = statusLabel ?: return
        mainHandler.post { label.text = text }
    }

    override fun onDestroy() {
        releaseCapture()
        panel?.let { removeViewSafe(it) }
        bubble?.let { removeViewSafe(it) }
        aimView?.let { removeViewSafe(it) }
        panel = null
        bubble = null
        aimView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // endregion
}

/**
 * Draws the cue ball line, the ghost ball and the object ball path found by [BallDetector].
 * It only reads state prepared on the main thread, so it needs no synchronisation.
 */
class AimCanvasView(context: Context) : View(context) {

    private val cueLinePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 5f
        isAntiAlias = true
    }
    private val ghostPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val objectLinePaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 4f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }
    private val ballRingPaint = Paint().apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val cue = PointF()
    private val targetBall = PointF()
    private var ballRadius = 24f
    private var hasAim = false
    private var linesVisible = true

    fun showAim(cuePoint: PointF, targetPoint: PointF, radius: Float) {
        cue.set(cuePoint.x, cuePoint.y)
        targetBall.set(targetPoint.x, targetPoint.y)
        ballRadius = radius
        hasAim = true
        invalidate()
    }

    fun clearAim() {
        hasAim = false
        invalidate()
    }

    fun setLinesVisible(visible: Boolean) {
        linesVisible = visible
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasAim || !linesVisible) return

        val dx = targetBall.x - cue.x
        val dy = targetBall.y - cue.y
        val distance = hypot(dx, dy)
        if (distance < ballRadius * 2.5f) return

        val ux = dx / distance
        val uy = dy / distance

        // The ghost ball sits two radii behind the object ball on the aim line.
        val ghostX = targetBall.x - ux * ballRadius * 2f
        val ghostY = targetBall.y - uy * ballRadius * 2f

        canvas.drawLine(cue.x, cue.y, ghostX, ghostY, cueLinePaint)
        canvas.drawCircle(ghostX, ghostY, ballRadius, ghostPaint)
        canvas.drawCircle(cue.x, cue.y, ballRadius, ballRingPaint)
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, ballRingPaint)

        val reach = (distance * 1.8f).coerceIn(160f, 900f)
        canvas.drawLine(
            targetBall.x,
            targetBall.y,
            targetBall.x + ux * reach,
            targetBall.y + uy * reach,
            objectLinePaint
        )
    }
}

/**
 * Pure Kotlin ball detection on RGBA_8888 screen frames.
 *
 * A pool cue ball is the brightest, least colourful blob on the table, and object balls are the
 * saturated blobs near it. The detector buckets those two pixel families into a coarse grid, keeps
 * the strongest cluster, refines it with a full-resolution centroid and smooths the result across
 * frames. No native code is involved.
 */
class BallDetector(private val frameWidth: Int, private val frameHeight: Int) {

    data class Aim(
        val cue: PointF,
        val target: PointF,
        val radius: Float,
        val confidence: Float
    )

    private val sampleStep = 4
    private val cellSize = 32
    private val columns = frameWidth / cellSize + 2
    private val rows = frameHeight / cellSize + 2
    private val whiteCells = IntArray(columns * rows)
    private val colourCells = IntArray(columns * rows)

    private var smoothedCue: PointF? = null
    private var smoothedTarget: PointF? = null
    private var smoothedRadius = 24f

    fun analyse(image: Image): Aim? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = minOf(image.width, frameWidth)
        val height = minOf(image.height, frameHeight)
        if (width <= 0 || height <= 0 || rowStride <= 0) return null
        val limit = buffer.limit()

        Arrays.fill(whiteCells, 0)
        Arrays.fill(colourCells, 0)

        val leftBound = (width * 0.04f).toInt()
        val rightBound = (width * 0.96f).toInt()
        val topBound = (height * 0.08f).toInt()
        val bottomBound = (height * 0.82f).toInt()

        var y = topBound
        while (y < bottomBound) {
            val rowStart = y * rowStride
            var x = leftBound
            while (x < rightBound) {
                val offset = rowStart + x * pixelStride
                if (offset + 2 < limit) {
                    val red = buffer.get(offset).toInt() and 0xFF
                    val green = buffer.get(offset + 1).toInt() and 0xFF
                    val blue = buffer.get(offset + 2).toInt() and 0xFF
                    val index = (y / cellSize) * columns + (x / cellSize)
                    if (index in whiteCells.indices) {
                        if (looksWhite(red, green, blue)) {
                            whiteCells[index]++
                        } else if (looksColoured(red, green, blue)) {
                            colourCells[index]++
                        }
                    }
                }
                x += sampleStep
            }
            y += sampleStep
        }

        val cueCell = strongestCell(whiteCells)
        if (cueCell < 0) return null
        val cuePixels = whiteCells[cueCell]
        if (cuePixels < 14) return null

        val cueCentre = refine(
            cellCentre(cueCell),
            buffer, rowStride, pixelStride, limit, white = true
        )
        val estimated = sqrt((cuePixels * sampleStep * sampleStep).toFloat() / PI.toFloat())
        val radius = estimated.coerceIn(10f, 64f)

        var targetCentre: PointF? = null
        var targetPixels = 0
        var bestScore = Float.MAX_VALUE
        for (index in colourCells.indices) {
            val count = colourCells[index]
            if (count < 8) continue
            val centre = cellCentre(index)
            val distance = hypot(centre.x - cueCentre.x, centre.y - cueCentre.y)
            if (distance < radius * 2.4f) continue
            if (distance > frameWidth * 0.7f) continue
            val score = distance - count * 1.5f
            if (score < bestScore) {
                bestScore = score
                targetCentre = centre
                targetPixels = count
            }
        }
        val seededTarget = targetCentre ?: return null
        val refinedTarget = refine(
            seededTarget,
            buffer, rowStride, pixelStride, limit, white = false
        )

        val cueOut = smooth(smoothedCue, cueCentre, 0.6f)
        val targetOut = smooth(smoothedTarget, refinedTarget, 0.55f)
        smoothedCue = cueOut
        smoothedTarget = targetOut
        smoothedRadius = smoothedRadius * 0.7f + radius * 0.3f

        val confidence = ((cuePixels / 60f).coerceAtMost(1f) + (targetPixels / 40f).coerceAtMost(1f)) / 2f
        return Aim(cueOut, targetOut, smoothedRadius, confidence)
    }

    private fun looksWhite(red: Int, green: Int, blue: Int): Boolean {
        val low = minOf(red, green, blue)
        val high = maxOf(red, green, blue)
        return low >= 198 && high - low <= 28
    }

    private fun looksColoured(red: Int, green: Int, blue: Int): Boolean {
        val low = minOf(red, green, blue)
        val high = maxOf(red, green, blue)
        return high >= 110 && high - low >= 70
    }

    private fun strongestCell(cells: IntArray): Int {
        var best = -1
        var bestCount = 0
        for (index in cells.indices) {
            if (cells[index] > bestCount) {
                bestCount = cells[index]
                best = index
            }
        }
        return best
    }

    /** Weighted centre of the strongest cell plus its eight neighbours. */
    private fun cellCentre(index: Int): PointF {
        val column = index % columns
        val row = index / columns
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nx = column + dx
                val ny = row + dy
                if (nx < 0 || ny < 0 || nx >= columns || ny >= rows) continue
                val cell = whiteCells[ny * columns + nx] + colourCells[ny * columns + nx]
                if (cell <= 0) continue
                sumX += (nx * cellSize + cellSize / 2f) * cell
                sumY += (ny * cellSize + cellSize / 2f) * cell
                count += cell
            }
        }
        if (count == 0) {
            return PointF(column * cellSize + cellSize / 2f, row * cellSize + cellSize / 2f)
        }
        return PointF(sumX / count, sumY / count)
    }

    /** Re-centres a coarse guess by averaging the matching pixels around it at full resolution. */
    private fun refine(
        seed: PointF,
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        limit: Int,
        white: Boolean
    ): PointF {
        val half = cellSize * 3 / 2
        val leftBound = (seed.x - half).toInt().coerceAtLeast(0)
        val rightBound = minOf((seed.x + half).toInt(), frameWidth)
        val topBound = (seed.y - half).toInt().coerceAtLeast(0)
        val bottomBound = minOf((seed.y + half).toInt(), frameHeight)

        var sumX = 0f
        var sumY = 0f
        var count = 0
        var y = topBound
        while (y < bottomBound) {
            val rowStart = y * rowStride
            var x = leftBound
            while (x < rightBound) {
                val offset = rowStart + x * pixelStride
                if (offset + 2 < limit) {
                    val red = buffer.get(offset).toInt() and 0xFF
                    val green = buffer.get(offset + 1).toInt() and 0xFF
                    val blue = buffer.get(offset + 2).toInt() and 0xFF
                    val hit = if (white) looksWhite(red, green, blue) else looksColoured(red, green, blue)
                    if (hit) {
                        sumX += x
                        sumY += y
                        count++
                    }
                }
                x++
            }
            y++
        }
        if (count < 20) return seed
        return PointF(sumX / count, sumY / count)
    }

    private fun smooth(previous: PointF?, current: PointF, keep: Float): PointF {
        if (previous == null) return PointF(current.x, current.y)
        return PointF(
            previous.x * keep + current.x * (1f - keep),
            previous.y * keep + current.y * (1f - keep)
        )
    }
}
