<#
  Chạy Relay Server (chế độ Internet) ngay trên máy này.

  relay.bat              relay trong mạng nội bộ: ws://<IP-LAN>:8080/ws (2 máy cùng mạng, để thử)
  relay.bat -Tunnel      công khai relay ra Internet bằng Cloudflare Tunnel miễn phí: không cần VPS, tên miền
                         hay mở cổng router. Script in ra địa chỉ wss://<tên>.trycloudflare.com/ws để nhập
                         trên CẢ 2 máy. Địa chỉ đổi mỗi lần chạy, giữ cửa sổ này mở suốt buổi demo.

  Tham số:
    -Port <n>   cổng relay trên máy này (mặc định 8080)
    -Tunnel     công khai qua Cloudflare Tunnel (tự tải cloudflared lần đầu, có kiểm tra chữ ký số)

  Cần chạy setup.bat trước (để có JDK). Khoá RELAY_LINK_SECRET được sinh ngẫu nhiên 1 lần và lưu trong
  %LOCALAPPDATA%\P2PFileShare\relay-link-secret.txt (không nằm trong thư mục dự án / Git).
#>
param(
    [int]$Port = 8080,
    [switch]$Tunnel
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$InstallDir  = Join-Path $env:LOCALAPPDATA 'P2PFileShare'
$MinJava     = 17
# Đường dẫn tương đối: java.exe trên Windows làm hỏng tham số dòng lệnh có dấu tiếng Việt
$RelayJarRel = 'relay-server\target\p2p-relay-server.jar'
$RelayJar    = Join-Path $ProjectRoot $RelayJarRel

function Step([string]$text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Ok([string]$text)   { Write-Host "    [OK] $text" -ForegroundColor Green }
function Warn([string]$text) { Write-Host "    [!]  $text" -ForegroundColor Yellow }
function Stop-Relay([string]$message) {
    Write-Host "    [X]  $message" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- 1. Java
function Get-JdkMajor([string]$javaHome) {
    if (-not $javaHome) { return 0 }
    if (-not (Test-Path (Join-Path $javaHome 'bin\javac.exe'))) { return 0 }
    $release = Join-Path $javaHome 'release'
    if (-not (Test-Path $release)) { return 0 }
    $line = Select-String -Path $release -Pattern '^JAVA_VERSION="([^"]+)"' | Select-Object -First 1
    if (-not $line) { return 0 }
    $parts = $line.Matches[0].Groups[1].Value.Split('.')
    if ($parts[0] -eq '1') { return [int]$parts[1] }
    return [int]($parts[0] -replace '\D.*$', '')
}

function Find-Jdk {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) {
        $props = cmd /c "`"$($cmd.Source)`" -XshowSettings:properties -version 2>&1"
        $homeLine = $props | Where-Object { $_ -match '^\s*java\.home\s*=' } | Select-Object -First 1
        if ($homeLine) { $candidates.Add(($homeLine -split '=', 2)[1].Trim()) }
    }
    foreach ($r in @($InstallDir, "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft",
                     "$env:ProgramFiles\Zulu", "$env:ProgramFiles\Amazon Corretto", "$env:ProgramFiles\BellSoft")) {
        if (Test-Path $r) { Get-ChildItem $r -Directory -ErrorAction SilentlyContinue | ForEach-Object { $candidates.Add($_.FullName) } }
    }
    foreach ($c in ($candidates | Select-Object -Unique)) {
        if ((Get-JdkMajor $c) -ge $MinJava) { return $c }
    }
    return $null
}

Step "Kiểm tra Java (JDK $MinJava trở lên)"
$jdkHome = Find-Jdk
if (-not $jdkHome) { Stop-Relay "Chưa có JDK $MinJava+. Hãy chạy setup.bat trước." }
$env:JAVA_HOME = $jdkHome
$env:Path = "$jdkHome\bin;$env:Path"
$JavaExe = Join-Path $jdkHome 'bin\java.exe'
Ok "JDK: $jdkHome"

# ---------------------------------------------------------------- 2. Build relay
$needBuild = -not (Test-Path $RelayJar)
if (-not $needBuild) {
    $jarTime = (Get-Item $RelayJar).LastWriteTime
    $changed = Get-ChildItem (Join-Path $ProjectRoot 'relay-server\src'), (Join-Path $ProjectRoot 'relay-server\pom.xml') -Recurse -File |
               Where-Object { $_.LastWriteTime -gt $jarTime } | Select-Object -First 1
    $needBuild = [bool]$changed
}
if ($needBuild) {
    Step 'Build Relay Server'
    Push-Location $ProjectRoot
    try {
        & "$ProjectRoot\mvnw.cmd" -q -B -DskipTests -f relay-server\pom.xml package
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    if ($code -ne 0 -or -not (Test-Path $RelayJar)) { Stop-Relay "Build relay thất bại (mã lỗi $code)" }
}
Ok "Relay: $RelayJarRel"

# ---------------------------------------------------------------- 3. Cấu hình
New-Item -ItemType Directory -Force $InstallDir | Out-Null
$secretFile = Join-Path $InstallDir 'relay-link-secret.txt'
if (-not (Test-Path $secretFile)) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    (($bytes | ForEach-Object { $_.ToString('x2') }) -join '') | Set-Content -Path $secretFile -Encoding Ascii
}
$env:RELAY_LINK_SECRET = (Get-Content $secretFile -Raw).Trim()
$env:RELAY_PORT = "$Port"

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    Stop-Relay "Cổng $Port đang được dùng (có thể relay đã chạy ở cửa sổ khác). Tắt cửa sổ đó hoặc chạy: relay.bat -Port 8090"
}

# ---------------------------------------------------------------- 4a. Relay trong mạng nội bộ
if (-not $Tunnel) {
    $env:RELAY_BIND = '0.0.0.0'
    Step 'Relay chạy trong mạng nội bộ (không mã hoá, chỉ để thử)'
    Write-Host "    Nhập 1 trong các địa chỉ sau vào ô 'Địa chỉ relay' (chế độ 🌍 Internet) trên cả 2 máy:" -ForegroundColor White
    Write-Host "      ws://127.0.0.1:$Port/ws   (máy đang chạy relay)" -ForegroundColor Yellow
    $nets = @(Get-NetIPConfiguration -ErrorAction SilentlyContinue | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' })
    foreach ($n in $nets) {
        $ip = ($n.IPv4Address | Select-Object -First 1).IPAddress
        Write-Host "      ws://${ip}:$Port/ws   ($($n.InterfaceAlias), cho máy khác cùng mạng)" -ForegroundColor Yellow
    }
    Write-Host '    Hai máy ở 2 mạng khác nhau (Wi-Fi + 4G): tắt cửa sổ này và chạy  relay.bat -Tunnel' -ForegroundColor White
    Write-Host '    Nhấn Ctrl+C để dừng relay.' -ForegroundColor White
    Push-Location $ProjectRoot
    try { & $JavaExe -jar $RelayJarRel } finally { Pop-Location }
    exit $LASTEXITCODE
}

# ---------------------------------------------------------------- 4b. Công khai qua Cloudflare Tunnel
function Test-CloudflareSigned([string]$path) {
    $sig = Get-AuthenticodeSignature -FilePath $path
    return $sig.Status -eq 'Valid' -and $sig.SignerCertificate -and $sig.SignerCertificate.Subject -match 'Cloudflare'
}

function Get-Cloudflared {
    $cmd = Get-Command cloudflared -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $exe = Join-Path $InstallDir 'cloudflared.exe'
    # chỉ file đã kiểm tra lúc tải mới được chuyển vào đây
    if (Test-Path $exe) { return $exe }

    Step 'Tải cloudflared (Cloudflare Tunnel, khoảng 60 MB, chỉ tải 1 lần)'
    $name = if ([Environment]::Is64BitOperatingSystem) { 'cloudflared-windows-amd64.exe' } else { 'cloudflared-windows-386.exe' }
    $url = "https://github.com/cloudflare/cloudflared/releases/latest/download/$name"
    $digest = $null
    try {
        $rel = Invoke-RestMethod -Uri 'https://api.github.com/repos/cloudflare/cloudflared/releases/latest' -UseBasicParsing -TimeoutSec 30
        $asset = $rel.assets | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if ($asset) {
            $url = $asset.browser_download_url
            if ($asset.digest -match '^sha256:([0-9a-fA-F]{64})$') { $digest = $Matches[1].ToUpper() }
        }
    } catch {
        Warn "Không đọc được thông tin bản phát hành trên GitHub, tải bản mới nhất: $($_.Exception.Message)"
    }
    $tmp = "$exe.download"
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) { & $curl.Source -L --fail --retry 3 -s -o $tmp $url; if ($LASTEXITCODE -ne 0) { Stop-Relay 'Tải cloudflared thất bại' } }
    else { Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing }

    # cần ít nhất 1 trong 2: SHA-256 khớp digest GitHub công bố, hoặc chữ ký số hợp lệ của Cloudflare
    $verified = $false
    if ($digest) {
        if ((Get-FileHash $tmp -Algorithm SHA256).Hash -ne $digest) {
            Remove-Item $tmp -Force
            Stop-Relay 'File cloudflared tải về sai SHA-256, hãy chạy lại'
        }
        Ok 'Đã kiểm tra SHA-256 theo GitHub'
        $verified = $true
    }
    if (Test-CloudflareSigned $tmp) {
        Ok 'Đã kiểm tra chữ ký số của Cloudflare'
        $verified = $true
    }
    if (-not $verified) {
        Remove-Item $tmp -Force
        Stop-Relay 'Không xác minh được file cloudflared (không có SHA-256 từ GitHub, không có chữ ký số), đã xoá'
    }
    Move-Item $tmp $exe -Force
    return $exe
}

$cloudflared = Get-Cloudflared
Ok "cloudflared: $cloudflared"

# relay chỉ nghe trên máy này, Internet vào qua cloudflared; lấy IP thật của máy client từ X-Forwarded-For
$env:RELAY_BIND = '127.0.0.1'
$env:RELAY_TRUST_PROXY = 'true'
$relayLog = Join-Path $InstallDir 'relay.log'
$tunnelLog = Join-Path $InstallDir 'cloudflared.log'
foreach ($f in @($relayLog, "$relayLog.err", $tunnelLog, "$tunnelLog.out")) { if (Test-Path $f) { Remove-Item $f -Force } }

$relay = $null
$tunnelProc = $null
try {
    Step "Khởi động relay (127.0.0.1:$Port)"
    $relay = Start-Process -FilePath $JavaExe -ArgumentList '-jar', $RelayJarRel -WorkingDirectory $ProjectRoot `
        -NoNewWindow -PassThru -RedirectStandardOutput $relayLog -RedirectStandardError "$relayLog.err"
    $healthy = $false
    for ($i = 0; $i -lt 30 -and -not $healthy -and -not $relay.HasExited; $i++) {
        Start-Sleep -Milliseconds 500
        try { $healthy = (Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" -UseBasicParsing -TimeoutSec 3).Content -match 'ok' } catch { }
    }
    if (-not $healthy) { Stop-Relay "Relay không khởi động được, xem $relayLog" }
    Ok 'Relay đã chạy'

    Step 'Mở Cloudflare Tunnel'
    $tunnelProc = Start-Process -FilePath $cloudflared `
        -ArgumentList 'tunnel', '--no-autoupdate', '--url', "http://127.0.0.1:$Port" `
        -NoNewWindow -PassThru -RedirectStandardError $tunnelLog -RedirectStandardOutput "$tunnelLog.out"
    $publicHost = $null
    for ($i = 0; $i -lt 90 -and -not $publicHost -and -not $tunnelProc.HasExited; $i++) {
        Start-Sleep -Seconds 1
        $text = (Get-Content $tunnelLog -Raw -ErrorAction SilentlyContinue) + (Get-Content "$tunnelLog.out" -Raw -ErrorAction SilentlyContinue)
        if ($text -match 'https://((?!api\.)[a-z0-9-]+\.trycloudflare\.com)') { $publicHost = $Matches[1] }
    }
    if (-not $publicHost) { Stop-Relay "Không tạo được Cloudflare Tunnel (mạng chặn?), xem $tunnelLog" }

    $wss = "wss://$publicHost/ws"
    $reachable = $false
    for ($i = 0; $i -lt 30 -and -not $reachable; $i++) {
        try { $reachable = (Invoke-WebRequest -Uri "https://$publicHost/health" -UseBasicParsing -TimeoutSec 5).Content -match 'ok' }
        catch { Start-Sleep -Seconds 2 }
    }
    try { Set-Clipboard -Value $wss } catch { }

    Write-Host ''
    Write-Host '  ================================================================================' -ForegroundColor Green
    Write-Host '   RELAY ĐÃ CÔNG KHAI RA INTERNET. Nhập địa chỉ sau vào ô "Địa chỉ relay"' -ForegroundColor Green
    Write-Host '   (tab Kết nối mạng -> 🌍 Internet) trên CẢ 2 máy, rồi bấm Kết nối relay:' -ForegroundColor Green
    Write-Host ''
    Write-Host "       $wss" -ForegroundColor Yellow
    Write-Host ''
    Write-Host '   (đã chép vào clipboard). Giữ cửa sổ này mở suốt buổi demo; nhấn Ctrl+C để dừng.' -ForegroundColor Green
    Write-Host '  ================================================================================' -ForegroundColor Green
    if ($reachable) { Ok "Kiểm tra https://$publicHost/health qua Internet: OK" }
    else { Warn 'Chưa truy cập được địa chỉ công khai (DNS có thể cần thêm vài chục giây), thử lại sau ít phút' }
    Write-Host "    Log relay: $relayLog" -ForegroundColor DarkGray

    while (-not $relay.HasExited -and -not $tunnelProc.HasExited) { Start-Sleep -Seconds 2 }
    if ($relay.HasExited) { Warn "Relay đã dừng, xem $relayLog" } else { Warn "Cloudflare Tunnel đã dừng, xem $tunnelLog" }
} finally {
    foreach ($p in @($tunnelProc, $relay)) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
}
