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

# nginx.conf의 ALBAM_MATE_APP2_HOST 를 실제 환경변수 값으로 치환한다.
# Nginx 내장 변수($host, $http_upgrade 등)는 목록에서 제외해 그대로 유지한다.
# ALBAM_MATE_APP2_HOST 를 설정하지 않으면 App1 자신(127.0.0.1)을 App2로도 사용한다.
export ALBAM_MATE_APP2_HOST="${ALBAM_MATE_APP2_HOST:-127.0.0.1}"
envsubst '${ALBAM_MATE_APP2_HOST}' < /etc/nginx/nginx.conf > /tmp/nginx.conf.rendered
cp /tmp/nginx.conf.rendered /etc/nginx/nginx.conf

exec /docker-entrypoint.sh "$@"
