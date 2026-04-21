# Installation — step by step

This doc is **written for an AI agent to execute autonomously**, but humans can follow it too. Every step has a concrete command and a verification. Don't skip the verifications.

## Scope

- **OS**: Windows 10/11 (Linux/macOS untested).
- **Target**: NXP S32 Design Studio 3.5.x (Eclipse 4.20, June 2021).
- **For Claude Code users**: prefer running `/s32:setup` — that's the automated version of this file.

## Prerequisites

| Requirement | Required for | Check |
|---|---|---|
| S32DS 3.5.x installed | everything | `ls "C:\NXP\S32DS.3.5\eclipse\s32ds.exe"` |
| Python 3.10+ | MCP server | `python --version` (must be ≥3.10) |
| JDK 17+ | building from source only | `java -version` |
| Maven 3.9+ | building from source only | `mvn -v` |

### Install missing Python (Windows)

```bash
winget install Python.Python.3.11
```

### Install missing JDK (Windows)

```bash
winget install EclipseAdoptium.Temurin.21.JDK
```

### Install missing Maven (Windows)

Maven isn't on winget. Download manually:

```bash
mkdir -p "$HOME/maven"
curl -sL -o "$HOME/maven/maven.zip" https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip
python -c "import zipfile; zipfile.ZipFile(r'$HOME/maven/maven.zip').extractall(r'$HOME/maven')"
export PATH="$HOME/maven/apache-maven-3.9.9/bin:$PATH"
mvn --version
```

## 1. Obtain the plug-in JAR

### Option 1a — Download a prebuilt release (recommended)

```bash
gh release download --repo bigbangten/s32ds-mcp-bridge \
  --pattern 'com.example.s32ds.agent.bridge_*.jar' \
  --dir /tmp/s32-install
JAR=$(ls /tmp/s32-install/com.example.s32ds.agent.bridge_*.jar | head -1)
```

### Option 1b — Build from source

```bash
export JAVA_HOME=$(cygpath -u "$(dirname "$(dirname "$(where java | head -1)")")")  # or set manually
export MAVEN_OPTS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0 -Djdk.xml.elementAttributeLimit=0 -Djdk.xml.maxOccurLimit=0 -Djdk.xml.maxXMLNameLimit=0"
mvn -f eclipse-bridge/releng/pom.xml clean verify
JAR=$(ls eclipse-bridge/releng/com.example.s32ds.agent.repository/target/repository/plugins/com.example.s32ds.agent.bridge_*.jar | head -1)
```

**Why all those `MAVEN_OPTS`?** JDK 17+ caps XML entity sizes at 100,000 chars by default; Eclipse 2021-06's p2 metadata exceeds that. Disabling the caps is safe for local builds.

Extract the version qualifier:

```bash
VER=$(basename "$JAR" | sed 's/com.example.s32ds.agent.bridge_//; s/.jar$//')
echo "Building version: $VER"
```

## 2. Close S32DS

```bash
tasklist //FI "IMAGENAME eq s32ds*" | grep -q s32ds.exe && taskkill //IM s32ds.exe //F
# Remove stale workspace lock if any
rm -f "$HOME/workspaceS32DS.3.5/.metadata/.lock"
```

**Verify**: `tasklist //FI "IMAGENAME eq s32ds*"` shows no running s32ds.exe.

## 3. Install the bundle

```bash
S32DS_ECLIPSE="C:/NXP/S32DS.3.5/eclipse"   # adjust if different
BI="$S32DS_ECLIPSE/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"

# Remove previous version (if any)
rm -f "$S32DS_ECLIPSE/plugins/com.example.s32ds.agent.bridge_"*.jar
rm -f "$S32DS_ECLIPSE/dropins/com.example.s32ds.agent.bridge_"*.jar

# Copy the new JAR to both plugins/ AND dropins/ (belt-and-braces)
mkdir -p "$S32DS_ECLIPSE/dropins"
cp "$JAR" "$S32DS_ECLIPSE/plugins/"
cp "$JAR" "$S32DS_ECLIPSE/dropins/"

# Strip any previous entry from bundles.info
grep -v "com\.example\.s32ds\.agent\.bridge" "$BI" > "$BI.new" && mv "$BI.new" "$BI"

# Append fresh entry — version MUST match the JAR filename qualifier exactly
echo "com.example.s32ds.agent.bridge,${VER},plugins/com.example.s32ds.agent.bridge_${VER}.jar,4,true" >> "$BI"
```

**Verify**:

