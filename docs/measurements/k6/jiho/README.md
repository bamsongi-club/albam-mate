# Jiho k6 측정 문서

인증·알림 용량 측정, Redis 세션 연결 진단과 OPS-02 지연·포화 관측 결과를 관리한다. 공통 보존 규칙은 [상위 README](../README.md)를 따른다.

| Campaign ID | 상태 | 보고서 | 판단서·근거 | 대체 관계 |
| --- | --- | --- | --- | --- |
| `ops02-20260819-044303` | `completed-with-limitations` | [OPS-02 지연·포화 실측](ops02-latency-saturation-2026-08-19.md) | [비식별 evidence](evidence/ops02-latency-saturation-2026-08-19.json) · App PR #799 · Infra PR #23~#33 | 첫 유효 OPS-02 baseline·주입·복구 Run, post-run alarm 보정은 재배포하지 않음 |
| `auth-noti-662-20260813h-auth` / `a662-h-notify-v4` | `completed-with-limitations` | [인증·알림 병목 진단 캠페인](auth-notification-bottleneck-campaign-2026-08-13.md) | App #662·PR #663, Infra PR #11 | 2026-08-11 결과를 대체하지 않는 내부 병목 진단 |
| `auth-notification-20260811T021040KST` | `completed-with-limitations` | [인증·알림 AWS 용량 측정](auth-notification-capacity-2026-08-11.md) | [알림 broker 판단](notification-broker-decision-2026-08-11.md) · [manifest](evidence/auth-notification-capacity-2026-08-11.json) | 후속 측정이 기존 결론을 대체하지 않음 |
| `tomcat64-20260811` | `INVALID` | [Tomcat 64 알림 혼합 부하 재측정](notification-tomcat64-capacity-2026-08-11.md) | [evidence](evidence/notification-tomcat64-capacity-2026-08-11.json) | 앞선 캠페인의 후속, 대체하지 않음 |
| `t4gsmall-20260812` | `INVALID` | [t4g.small 알림 혼합 부하 단일 측정](notification-t4gsmall-capacity-2026-08-12.md) | [evidence](evidence/notification-t4gsmall-capacity-2026-08-12.json) | OOM 제거만 확인, 용량 경계를 대체하지 않음 |
| `redis-session-diagnostic-20260812` | `completed` | [Redis 세션 연결 진단·A/B](redis-session-connection-diagnostic-2026-08-12.md) | [evidence](evidence/redis-session-connection-diagnostic-2026-08-12.json) | Issue #607 후보 A/B와 mixed 0.5× 검증 완료 |
