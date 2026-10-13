package dev.fajar.starter.expenses.presentation.receipts

/** Each opening owns a distinct target; callbacks from an earlier dialog cannot edit the form. */
class ExpenseReceiptTarget(
    val workspaceId: String,
    val employeeId: String,
    val selectedDocumentIds: Set<String>,
)
