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

/**
 * Render ảnh "gửi khách" (mirror web SaleOrderFormView.renderCustomerImage): header
 * (KH · ngày · mã), mỗi dòng [ảnh][tên · SL đơn-vị × đơn-giá … thành-tiền căn phải],
 * bảng tiền (tiền hàng/ship/thu hộ/giảm → Tổng cộng), ảnh xác nhận giao hàng full-width,
 * khung viền bo góc bao ngoài. Vẽ theo web-unit rồi scale ×2. Constants copy y hệt web.
 *
 * @param includeAttach false = bỏ ảnh giao hàng khỏi ảnh đơn
 * @return (bitmap, attachTopRatio) — attachTopRatio (0..1 theo chiều cao) để đặt nút × (null nếu không có ảnh)
 */
fun renderCustomerBitmap(
    data: SupplierImageDto, custName: String, dateStr: String, includeAttach: Boolean,
): Triple<Bitmap, Float?, Float?> {
    val s = 2f
    val w = 600f
    val padX = 20f; val padTop = 84f; val padBottom = 24f
    val imgS = 107f; val gapIT = 16f; val rowGap = 8f; val rowH = imgS
    val tx = padX + imgS + gapIT
    val twMax = w - tx - padX
    val nameSize = 24f; val lineH = 32f; val priceGap = 8f; val priceH = 26f
    val colQty = 80f; val colUnit = 55f; val colMul = 25f; val colPrice = 100f

    val items = data.items

    fun paint(size: Float, color: Int, tf: Typeface = Typeface.DEFAULT, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = tf; isSubpixelText = true; textAlign = align }
    val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val italic = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
    val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    val namePaint = paint(nameSize, Color.parseColor("#222222"))
    val nameLines = items.map { wrapLines(it.name, twMax, 2, namePaint) }

    // Bảng tiền: dòng hiện có.
    data class MLine(val label: String, val amt: Double, val sign: String, val deduct: Boolean)
    val goods = items.sumOf { it.qty * it.price }
    val moneyLines = mutableListOf(MLine("Tổng tiền hàng", goods, "", false))
    if (data.shippingFee > 0) moneyLines.add(MLine("Phí ship", data.shippingFee, "", false))
    if (data.codCollected > 0) moneyLines.add(MLine("Thu tiền mặt", data.codCollected, "−", true))
    if (data.discountAmount > 0) moneyLines.add(MLine("Giảm cả đơn", data.discountAmount, "−", true))
    val grand = goods + data.shippingFee - data.codCollected - data.discountAmount
    val moneyBorderGap = 12f; val moneyGapTop = 38f; val moneyLineH = 34f
    val dividerGapAbove = 10f; val dividerGapBelow = 16f; val totalLineH = 40f
    val moneyBlockH = moneyGapTop + moneyLines.size * moneyLineH + dividerGapAbove + dividerGapBelow + totalLineH

    // Ảnh giao hàng — decode trước để tính chiều cao thật.
    val maxAttW = w - padX * 2; val attGap = 14f
    val attBmps = if (includeAttach) data.attachments.mapNotNull { decodeDataUri(it) } else emptyList()
    data class Att(val bmp: Bitmap, val dw: Float, val dh: Float)
    val atts = attBmps.filter { it.width > 0 && it.height > 0 }
        .map { Att(it, maxAttW, it.height * (maxAttW / it.width)) }
    val attGapTop = 24f; val attLabelH = 26f; val attLabelGap = 10f
    val attContentH = if (atts.isNotEmpty()) atts.sumOf { it.dh.toDouble() }.toFloat() + (atts.size - 1) * attGap else 0f
    val attBlockH = if (atts.isNotEmpty()) attGapTop + attLabelH + attLabelGap + attContentH else 0f

    val itemsH = items.size * rowH + (items.size - 1).coerceAtLeast(0) * rowGap
    val h = padTop + itemsH + moneyBlockH + attBlockH + padBottom

    // Khung viền bao ngoài: 3px đệm ngoài + viền 1px + 3px đệm trong.
    val fr = 7f
    val cw = w + fr * 2; val ch = h + fr * 2

    val bmp = Bitmap.createBitmap((cw * s).toInt(), (ch * s).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(s, s)
    c.drawColor(Color.WHITE)
    val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#a3a3a3"); strokeWidth = 1f }
    c.drawRoundRect(RectF(3.5f, 3.5f, cw - 3.5f, ch - 3.5f), 20f, 20f, framePaint)
    c.translate(fr, fr)

    fun drawTopC(text: String, x: Float, topY: Float, p: Paint) = c.drawText(text, x, topY - p.fontMetrics.ascent, p)
    fun measure(text: String, p: Paint) = p.measureText(text)

    // Header: KH · | · ngày · | · mã (mã xanh mono).
    val titlePaint = paint(24f, Color.parseColor("#111111"), bold)
    val sepPaint = paint(24f, Color.parseColor("#c2c2c2"), bold)
    val codePaint = paint(22f, Color.parseColor("#2f6df6"), mono)
    val sep = "  |  "
    val wName = measure(custName, titlePaint); val wSep = measure(sep, titlePaint)
    val wDate = measure(dateStr, titlePaint); val wCode = measure(data.orderCode, codePaint)
    var hx = (w - (wName + wSep + wDate + wSep + wCode)) / 2f
    drawTopC(custName, hx, 26f, titlePaint); hx += wName
    drawTopC(sep, hx, 26f, sepPaint); hx += wSep
    drawTopC(dateStr, hx, 26f, titlePaint); hx += wDate
    drawTopC(sep, hx, 26f, sepPaint); hx += wSep
    drawTopC(data.orderCode, hx, 27f, codePaint)

    val grayLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e5e5e5"); strokeWidth = 1f }
    c.drawLine(padX, padTop - 14f, w - padX, padTop - 14f, grayLine)

    val phPaint = paint(13f, Color.parseColor("#bbbbbb")).apply { textAlign = Paint.Align.CENTER }
    val qtyPaint = paint(24f, Color.parseColor("#111111"), bold, Paint.Align.CENTER)
    val unitPaint = paint(19f, Color.parseColor("#808080"), italic, Paint.Align.CENTER)
    val mulPaint = paint(20f, Color.parseColor("#aaaaaa"), Typeface.DEFAULT, Paint.Align.CENTER)
    val pricePaint = paint(22f, Color.parseColor("#333333"), bold, Paint.Align.CENTER)
    val lineTotalPaint = paint(24f, Color.parseColor("#111111"), bold, Paint.Align.RIGHT)

    items.forEachIndexed { i, it ->
        val y = padTop + i * (rowH + rowGap)
        c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(padX, y, padX + imgS, y + imgS), 5f, 5f, Path.Direction.CW) })
        c.drawColor(Color.parseColor("#f4f4f4"))
        val im = decodeDataUri(it.image)
        if (im != null) {
            val sc = minOf(imgS / im.width, imgS / im.height)
            val dw = im.width * sc; val dh = im.height * sc
            c.drawBitmap(im, null, RectF(padX + (imgS - dw) / 2, y + (imgS - dh) / 2, padX + (imgS + dw) / 2, y + (imgS + dh) / 2), null)
        } else drawTopC("Không có ảnh", padX + imgS / 2, y + imgS / 2 - 7f, phPaint)
        c.restore()

        val lines = nameLines[i]
        val blockH = lines.size * lineH + priceGap + priceH
        var ty = y + (imgS - blockH) / 2f
        lines.forEachIndexed { k, ln -> drawTopC(ln, tx, ty + k * lineH, namePaint) }
        ty += lines.size * lineH + priceGap

        // Cột SL/đơn giá căn giữa.
        var cx = tx
        drawTopC(fmtQtyA(it.qty), cx + colQty / 2, ty, qtyPaint); cx += colQty
        if (it.unit.isNotBlank()) drawTopC(it.unit, cx + colUnit / 2, ty, unitPaint)
        cx += colUnit
        drawTopC("×", cx + colMul / 2, ty + 2f, mulPaint); cx += colMul
        drawTopC(fmtMoneyA(it.price), cx + colPrice / 2, ty, pricePaint)
        // Thành tiền căn phải.
        drawTopC(fmtMoneyA(it.qty * it.price), w - padX, ty, lineTotalPaint)

        if (i < items.size - 1) {
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#eeeeee"); strokeWidth = 1f }
            c.drawLine(padX, y + rowH + rowGap / 2, w - padX, y + rowH + rowGap / 2, dp)
        }
    }

    var cy = padTop + itemsH

    // Bảng tiền — nhãn trái, số căn phải; border-top xám.
    var my = cy + moneyGapTop
    c.drawLine(padX, cy + moneyBorderGap, w - padX, cy + moneyBorderGap, grayLine)
    val mLabelPaint = paint(21f, Color.parseColor("#666666"))
    val mAmtPaint = paint(24f, Color.parseColor("#111111"), bold, Paint.Align.RIGHT)
    val mAmtDeduct = paint(24f, Color.parseColor("#c0392b"), bold, Paint.Align.RIGHT)
    moneyLines.forEach { ln ->
        drawTopC(ln.label, padX, my + 3f, mLabelPaint)
        drawTopC(ln.sign + fmtMoneyA(ln.amt), w - padX, my, if (ln.deduct) mAmtDeduct else mAmtPaint)
        my += moneyLineH
    }
    val dy = my + dividerGapAbove
    c.drawLine(padX, dy, w - padX, dy, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2f6df6"); strokeWidth = 2f })
    val ty2 = dy + dividerGapBelow
    drawTopC("Tổng cộng", padX, ty2 + 6f, paint(23f, Color.parseColor("#111111"), bold))
    // "đ" vàng nghiêng ở mép phải; số tổng đậm đặt bên trái "đ".
    val dSymPaint = paint(26f, Color.parseColor("#e0a400"), italic, Paint.Align.RIGHT)
    drawTopC("đ", w - padX, ty2 + 1f, dSymPaint)
    val wDsym = paint(26f, 0, italic).measureText("đ")
    drawTopC(fmtMoneyA(grand), w - padX - wDsym - 8f, ty2, paint(27f, Color.parseColor("#111111"), bold, Paint.Align.RIGHT))
    cy += moneyBlockH

    // Ảnh xác nhận giao hàng — căn giữa.
    var attachTopRatio: Float? = null
    var attachRightRatio: Float? = null
    if (atts.isNotEmpty()) {
        var ay = cy + attGapTop
        drawTopC("Ảnh xác nhận giao hàng", w / 2, ay, paint(16f, Color.parseColor("#888888"), bold, Paint.Align.CENTER))
        ay += attLabelH + attLabelGap
        attachTopRatio = (ay + fr) / ch
        // Mép phải ảnh đính kèm: ax = padX (do dw = maxAttW = w - 2padX) → phải = padX + maxAttW = w - padX.
        attachRightRatio = (fr + w - padX) / cw
        val attBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#e5e5e5"); strokeWidth = 1f }
        for (a in atts) {
            val ax = (w - a.dw) / 2
            c.save()
            c.clipPath(Path().apply { addRoundRect(RectF(ax, ay, ax + a.dw, ay + a.dh), 6f, 6f, Path.Direction.CW) })
            c.drawBitmap(a.bmp, null, RectF(ax, ay, ax + a.dw, ay + a.dh), null)
            c.restore()
            c.drawRoundRect(RectF(ax + 0.5f, ay + 0.5f, ax + a.dw - 0.5f, ay + a.dh - 0.5f), 6f, 6f, attBorder)
            ay += a.dh + attGap
        }
    }

    return Triple(bmp, attachTopRatio, attachRightRatio)
}

/** Số tiền: làm tròn + locale vi (dấu . ngăn nghìn). */
private fun fmtMoneyA(n: Double): String {
    val nf = java.text.NumberFormat.getInstance(java.util.Locale("vi"))
    nf.maximumFractionDigits = 0
    return nf.format(Math.round(n))
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

/** Lưu bitmap vào thư viện ảnh (Pictures) qua MediaStore. jpeg=true → JPEG q85. true nếu thành công. */
fun saveBitmapToGallery(context: android.content.Context, bmp: Bitmap, name: String, jpeg: Boolean = false): Boolean = runCatching {
    val ext = if (jpeg) "jpg" else "png"
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.$ext")
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, if (jpeg) "image/jpeg" else "image/png")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES)
    }
    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return false
    context.contentResolver.openOutputStream(uri)?.use {
        if (jpeg) bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) else bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
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
