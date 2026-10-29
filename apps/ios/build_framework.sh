#!/bin/sh
set -eu
cd "$SRCROOT/../.."
environment=$1
case "$environment" in dev|staging|prod) ;; *) exit 1 ;; esac
set -- "-PappEnvironment=$environment" "-PappVersion=$MARKETING_VERSION" \
    "-PappVersionCode=$CURRENT_PROJECT_VERSION"
if [ -n "${DISTRIBUTION_BACKEND:-}" ]; then
    set -- "$@" "-Pbackend=$DISTRIBUTION_BACKEND"
    if [ "$DISTRIBUTION_BACKEND" = remote ]; then
        set -- "$@" "-PapiBaseUrl=${API_BASE_URL:?Remote API URL is required}" \
            "-PapiBaseUrl.$environment=$API_BASE_URL"
    fi
fi
./gradlew "$@" :apps:shared:embedAndSignAppleFrameworkForXcode
