#!/bin/sh
set -eu

release="${ALBAM_MATE_RELEASE:-}"
case "$release" in
    ''|*[!0-9a-f]*)
        echo 'ALBAM_MATE_RELEASE must be a 40-character lowercase Git SHA' >&2
        exit 1
        ;;
esac
if [ "${#release}" -ne 40 ]; then
    echo 'ALBAM_MATE_RELEASE must be a 40-character lowercase Git SHA' >&2
    exit 1
fi

if [ "${1:-}" = '--validate-release-only' ]; then
    exit 0
fi

exec /docker-entrypoint.sh "$@"
