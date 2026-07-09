package vn.chat9.app.ui.modules.accounting

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import vn.chat9.app.data.vapi.dto.DebtStatementDto
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Render bảng công nợ "net sạch" (mirror web utils/debtStatementImage) ra Bitmap để
 * copy/gửi khách. Số dư từng dòng = đầu kỳ + luỹ kế (debit − credit) (không dùng
 * remaining_debt/credit vì DTO Android không có). Vẽ theo web-unit rồi scale ×2.6.
 */
fun renderDebtStatementBitmap(partyName: String, st: DebtStatementDto, exportedAt: String): Bitmap {
    val scale = 2.6f
    val w = 958f
    val padX = 21f
    val rowH = 30f
    val headerH = 123f
    val tableHeadH = 34f
    val totalRowH = 46f
    val footerH = 96f
    val m = 3f
    val gap = 20f
    val moneyColW = 88f
    val priceColW = 86f
    val qtyColW = 24f
    val sttColW = 18f

    val rows = st.rows
    val bodyH = maxOf(rows.size, 1) * rowH
    val h = headerH + tableHeadH + bodyH + totalRowH + footerH

    val bmp = Bitmap.createBitmap(((w + 2 * m) * scale).toInt(), ((h + 2 * m) * scale).toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(Color.WHITE)
    c.scale(scale, scale)
    c.translate(m, m)

    val ink = Color.parseColor("#111111")
    val muted = Color.parseColor("#6b7280")
    val debitColor = Color.parseColor("#b45309")
    val creditColor = Color.parseColor("#047857")
    val negColor = Color.parseColor("#dc2626")
    val gold = Color.parseColor("#d4af37")
    val zebra = Color.parseColor("#f9fafb")

    val sans = Typeface.SANS_SERIF
    val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val italic = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
    fun p(size: Float, color: Int, tf: Typeface = sans) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = tf
    }

    val nf = NumberFormat.getInstance(Locale("vi"))
    val qf = NumberFormat.getInstance(Locale("vi")).apply { maximumFractionDigits = 3 }
    fun num(v: Double) = nf.format(v.roundToLong())
    fun money(v: Double) = if (v != 0.0) num(abs(v)) else ""

    // ---- cột x ----
    val measure = p(15f, ink)
    val dateColW = measure.measureText("00/00/0000")
    val cSttCenter = padX + sttColW / 2
    val cDate = padX + sttColW + 13f
    val cBalR = w - padX
    val cCreditR = cBalR - moneyColW - gap
    val cDebitR = cCreditR - moneyColW - gap
    val cPriceR = cDebitR - moneyColW - gap
    val cQtyR = cPriceR - priceColW - gap
    val cQtyCenter = cQtyR - qtyColW / 2
    val cDesc = cDate + dateColW + gap
    val cDescMax = cQtyR - qtyColW - gap - cDesc

    fun fit(text: String, maxW: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    // ===== HEADER =====
    val titlePaint = p(21f, ink, bold).apply { textAlign = Paint.Align.CENTER }
    c.drawText(fit(partyName, w - 2 * padX, titlePaint), w / 2, 42f, titlePaint)
    val periodPaint = p(16f, muted).apply { textAlign = Paint.Align.CENTER }
    c.drawText("Kỳ: ${fmtD(st.period.from)} — ${fmtD(st.period.to)}", w / 2, 75f, periodPaint)

    val obLabel = p(16.5f, ink, bold).apply { textAlign = Paint.Align.LEFT }
    c.drawText("Số dư đầu kỳ:", padX, 105f, obLabel)
    val ob = st.openingBalance
    val goldPaint = p(19.5f, gold, italic).apply { textAlign = Paint.Align.RIGHT }
    c.drawText("đ", cBalR, 105f, goldPaint)
    val obDW = goldPaint.measureText("đ")
    val obVal = p(16.5f, if (ob < 0) negColor else ink, bold).apply { textAlign = Paint.Align.RIGHT }
    c.drawText(if (ob < 0) "(${num(-ob)})" else num(ob), cBalR - obDW - 5f, 105f, obVal)

    // ===== ĐẦU BẢNG =====
    var y = headerH
    val headBg = Paint().apply { color = Color.parseColor("#f3f4f6") }
    c.drawRect(padX - 8, y, w - padX + 8, y + tableHeadH, headBg)
    val headY = y + 22f
    val hL = p(15.5f, muted, bold).apply { textAlign = Paint.Align.LEFT }
    val hC = p(15.5f, muted, bold).apply { textAlign = Paint.Align.CENTER }
    val hR = p(15.5f, muted, bold).apply { textAlign = Paint.Align.RIGHT }
    c.drawText("#", cSttCenter, headY, hC)
    c.drawText("Ngày", cDate, headY, hL)
    c.drawText("Diễn giải", cDesc, headY, hL)
    c.drawText("SL", cQtyCenter, headY, hC)
    c.drawText("Đơn giá", cPriceR, headY, hR)
    c.drawText("Phát sinh", cDebitR, headY, hR)
    c.drawText("Thanh toán", cCreditR, headY, hR)
    c.drawText("Số dư", cBalR, headY, hR)
    y += tableHeadH

    // ===== THÂN BẢNG =====
    if (rows.isEmpty()) {
        c.drawText("Không có giao dịch trong kỳ", w / 2, y + 20f, p(16f, muted).apply { textAlign = Paint.Align.CENTER })
        y += rowH
    } else {
        val zebraPaint = Paint().apply { color = zebra }
        val sttPaint = p(14f, muted).apply { textAlign = Paint.Align.CENTER; alpha = 90 }
        val datePaint = p(15f, ink).apply { textAlign = Paint.Align.LEFT }
        val descPaint = p(16f, ink).apply { textAlign = Paint.Align.LEFT }
        val qtyPaint = p(16f, Color.parseColor("#374151")).apply { textAlign = Paint.Align.CENTER }
        val pricePaint = p(16f, Color.parseColor("#374151")).apply { textAlign = Paint.Align.RIGHT }
        val debitPaint = p(16f, debitColor).apply { textAlign = Paint.Align.RIGHT }
        val creditPaint = p(16f, creditColor).apply { textAlign = Paint.Align.RIGHT }
        var running = ob
        var pDate = ""
        var pDoc = ""
        rows.forEachIndexed { i, r ->
            if (i % 2 == 1) c.drawRect(padX - 8, y, w - padX + 8, y + rowH, zebraPaint)
            val ty = y + 20f
            running += r.debit - r.credit
            val doc = r.docNo ?: ""
            val showHeader = !(r.date == pDate && doc == pDoc)
            pDate = r.date; pDoc = doc
            c.drawText("${i + 1}", cSttCenter, ty, sttPaint)
            if (showHeader) c.drawText(fmtD(r.date), cDate, ty, datePaint)
            c.drawText(fit(r.description ?: "", cDescMax, descPaint), cDesc, ty, descPaint)
            r.qty?.let { c.drawText(qf.format(it), cQtyCenter, ty, qtyPaint) }
            r.unitPrice?.let { c.drawText(num(it), cPriceR, ty, pricePaint) }
            c.drawText(money(r.debit), cDebitR, ty, debitPaint)
            c.drawText(money(r.credit), cCreditR, ty, creditPaint)
            c.drawText(if (running < 0) "(${num(-running)})" else num(running), cBalR, ty,
                p(16f, if (running < 0) negColor else ink).apply { textAlign = Paint.Align.RIGHT })
            y += rowH
        }
    }

    // ===== DÒNG TỔNG =====
    c.drawLine(padX - 8, y + 0.75f, w - padX + 8, y + 0.75f, Paint().apply { color = Color.parseColor("#4b5563"); strokeWidth = 1.5f })
    val closing = st.closingBalance
    val ty = y + 25f
    c.drawText("TỔNG", cDesc, ty, p(16.5f, ink, bold).apply { textAlign = Paint.Align.LEFT })
    c.drawText(num(st.totalDebit), cDebitR, ty, p(16.5f, debitColor, bold).apply { textAlign = Paint.Align.RIGHT; alpha = 77 })
    c.drawText(num(st.totalCredit), cCreditR, ty, p(16.5f, creditColor, bold).apply { textAlign = Paint.Align.RIGHT; alpha = 77 })
    c.drawText(if (closing < 0) "(${num(-closing)})" else num(closing), cBalR, ty,
        p(16.5f, if (closing < 0) negColor else ink, bold).apply { textAlign = Paint.Align.RIGHT; alpha = 153 })
    y += totalRowH

    // ===== FOOTER =====
    val labelColor = if (closing >= 0) Color.parseColor("#7f1d1d") else Color.parseColor("#065f46")
    val fy = y + 24f
    val label = if (closing >= 0) "Số dư cuối kỳ (còn phải thu):" else "Số dư cuối kỳ (trả trước):"
    val labelPaint = p(19f, muted, bold).apply { textAlign = Paint.Align.LEFT; alpha = 153 }
    c.drawText(label, padX, fy, labelPaint)
    val valX = padX + labelPaint.measureText(label) + 12f
    val valPaint = p(22f, labelColor, bold).apply { textAlign = Paint.Align.LEFT }
    val valText = num(abs(closing))
    c.drawText(valText, valX, fy, valPaint)
    val valW = valPaint.measureText(valText)
    c.drawText("đ", valX + valW + 4f, fy, p(21f, gold, italic).apply { textAlign = Paint.Align.LEFT })
    c.drawText("Ngày xuất: $exportedAt", padX, y + 54f, p(14.5f, muted))

    // ===== BORDER bo góc 20 =====
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#4b5563"); strokeWidth = 1f }
    val path = Path().apply { addRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), 20f, 20f, Path.Direction.CW) }
    c.drawPath(path, border)

    return bmp
}

/** ISO/date → DD/MM/YYYY. */
private fun fmtD(s: String?): String {
    if (s.isNullOrBlank()) return ""
    val d = s.substringBefore("T").split("-")
    return if (d.size == 3) "${d[2]}/${d[1]}/${d[0]}" else s
}
