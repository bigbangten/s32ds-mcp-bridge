# Install S32DS Bridge

## 1. 빌드

```powershell
cd D:\workspace_ai\s32ds-mcp-agent
mvn -f eclipse-bridge/releng/pom.xml verify
```

성공 시 bundle 산출물은 일반적으로 `eclipse-bridge/bundles/com.example.s32ds.agent.bridge/target/` 아래에 생성된다.

## 2. Dropins 설치

1. S32DS를 종료한다.
2. 생성된 bundle JAR을 `C:\NXP\S32DS.3.5\eclipse\dropins\` 로 복사한다.
3. S32DS를 다시 실행한다.

## 3. 초기 기동 확인

S32DS가 workbench까지 완전히 뜨면 bridge가 자동 시작된다.

토큰 파일 확인:

```text
C:\Users\<user>\workspaceS32DS.3.5\.metadata\.plugins\com.example.s32ds.agent.bridge\token
```

포트 기본값:

```text
39231
```

변경하려면 S32DS 실행 전에 환경변수 설정:

```powershell
$env:S32DS_AGENT_PORT="39241"
```

## 4. HTTP 확인

```powershell
$token = (Get-Content "C:\Users\<user>\workspaceS32DS.3.5\.metadata\.plugins\com.example.s32ds.agent.bridge\token" -Raw).Trim()
$headers = @{ Authorization = "Bearer $token" }
Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:39231/health" -Headers $headers
```

## 5. Update Site 방식

현재 releng 구조는 feature/repository packaging을 포함하지만, README 기준 운영 절차는 dropins를 우선한다. p2 update site packaging은 후속 검증이 더 필요하며, 관련 불확실성은 [docs/OPEN_QUESTIONS.md](/D:/workspace_ai/s32ds-mcp-agent/docs/OPEN_QUESTIONS.md)에 기록했다.
