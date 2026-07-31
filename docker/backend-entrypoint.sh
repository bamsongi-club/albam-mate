#!/bin/sh
set -eu

validate_release() {
    release="${ALBAM_MATE_RELEASE:-}"
    case "$release" in
        ''|*[!0-9a-f]*)
            echo 'ALBAM_MATE_RELEASE must be a 40-character lowercase Git SHA' >&2
            return 1
            ;;
    esac
    if [ "${#release}" -ne 40 ]; then
        echo 'ALBAM_MATE_RELEASE must be a 40-character lowercase Git SHA' >&2
        return 1
    fi
}

if [ "${SPRING_PROFILES_ACTIVE:-}" = 'production' ] || [ -n "${ALBAM_MATE_RELEASE:-}" ]; then
    validate_release
fi

if [ "${1:-}" = '--validate-release-only' ]; then
    exit 0
fi

exec "$@"
