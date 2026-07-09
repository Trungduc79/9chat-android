package vn.chat9.app.ui.modules.sale

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import vn.chat9.app.data.vapi.dto.SupplierImageDto

/**
 * Render ảnh "gửi NCC" (mirror canvas web SaleOrderFormView.renderSupplierImage):
 * danh sách DỌC [ảnh bo góc 5px] - tên - "SL: {số} {đơn vị}", footer Tổng SL theo
 * từng đơn vị, khung ghi chú (nếu có). Vẽ theo "web-unit" rồi scale ×2 cho nét.
 *
 * @param dateStr ngày hiển thị sau title (DD/MM/YYYY)
 * @param note    ghi chú đơn (rỗng = không vẽ khung)
 */
fun renderSupplierBitmap(data: SupplierImageDto, dateStr: String, note: String): Bitmap {
    val s = 2f
    val w = 600f
    val padX = 20f; val padTop = 84f; val padBottom = 24f
    val imgS = 119f; val gapIT = 16f; val rowGap = 8f; val rowH = imgS
    val tx = padX + imgS + gapIT
    val twMax = w - tx - padX
    val nameSize = 24f; val lineH = 32f
    val qtyGap = 8f; val qtyH = 24f
    val footerH = 46f
    // Ghi chú
    val noteSize = 22f; val noteLineH = 30f; val notePad = 12f; val noteGapTop = 16f; val noteLabelH = 24f

    val items = data.items

    // ---- Paints ----
    fun paint(size: Float, color: Int, tf: Typeface = Typeface.DEFAULT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = tf; isSubpixelText = true
    }
    val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val italic = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)

    val namePaint = paint(nameSize, Color.parseColor("#222222"))
    val slPaint = paint(20f, Color.parseColor("#888888"), bold)
    val numPaint = paint(24f, Color.parseColor("#111111"), bold)
    val unitPaint = paint(19f, Color.parseColor("#808080"), italic)

    // ---- Đo trước: tên mỗi item + số dòng ghi chú → tính chiều cao ----
    val nameLines = items.map { wrapLines(it.name, twMax, 2, namePaint) }
    val noteLines = if (note.isNotBlank()) wrapMultiline(note, w - padX * 2 - notePad * 2, 14, paint(noteSize, 0)) else emptyList()
    val noteBoxH = if (noteLines.isNotEmpty()) noteLines.size * noteLineH + notePad * 2 else 0f
    val noteBlockH = if (noteLines.isNotEmpty()) noteGapTop + noteLabelH + noteBoxH else 0f

    val h = padTop + items.size * rowH + (items.size - 1).coerceAtLeast(0) * rowGap + footerH + noteBlockH + padBottom

    val bmp = Bitmap.createBitmap((w * s).toInt(), (h * s).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(s, s)
    c.drawColor(Color.WHITE)

    // Vẽ text kiểu "top-baseline" như web.
    fun drawTop(text: String, x: Float, topY: Float, p: Paint) = c.drawText(text, x, topY - p.fontMetrics.ascent, p)
    fun measure(text: String, p: Paint) = p.measureText(text)

    // ---- Title căn giữa: tên (đen) · " | " (xám mờ) · ngày (đen) ----
    val titlePaint = paint(26f, Color.parseColor("#111111"), bold)
    val sepPaint = paint(26f, Color.parseColor("#c2c2c2"), bold)
    val sep = "  |  "
    val wTitle = measure(data.title, titlePaint)
    val wSep = measure(sep, sepPaint)
    val wDate = measure(dateStr, titlePaint)
    var hx = (w - (wTitle + wSep + wDate)) / 2f
    drawTop(data.title, hx, 26f, titlePaint); hx += wTitle
    drawTop(sep, hx, 26f, sepPaint); hx += wSep
    drawTop(dateStr, hx, 26f, titlePaint)

    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e5e5e5"); strokeWidth = 1f }
    c.drawLine(padX, padTop - 14f, w - padX, padTop - 14f, linePaint)

    // ---- Các dòng item ----
    val phPaint = paint(13f, Color.parseColor("#bbbbbb")).apply { textAlign = Paint.Align.CENTER }
    items.forEachIndexed { i, it ->
        val y = padTop + i * (rowH + rowGap)

        // Ô ảnh bo góc 5px (clip).
        c.save()
        val clip = Path().apply { addRoundRect(RectF(padX, y, padX + imgS, y + imgS), 5f, 5f, Path.Direction.CW) }
        c.clipPath(clip)
        c.drawColor(Color.parseColor("#f4f4f4"))
        val bmpIt = decodeDataUri(it.image)
        if (bmpIt != null) {
            val sc = minOf(imgS / bmpIt.width, imgS / bmpIt.height)
            val dw = bmpIt.width * sc; val dh = bmpIt.height * sc
            c.drawBitmap(bmpIt, null, RectF(padX + (imgS - dw) / 2, y + (imgS - dh) / 2, padX + (imgS + dw) / 2, y + (imgS + dh) / 2), null)
        } else {
            drawTop("Không có ảnh", padX + imgS / 2, y + imgS / 2 - 7f, phPaint)
        }
        c.restore()

        // Cột text căn giữa dọc theo chiều cao ảnh: tên → SL.
        val lines = nameLines[i]
        val blockH = lines.size * lineH + qtyGap + qtyH
        var ty = y + (imgS - blockH) / 2f
        lines.forEachIndexed { k, ln -> drawTop(ln, tx, ty + k * lineH, namePaint) }
        ty += lines.size * lineH + qtyGap

        val spW = measure(" ", slPaint)
        var sx = tx
        drawTop("SL:", sx, ty, slPaint)
        sx += measure("SL:", slPaint) + spW * 2
        drawTop(fmtQtyA(it.qty), sx, ty, numPaint)
        sx += measure(fmtQtyA(it.qty), numPaint) + spW * 2
        if (it.unit.isNotBlank()) drawTop(it.unit, sx, ty, unitPaint)

        if (i < items.size - 1) {
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#eeeeee"); strokeWidth = 1f }
            c.drawLine(padX, y + rowH + rowGap / 2, w - padX, y + rowH + rowGap / 2, dp)
        }
    }

    // ---- Footer: Tổng SL theo từng đơn vị ----
    val listBottom = padTop + items.size * rowH + (items.size - 1).coerceAtLeast(0) * rowGap
    c.drawLine(padX, listBottom + 10f, w - padX, listBottom + 10f, linePaint)

    val spWF = measure(" ", slPaint)
    val anchorR = tx + measure("SL:", slPaint)
    val numX = anchorR + spWF * 2

    val totals = LinkedHashMap<String, Double>()
    for (it in items) totals[it.unit] = (totals[it.unit] ?: 0.0) + it.qty

    val fy = listBottom + 20f
    // "Tổng SL:" căn phải trùng mép "SL:" ở trên.
    val slRight = paint(20f, Color.parseColor("#888888"), bold).apply { textAlign = Paint.Align.RIGHT }
    drawTop("Tổng SL:", anchorR, fy, slRight)
    val footUnit = paint(20f, Color.parseColor("#333333"), italic).apply { alpha = 191 } // ~75%
    var gx = numX
    totals.entries.forEachIndexed { idx, (u, q) ->
        if (idx > 0) { drawTop(" + ", gx, fy, numPaint); gx += measure(" + ", numPaint) }
        val qs = fmtQtyA(q)
        drawTop(qs, gx, fy, numPaint); gx += measure(qs, numPaint)
        if (u.isNotBlank()) {
            gx += spWF * 2
            drawTop(u, gx, fy, footUnit); gx += measure(u, footUnit)
        }
    }

    // ---- Khung ghi chú ----
    if (noteLines.isNotEmpty()) {
        val labelY = listBottom + footerH + noteGapTop
        val notLabel = paint(15f, Color.parseColor("#888888"), bold)
        drawTop("Ghi chú", padX, labelY, notLabel)
        val by = labelY + noteLabelH
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#cccccc"); strokeWidth = 1f }
        c.drawRoundRect(RectF(padX + 0.5f, by + 0.5f, w - padX - 0.5f, by + noteBoxH - 0.5f), 5f, 5f, boxPaint)
        val notePaint = paint(noteSize, Color.parseColor("#12377a"))
        noteLines.forEachIndexed { k, ln -> drawTop(ln, padX + notePad, by + notePad + k * noteLineH, notePaint) }
    }

    return bmp
}

