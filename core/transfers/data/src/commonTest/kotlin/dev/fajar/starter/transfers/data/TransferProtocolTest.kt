package dev.fajar.starter.transfers.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import dev.fajar.starter.transfers.data.api.TransferApi
import dev.fajar.starter.transfers.data.datasources.ApiTransferRemoteDataSource
import dev.fajar.starter.transfers.data.repositories.HttpTransferGatewayRepository
import dev.fajar.starter.transfers.domain.entities.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class TransferProtocolTest {
    private val transfer =
        Transfer(
            "id",
            TransferDirection.Download,
            TransferFile("file", "text/plain", 8),
            1,
            "file",
            "\"v1\"",
        )

    @Test
    fun validRangeCarriesSessionAndReadsOnlyTheRequestedBlock() = runTest {
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals("bytes=0-3", request.headers[HttpHeaders.Range])
                    assertEquals("\"v1\"", request.headers[HttpHeaders.IfMatch])
                    assertEquals("one", request.attributes[RequestSession])
                    respond(
                        byteArrayOf(1, 2, 3, 4),
                        HttpStatusCode.PartialContent,
                        headersOf(
                            HttpHeaders.ETag to listOf("\"v1\""),
                            HttpHeaders.ContentRange to listOf("bytes 0-3/8"),
                        ),
                    )
                },
                "https://api.example/",
            )
        try {
            val result =
                HttpTransferGatewayRepository(ApiTransferRemoteDataSource(TransferApi(client)))
                    .download("one", transfer, 4)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), (result as AppResult.Success).value)
        } finally {
            client.close()
        }
    }

    @Test
    fun changedVersionIncorrectRangeTruncationAndOversizeAreRejected() = runTest {
        for ((version, range, length) in
            listOf(
                Triple("\"v2\"", "bytes 0-3/8", 4),
                Triple("\"v1\"", "bytes 4-7/8", 4),
                Triple("\"v1\"", "bytes 0-3/8", 3),
                Triple("\"v1\"", "bytes 0-3/8", 5),
            )) {
            val client =
                createHttpClient(
                    MockEngine {
                        respond(
                            ByteArray(length),
                            HttpStatusCode.PartialContent,
                            headersOf(
                                HttpHeaders.ETag to listOf(version),
                                HttpHeaders.ContentRange to listOf(range),
                            ),
                        )
                    },
                    "https://api.example/",
                )
            try {
                assertIs<AppResult.Failed>(
                    HttpTransferGatewayRepository(ApiTransferRemoteDataSource(TransferApi(client)))
                        .download("one", transfer, 4)
                )
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun redirectIsNotFollowedAndBodyIsNotExposedAsFailureText() = runTest {
        var requests = 0
        val client =
            createHttpClient(
                MockEngine {
                    requests++
                    respond(
                        "private body",
                        HttpStatusCode.TemporaryRedirect,
                        headersOf(HttpHeaders.Location, "https://other.example/private"),
                    )
                },
                "https://api.example/",
            )
        try {
            val result =
                HttpTransferGatewayRepository(ApiTransferRemoteDataSource(TransferApi(client)))
                    .download("one", transfer, 4)
            assertIs<AppResult.Failed>(result)
            assertFalse(result.failure.message.contains("private"))
            assertEquals(1, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectedStreamRetainsHttpFailureWithoutReadingItsBody() = runTest {
        val client =
            createHttpClient(
                MockEngine { respond("not a validation document", HttpStatusCode.BadRequest) },
                "https://api.example/",
            )
        try {
            val result =
                HttpTransferGatewayRepository(ApiTransferRemoteDataSource(TransferApi(client)))
                    .download("one", transfer, 4)
            assertEquals(FailureKind.Validation, (result as AppResult.Failed).failure.kind)
        } finally {
            client.close()
        }
    }

    @Test
    fun pendingRequestCancellationStaysCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val client =
            createHttpClient(
                MockEngine {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                },
                "https://api.example/",
            )
        try {
            val job = launch {
                HttpTransferGatewayRepository(ApiTransferRemoteDataSource(TransferApi(client)))
                    .download("one", transfer, 4)
            }
            started.await()
            job.cancelAndJoin()
            cancelled.await()
            assertTrue(job.isCancelled)
        } finally {
            client.close()
        }
    }
}
