# Kids TV installer for Windows (PowerShell 5.1 or 7). Run it with nothing set up:
#
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/pedroteixeira/kids-tv-launcher/main/install.ps1)))
#
# Add options at the end, e.g.  ... -Titles C:\Users\me\titles.json
# It downloads Google's adb (platform-tools) and the app's latest release into
# %USERPROFILE%\.kids-tv, connects to the TV over Wireless debugging (pairing it the first
# time), installs the app, sets the PIN, sends your titles and switches the watchdog on.
#
#   -Titles FILE   your titles.json (see README); without it the TV shows the example list
#   -TitlesOnly    only send the titles (and a new PIN, if you type one); don't reinstall
#   -Tv IP:PORT    the TV's "IP address & port" from the Wireless debugging screen
#   -Apk FILE      install this APK instead of downloading the latest release
param(
    [string]$Titles = "",
    [switch]$TitlesOnly,
    [string]$Tv = "",
    [string]$Apk = ""
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue" # Invoke-WebRequest is very slow with the progress bar
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Repo = "pedroteixeira/kids-tv-launcher"
$Pkg = "app.tvdascriancas"
$Watchdog = "$Pkg/$Pkg.Watchdog"
$Listener = "$Pkg/$Pkg.MediaListener"
$Files = "/sdcard/Android/data/$Pkg/files"
$Work = if ($env:KIDS_TV_HOME) { $env:KIDS_TV_HOME } else { Join-Path $HOME ".kids-tv" }

# throw, not exit: under `& ([scriptblock]::Create(...))` exit would close the window
# before the message can be read.
function Die($msg) { Write-Host "`n$msg" -ForegroundColor Red; throw "Kids TV install stopped." }
function Say($msg) { Write-Host "`n$msg" -ForegroundColor Cyan }
function AskSecret($prompt) {
    $s = Read-Host -Prompt $prompt -AsSecureString
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
}
function AskPin($prompt) {
    while ($true) {
        $p = AskSecret $prompt
        if ($p -eq "") { return "" }
        if ($p -notmatch '^[0-9]{4,8}$') { Write-Host "The PIN must be 4 to 8 digits."; continue }
        if ((AskSecret "Type it again") -eq $p) { return $p }
        Write-Host "They don't match - again."
    }
}

if ($Titles) { if (-not (Test-Path $Titles -PathType Leaf)) { Die "titles file not found: $Titles" }; $Titles = (Resolve-Path $Titles).Path }
if ($Apk) { if (-not (Test-Path $Apk -PathType Leaf)) { Die "APK not found: $Apk" }; $Apk = (Resolve-Path $Apk).Path }
New-Item -ItemType Directory -Force -Path $Work | Out-Null

# --- adb: Google's platform-tools, downloaded once (an adb on PATH is used if it can pair).
function AdbOk($exe) {
    $ErrorActionPreference = "Continue"
    try { return ((& $exe version 2>$null) -join "`n") -match '(?m)^Version (3[0-9]|[4-9][0-9])' } catch { return $false }
}
$Adb = $null
$onPath = Get-Command adb -ErrorAction SilentlyContinue
if ($onPath -and (AdbOk $onPath.Source)) { $Adb = $onPath.Source }
if (-not $Adb) {
    if ($IsLinux) { $os = "linux"; $exe = "adb" } elseif ($IsMacOS) { $os = "darwin"; $exe = "adb" } else { $os = "windows"; $exe = "adb.exe" }
    $Adb = Join-Path (Join-Path $Work "platform-tools") $exe
    if (-not (AdbOk $Adb)) {
        Say "Downloading adb (Google's platform-tools)..."
        $zip = Join-Path $Work "platform-tools.zip"
        Invoke-WebRequest -UseBasicParsing -Uri "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" -OutFile $zip
        Remove-Item -Recurse -Force (Join-Path $Work "platform-tools") -ErrorAction SilentlyContinue
        Expand-Archive -Path $zip -DestinationPath $Work -Force
        Remove-Item $zip
        if ($os -ne "windows") { chmod +x $Adb }
    }
}
# Runs adb and returns its output as one string; the exit code goes to $LASTCODE.
# adb's stderr chatter ("daemon started") isn't an error: with "Stop", Windows
# PowerShell 5.1 would throw on it. Plain functions ($args), so "-s" reaches adb.
function Run {
    $ErrorActionPreference = "Continue"
    $out = & $Adb @args 2>&1 | ForEach-Object { "$_" }
    $global:LASTCODE = $LASTEXITCODE
    return ($out -join "`n")
}
# -n: adb shell must not read the console, or it swallows typed answers.
function TvShell { Run shell -n @args }

# --- the APK
if (-not $TitlesOnly -and -not $Apk) {
    Say "Downloading the latest Kids TV release..."
    $Apk = Join-Path $Work "kids-tv.apk"
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/$Repo/releases/latest/download/kids-tv.apk" -OutFile $Apk
    } catch { Die "Couldn't download the release from github.com/$Repo/releases" }
}

# --- connect to the TV
Write-Host @"

On the TV (once):
  1. Settings > System > About > click "Android TV OS build" 7 times (developer mode).
  2. Settings > System > Developer options > Wireless debugging > On.
     The TV must be on Wi-Fi, on the same network as this computer.
