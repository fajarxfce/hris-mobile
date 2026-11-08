# Delivery wakes and passive timeline updates

A failed SDK send changes its local echo. Retrying changes the echo back to pending.
Previously ChatThreadViewModel treated every such timeline change as a new delivery
request whenever any durable message remained. ForegroundSyncWorker accepts explicit
requests before its exponential-backoff timer, so this feedback could continually
wake a failing sender. The regression fixture reproduces that scheduling behavior;
it does not establish the entire request count or latency observed on the phone.

The route now captures one recovery wake when it opens a timeline with retained
commands. Subsequent passive timeline changes acquire history and update the local
projection without scheduling another send. QueueCollaborationText still schedules
newly stored commands; explicit retry still requests delivery. Reopening the chat
can wake its retained work once. No new retry timer or delivery policy moves into
the ViewModel, and no message, transaction ID, key, receipt or acknowledgement is
rewritten. The application worker continues to own retries and admission.

The regression covers four failed/pending echo changes, preservation of the durable
original, explicit retry, and reentry. Existing queue/send tests continue to cover
new messages. Server support for the exact own-account secret-storage default-key
read is a separate compatibility correction in HRIS Platform. Device request counts
and latency must be measured after both corrections before claiming resolution of
the user's full pending-message report.
