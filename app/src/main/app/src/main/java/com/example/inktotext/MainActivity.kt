package com.example.inktotext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

// Full-screen board: pen strokes, and recognized text/maths drawn in the SAME place
class DrawingView(context: Context) : View(context) {
    class TextItem(
        val text: String, val copy: String, val cx: Float, val baseline: Float,
        val size: Float, val color: Int, val sup: Boolean
    ) { var group = 0 }

    class LineItem(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int
    ) { var group = 0 }

    // One converted line of writing, remembered for Copy/Save
    class Group(val copy: String, val y: Float, val x0: Float, val size: Float, val count: Int)

    class PStroke(val stroke: Ink.Stroke, val path: Path, val color: Int)

    var penColor = Color.BLACK
    var eraser = false
    private val eraseRadius = 40f

    private val inkPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true; typeface = Typeface.SANS_SERIF; textAlign = Paint.Align.CENTER
    }
    private var curPath = Path()
    private var curColor = Color.BLACK
    private var strokeBuilder: Ink.Stroke.Builder? = null

    val pending = mutableListOf<PStroke>()           // handwriting not yet converted
    private val items = mutableListOf<TextItem>()    // converted text
    private val lineItems = mutableListOf<LineItem>() // fraction bars, root signs
    private val groups = HashMap<Int, Group>()

    var onStrokeFinished: (() -> Unit)? = null
    var onStrokeStarted: (() -> Unit)? = null

    init { setBackgroundColor(Color.WHITE) }

    override fun onDraw(canvas: Canvas) {
        for (p in pending) { inkPaint.color = p.color; canvas.drawPath(p.path, inkPaint) }
        inkPaint.color = curColor
        canvas.drawPath(curPath, inkPaint)
        for (ln in lineItems) {
            linePaint.color = ln.color
            canvas.drawLine(ln.x1, ln.y1, ln.x2, ln.y2, linePaint)
        }
        for (ti in items) {
            textPaint.color = ti.color
            textPaint.textSize = ti.size
            canvas.drawText(ti.text, ti.cx, ti.baseline, textPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y; val t = SystemClock.uptimeMillis()

        if (eraser) {
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                erase(x, y); invalidate()
            }
            return true
        }

        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                onStrokeStarted?.invoke()
                curColor = penColor
                curPath = Path().also { it.moveTo(x, y) }
                strokeBuilder = Ink.Stroke.builder().also {
                    it.addPoint(Ink.Point.create(x, y, t))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                curPath.lineTo(x, y)
                strokeBuilder?.addPoint(Ink.Point.create(x, y, t))
            }
            MotionEvent.ACTION_UP -> {
                curPath.lineTo(x, y)
                strokeBuilder?.addPoint(Ink.Point.create(x, y, t))
                strokeBuilder?.let { pending.add(PStroke(it.build(), curPath, curColor)) }
                curPath = Path()
                strokeBuilder = null
                onStrokeFinished?.invoke()
            }
            else -> return false
        }
        invalidate()
        return true
    }

    private fun distToSegment(px: Float, py: Float, ln: LineItem): Float {
        val dx = ln.x2 - ln.x1; val dy = ln.y2 - ln.y1
        val len2 = dx * dx + dy * dy
        var t = if (len2 == 0f) 0f else ((px - ln.x1) * dx + (py - ln.y1) * dy) / len2
        t = t.coerceIn(0f, 1f)
        val cx = ln.x1 + t * dx; val cy = ln.y1 + t * dy
        return Math.hypot((px - cx).toDouble(), (py - cy).toDouble()).toFloat()
    }

    // Eraser: removes handwriting, converted text and lines under the finger
    private fun erase(x: Float, y: Float) {
        pending.removeAll { p ->
            p.stroke.points.any {
                Math.hypot((it.x - x).toDouble(), (it.y - y).toDouble()) < eraseRadius
            }
        }
        lineItems.removeAll { ln -> distToSegment(x, y, ln) < eraseRadius }
        items.removeAll { item ->
            textPaint.textSize = item.size
            val w = textPaint.measureText(item.text) / 2 + eraseRadius
            x in (item.cx - w)..(item.cx + w) &&
                    y in (item.baseline - item.size - eraseRadius / 2)..(item.baseline + item.size * 0.3f + eraseRadius / 2)
        }
    }

    // Replace the handwritten strokes in `done` by recognized text
    fun commit(
        done: List<PStroke>, newItems: List<TextItem>,
        newLines: List<LineItem>, newGroups: Map<Int, Group>
    ) {
        pending.removeAll { p -> done.any { it === p } }
        items.addAll(newItems)
        lineItems.addAll(newLines)
        groups.putAll(newGroups)
        invalidate()
    }

    fun clearAll() {
        pending.clear(); items.clear(); lineItems.clear(); groups.clear()
        curPath = Path(); strokeBuilder = null
        invalidate()
    }

    fun toBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp))
        return bmp
    }

    // All converted text line by line: (a)/(b), √(x), x² style
    fun plainText(): String {
        class E(val y: Float, val x0: Float, val size: Float, val text: String)
        val entries = mutableListOf<E>()
        for ((g, list) in items.groupBy { it.group }) {
            val info = groups[g]
            if (info != null && info.count == list.size) {
                entries.add(E(info.y, info.x0, info.size, info.copy))
            } else {
                // partly erased: fall back to remaining characters
                val normals = list.filter { !it.sup }
                val ys = (if (normals.isEmpty()) list else normals).map { it.baseline }
                entries.add(E(ys.average().toFloat(), list.minOf { it.cx }, list[0].size,
                    list.sortedBy { it.cx }.joinToString("") { it.copy }))
            }
        }
        val lines = mutableListOf<MutableList<E>>()
        for (e in entries.sortedBy { it.y }) {
            val ln = lines.firstOrNull { Math.abs(it[0].y - e.y) < e.size * 0.6f }
            if (ln != null) ln.add(e) else lines.add(mutableListOf(e))
        }
        return lines.joinToString("\n") { ln ->
            ln.sortedBy { it.x0 }.joinToString("") { it.text }
        }
    }
}

