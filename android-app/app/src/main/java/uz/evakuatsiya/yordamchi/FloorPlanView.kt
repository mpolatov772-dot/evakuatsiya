package uz.evakuatsiya.yordamchi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Evakuatsiya rasmini va uning ustidagi indoor koordinatalarni bitta qatlamda chizadi.
 * Koordinatalar 0..100 foiz ko‘rinishida saqlanadi, shuning uchun rasm o‘lchami o‘zgarsa ham marker mos qoladi.
 */
class FloorPlanView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private val imageRect = RectF()
    private val route = mutableListOf<PointF>()
    private var user = PointF(50f, 45f)
    private var exit = PointF(42f, 21f)
    private var routeVisible = false
    private var calibrationMode = false
    var onPlanTap: ((x: Float, y: Float) -> Unit)? = null

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(239, 106, 91)
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(239, 106, 91)
        style = Paint.Style.FILL
    }
    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 112, 220) }
    private val exitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 145, 92) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(16, 39, 56)
        textSize = dp(12f)
        isFakeBoldText = true
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init {
        setBackgroundColor(Color.rgb(238, 246, 244))
        isClickable = true
    }

    fun setDefaultImage(resourceId: Int) {
        bitmap = BitmapFactory.decodeResource(resources, resourceId)
        requestLayout()
        invalidate()
    }

    fun setImageUri(uri: Uri) {
        bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        requestLayout()
        invalidate()
    }

    fun setCalibrationMode(enabled: Boolean) {
        calibrationMode = enabled
        invalidate()
    }

    fun setUserPercent(x: Float, y: Float) {
        user = PointF(x.coerceIn(0f, 100f), y.coerceIn(0f, 100f))
        if (routeVisible) rebuildRoute()
        invalidate()
    }

    fun setRouteVisible(visible: Boolean) {
        routeVisible = visible
        if (visible) rebuildRoute()
        invalidate()
    }

    fun setRoute(points: List<PointF>) {
        route.clear()
        route.addAll(points.map { PointF(it.x, it.y) })
        routeVisible = points.size > 1
        invalidate()
    }

    fun currentUser(): PointF = PointF(user.x, user.y)

    fun currentExit(): PointF = PointF(exit.x, exit.y)

    fun planBitmap(): Bitmap? = bitmap

    private fun rebuildRoute() {
        route.clear()
        route += PointF(user.x, user.y)
        // Berilgan evakuatsiya planidagi asosiy yo‘lak tuguni va chiqish nuqtasi.
        route += PointF(42f, 42f)
        route += PointF(exit.x, exit.y)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            dp(330f).toInt()
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap ?: return
        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        imageRect.set(
            (width - drawWidth) / 2f,
            (height - drawHeight) / 2f,
            (width + drawWidth) / 2f,
            (height + drawHeight) / 2f
        )
        canvas.drawBitmap(source, null, imageRect, null)

        if (routeVisible && route.size > 1) {
            val path = Path()
            route.forEachIndexed { index, point ->
                val mapped = toCanvas(point)
                if (index == 0) path.moveTo(mapped.x, mapped.y) else path.lineTo(mapped.x, mapped.y)
            }
            canvas.drawPath(path, routePaint)
            route.drop(1).dropLast(1).forEach { point ->
                val mapped = toCanvas(point)
                canvas.drawCircle(mapped.x, mapped.y, dp(4f), arrowPaint)
            }
        }

        val exitPoint = toCanvas(exit)
        canvas.drawCircle(exitPoint.x, exitPoint.y, dp(13f), exitPaint)
        canvas.drawCircle(exitPoint.x, exitPoint.y, dp(8f), whitePaint)
        canvas.drawText("EXIT", exitPoint.x - dp(14f), exitPoint.y - dp(18f), labelPaint)

        val userPoint = toCanvas(user)
        canvas.drawCircle(userPoint.x, userPoint.y, dp(14f), whitePaint)
        canvas.drawCircle(userPoint.x, userPoint.y, dp(10f), userPaint)
        canvas.drawText("Siz", userPoint.x + dp(14f), userPoint.y + dp(4f), labelPaint)

        if (calibrationMode) {
            val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = dp(12f)
                isFakeBoldText = true
            }
            canvas.drawRect(0f, 0f, width.toFloat(), dp(35f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC078F82.toInt() })
            canvas.drawText("Hozir turgan joyingizni rasm ustiga bosing", dp(12f), dp(23f), hint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && imageRect.contains(event.x, event.y)) {
            val x = ((event.x - imageRect.left) / imageRect.width()) * 100f
            val y = ((event.y - imageRect.top) / imageRect.height()) * 100f
            if (calibrationMode) {
                setUserPercent(x, y)
                calibrationMode = false
                onPlanTap?.invoke(x, y)
            }
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun toCanvas(point: PointF): PointF = PointF(
        imageRect.left + imageRect.width() * point.x / 100f,
        imageRect.top + imageRect.height() * point.y / 100f
    )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
