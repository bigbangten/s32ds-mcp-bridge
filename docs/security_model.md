# Security Model

## 기본 원칙

1. Bridge는 `127.0.0.1` 에만 bind 한다.
2. 모든 endpoint는 bearer token을 요구한다.
3. 토큰은 workspace metadata 아래 파일로 저장한다.
4. Phase 1 endpoint는 읽기 전용이다.
5. CORS는 기본적으로 비활성이다.

## 토큰

- 경로: `<workspace>/.metadata/.plugins/com.example.s32ds.agent.bridge/token`
- 생성 방식: `SecureRandom` 32바이트를 Base64 URL-safe 문자열로 인코딩
- 우선순위:
  1. `S32DS_AGENT_TOKEN` 환경변수
  2. 기존 토큰 파일
  3. 새 랜덤 토큰 생성

## 파일 권한

Windows에서는 `AclFileAttributeView`를 사용해 현재 사용자만 읽고 쓸 수 있도록 ACL을 줄이려고 시도한다. ACL 강제는 파일시스템과 권한 모델에 따라 제한될 수 있으므로 실패 시 bridge 시작을 막지는 않고 로그만 남긴다.

## 네트워크 범위

- 허용: `http://127.0.0.1:<port>`
- 금지: `0.0.0.0`, 외부 NIC bind, 인증 없는 공개 HTTP

## 포트

- 기본 포트: `39231`
- 오버라이드: `S32DS_AGENT_PORT`

## Allowlist

Phase 1은 mutation을 노출하지 않지만, 향후 command 실행 단계에서 allowlist가 필요하다. 샘플 파일은 [samples/allowlist.example.json](/D:/workspace_ai/s32ds-mcp-agent/samples/allowlist.example.json)에 둔다.