// Small calculator: + - * / ^ ( ) sqrt( ), implicit multiplication like 2(3+4)
private class Parser(val s: String) {
    var i = 0
    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

    fun parse(): Double {
        val v = expr()
        if (i != s.length) throw IllegalArgumentException()
        return v
    }

    private fun expr(): Double {
        var v = term()
        while (peek() == '+' || peek() == '-') {
            val op = s[i++]
            val r = term()
            v = if (op == '+') v + r else v - r
        }
        return v
    }

    private fun term(): Double {
        var v = power()
        while (true) {
            val c = peek()
            if (c == '*') { i++; v *= power() }
            else if (c == '/') { i++; v /= power() }
            else if (c == '(' || c == 's' || c.isDigit() || c == '.') v *= power()
            else break
        }
        return v
    }

    private fun power(): Double {
        val b = unary()
        if (peek() == '^') { i++; return Math.pow(b, unary()) }
        return b
    }

    private fun unary(): Double {
        if (peek() == '-') { i++; return -unary() }
        if (peek() == '+') { i++; return unary() }
        return atom()
    }

    private fun atom(): Double {
        if (s.startsWith("sqrt", i)) { i += 4; return Math.sqrt(atom()) }
        if (peek() == '(') {
            i++
            val v = expr()
            if (peek() != ')') throw IllegalArgumentException()
            i++
            return v
        }
        val st = i
        while (peek().isDigit() || peek() == '.') i++
        if (st == i) throw IllegalArgumentException()
        return s.substring(st, i).toDouble()
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var recognizer: DigitalInkRecognizer
    private lateinit var drawing: DrawingView
    private val handler = Handler(Looper.getMainLooper())
    private var modelReady = false
    private var textScale = 1.0f
    private var nextGroup = 1
    private val measurePaint = Paint().apply { typeface = Typeface.SANS_SERIF }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawing = DrawingView(this)

        // ---- Toolbar ----
        fun tool(label: String) = Button(this).apply { text = label; isAllCaps = false }
        val modeBtn = tool("✏️ Pen")
        val minusBtn = tool("A−")
        val plusBtn = tool("A+")
        val scaleLabel = TextView(this).apply {
            text = "100%"; gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), 0)
        }
        val saveBtn = tool("Save")
        val copyBtn = tool("Copy")
        val clearBtn = tool("Clear")

        val colors = listOf(
            Color.BLACK, 0xFFD32F2F.toInt(), 0xFF1976D2.toInt(),
            0xFF388E3C.toInt(), 0xFFF57C00.toInt()
        )
        val colorBtns = colors.map { c ->
            Button(this).apply {
                setBackgroundColor(c); setTextColor(Color.WHITE)
                minWidth = 0; minimumWidth = 0
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).also {
                    it.setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }
        }
        fun refresh() {
            colorBtns.forEachIndexed { i, b -> b.text = if (colors[i] == drawing.penColor) "✓" else "" }
            modeBtn.text = if (drawing.eraser) "🧽 Eraser" else "✏️ Pen"
            scaleLabel.text = "${Math.round(textScale * 100)}%"
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(modeBtn)
            colorBtns.forEach { addView(it) }
            addView(minusBtn); addView(scaleLabel); addView(plusBtn)
            addView(saveBtn); addView(copyBtn); addView(clearBtn)
        }
        val bar = HorizontalScrollView(this).apply {
            setBackgroundColor(0xEEFFFFFF.toInt()); addView(row)
        }
        val frame = FrameLayout(this).apply {
            addView(drawing, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        setContentView(frame)
        refresh()

        modeBtn.setOnClickListener { drawing.eraser = !drawing.eraser; refresh() }
        colors.forEachIndexed { i, c ->
            colorBtns[i].setOnClickListener {
                drawing.penColor = c; drawing.eraser = false; refresh()
            }
        }
        minusBtn.setOnClickListener { textScale = maxOf(0.5f, textScale - 0.1f); refresh() }
        plusBtn.setOnClickListener { textScale = minOf(3.0f, textScale + 0.1f); refresh() }
        clearBtn.setOnClickListener { drawing.clearAll() }
        copyBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("text", drawing.plainText()))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        saveBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Save")
                .setItems(arrayOf("Image (PNG)", "Text (.txt)")) { _, which ->
                    if (which == 0) saveImage() else saveText()
                }.show()
        }

        // ---- Recognition ----
        setupRecognizer("en-US")
        drawing.onStrokeStarted = { handler.removeCallbacksAndMessages(null) }
        drawing.onStrokeFinished = {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ recognizeAndReplace() }, 800)
        }
    }

    // ---- Save (Android 10+; no storage permission needed) ----
    private fun saveImage() {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "board_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/InkToText")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)?.use {
                drawing.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Toast.makeText(this, "Gallery లో Save అయింది", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveText() {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "board_${System.currentTimeMillis()}.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/InkToText")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)?.use {
                it.write(drawing.plainText().toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "Downloads లో Save అయింది", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    // First run downloads the model once (needs internet). After that: offline.
    private fun setupRecognizer(tag: String) {
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)!!
        val model = DigitalInkRecognitionModel.builder(id).build()
        recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build()
        )
        val manager = RemoteModelManager.getInstance()
        manager.isModelDownloaded(model).addOnSuccessListener { downloaded ->
            if (downloaded) { modelReady = true; return@addOnSuccessListener }
            Toast.makeText(this, "Model download అవుతోంది…", Toast.LENGTH_LONG).show()
            manager.download(model, DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    modelReady = true
                    Toast.makeText(this, "సిద్ధం. వ్రాయండి!", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Download failed. Internet ఆన్ చేయండి.",
                        Toast.LENGTH_LONG).show()
                }
        }
    }

    // ================= Maths layout =================

    // One written symbol (may have several strokes, e.g. "=" or "x")
    private class Sym(var l: Float, var t: Float, var r: Float, var b: Float, val color: Int) {
        val strokes = mutableListOf<Ink.Stroke>()
        var text = ""
        var root = false   // a √ sign drawn with its top bar
        var flat = false   // a single long horizontal stroke (fraction bar or minus)
        val w get() = r - l
        val h get() = b - t
        val cy get() = (t + b) / 2
        val cx get() = (l + r) / 2
    }

    // Layout element: a plain symbol, a fraction block, or a root block
    private class El(
        val kind: Int,                      // 0 plain, 1 fraction, 2 root
        val sym: Sym,                       // the symbol / the bar / the √ sign
        val a: List<Sym> = emptyList(),     // numerator, or content under √
        val d: List<Sym> = emptyList()      // denominator
    ) {
        var l = 0f; var t = 0f; var r = 0f; var b = 0f
        var sup = false
        val h get() = b - t
        val cy get() = (t + b) / 2
        init {
            val all = listOf(sym) + a + d
            l = all.minOf { it.l }; t = all.minOf { it.t }
            r = all.maxOf { it.r }; b = all.maxOf { it.b }
        }
    }

    private class Out {
        val items = mutableListOf<DrawingView.TextItem>()
        val lines = mutableListOf<DrawingView.LineItem>()
        val groups = HashMap<Int, DrawingView.Group>()
    }

    private class Res(val copy: String, val expr: String, val baseline: Float, val size: Float)

    // √ drawn as: small tick, down, up, long top bar, all in ONE stroke
    private fun isRoot(st: Ink.Stroke, l: Float, t: Float, r: Float, b: Float): Boolean {
        val pts = st.points
        val w = r - l; val h = b - t
        if (pts.size < 8 || h < 20f || w < h * 0.9f) return false
        val first = pts.first(); val last = pts.last()
        if (first.x > l + 0.3f * w || first.y < t + 0.3f * h) return false
        if (last.x < l + 0.75f * w || last.y > t + 0.2f * h) return false
        val low = pts.maxByOrNull { it.y } ?: return false
        return low.x <= l + 0.45f * w
    }

    private fun canMerge(s: Sym, l: Float, t: Float, r: Float, b: Float): Boolean {
        val w2 = r - l
        val cx2 = (l + r) / 2
        val inside = if (w2 <= s.w) cx2 >= s.l - 5 && cx2 <= s.r + 5
        else s.cx >= l - 5 && s.cx <= r + 5
        if (!inside) return false
        val vo = minOf(s.b, b) - maxOf(s.t, t)
        val maxH = maxOf(s.h, b - t, 20f)
        if (vo <= 0 && -vo > 0.8f * maxH) return false
        // a long flat bar with a much narrower stroke away from it = fraction, not one symbol
        val wide = maxOf(s.w, w2); val narrow = minOf(s.w, w2)
        val wideH = if (s.w >= w2) s.h else (b - t)
        if (wide > 2.5f * maxOf(wideH, 10f) && narrow < 0.5f * wide && vo <= 0) return false
        return true
    }

    private fun groupSymbols(list: List<DrawingView.PStroke>): List<Sym> {
        val syms = mutableListOf<Sym>()
        for (ps in list) {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (p in ps.stroke.points) {
                l = minOf(l, p.x); r = maxOf(r, p.x)
                t = minOf(t, p.y); b = maxOf(b, p.y)
            }
            val rootStroke = isRoot(ps.stroke, l, t, r, b)
            val host = if (rootStroke) null
            else syms.firstOrNull { !it.root && canMerge(it, l, t, r, b) }
            if (host != null) {
                host.strokes.add(ps.stroke)
                host.l = minOf(host.l, l); host.r = maxOf(host.r, r)
                host.t = minOf(host.t, t); host.b = maxOf(host.b, b)
            } else {
                syms.add(Sym(l, t, r, b, ps.color).also {
                    it.strokes.add(ps.stroke); it.root = rootStroke
                })
            }
        }
        for (s in syms) s.flat = !s.root && s.strokes.size == 1 && s.w > 2f * maxOf(s.h, 10f)
        return syms.sortedBy { it.l }
    }

    private fun tickWidth(s: Sym) = minOf(s.h * 0.55f, s.w * 0.4f)

    // Find fractions and roots, and turn everything else into plain elements
    private fun buildElements(syms: List<Sym>): List<El> {
        if (syms.isEmpty()) return emptyList()
        val hs = syms.filter { !it.root && !it.flat }.map { it.h }.sorted()
        val refH = if (hs.isEmpty()) 40f else hs[hs.size / 2]
        val consumed = HashSet<Sym>()
        val blocks = mutableListOf<El>()
        for (c in syms.filter { it.root || it.flat }.sortedByDescending { it.w }) {
            if (c in consumed) continue
            if (c.root) {
                val tw = tickWidth(c)
                val inner = syms.filter {
                    it !== c && it !in consumed && it.cx > c.l + tw - 5 && it.cx < c.r + 5 &&
                            it.cy > c.t && it.cy < c.b + c.h * 0.2f
                }
                if (inner.isEmpty()) continue
                consumed.add(c); consumed.addAll(inner)
                blocks.add(El(2, c, inner))
            } else {
                val near = syms.filter {
                    it !== c && it !in consumed && it.cx >= c.l - 5 && it.cx <= c.r + 5 &&
                            Math.abs(it.cy - c.cy) < 4 * refH
                }
                val num = near.filter { it.cy < c.cy }
                val den = near.filter { it.cy >= c.cy }
                if (num.isEmpty() || den.isEmpty()) continue
                consumed.add(c); consumed.addAll(near)
                blocks.add(El(1, c, num, den))
            }
        }
        val plain = syms.filter { it !in consumed }.map {
            if (it.root) it.text = "√"
            if (it.flat) it.text = "-"
            El(0, it)
        }
        return (plain + blocks).sortedBy { it.l }
    }

    // Split elements into lines (powers sit above the line, so allow some margin)
    private fun groupLines(els: List<El>): List<List<El>> {
        val lines = mutableListOf<MutableList<El>>()
        for (e in els) {
            val line = lines.firstOrNull { ln ->
                val t = ln.minOf { it.t }; val b = ln.maxOf { it.b }; val h = b - t
                e.cy >= t - 0.8f * h && e.cy <= b + 0.3f * h
            }
            if (line != null) line.add(e) else lines.add(mutableListOf(e))
        }
        return lines.sortedBy { ln -> ln.minOf { it.t } }
    }

    private fun addRootLines(s: Sym, out: Out) {
        val tw = tickWidth(s)
        val ax = s.l; val ay = s.t + 0.62f * s.h
        val bx = s.l + 0.22f * tw; val by = s.t + 0.55f * s.h
        val cx = s.l + 0.55f * tw; val cy = s.b
        val dx = s.l + tw; val dy = s.t
        out.lines.add(DrawingView.LineItem(ax, ay, bx, by, s.color))
        out.lines.add(DrawingView.LineItem(bx, by, cx, cy, s.color))
        out.lines.add(DrawingView.LineItem(cx, cy, dx, dy, s.color))
        out.lines.add(DrawingView.LineItem(dx, dy, s.r, dy, s.color))
    }

    // Draw one line of elements; returns its text (copy form and calculable form)
    private fun layoutLine(els: List<El>, parentSize: Float, out: Out): Res {
        if (els.isEmpty()) return Res("", "", 0f, 24f)

        var base: El? = null
        var sup: El? = null
        for (e in els) {
            val b = base; val u = sup
            e.sup = e.kind == 0 && when {
                u != null && Math.abs(e.cy - u.cy) < u.h * 0.7f &&
                        e.l - u.r < u.h * 1.5f -> true
                b != null && e.l >= b.l && e.b < b.t + b.h * 0.65f &&
                        e.h <= b.h * 1.1f -> true
                else -> false
            }
            if (e.sup) sup = e else { base = e; sup = null }
        }

        val normal = els.filter { !it.sup }
        val plainNormal = normal.filter { it.kind == 0 }
        val size = when {
            plainNormal.isNotEmpty() ->
                (plainNormal.maxOf { it.h } / 0.72f).coerceIn(24f, 300f) * textScale
            parentSize > 0f -> parentSize
            else -> (normal.minOf { it.h } / 2.2f).coerceIn(24f, 300f) * textScale
        }
        val baseline = if (plainNormal.isNotEmpty())
            plainNormal.map { it.b }.sorted()[plainNormal.size / 2] else normal[0].cy

        val copy = StringBuilder()
        val expr = StringBuilder()
        val expBuf = StringBuilder()
        fun flush() {
            if (expBuf.isNotEmpty()) {
                copy.append(toSuper(expBuf.toString()))
                expr.append("^(").append(expBuf).append(")")
                expBuf.setLength(0)
            }
        }

        for (e in els) {
            val s = e.sym
            if (e.sup) {
                out.items.add(DrawingView.TextItem(s.text, toSuper(s.text), s.cx, s.b,
                    size * 0.6f, s.color, true))
                expBuf.append(s.text)
                continue
            }
            flush()
            when (e.kind) {
                0 -> {
                    out.items.add(DrawingView.TextItem(s.text, s.text, s.cx, baseline,
                        size, s.color, false))
                    copy.append(s.text); expr.append(s.text)
                }
                1 -> {
                    out.lines.add(DrawingView.LineItem(s.l, s.cy, s.r, s.cy, s.color))
                    val n = layoutLine(buildElements(e.a), size * 0.85f, out)
                    val d = layoutLine(buildElements(e.d), size * 0.85f, out)
                    copy.append("(").append(n.copy).append(")/(").append(d.copy).append(")")
                    expr.append("((").append(n.expr).append(")/(").append(d.expr).append("))")
                }
                else -> {
                    addRootLines(s, out)
                    val inner = layoutLine(buildElements(e.a), size, out)
                    copy.append("√(").append(inner.copy).append(")")
                    expr.append("sqrt(").append(inner.expr).append(")")
                }
            }
        }
        flush()
        return Res(copy.toString(), expr.toString(), baseline, size)
    }

    private fun layoutAll(syms: List<Sym>): Out {
        val out = Out()
        for (line in groupLines(buildElements(syms))) {
            val sorted = line.sortedBy { it.l }
            val i0 = out.items.size
            val l0 = out.lines.size
            val res = layoutLine(sorted, 0f, out)
            var copy = res.copy

            // "=" at the end: write the answer right after it
            if (copy.trimEnd().endsWith("=")) {
                val v = evaluate(res.expr)
                if (v != null) {
                    val ans = formatNumber(v)
                    measurePaint.textSize = res.size
                    val w = measurePaint.measureText(ans)
                    val cx = sorted.last().r + res.size * 0.35f + w / 2
                    out.items.add(DrawingView.TextItem(ans, ans, cx, res.baseline,
                        res.size, 0xFF2E7D32.toInt(), false))
                    copy = copy.trimEnd() + ans
                }
            }

            if (out.items.size == i0) continue
            val gid = nextGroup++
            var x0 = Float.MAX_VALUE
            for (k in i0 until out.items.size) {
                out.items[k].group = gid
                x0 = minOf(x0, out.items[k].cx)
            }
            for (k in l0 until out.lines.size) out.lines[k].group = gid
            out.groups[gid] = DrawingView.Group(copy, res.baseline, x0, res.size, out.items.size - i0)
        }
        return out
    }

    private fun evaluate(raw: String): Double? {
        var s = raw.replace(" ", "").trimEnd('=')
        s = s.replace('×', '*').replace('x', '*').replace('X', '*').replace('·', '*')
            .replace('÷', '/').replace('−', '-').replace('–', '-').replace('—', '-')
            .replace(',', '.')
        val check = s.replace("sqrt", "")
        if (s.isEmpty() || !check.all { it in "0123456789+-*/^()." }) return null
        return try {
            val v = Parser(s).parse()
            if (v.isNaN() || v.isInfinite()) null else v
        } catch (e: Exception) { null }
    }

    private fun formatNumber(v: Double): String {
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15)
            return Math.rint(v).toLong().toString()
        return String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
    }

    // ================= Recognition =================

    private fun recognizeAndReplace() {
        if (!modelReady) return
        val snapshot = drawing.pending.toList()
        if (snapshot.isEmpty()) return
        val syms = groupSymbols(snapshot)
        val todo = syms.filter { !it.root && !it.flat }
        if (todo.isEmpty()) { finish(snapshot, syms); return }
        var remaining = todo.size
        for (s in todo) {
            val ink = Ink.builder().also { b -> s.strokes.forEach { b.addStroke(it) } }.build()
            recognizer.recognize(ink)
                .addOnSuccessListener { r -> s.text = r.candidates.firstOrNull()?.text ?: "?" }
                .addOnFailureListener { s.text = "?" }
                .addOnCompleteListener {
                    remaining--
                    if (remaining == 0) finish(snapshot, syms)
                }
        }
    }

    private fun finish(snapshot: List<DrawingView.PStroke>, syms: List<Sym>) {
        val out = layoutAll(syms)
        drawing.commit(snapshot, out.items, out.lines, out.groups)
    }

    // For Copy/Save text only: x² style characters
    private val supMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', 'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ'
    )

    private fun toSuper(t: String): String {
        val sb = StringBuilder()
        for (c in t) sb.append(supMap[c] ?: return "^($t)")
        return sb.toString()
    }

    override fun onDestroy() { recognizer.close(); super.onDestroy() }
}
