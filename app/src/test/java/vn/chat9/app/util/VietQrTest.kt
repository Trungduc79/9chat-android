package vn.chat9.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kiểm chứng port EMVCo sang Kotlin cho ra CÙNG kết quả với bản web
 * (`admin.ai.vn/src/utils/vietqr.ts`) — hai bên phải sinh y hệt nhau.
 */
class VietQrTest {

    /** Vector chuẩn của CRC-16/CCITT-FALSE. */
    @Test
    fun crc16_vectorChuan() {
        assertEquals("29B1", VietQr.crc16("123456789"))
    }

    /**
     * QR THẬT của ACB — ca quan trọng nhất: nó xếp trường NGƯỢC với chuỗi ta sinh
     * (số TK trước BIN, tag 62 trước 53/58). Đọc theo vị trí sẽ fail ở đây.
     */
    @Test
    fun parse_qrThatCuaAcb_docTheoTagKhongTheoViTri() {
        val real = "00020101021138520010A000000727012201084293499700069704160208QRIBFTTA" +
            "620053037045802VN63042B63"
        val d = VietQr.parsePayload(real)!!
        assertEquals("970416", d.bankBin)
        assertEquals("42934997", d.accountNo)
        assertEquals("QRIBFTTA", d.service)
        assertNull(d.amount)
        assertNull(d.addInfo)
        assertTrue("QR tĩnh dùng nhiều lần", d.isStatic)
        assertTrue("CRC phải hợp lệ", d.crcValid)
    }

    @Test
    fun roundTrip_dungRoiDocNguoc() {
        val payload = VietQr.buildPayload(
            bankBin = "970436",
            accountNo = "0123456789123",
            amount = 60_000,
            addInfo = "CP189NJJE Tra ship",
        )
        val d = VietQr.parsePayload(payload)!!
        assertEquals("970436", d.bankBin)
        assertEquals("0123456789123", d.accountNo)
        assertEquals(60_000L, d.amount)
        assertEquals("CP189NJJE Tra ship", d.addInfo)
        assertTrue("có số tiền → QR động", !d.isStatic)
        assertTrue(d.crcValid)
    }

    /** Số TK 13 ký tự: bản 1api gốc hardcode độ dài 22 nên hỏng ca này. */
    @Test
    fun soTaiKhoanDaiKhacNhau_doDaiTinhDong() {
        listOf("1", "22505511", "0123456789123", "12345678901234567890").forEach { acc ->
            val d = VietQr.parsePayload(VietQr.buildPayload("970416", acc))!!
            assertEquals(acc, d.accountNo)
        }
    }

    @Test
    fun khongCoSoTien_thiKhongCoTag54_vaLaQrTinh() {
        val d = VietQr.parsePayload(VietQr.buildPayload("970416", "42934997"))!!
        assertNull(d.amount)
        assertTrue(d.isStatic)
    }

    @Test
    fun chuoiKhongPhaiQrNganHang_traNull() {
        assertNull(VietQr.parsePayload("https://9chat.vn/user/12"))
        assertNull(VietQr.parsePayload(""))
        assertNull(VietQr.parsePayload(null))
        assertNull(VietQr.parsePayload("linh tinh khong phai TLV"))
    }

    @Test
    fun binSai_thiNem() {
        listOf("97041", "ABCDEF", "").forEach { bin ->
            runCatching { VietQr.buildPayload(bin, "123") }
                .onSuccess { throw AssertionError("BIN '$bin' lẽ ra phải bị chặn") }
        }
        runCatching { VietQr.buildPayload("970416", "") }
            .onSuccess { throw AssertionError("thiếu số TK lẽ ra phải bị chặn") }
    }
}