/** Ngắt text ≤ maxLines dòng vừa maxW, thêm "…" nếu tràn. */
private fun wrapLines(text: String, maxW: Float, maxLines: Int, p: Paint): List<String> {
    val words = text.split(" ")
    val lines = mutableListOf<String>()
    var line = ""
    for (word in words) {
        val test = if (line.isEmpty()) word else "$line $word"
        if (p.measureText(test) > maxW && line.isNotEmpty()) {
            lines.add(line); line = word
            if (lines.size == maxLines) break
        } else line = test
    }
    if (lines.size < maxLines && line.isNotEmpty()) lines.add(line)
    if (lines.size == maxLines && lines.joinToString(" ").length < text.length) {
        var last = lines[maxLines - 1]
        while (last.isNotEmpty() && p.measureText("$last…") > maxW) last = last.dropLast(1)
        lines[maxLines - 1] = "$last…"
    }
    return lines
}

/** Wrap tôn trọng xuống dòng thật (\n): mỗi đoạn wrap riêng, giữ dòng trống. */
private fun wrapMultiline(text: String, maxW: Float, maxTotal: Int, p: Paint): List<String> {
    val out = mutableListOf<String>()
    for (para in text.split(Regex("\\r?\\n"))) {
        if (para.isBlank()) { out.add(""); continue }
        out.addAll(wrapLines(para, maxW, 100, p))
        if (out.size >= maxTotal) break
    }
    return out.take(maxTotal)
}

private fun decodeDataUri(dataUri: String?): Bitmap? {
    if (dataUri.isNullOrBlank()) return null
    return runCatching {
        val b64 = dataUri.substringAfter("base64,", dataUri)
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

/** Bytes → "KB"/"MB" cho dòng dung lượng dialog. */
fun fmtBytesA(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.2f MB", n / 1024.0 / 1024.0)
}

/** Lưu bitmap vào thư viện ảnh (Pictures) qua MediaStore. true nếu thành công. */
fun saveBitmapToGallery(context: android.content.Context, bmp: Bitmap, name: String): Boolean = runCatching {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
    }
    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return false
    context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    true
}.getOrDefault(false)

/** ISO (2026-07-09T...) → "09/07/2026". Rỗng nếu null. */
fun poDateStr(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val d = iso.substringBefore("T").split("-")
    return if (d.size == 3) "${d[2]}/${d[1]}/${d[0]}" else iso
}

/** Format SL: bỏ số 0 thừa, locale vi (dấu . ngăn nghìn). */
private fun fmtQtyA(n: Double): String {
    val nf = java.text.NumberFormat.getInstance(java.util.Locale("vi"))
    nf.maximumFractionDigits = 3
    return nf.format(n)
}
