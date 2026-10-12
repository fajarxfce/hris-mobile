# Document integration

`core/documents` owns shared HRIS document contracts and the authenticated API
adapter. Leave and expense workflows can use these contracts without depending
on each other's feature modules. This is HRIS business integration and does not
belong in the generic starter.

`LoadDocumentEvidence` coordinates current identity, workspace ownership and fresh
company policy before acquiring the account's own published revisions. It repeats
scope and availability checks after pending work, propagates cancellation and has
a finite request deadline. Ordinary policy expiry allows fresh acquisition; known
access denial or maintenance remains gated. The returned read deadline includes
company policy expiry, scheduled maintenance, workspace admission and session
lifetime.

The datasource delegates raw I/O to `DocumentsApi`. The repository maps the bounded
response inside `safeApiCall`, checking the echoed account/company/employment and
classification, accepted file declarations, unique ordered IDs and cursor progress.
An unvalidated replacement is excluded by the server's published-revision catalog.
Metadata neither grants content access nor reserves a later business attachment.

The Leave feature consumes this catalog through its own evidence picker. It owns
selection, pagination and dialog lifetime; the shared document modules contain no
Leave ViewModels or attachment policy. Only accepted personal revisions for the
form's employment are selectable. A replaced or expired dialog cannot publish
into a later opening, and pending metadata work is cancelled on dismissal.

The catalog is an online selection capability. It does not persist file contents,
silently retry or start a background observer. The inherited generic transfer
screen is not an HRIS upload implementation; encrypted resumable file acquisition
and transfer remain separate integration work.
