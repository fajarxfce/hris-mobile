#!/bin/sh
set -eu
cd "$SRCROOT/../.."
./gradlew :core:common:generateAppLinks --no-daemon
IFS= read -r expected_bundle < "core/common/build/generated/app-links/ios/$HRIS_ENVIRONMENT.bundle-id"
if [ "$expected_bundle" != "$PRODUCT_BUNDLE_IDENTIFIER" ]; then
    echo 'error: App-link configuration does not match the selected iOS bundle.' >&2
    exit 1
fi
