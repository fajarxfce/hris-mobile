package dev.fajar.starter.fixtures

import dev.fajar.starter.fileio.Sha256Digest
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.serialization.json.*

/** Wire fixture for employee payroll pages, independent of their domain/data implementation. */
class NativePayslipUi {
    @Volatile var enabled = false
    val lists = AtomicInteger()
    val bootstraps = AtomicInteger()
    val changes = AtomicInteger()
    val details = AtomicInteger()
    val inspections = AtomicInteger()
    val downloads = AtomicInteger()
    val bytes = "%PDF-1.4\nFictional employee payslip\n%%EOF".encodeToByteArray()
    val sha256 = Sha256Digest().apply { update(bytes) }.finishHex()
    private val summary =
        Json.parseToJsonElement(
            """{
          "id":"$ID","employeeId":"${NativePeopleUi.EMPLOYEE}",
          "employeeNumber":"E01","employeeName":"Alex Employee","taxMonth":"2026-09",
          "plannedPaymentDate":"2026-09-28","currency":"IDR",
          "taxableGross":"10500000.0000","withheld":"157500.0000","takeHome":"10232500.0000",
          "publishedAt":"2026-09-28T01:00:00Z","version":0
        }"""
        )

    fun response(request: HttpRequestData, scope: MockRequestHandleScope): HttpResponseData? {
        val base = "/api/v1/companies/${NativeUiServer.COMPANY_ONE}/payroll/payslips"
        val path = request.url.encodedPath
        if (!enabled) return null
        val sync = "/api/v1/companies/${NativeUiServer.COMPANY_ONE}/sync/"
        if (path.startsWith(sync)) return syncResponse(request, scope, path.removePrefix(sync))
        if (!path.startsWith(base)) return null
        assertEquals("SELF", request.url.parameters["scope"])
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        if (path.endsWith("/pdf")) {
            assertEquals("$base/$ID/pdf", path)
            assertEquals("ID", request.url.parameters["language"])
            val headers =
                Headers.build {
                    append(HttpHeaders.ContentType, "application/pdf")
                    append(HttpHeaders.ETag, "\"$sha256\"")
                    append(HttpHeaders.ContentLength, bytes.size.toString())
                    if (request.method == HttpMethod.Get)
                        append(HttpHeaders.ContentRange, "bytes 0-${bytes.lastIndex}/${bytes.size}")
                }
            if (request.method == HttpMethod.Head) {
                inspections.incrementAndGet()
                return scope.respond(ByteArray(0), HttpStatusCode.OK, headers)
            }
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("bytes=0-${bytes.lastIndex}", request.headers[HttpHeaders.Range])
            assertEquals("\"$sha256\"", request.headers[HttpHeaders.IfRange])
            downloads.incrementAndGet()
            return scope.respond(bytes.copyOf(), HttpStatusCode.PartialContent, headers)
        }
        assertEquals(HttpMethod.Get, request.method)
        val body =
            when (path) {
                base -> {
                    lists.incrementAndGet()
                    assertEquals("20", request.url.parameters["limit"])
                    buildJsonObject {
                            putJsonArray("items") {
                                if (request.url.parameters["from"] == "2026-01") add(summary)
                            }
                            put("nextCursor", JsonNull)
                        }
                        .toString()
                }
                "$base/$ID" -> {
                    details.incrementAndGet()
                    """{
                  "summary":$summary,"companyCode":"ND","companyName":"PT Nusa Digital",
                  "calculation":{
                    "earnings":[{"kind":"BASIC","code":"BASIC","name":"Basic pay","amount":"10500000","taxable":true}],
                    "contributions":[{"program":"HEALTH","base":"10000000","employeeAmount":"100000","employerAmount":"400000"}],
                    "tax":{"ruleId":"pph21-ter-2026","taxableGross":"10500000","taxAllowance":"0","deductionAllowance":"0","withheld":"157500","takeHome":"10232500"},
                    "taxInput":{"retirementContributions":"100000","qualifiedDonations":"0","otherNetDeductions":"10000","nonCashTaxable":"0"},
                    "employeeDeductions":"110000"
                  }
                }"""
                }
                "$base/$ID/payments" -> """{"assessmentId":"$ID","version":0,"attempts":[]}"""
                else -> error("Unexpected payslip fixture path")
            }
        return scope.respond(body, HttpStatusCode.OK, jsonHeaders)
    }

    private fun syncResponse(
        request: HttpRequestData,
        scope: MockRequestHandleScope,
        action: String,
    ): HttpResponseData? {
        if (
            request.url.parameters["collections"]?.split(',')?.toSet() !=
                setOf("PAYSLIPS", "PAYROLL_PAYMENTS")
        )
            return null
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("8", request.url.parameters["limit"])
        val body =
            when (action) {
                "bootstrap" -> {
                    bootstraps.incrementAndGet()
                    buildJsonObject {
                        putJsonArray("collections") {
                            add("PAYSLIPS")
                            add("PAYROLL_PAYMENTS")
                        }
                        putJsonArray("items") {
                            add(
                                buildJsonObject {
                                    put("collection", "PAYSLIPS")
                                    put("id", ID)
                                    put("version", 0)
                                }
                            )
                        }
                        put("nextCursor", JsonNull)
                        put("changesCursor", "payroll/head")
                        put("serverTime", Clock.System.now().toString())
                    }
                }
                "changes" -> {
                    changes.incrementAndGet()
                    buildJsonObject {
                        putJsonArray("items") {}
                        put("cursor", "payroll/head")
                        put("hasMore", false)
                        put("pendingPublication", false)
                        put("pollAfterSeconds", 5)
                        put("serverTime", Clock.System.now().toString())
                    }
                }
                else -> error("Unexpected payroll sync fixture action")
            }
        return scope.respond(
            body.toString(),
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    companion object {
        const val ID = "44444444-4444-4444-8444-444444444444"
    }
}
