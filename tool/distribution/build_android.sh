#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
case "$RELEASE_ENVIRONMENT" in
    dev) variant=Dev ;;
    staging) variant=Staging ;;
    prod) variant=Prod ;;
    *) echo 'Invalid release environment.' >&2; exit 1 ;;
esac
set -- ":apps:android:assemble${variant}Release" ":apps:android:bundle${variant}Release" \
    :apps:android:cyclonedxDirectBom "-PsbomConfiguration=${RELEASE_ENVIRONMENT}ReleaseRuntimeClasspath" \
    -PrequireSigning=true "-PappEnvironment=$RELEASE_ENVIRONMENT" "-Pbackend=$RELEASE_BACKEND" \
    "-PappVersion=$RELEASE_VERSION" "-PappVersionCode=$RELEASE_BUILD"
if [ "$RELEASE_BACKEND" = remote ]; then
    # The selected environment must override an existing per-flavor project property.
    set -- "$@" "-PapiBaseUrl=${API_BASE_URL:?Remote API URL is required}" \
        "-PapiBaseUrl.$RELEASE_ENVIRONMENT=$API_BASE_URL"
fi
# Run the repository's test configuration before compiling the selected release.
# In particular, a starter's demo UI fixture must never contact the release API.
xvfb-run -a ./gradlew :check
./gradlew "$@"
