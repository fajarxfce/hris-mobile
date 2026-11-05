# Chat header and message selection

The conversation header contains the name and Back action. Connection state never
appears below the name, and the normal header has no select/checkmark button.
Transport state still owns synchronization and recovery; it is not presented as
contact presence. A required reconnect action remains beside the conversation.

Long-press selects the first bubble. Tap additional bubbles while selecting, then
use the contextual count/copy/delete bar. Tapping a bubble normally does not enter
selection. Removing the last selection or pressing Back restores the name header.
Bubble accessibility actions retain an equivalent way to select. The obsolete
empty-selection event and handler are removed.

This is a focused UI correction over the original-store authentication recovery.
Local history, automatic reconnect, draft ownership and deletion policy are unchanged.
The user confirmed immediate Bima history on reopening release31. A physical-phone
internet-off test has not been performed. Closed release31 diagnostics still show
Generic timeline/sync failures; their actual cause remains unattributed.

Validation: focused 18 fresh cases / 89.943s; root check and Android compile 812 fresh cases / 286.239s. Zero failures; 4,050 source inputs unchanged during both gates. Reviewed compact, landscape, 200% dark text, offline and reconnect screenshots. [Measurements](measurements/chat-header-2026-11-05.json).
