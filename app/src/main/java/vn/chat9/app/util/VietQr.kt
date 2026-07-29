package vn.chat9.app.util

/**
 * Dựng và đọc payload VietQR (EMVCo Merchant-Presented QR) hoàn toàn OFFLINE.
 *
 * Port từ `admin.ai.vn/src/utils/vietqr.ts` — giữ nguyên hành vi để web và
 * Android sinh ra cùng một chuỗi.
 *
 * KHÔNG gọi API bên thứ 3 để sinh ảnh QR. Payload chứa số tài khoản + số tiền +
 * nội dung, gửi sang dịch vụ ngoài là rò dữ liệu tài chính.
 * (Lưu ý: `AddFriendScreen` vẫn đang dùng api.qrserver.com cho QR profile 9chat —
 * cái đó chỉ chứa `9chat://user/{id}`, không nhạy cảm, nhưng nên chuyển sang
 * sinh offline luôn để app chỉ còn MỘT cơ chế QR.)
 */
object VietQr {

    /** Định danh Napas cho dịch vụ chuyển khoản nhanh trong nước. */
    private const val GUID_NAPAS = "A000000727"

    /** Chuyển khoản nhanh tới SỐ TÀI KHOẢN (QRIBFTTC là tới thẻ). */
    private const val SERVICE_TO_ACCOUNT = "QRIBFTTA"
    private const val CURRENCY_VND = "704"
    private const val COUNTRY_VN = "VN"

    /** Bọc 1 trường EMVCo: tag + độ dài 2 chữ số + giá trị. */
    private fun tlv(tag: String, value: String): String =
        tag + value.length.toString().padStart(2, '0') + value

    /**
     * Tách chuỗi TLV thành map tag → value.
     *
     * TLV KHÔNG phụ thuộc thứ tự — QR thật của ACB xếp trường khác hẳn chuỗi ta
     * sinh (số TK trước BIN, tag 62 trước 53/58). Vì vậy phải đọc theo TAG,
     * tuyệt đối không đọc theo vị trí.
     */
    private fun parseTlv(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i + 4 <= s.length) {
            val tag = s.substring(i, i + 2)
            val len = s.substring(i + 2, i + 4).toIntOrNull() ?: break
            if (len < 0 || i + 4 + len > s.length) break
            out[tag] = s.substring(i + 4, i + 4 + len)
            i += 4 + len
        }
        return out
    }

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) — chuẩn bắt buộc của EMVCo. */
    fun crc16(input: String): String {
        var crc = 0xFFFF
        for (ch in input) {
            crc = crc xor (ch.code shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc.toString(16).uppercase().padStart(4, '0')
    }

    /**
     * Sinh chuỗi payload VietQR.
     *
     * @param amount null hoặc <= 0 → QR không ấn định số tiền (người trả tự nhập).
     * @throws IllegalArgumentException nếu BIN không phải 6 chữ số hoặc thiếu số TK.
     */
    fun buildPayload(
        bankBin: String,
        accountNo: String,
        amount: Long? = null,
        addInfo: String? = null,
    ): String {
        val bin = bankBin.trim()
        val acc = accountNo.trim()
        require(Regex("^\\d{6}$").matches(bin)) { "BIN ngân hàng phải gồm đúng 6 chữ số" }
        require(acc.isNotEmpty()) { "Thiếu số tài khoản nhận" }

        val beneficiary = tlv("00", bin) + tlv("01", acc)
        val merchant = tlv("00", GUID_NAPAS) + tlv("01", beneficiary) + tlv("02", SERVICE_TO_ACCOUNT)
        val info = addInfo?.trim().orEmpty()

        val payload = buildString {
            append(tlv("00", "01"))                       // Payload Format Indicator
            append(tlv("01", if (amount != null && amount > 0) "12" else "11")) // 12 = dùng 1 lần
            append(tlv("38", merchant))
            append(tlv("53", CURRENCY_VND))
            if (amount != null && amount > 0) append(tlv("54", amount.toString()))
            append(tlv("58", COUNTRY_VN))
            if (info.isNotEmpty()) append(tlv("62", tlv("08", info)))
            append("6304")                                 // tag CRC + độ dài
        }
        return payload + crc16(payload)
    }

    /** Thông tin đọc được từ một QR chuyển khoản. */
    data class Decoded(
        val bankBin: String?,
        val accountNo: String?,
        /** QRIBFTTA = tới số TK · QRIBFTTC = tới thẻ. */
        val service: String?,
        val amount: Long?,
        val addInfo: String?,
        /** tag 01: '11' = tĩnh (dùng nhiều lần) · '12' = động (một lần). */
        val isStatic: Boolean,
        val crcValid: Boolean,
    )

    /**
     * Đọc ngược payload VietQR.
     *
     * QR chuyển khoản KHÔNG mã hoá: toàn bộ là TLV plaintext, không chữ ký, không
     * xác thực người tạo. Ai chụp được ảnh cũng lấy ra được số TK + ngân hàng —
     * nên luôn đối chiếu TÊN CHỦ TÀI KHOẢN trước khi chuyển tiền theo QR người khác đưa.
     *
     * @return null nếu không phải payload VietQR hợp lệ (link web, vé, mã khác…).
     */
    fun parsePayload(payload: String?): Decoded? {
        val s = payload?.trim().orEmpty()
        if (s.length < 12) return null

        val top = parseTlv(s)
        val merchant = top["38"]?.let { parseTlv(it) } ?: emptyMap()
        // Chỉ nhận QR chuyển khoản Napas — mã khác không có GUID này.
        if (merchant["00"] != GUID_NAPAS) return null

        val beneficiary = merchant["01"]?.let { parseTlv(it) } ?: emptyMap()
        val additional = top["62"]?.let { parseTlv(it) } ?: emptyMap()

        return Decoded(
            bankBin = beneficiary["00"],
            accountNo = beneficiary["01"],
            service = merchant["02"],
            amount = top["54"]?.toLongOrNull(),
            addInfo = additional["08"],
            isStatic = top["01"] != "12",
            crcValid = s.length > 4 && crc16(s.dropLast(4)) == s.takeLast(4).uppercase(),
        )
    }
}
