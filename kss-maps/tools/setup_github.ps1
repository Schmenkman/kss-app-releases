<#
  Einmalig: eigenen Signier-Schluessel als GitHub-Secrets hinterlegen und neu bauen.

  Vorher:
    1. python tools\make_keystore.py   (erzeugt signing\ -- bleibt auf deinem Rechner)
    2. gh auth login                   (Anmeldung bei GitHub)

  Aufruf (im Ordner kss-maps):  powershell -ExecutionPolicy Bypass -File tools\setup_github.ps1
  Wiederholbar. Die Secrets landen im Repo Schmenkman/kss-app-releases.
#>

$Repo = "Schmenkman/kss-app-releases"
$Root = Split-Path -Parent $PSScriptRoot

function Fail($msg) { Write-Host ""; Write-Host "  FEHLER: $msg" -ForegroundColor Red; Write-Host ""; exit 1 }
function Step($msg) { Write-Host ""; Write-Host "=== $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }

Step "Voraussetzungen pruefen"
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail "gh (GitHub CLI) ist nicht installiert." }
gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "Nicht bei GitHub angemeldet. Zuerst:  gh auth login" }
Ok "GitHub-Anmeldung steht"

$envFile = Join-Path $Root "signing\secrets.env"
$b64File = Join-Path $Root "signing\kssmaps.p12.base64"
if (-not (Test-Path $envFile)) { Fail "signing\secrets.env fehlt. Zuerst:  python tools\make_keystore.py" }
if (-not (Test-Path $b64File)) { Fail "signing\kssmaps.p12.base64 fehlt. Zuerst:  python tools\make_keystore.py" }
$sec = @{}
foreach ($line in (Get-Content $envFile)) { if ($line -match "^([^=]+)=(.*)$") { $sec[$matches[1]] = $matches[2] } }
$b64 = (Get-Content $b64File -Raw).Trim()
if (-not $b64) { Fail "signing\kssmaps.p12.base64 ist leer." }
Ok "Schluessel gefunden (Alias: $($sec['KSSMAPS_KEY_ALIAS']))"

Step "Secrets setzen ($Repo)"
$all = [ordered]@{
    "KSSMAPS_KEYSTORE_BASE64" = $b64
    "KSSMAPS_KEY_ALIAS"       = $sec["KSSMAPS_KEY_ALIAS"]
    "KSSMAPS_STORE_PASSWORD"  = $sec["KSSMAPS_STORE_PASSWORD"]
    "KSSMAPS_KEY_PASSWORD"    = $sec["KSSMAPS_KEY_PASSWORD"]
}
foreach ($name in $all.Keys) {
    if (-not $all[$name]) { Fail "$name ist leer -- signing\secrets.env unvollstaendig?" }
    # Ueber die Pipeline statt --body: so steht das Geheimnis nicht in der Prozessliste.
    $all[$name] | gh secret set $name --repo $Repo
    if ($LASTEXITCODE -ne 0) { Fail "Secret $name konnte nicht gesetzt werden." }
    Ok $name
}

Step "API-Schluessel in die App einbauen (optional)"
Write-Host "    Leer lassen = ueberspringen (vorhandene Secrets bleiben)."
$keys = [ordered]@{
    "KSSMAPS_TOMTOM_KEY"       = "TomTom API-Schluessel"
    "KSSMAPS_TANKERKOENIG_KEY" = "Tankerkoenig API-Schluessel"
}
foreach ($name in $keys.Keys) {
    $val = (Read-Host "    $($keys[$name])").Trim()
    if ($val) {
        $val | gh secret set $name --repo $Repo
        if ($LASTEXITCODE -ne 0) { Fail "Secret $name konnte nicht gesetzt werden." }
        Ok $name
    }
}

Step "Neuen Build starten"
$branch = Read-Host "    Branch (Enter = main)"
if (-not $branch) { $branch = "main" }
gh workflow run kss-maps.yml --repo $Repo --ref $branch
if ($LASTEXITCODE -ne 0) { Fail "Build konnte nicht gestartet werden (Workflow auf dem Branch vorhanden?)." }
Ok "Build gestartet: https://github.com/$Repo/actions"

Write-Host @"

  Ab jetzt ist jede APK mit DEINEM Schluessel signiert -- Updates funktionieren ohne
  Neuinstallation. Einmalig: eine evtl. installierte Testversion deinstallieren und die
  neue APK installieren.

  NICHT VERGESSEN: Ordner signing\ ausserhalb dieses Projekts sichern.
"@ -ForegroundColor Green
