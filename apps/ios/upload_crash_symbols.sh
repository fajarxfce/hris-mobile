#!/bin/sh
set -eu
# Config-free CI and simulator builds do not upload symbols.
case "$PLATFORM_NAME" in *simulator*) exit 0 ;; esac
plist="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/GoogleService-Info.plist"
[ -f "$plist" ] || exit 0
[ "$CONFIGURATION" != Debug ] || exit 0
case "$CONFIGURATION" in *Debug*) exit 0 ;; esac
"${BUILD_DIR%/Build/*}/SourcePackages/checkouts/firebase-ios-sdk/Crashlytics/upload-symbols" \
    -gsp "$plist" -p ios "$DWARF_DSYM_FOLDER_PATH/$DWARF_DSYM_FILE_NAME"
