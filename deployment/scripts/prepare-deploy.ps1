# Builds the backend jar and prepares a deployment folder outside the repo (it contains secrets).
# Usage (from the repo root):  powershell -File deployment\scripts\prepare-deploy.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$out = Join-Path $env:TEMP 'finance-deploy'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null

$mvn = "$env:LOCALAPPDATA\Apache\apache-maven-3.9.16\bin\mvn.cmd"
if (-not (Test-Path $mvn)) { $mvn = 'mvn' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.3' }
Push-Location "$root\backend"
try { & $mvn -B -q -DskipTests package; if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' } } finally { Pop-Location }
Copy-Item (Get-ChildItem "$root\backend\target\finance-aggregator-backend-*.jar" | Where-Object { $_.Name -notmatch 'plain' } | Select-Object -First 1).FullName "$out\app.jar"

# Server env file: same values as the local .env, with server paths. The encryption key must stay identical.
$keep = 'DB_HOST','DB_PORT','DB_NAME','DB_USER','DB_PASSWORD','DB_TRUSTSTORE_PASSWORD','DEVICE_PAIRING_CODE','PAYLOAD_ENCRYPTION_KEY'
$lines = foreach ($line in Get-Content "$root\.env") {
    if ($line -match '^([A-Z_]+)=(.*)$' -and $keep -contains $Matches[1]) { $line }
}
$lines += 'DB_TRUSTSTORE_PATH=/etc/finance-aggregator/secrets/aiven-ca.p12'
Set-Content "$out\env" $lines -Encoding ascii
Copy-Item "$root\secrets\aiven-ca.p12" "$out\aiven-ca.p12"
Copy-Item "$root\deployment\oracle-cloud\*" $out -Include 'Caddyfile.template','finance-backend.service','setup-server.sh'
# Linux needs LF line endings; a CR at the end of a systemd value breaks the unit.
foreach ($f in 'Caddyfile.template','finance-backend.service','setup-server.sh','env') {
    $text = [IO.File]::ReadAllText("$out\$f") -replace "`r`n", "`n"
    [IO.File]::WriteAllText("$out\$f", $text, (New-Object System.Text.UTF8Encoding($false)))
}

"Prepared: $out"
Get-ChildItem $out | Select-Object Name, Length | Format-Table -AutoSize
"Keys in env (values hidden): " + (($lines | ForEach-Object { ($_ -split '=')[0] }) -join ', ')