That screen shows the TV's "IP address & port". The port changes when the TV restarts.
"@
if (-not $Tv) { $Tv = Read-Host "`nIP address & port (e.g. 192.168.1.20:37415)" }
if ($Tv -notmatch '^[^:]+:[0-9]+$') { Die "Expected IP:PORT, e.g. 192.168.1.20:37415" }
$ip = $Tv.Substring(0, $Tv.LastIndexOf(":"))

function Connected { (Run -s $Tv get-state).Trim() -eq "device" }
Run connect $Tv | Out-Null
if (-not (Connected)) {
    Write-Host @"

This computer isn't paired with the TV yet. On the Wireless debugging screen choose
"Pair device with pairing code": the TV shows a 6-digit code and a second port.
"@
    $pairPort = Read-Host 'Pairing port (after the ":" in the pairing popup)'
    $code = Read-Host "Pairing code"
    $out = Run pair "${ip}:$pairPort" $code
    Write-Host $out
    if ($LASTCODE -ne 0 -or $out -notmatch "Successfully paired") { Die "Pairing failed - check the code and port, then run again." }
    Run disconnect $Tv | Out-Null
    Run connect $Tv | Out-Null
    for ($i = 0; $i -lt 5 -and -not (Connected); $i++) { Start-Sleep 1 }
    if (-not (Connected)) { Die "Paired, but couldn't connect to $Tv - check the IP address & port on the TV." }
}
$env:ANDROID_SERIAL = $Tv
Write-Host "connected: $((TvShell getprop ro.product.model).Trim())"
if ((TvShell pm list features) -notmatch "leanback") {
    Write-Host "warning: this device doesn't look like a Google TV / Android TV; continuing anyway."
}

# --- PIN: required the first time, optional afterwards
$installed = (TvShell pm path $Pkg) -match "package:"
if ($installed) {
    $pin = AskPin "`nNew PIN to leave kids mode (4-8 digits; Enter keeps the current one)"
} else {
    do { $pin = AskPin "`nChoose the PIN to leave kids mode (4-8 digits)" } while ($pin -eq "")
}

# --- install
if (-not $TitlesOnly) {
    Say "Installing..."
    $out = Run install -r $Apk
    if ($LASTCODE -ne 0) {
        if ($out -notmatch "INSTALL_FAILED_UPDATE_INCOMPATIBLE") { Die $out }
        Write-Host "The TV has a copy signed with another key (e.g. built from source)."
        if ((Read-Host "Uninstall it and install this one? Its PIN and history go with it [y/N]") -ne "y") { Die "Nothing changed." }
        Run uninstall $Pkg | Out-Null
        while ($pin -eq "") { $pin = AskPin "Choose the PIN to leave kids mode (4-8 digits)" }
        $out = Run install $Apk
        if ($LASTCODE -ne 0) { Die $out }
    }
    Write-Host "installed: $Pkg"
} elseif (-not $installed) {
    Die "Kids TV isn't on this TV yet: run without -TitlesOnly first."
}

# --- settings the app reads from its files folder (it picks them up on its own)
TvShell mkdir -p $Files | Out-Null
if ($pin) {
    # Typed digits only (no quoting needed); the app reads the file, moves the PIN into
    # private storage and deletes it. 666: the app can't read a file only adb may read.
    TvShell "printf %s $pin > $Files/pin && chmod 666 $Files/pin" | Out-Null
    if ($LASTCODE -ne 0) { Die "Couldn't send the PIN." }
    Write-Host "PIN set"
}
if ($Titles) {
    $out = Run push $Titles "$Files/titles.json"
    if ($LASTCODE -ne 0) { Die $out }
    TvShell chmod 666 "$Files/titles.json" | Out-Null
    Write-Host "titles sent: $Titles"
}

if (-not $TitlesOnly) {
    # TCL TVs only let the system start a third-party app's services when the app holds
    # TCL's AUTO_START app-op; other TVs don't have it (the error is ignored there).
    TvShell appops set $Pkg AUTO_START allow | Out-Null

    # The watchdog is an accessibility service; Android greys its toggle out for apps
    # from outside the Play Store, so it's switched on here. Ours is removed and added
    # back (after a reinstall the system may keep it "on" without running it); any other
    # enabled services are kept.
    $current = (TvShell settings get secure enabled_accessibility_services).Trim()
    $others = @($current -split ":" | Where-Object { $_ -and $_ -ne "null" -and $_ -ne $Watchdog }) -join ":"
    if ($others) { TvShell settings put secure enabled_accessibility_services $others | Out-Null }
    else { TvShell settings delete secure enabled_accessibility_services | Out-Null }
    Start-Sleep 1
    $all = if ($others) { "${others}:$Watchdog" } else { $Watchdog }
    TvShell settings put secure enabled_accessibility_services $all | Out-Null
    TvShell settings put secure accessibility_enabled 1 | Out-Null
    # Reading other apps' "now playing" needs a notification listener.
    TvShell cmd notification allow_listener $Listener | Out-Null
    Write-Host "watchdog on"
}

TvShell am start -n "$Pkg/.MainActivity" | Out-Null
Say "Done - Kids TV is open on the TV."
Write-Host @"
- Hand the TV over with "Turn on kids mode"; leaving it takes the PIN.
- To change the titles later: edit your titles.json and run the same command with
    -TitlesOnly -Titles <file>
- You can turn Wireless debugging off now; the app keeps working.
"@
