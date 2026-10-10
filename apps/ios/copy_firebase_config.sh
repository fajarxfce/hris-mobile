#!/bin/sh
set -eu
config="$SRCROOT/Configuration/$FLUENT_ENVIRONMENT/GoogleService-Info.plist"
destination="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/GoogleService-Info.plist"
if [ ! -f "$config" ]; then
    rm -f "$destination"
    exit 0
fi
bundle_id=$(/usr/libexec/PlistBuddy -c 'Print :BUNDLE_ID' "$config")
if [ "$bundle_id" != "$PRODUCT_BUNDLE_IDENTIFIER" ]; then
    echo 'error: Firebase bundle ID does not match the selected environment.' >&2
    exit 1
fi
cp "$config" "$destination"
