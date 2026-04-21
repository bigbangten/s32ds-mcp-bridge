# Phase 1 Architecture

## 목표

Phase 1은 S32DS 3.5.x 내부 Eclipse Workbench를 read-only로 탐색하는 bridge를 만든다. 화면 OCR이나 접근성 트리를 사용하지 않고, Eclipse 내부 registry와 workbench service를 통해 데이터를 수집한다.

## 구성 요소

1. Eclipse bridge bundle
   - `AgentEarlyStartup`: S32DS 시작 시 bridge 부팅
   - `BridgeServer`: `127.0.0.1` 전용 `HttpServer`
   - `Router`: 인증, 라우팅, JSON 응답 포맷
   - `TokenStore`: workspace metadata 아래 bearer token 유지
   - `UiThread`: 모든 Workbench/SWT 접근을 `syncExec`로 강제
   - indexers:
     - `CommandIndexer`
     - `ExtensionRegistryIndexer`
     - `MenuMaterializer`
     - `StateInspector`
     - `ViewIndexer`
     - `PerspectiveIndexer`
     - `WizardIndexer`

2. Python MCP server
   - `bridge_client.py`: `httpx` 기반 bridge wrapper
   - `server.py`: FastMCP resources/tools 등록
   - `tests/test_server.py`: bridge 모킹 기반 단위 테스트

## 요청 흐름

1. S32DS 실행
2. `AgentEarlyStartup`가 `BridgeServer.startAsync()` 호출
3. `BridgeServer`가 `TokenStore`에서 token 준비 후 `127.0.0.1:<port>`에 bind
4. 외부 Python MCP 서버가 bridge endpoint 호출
5. 라우터가 bearer token 검사
6. 인덱서가 UI thread에서 필요한 Workbench 정보를 읽음
7. bridge가 표준 JSON envelope로 반환

## API 경계

Phase 1에서 구현되는 endpoint:

- `GET /health`
- `GET /state`
- `GET /commands`
- `GET /commands/search?q=`
- `GET /registry/menus`
- `GET /registry/legacy-actions`
- `GET /views`
- `GET /perspectives`
- `GET /wizards?type=`
- `POST /visible-menu`

Phase 1은 mutation endpoint를 열지 않는다.

## 데이터 수집 전략

### Registry 후보

아직 UI에 나타나지 않은 contribution도 확인하기 위해 `IExtensionRegistry`를 읽는다.

- 메뉴: `org.eclipse.ui.menus`
- legacy actions: `org.eclipse.ui.actionSets`, `org.eclipse.ui.popupMenus`
- views: `org.eclipse.ui.views`
- perspectives: `org.eclipse.ui.perspectives`
- wizards: `org.eclipse.ui.newWizards`, `org.eclipse.ui.importWizards`, `org.eclipse.ui.exportWizards`

### 현재 context materialization

현재 perspective, selection, active editor 기준으로 실제 보이는 메뉴를 확인하기 위해 `IMenuService.populateContributionManager()`를 사용한다.

## JSON 응답 형식

모든 응답은 아래 envelope를 사용한다.

```json
{
  "ok": true,
  "data": {},
  "warnings": [],
  "error": null
}
```

오류 시 `ok=false`, `data=null`, `error.code`, `error.message`, `error.details`를 채운다.

## 비목표

- command 실행
- view 열기
- perspective 전환
- build/marker 수집
- wizard fallback
- S32DS/NXP 특화 inventory 분류

이 항목들은 Phase 2 이후 범위다.
