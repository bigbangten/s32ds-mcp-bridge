# Installation

This document is written for an AI agent to execute, but humans can follow it directly.

## Supported Setup

- Windows 10/11
- NXP S32 Design Studio 3.5.x
- Python 3.10+
- JDK 17+ and Maven 3.9+ only if building the Eclipse bundle from source

## Plugin Install

### Claude Code

```text
/plugin marketplace add bigbangten/s32ds-mcp-bridge
/plugin install s32
/s32:setup
```

### Codex

```bash
codex plugin marketplace add bigbangten/s32ds-mcp-bridge --ref v0.4.3
codex plugin add s32@s32ds-mcp-bridge
```

Start a new Codex thread after install so Codex reloads the `s32-menu-lookup` skill and the `s32ds` MCP server. Then ask Codex to run the S32DS setup flow, or perform the manual bridge install below.

## Manual Bridge Install

### 1. Get The Eclipse Bundle

Recommended: download the latest release artifact.

```bash
gh release download --repo bigbangten/s32ds-mcp-bridge \
  --pattern 'com.example.s32ds.agent.bridge_*.jar' \
  --dir /tmp/s32-install
JAR=$(ls /tmp/s32-install/com.example.s32ds.agent.bridge_*.jar | head -1)
```

Alternative: build from source.

```bash
export MAVEN_OPTS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0 -Djdk.xml.elementAttributeLimit=0 -Djdk.xml.maxOccurLimit=0 -Djdk.xml.maxXMLNameLimit=0"
mvn -f eclipse-bridge/releng/pom.xml clean verify
JAR=$(ls eclipse-bridge/releng/com.example.s32ds.agent.repository/target/repository/plugins/com.example.s32ds.agent.bridge_*.jar | head -1)
```

### 2. Close S32DS

```bash
tasklist //FI "IMAGENAME eq s32ds*" | grep -q s32ds.exe && taskkill //IM s32ds.exe //F
rm -f "$HOME/workspaceS32DS.3.5/.metadata/.lock"
```

### 3. Install The Bundle

```bash
S32DS_ROOT="C:/NXP/S32DS.3.5"
S32DS_ECLIPSE="$S32DS_ROOT/eclipse"
BI="$S32DS_ECLIPSE/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
VER=$(basename "$JAR" | sed 's/com.example.s32ds.agent.bridge_//; s/.jar$//')

rm -f "$S32DS_ECLIPSE/plugins/com.example.s32ds.agent.bridge_"*.jar
rm -f "$S32DS_ECLIPSE/dropins/com.example.s32ds.agent.bridge_"*.jar
mkdir -p "$S32DS_ECLIPSE/dropins"
cp "$JAR" "$S32DS_ECLIPSE/plugins/"
cp "$JAR" "$S32DS_ECLIPSE/dropins/"

grep -v "com\.example\.s32ds\.agent\.bridge" "$BI" > "$BI.new" && mv "$BI.new" "$BI"
echo "com.example.s32ds.agent.bridge,${VER},plugins/com.example.s32ds.agent.bridge_${VER}.jar,4,true" >> "$BI"
```

Important: keep `bundles.info` as UTF-8 without BOM. A BOM before `#encoding=UTF-8` can break Eclipse simpleconfigurator parsing.

### 4. Start S32DS Correctly

Use the S32DS install root as the working directory so NXP JavaFX Configuration Tools can resolve their relative module path.

```powershell
$workspace = "$env:USERPROFILE\workspaceS32DS.3.5"
$root = "C:\NXP\S32DS.3.5"
$exe = Join-Path $root "eclipse\s32ds.exe"
$vm = Join-Path $root "jre\bin\javaw.exe"
Start-Process -FilePath $exe -ArgumentList @("-vm",$vm,"-clean","-data",$workspace) -WorkingDirectory $root
```

### 5. Verify Bridge Health

The bridge writes discovery data to `%USERPROFILE%\.s32ds-mcp\bridge.json`.

```powershell
$d = Get-Content "$env:USERPROFILE\.s32ds-mcp\bridge.json" -Raw | ConvertFrom-Json
$headers = @{ Authorization = "Bearer $($d.token)" }
Invoke-RestMethod "$($d.url.TrimEnd('/'))/health" -Headers $headers
```

Expected: `ok: true`, `workbenchRunning: true`, and `bridgeVersion` matching the release line.

## Manual MCP Client Wiring

If you are not using the Claude Code or Codex plugin packaging:

```bash
pip install -e ./mcp-server
python -m s32ds_mcp_server.server
```

For a static Codex CLI config:

```toml
[mcp_servers.s32ds]
command = "python"
args = ["-m", "s32ds_mcp_server.server"]
startup_timeout_sec = 20
tool_timeout_sec = 120

[mcp_servers.s32ds.env]
S32DS_BRIDGE_URL = "http://127.0.0.1:39231"
```

The Python client prefers `%USERPROFILE%\.s32ds-mcp\bridge.json`, so it normally does not need a hard-coded token.

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---|---|---|
| Bridge health connection refused | Eclipse bundle did not load | Check the JAR in `plugins/`, the `bundles.info` entry, and relaunch S32DS with `-clean`. |
| HTTP 401 | Stale token | Re-read `%USERPROFILE%\.s32ds-mcp\bridge.json` after restarting S32DS. |
| `Dashboard UI refresh` or generated-files NPE with JavaFX class missing | S32DS was launched from the wrong working directory | Start S32DS with `-WorkingDirectory C:\NXP\S32DS.3.5` and the bundled `javaw.exe`. |
| Run-control says no suspended target while UI shows a frame | CDT/DSF context mismatch | Use the DSF-aware `/debug/status` and `/debug/location` tools before changing run-control logic. |
| Tycho build fails with XML entity limits | JDK 17+ XML hardening | Use the full `MAVEN_OPTS` line above. |

## Uninstall

```bash
taskkill //IM s32ds.exe //F 2>/dev/null
rm -f "$S32DS_ECLIPSE/plugins/com.example.s32ds.agent.bridge_"*.jar
rm -f "$S32DS_ECLIPSE/dropins/com.example.s32ds.agent.bridge_"*.jar
BI="$S32DS_ECLIPSE/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
grep -v "com\.example\.s32ds\.agent\.bridge" "$BI" > "$BI.new" && mv "$BI.new" "$BI"
```