```bash
tail -1 "$BI"
# Expect: com.example.s32ds.agent.bridge,<VER>,plugins/com.example.s32ds.agent.bridge_<VER>.jar,4,true
ls "$S32DS_ECLIPSE/plugins/com.example.s32ds.agent.bridge_"*.jar
```

## 4. Start S32DS with `-clean`

`-clean` is required the first time after installing a new bundle so OSGi rescans. Subsequent launches don't need it.

```bash
powershell -NoProfile -Command "Start-Process -FilePath '$S32DS_ECLIPSE/s32ds.exe' -ArgumentList '-clean' -WorkingDirectory '$S32DS_ECLIPSE'"
```

## 5. Wait for bridge to come up

```bash
TOKEN_PATH="$HOME/workspaceS32DS.3.5/.metadata/.plugins/com.example.s32ds.agent.bridge/token"
echo -n "Waiting for bridge..."
for i in $(seq 1 60); do
  [ -f "$TOKEN_PATH" ] && { echo " ready ($((i*2))s)"; break; }
  sleep 2; echo -n .
done
[ -f "$TOKEN_PATH" ] || { echo; echo "TIMEOUT"; exit 1; }
```

## 6. Fix ACL (one-time, per workspace)

The plug-in's `TokenStore` locks the token file to owner-only, but Windows + bash combinations fail even for the owner. Re-grant read:

```bash
icacls "$(cygpath -w "$TOKEN_PATH")" //grant "$USER:R"
```

**Verify**:

```bash
TOKEN=$(cat "$TOKEN_PATH" | tr -d '\r\n')
[ ${#TOKEN} -gt 20 ] && echo "Token read OK (${#TOKEN} chars)" || { echo "FAIL"; exit 1; }
```

## 7. Verify the bridge responds

```bash
curl -fs -H "Authorization: Bearer $TOKEN" http://127.0.0.1:39231/health | python -m json.tool
```

**Expected output includes**:
- `"ok": true`
- `"workbenchRunning": true`
- `"s32dsProduct": "S32 Design Studio for S32 Platform"`

If you see `ok: false` or connection refused, consult **Troubleshooting** below.

## 8. Install the Python MCP server

```bash
pip install -e ./mcp-server
# Verify
python -c "import s32ds_mcp_server; print('MCP server importable')"
```

## 9. Wire your AI client

### Claude Code (automatic via plugin)

The plugin's `plugin.json` already declares the MCP server. Just reload:

```
/plugin install s32ds-mcp    # or /plugin reload if already installed
```

Confirm with `/s32:status`.

### Codex CLI

Add to `~/.codex/config.toml`:

```toml
[mcp_servers.s32ds]
command = "python"   # or full path to 3.10+ Python
args = ["-m", "s32ds_mcp_server.server"]
startup_timeout_sec = 20
tool_timeout_sec = 120

[mcp_servers.s32ds.env]
S32DS_BRIDGE_URL = "http://127.0.0.1:39231"
S32DS_BRIDGE_TOKEN = "<paste_token_from_step_6>"
```

Test: `codex exec "use the s32ds tool to call fetch_health"`.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Step 5 times out | Plugin not loading | Look at `$HOME/workspaceS32DS.3.5/.metadata/.log` for any line mentioning `com.example.s32ds.agent.bridge`. If silent, check `bundles.info` version matches JAR filename qualifier exactly. |
| Step 7 returns HTTP 401 | Wrong token | Make sure you re-read the token after ACL fix. If test still fails, delete token file and restart S32DS — it'll regenerate. |
| Step 7 returns connection refused | Bundle not started | Verify the JAR exists in `plugins/` AND an entry in `bundles.info`. If both present, relaunch S32DS with `-clean -console` to see OSGi startup messages. |
| `taskkill` says "not found" | Already closed | Fine, continue. |
| Tycho build fails with "class file version 61.0" | JDK too old | Tycho 4.x needs JDK 17+. Install Temurin 21: `winget install EclipseAdoptium.Temurin.21.JDK`. |
| Tycho build fails with "maxGeneralEntitySizeLimit" | JDK 17+ XML hardening | Export all the `MAVEN_OPTS` listed in step 1b. |

## Uninstall

```bash
taskkill //IM s32ds.exe //F 2>/dev/null
rm -f "$S32DS_ECLIPSE/plugins/com.example.s32ds.agent.bridge_"*.jar
rm -f "$S32DS_ECLIPSE/dropins/com.example.s32ds.agent.bridge_"*.jar
BI="$S32DS_ECLIPSE/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
grep -v "com\.example\.s32ds\.agent\.bridge" "$BI" > "$BI.new" && mv "$BI.new" "$BI"
# Start S32DS normally next time (no -clean required)
```
