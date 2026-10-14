package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/** Wire fixture remains independent of feature DTOs and presentation models. */
class NativeExpenseUi {
    @Volatile var enabled = false
    @Volatile var offline = false
    val lists = AtomicInteger()
    val details = AtomicInteger()
    val historyReads = AtomicInteger()
    private val actor = NativePeopleUi.ACCOUNT
    private val employee = NativePeopleUi.EMPLOYEE
    private val line =
        """{"id":"$LINE","categoryId":"$CATEGORY","occurredOn":"2026-10-12",
        "amount":"150000","description":"Client transport","costCenterId":null,"receiptRevisionIds":["$RECEIPT"]}"""
    private val draft =
        """{"revision":0,"employeeNumber":"E01","employeeName":"Alex Employee","title":"Client visit",
        "description":"Transport reimbursement","totalAmount":"150000","currency":"IDR","lines":[$line],
        "actorId":"$actor","reason":"Created","recordedAt":"2026-10-13T01:00:00Z"}"""
    private val claim =
        """{"id":"$ID","employmentId":"$employee","createdBy":"$actor","createdAt":"2026-10-13T01:00:00Z",
        "version":2,"draftRevision":0,"status":"APPROVED","draft":$draft,"submissionCount":1,"latestSubmissionId":"$SUBMISSION"}"""
    private val summary =
        """{"id":"$ID","employmentId":"$employee","employeeNumber":"E01","employeeName":"Alex Employee",
        "title":"Client visit","totalAmount":"150000","currency":"IDR","createdAt":"2026-10-13T01:00:00Z","status":"APPROVED","version":2}"""

    private val submitted =
        """{"id":"$SUBMISSION","claimId":"$ID","employmentId":"$employee","number":1,
                "draftRevision":0,"title":"Client visit","description":"Transport reimbursement","totalAmount":"150000","currency":"IDR",
                "lines":[{"id":"$LINE","occurredOn":"2026-10-12","amount":"150000","description":"Client transport",
                "category":{"id":"$CATEGORY","code":"TRAVEL","name":"Travel","version":0,"appliedRevision":0},"costCenter":null,
                "receipts":[{"revisionId":"$RECEIPT","fileName":"Transport receipt.pdf","mediaType":"application/pdf","size":1024,
                "sha256":"${"a".repeat(64)}","possibleDuplicate":false}]}],"submittedAt":"2026-10-13T02:00:00Z","reason":"Submitted",
                "approval":{"id":"$APPROVAL","status":"APPROVED","version":1,"currentStep":0,"stages":[["$REVIEWER"]]},
                "claimVersion":2,"claimStatus":"APPROVED","current":true,"reviews":[{"claimVersion":2,"approvalVersion":1,"step":0,
                "actorId":"$REVIEWER","decision":"APPROVE","status":"APPROVED","reason":"Approved for reimbursement","decidedAt":"2026-10-13T03:00:00Z"}]}"""
    private val currentHistory =
        """{"items":[{"version":2,"draftRevision":0,"status":"APPROVED","kind":"REVIEWED",
        "actorId":"$REVIEWER","reason":"Approved for reimbursement","recordedAt":"2026-10-13T03:00:00Z","submissionId":"$SUBMISSION"},
        {"version":1,"draftRevision":0,"status":"PENDING","kind":"SUBMITTED","actorId":"$actor","reason":"Submitted",
        "recordedAt":"2026-10-13T02:00:00Z","submissionId":"$SUBMISSION"}],"nextCursor":"1"}"""
    private val earlierHistory =
        """{"items":[{"version":0,"draftRevision":0,"status":"DRAFT","kind":"DRAFT_SAVED","actorId":"$actor",
        "reason":"Created","recordedAt":"2026-10-13T01:00:00Z","submissionId":null}],"nextCursor":null}"""

    fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val base = "/api/v1/companies/${NativeUiServer.COMPANY_ONE}/expenses"
        val path = request.url.encodedPath
        val sync =
            path.contains("/sync/") &&
                request.url.parameters["collections"]?.split(',')?.toSet() ==
                    setOf("EXPENSE_CLAIMS", "EXPENSE_PAYMENTS")
        if (!enabled || (!path.startsWith(base) && !sync)) return null
        if (offline) throw IOException("Expense fixture offline")
        assertEquals(HttpMethod.Get, request.method)
        if (sync) {
            assertEquals("2", request.url.parameters["limit"])
            val response =
                if (path.endsWith("/bootstrap"))
                    """{"collections":["EXPENSE_CLAIMS","EXPENSE_PAYMENTS"],"items":[{"collection":"EXPENSE_CLAIMS","id":"$ID","version":2}],"nextCursor":null,"changesCursor":"expense-head","serverTime":"2026-10-13T03:00:00Z"}"""
                else
                    """{"items":[],"cursor":"expense-head","hasMore":false,"pendingPublication":false,"pollAfterSeconds":5,"serverTime":"2026-10-13T03:00:00Z"}"""
            return response to HttpStatusCode.OK
        }
        if (path != "$base/me/employments") assertEquals("SELF", request.url.parameters["scope"])
        val body =
            when (path) {
                "$base/me/employments" ->
                    """{"accountId":"$actor","companyId":"${NativeUiServer.COMPANY_ONE}",
                "asOf":"2026-10-13","timezone":"Asia/Jakarta","employments":{"items":[
                {"id":"$employee","employeeNumber":"E01","status":"ACTIVE","version":0}],"nextCursor":null}}"""
                "$base/claims" -> {
                    lists.incrementAndGet()
                    assertEquals(employee, request.url.parameters["employmentId"])
                    assertEquals("20", request.url.parameters["limit"])
                    val selected =
                        request.url.parameters["from"] == "2026-10-01" &&
                            request.url.parameters["status"].let { it == null || it == "APPROVED" }
                    """{"items":[${if (selected) summary else ""}],"nextCursor":null}"""
                }
                "$base/claims/$ID" -> {
                    details.incrementAndGet()
                    claim
                }
                "$base/claims/$ID/record" -> {
                    details.incrementAndGet()
                    assertEquals("20", request.url.parameters["historyLimit"])
                    """{"claim":$claim,"submission":$submitted,"history":$currentHistory}"""
                }
                "$base/claims/$ID/history" -> {
                    historyReads.incrementAndGet()
                    if (request.url.parameters["after"] == null) currentHistory
                    else {
                        assertEquals("1", request.url.parameters["after"])
                        earlierHistory
                    }
                }
                "$base/submissions/$SUBMISSION" -> submitted

                "$base/claims/$ID/payment-progress" ->
                    """{"claimId":"$ID","version":0,"attempts":[]}"""
                else -> error("Unexpected expense fixture path")
            }
        return body to HttpStatusCode.OK
    }

    companion object {
        const val ID = "44444444-4444-4444-8444-444444444444"
        const val LINE = "55555555-5555-4555-8555-555555555556"
        const val CATEGORY = "66666666-6666-4666-8666-666666666667"
        const val RECEIPT = "77777777-7777-4777-8777-777777777778"
        const val APPROVAL = "88888888-8888-4888-8888-888888888889"
        const val SUBMISSION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val REVIEWER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
