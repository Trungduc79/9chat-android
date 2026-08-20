package vn.chat9.app.ui.transaction

import vn.chat9.app.data.vapi.dto.MoneyTransactionDto

/**
 * Quy tắc DÙNG CHUNG: khi nào 1 GD tiền được mở dialog gán khách (tiền vào) / phân bổ
 * (tiền ra). Mirror 1:1 web `useMoneyTxAction.ts` — một chỗ quyết định, hai nền tảng
 * khỏi lệch nhau.
 */
object MoneyTxActionRules {

    /** Tiền GD đã gán thanh toán cho HĐ VAT (decimal BE trả chuỗi → đã parse Double ở DTO). */
    fun vatAllocated(tx: MoneyTransactionDto): Double =
        tx.vatInvoicePayments?.sumOf { it.amount } ?: 0.0

    fun vatUnallocated(tx: MoneyTransactionDto): Double = tx.amount - vatAllocated(tx)

    /** GD nội bộ (đối trừ chuyển khoản) — không phải tiền khách trả, không gán HĐ. */
    fun isInternalTx(tx: MoneyTransactionDto): Boolean = tx.meta?.internalTransfer == true

    /**
     * Mở được dialog khi:
     *  - GD pending (gán khách / phân bổ), HOẶC
     *  - GD tiền vào đã gán khách nhưng CHƯA chốt nợ (sửa lại khách), HOẶC
     *  - GD tiền ra đã phân bổ nhưng CHƯA chốt nợ (phân bổ lại), HOẶC
     *  - BẤT KỲ GD tiền vào nào CHƯA gán HĐ VAT (độc lập debt_calc/money_calc).
     */
    fun canOpenAction(tx: MoneyTransactionDto): Boolean {
        if (tx.type == "pending") return true
        if (tx.direction == "in") {
            if (tx.partyType == "customer" && tx.debtCalc.isNullOrBlank()) return true // đổi khách
            return vatUnallocated(tx) > 1 && !isInternalTx(tx)
        }
        return tx.direction == "out" && tx.debtCalc.isNullOrBlank() // tiền ra đã phân bổ → phân bổ lại
    }

    enum class InPanelMode { ASSIGN, REASSIGN, VAT_ONLY }

    /**
     * Chế độ panel gán tiền vào:
     *  - pending → ASSIGN (gán khách + HĐ);
     *  - đã gán khách, chưa chốt nợ → REASSIGN (đổi khách + HĐ);
     *  - còn lại (đã chốt nợ/quỹ, baseline…) → VAT_ONLY: CHỈ gán thanh toán HĐ VAT.
     */
    fun inPanelMode(tx: MoneyTransactionDto): InPanelMode = when {
        tx.type == "pending" -> InPanelMode.ASSIGN
        tx.partyType == "customer" && tx.debtCalc.isNullOrBlank() -> InPanelMode.REASSIGN
        else -> InPanelMode.VAT_ONLY
    }
}
