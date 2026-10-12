# File output

`core/fileio` exposes raw `FileOutputSource` operations: select a destination,
write bounded sequential bytes, complete the declared extent, and release the
request. Repositories map these technical operations through `safeFileWriteCall`.
Use cases own admission, picker/transfer deadlines and the lifetime of the copy.
No SDK handle enters domain or presentation.

The caller allocates a unique request ID before selection and releases it in
`finally`, including cancellation. The shared handle registry bounds retained
resources, rejects concurrent operations on one handle and prevents late cleanup
from changing a replacement registration. Inputs and outputs use the same
ownership primitive. Neutral file exception types retain platform causes for
internal diagnostics while read/write failures have distinct localizable codes.

Writes borrow at most 1 MiB per call and must start at the current output offset.
Zero-progress native writes fail. Completion verifies the declared byte count
before flushing/committing and closing the output. An interrupted output is
released; resuming an HRIS download remains a separate encrypted-storage operation.
Release does not delete a user-selected path. Native cancellation or a failed copy
can leave an empty or partial file at that location.

## Platform ownership

- Android uses Storage Access Framework and the Activity's result registry. Its
  weak Activity reference belongs to the host; selected descriptors belong to the
  output request. A late provider result is registered or closed before delivery.
- iOS owns one UIKit delegate per picker. A temporary, empty placeholder selects
  the destination; its app-owned directory is cleaned after the dialog. Subsequent
  writes hold balanced security-scoped access and a native handle. Closing during
  a write defers descriptor disposal until that operation exits.
- Desktop uses a native save dialog and `CREATE_NEW`. An existing path is retained
  and reported through `client.file_exists`; the user can select another name.
- Web uses the File System Access save picker in a secure context. Each write is
  bounded, and the writable stream commits at completion. Unsupported browsers or
  contexts return an explicit unavailable failure. Browser native pickers cannot
  be dismissed programmatically; cancelled requests discard late selections and
  abort late writable streams.

Platform entry points attach native owners and the isolated DI container closes
all registered sources. A host without a file UI uses `UnavailableFileOutputSource`.
SDK adapters do not request application permission, select another account, retry
operations or decide when a saved document may be disclosed.
