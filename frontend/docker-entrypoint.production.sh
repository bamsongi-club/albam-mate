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

app2_host="${ALBAM_MATE_APP2_HOST:-}"
if [ -z "$app2_host" ]; then
    echo 'ALBAM_MATE_APP2_HOST must be set' >&2
    exit 1
fi
case "$app2_host" in
    *:*)
        echo 'ALBAM_MATE_APP2_HOST must not include a port' >&2
        exit 1
        ;;
esac

if [ "${1:-}" = '--validate-release-only' ]; then
    exit 0
fi

# nginx.conf의 ALBAM_MATE_APP2_HOST 를 실제 환경변수 값으로 치환한다.
# Nginx 내장 변수($host, $http_upgrade 등)는 목록에서 제외해 그대로 유지한다.
envsubst '${ALBAM_MATE_APP2_HOST}' < /etc/nginx/nginx.conf > /tmp/nginx.conf.rendered

exec /docker-entrypoint.sh "$@" -c /tmp/nginx.conf.rendered
