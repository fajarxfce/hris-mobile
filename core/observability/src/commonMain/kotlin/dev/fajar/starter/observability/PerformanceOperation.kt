package dev.fajar.starter.observability

/** Bounded names avoid cardinality growth and prevent credentials entering trace labels. */
enum class PerformanceOperation {
    AppBootstrap,
    Sync,
    HttpRequest,
    FileUpload,
    FileDownload,
    DraftPersistence,
    CommandPreparation,
    CommandQueue,
    CommandConfirmation,
}

enum class PerformanceOutcome {
    Succeeded,
    Failed,
    Cancelled,
}
