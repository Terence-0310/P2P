<#
  Cài đặt và chạy P2PFileShare trên máy Windows mới (chưa có Java/Maven).

  Các bước:
    1. Kiểm tra Windows, PowerShell, Internet
    2. Tìm JDK >= 17 có sẵn; nếu không có thì tải Eclipse Temurin JDK 21 (bản zip, không cần quyền Admin)
       vào %LOCALAPPDATA%\P2PFileShare\jdk-21, có kiểm tra SHA-256
    3. Build dự án bằng Maven Wrapper (mvnw tự tải Maven, Maven tự tải JavaFX và thư viện)
    4. Cho phép Java qua Windows Firewall (hỏi quyền Admin bằng UAC)
    5. In IP của máy trong LAN (để dùng "Thêm theo IP") và chạy app

  Tham số:
    -Run              chạy app sau khi cài xong
    -Instances <n>    số cửa sổ app mở ra (mặc định 1; đặt 2 để thử 2 peer trên cùng 1 máy)
    -Quick            bỏ qua kiểm tra Internet và tường lửa (dùng cho run.bat)
    -SkipFirewall     không đụng tới tường lửa
    -ForceDownloadJdk luôn tải JDK mới kể cả khi máy đã có Java
#>
param(
    [switch]$Run,
    [int]$Instances = 1,
    [switch]$Quick,
    [switch]$SkipFirewall,
    [switch]$ForceDownloadJdk
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest trên PowerShell 5.1 rất chậm nếu hiện thanh tiến trình
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$MinJava     = 17
$JdkVersion  = 21
$InstallDir  = Join-Path $env:LOCALAPPDATA 'P2PFileShare'
$LocalJdk    = Join-Path $InstallDir "jdk-$JdkVersion"
$RuleName    = 'P2PFileShare'

$summary = [System.Collections.Generic.List[object]]::new()
function Add-Result([string]$item, [string]$status, [string]$detail) {
    $summary.Add([pscustomobject]@{ 'Hạng mục' = $item; 'Trạng thái' = $status; 'Chi tiết' = $detail })
}
function Step([string]$text)  { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Ok([string]$text)    { Write-Host "    [OK] $text" -ForegroundColor Green }
function Warn([string]$text)  { Write-Host "    [!]  $text" -ForegroundColor Yellow }
function Fail([string]$text)  { Write-Host "    [X]  $text" -ForegroundColor Red }

function Show-Summary {
    Write-Host "`n================ TÓM TẮT ================" -ForegroundColor Cyan
    $summary | Format-Table -AutoSize -Wrap | Out-String | Write-Host
}

function Stop-Setup([string]$message) {
    Fail $message
    Show-Summary
    Write-Host "Cài đặt dừng lại do lỗi ở trên." -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- 1. Hệ thống
Step 'Kiểm tra hệ thống'
$os = [Environment]::OSVersion.Version
$arch = $env:PROCESSOR_ARCHITECTURE
if ($os.Major -lt 10) { Stop-Setup "Cần Windows 10 trở lên (máy này: $os)" }
Ok "Windows $os, $arch, PowerShell $($PSVersionTable.PSVersion)"
Add-Result 'Windows' 'OK' "$os ($arch)"
if (-not (Test-Path (Join-Path $ProjectRoot 'mvnw.cmd'))) { Stop-Setup "Không thấy mvnw.cmd trong $ProjectRoot" }
Ok "Thư mục dự án: $ProjectRoot"

function Test-Url([string]$url) {
    try {
        Invoke-WebRequest -Uri $url -Method Head -UseBasicParsing -TimeoutSec 15 | Out-Null
        return $true
    } catch {
        # một số máy chủ không cho HEAD nhưng vẫn trả lời -> coi như có mạng
        return $null -ne $_.Exception.Response
    }
}

if (-not $Quick) {
    Step 'Kiểm tra Internet (cần cho lần cài đầu tiên)'
    $mavenOk = Test-Url 'https://repo.maven.apache.org/maven2/'
    if ($mavenOk) { Ok 'Kết nối được Maven Central'; Add-Result 'Internet (Maven)' 'OK' 'repo.maven.apache.org' }
    else {
        $m2 = Join-Path $env:USERPROFILE '.m2\repository\org\openjfx'
        if (Test-Path $m2) { Warn 'Không kết nối được Maven Central, dùng thư viện đã tải trước đó'; Add-Result 'Internet (Maven)' 'CẢNH BÁO' 'Offline, dùng cache ~/.m2' }
        else { Stop-Setup 'Không kết nối được https://repo.maven.apache.org. Cần Internet để tải Maven và thư viện ở lần đầu.' }
    }
}

# ---------------------------------------------------------------- 2. Java
function Get-JdkMajor([string]$javaHome) {
    if (-not $javaHome) { return 0 }
    if (-not (Test-Path (Join-Path $javaHome 'bin\javac.exe'))) { return 0 }   # chỉ có JRE thì không build được
    $release = Join-Path $javaHome 'release'
    if (-not (Test-Path $release)) { return 0 }
    $line = Select-String -Path $release -Pattern '^JAVA_VERSION="([^"]+)"' | Select-Object -First 1
    if (-not $line) { return 0 }
    $v = $line.Matches[0].Groups[1].Value          # vd. 21.0.5 hoặc 1.8.0_402
    $parts = $v.Split('.')
    if ($parts[0] -eq '1') { return [int]$parts[1] }
    return [int]($parts[0] -replace '\D.*$', '')
}

function Find-Jdk {
    # JAVA_HOME người dùng đã đặt thì ưu tiên dùng (giống IDE / Maven)
    $m = Get-JdkMajor $env:JAVA_HOME
    if ($m -ge $MinJava) { return [pscustomobject]@{ Home = $env:JAVA_HOME; Major = $m } }

    $candidates = [System.Collections.Generic.List[string]]::new()
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) {
        # java trong PATH có thể là shim (Oracle javapath) -> hỏi thẳng java.home
        $props = cmd /c "`"$($cmd.Source)`" -XshowSettings:properties -version 2>&1"
        $homeLine = $props | Where-Object { $_ -match '^\s*java\.home\s*=' } | Select-Object -First 1
        if ($homeLine) { $candidates.Add(($homeLine -split '=', 2)[1].Trim()) }
    }
    $roots = @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft",
               "$env:ProgramFiles\Zulu", "$env:ProgramFiles\Amazon Corretto", "$env:ProgramFiles\BellSoft", $InstallDir)
    foreach ($r in $roots) {
        if (Test-Path $r) { Get-ChildItem $r -Directory -ErrorAction SilentlyContinue | ForEach-Object { $candidates.Add($_.FullName) } }
    }
    $best = $null; $bestMajor = 0
    foreach ($c in ($candidates | Select-Object -Unique)) {
        $m = Get-JdkMajor $c
        if ($m -ge $MinJava -and $m -gt $bestMajor) { $best = $c; $bestMajor = $m }
    }
    if ($best) { return [pscustomobject]@{ Home = $best; Major = $bestMajor } }
    return $null
}

function Install-Jdk {
    Step "Tải Eclipse Temurin JDK $JdkVersion (khoảng 190 MB, chỉ tải 1 lần)"
    # JavaFX trong pom chỉ có bản Windows x64; Windows ARM vẫn chạy được bản x64 nhờ giả lập
    $api = "https://api.adoptium.net/v3/assets/latest/$JdkVersion/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse"
    try { $assets = Invoke-RestMethod -Uri $api -UseBasicParsing -TimeoutSec 30 }
    catch { Stop-Setup "Không lấy được thông tin JDK từ api.adoptium.net: $($_.Exception.Message)" }
    $pkg = $assets[0].binary.package
    if (-not $pkg -or -not $pkg.link) { Stop-Setup 'api.adoptium.net trả về dữ liệu không như mong đợi' }

    New-Item -ItemType Directory -Force $InstallDir | Out-Null
    $zip = Join-Path $InstallDir $pkg.name
    Write-Host "    Đang tải $($pkg.name) ..."
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) { & $curl.Source -L --fail --retry 3 -o $zip $pkg.link; if ($LASTEXITCODE -ne 0) { Stop-Setup 'Tải JDK thất bại (curl)' } }
    else { Invoke-WebRequest -Uri $pkg.link -OutFile $zip -UseBasicParsing }

    $hash = (Get-FileHash $zip -Algorithm SHA256).Hash
    if ($pkg.checksum -and $hash -ne $pkg.checksum.ToUpper()) {
        Remove-Item $zip -Force
        Stop-Setup 'File JDK tải về bị lỗi (sai SHA-256), hãy chạy lại'
    }
    Ok 'Đã kiểm tra SHA-256'

    Write-Host '    Đang giải nén ...'
    $tmp = Join-Path $InstallDir 'extract-tmp'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    New-Item -ItemType Directory $tmp | Out-Null
    $tar = Get-Command tar.exe -ErrorAction SilentlyContinue
    if ($tar) { & $tar.Source -xf $zip -C $tmp; if ($LASTEXITCODE -ne 0) { Stop-Setup 'Giải nén JDK thất bại' } }
    else { Expand-Archive -Path $zip -DestinationPath $tmp -Force }
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (Test-Path $LocalJdk) { Remove-Item $LocalJdk -Recurse -Force }
    Move-Item $inner.FullName $LocalJdk
    Remove-Item $tmp -Recurse -Force
    Remove-Item $zip -Force
    Ok "Đã cài JDK vào $LocalJdk"
    return [pscustomobject]@{ Home = $LocalJdk; Major = (Get-JdkMajor $LocalJdk) }
}

Step "Kiểm tra Java (JDK $MinJava trở lên)"
$jdk = if ($ForceDownloadJdk) { $null } else { Find-Jdk }
if ($jdk) {
    Ok "Dùng JDK $($jdk.Major): $($jdk.Home)"
    Add-Result 'Java (JDK)' 'Có sẵn' "JDK $($jdk.Major) - $($jdk.Home)"
} else {
    if (-not $ForceDownloadJdk) { Warn "Chưa có JDK $MinJava+ trên máy, sẽ tải về" }
    $jdk = Install-Jdk
    if ($jdk.Major -lt $MinJava) { Stop-Setup 'Cài JDK xong nhưng không đọc được phiên bản' }
    Add-Result 'Java (JDK)' 'Đã tải' "JDK $($jdk.Major) - $($jdk.Home)"
}
$env:JAVA_HOME = $jdk.Home
$env:Path = "$($jdk.Home)\bin;$env:Path"
$JavaExe = Join-Path $jdk.Home 'bin\java.exe'

# ---------------------------------------------------------------- 3. Build
Step 'Build dự án (lần đầu Maven sẽ tải thư viện, mất vài phút)'
Push-Location $ProjectRoot
try {
    & "$ProjectRoot\mvnw.cmd" -q -DskipTests compile
    $buildCode = $LASTEXITCODE
} finally { Pop-Location }
if ($buildCode -ne 0) { Stop-Setup "Build thất bại (mã lỗi $buildCode). Xem log Maven ở trên." }
Ok 'Build thành công'
Add-Result 'Maven + thư viện' 'OK' 'mvnw compile thành công'

# ---------------------------------------------------------------- 4. Tường lửa
function Get-FirewallState([string]$program) {
    $rules = @(Get-NetFirewallApplicationFilter -Program $program -ErrorAction SilentlyContinue |
               Get-NetFirewallRule -ErrorAction SilentlyContinue |
               Where-Object { $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' })
    [pscustomobject]@{
        Allow = @($rules | Where-Object { $_.DisplayName -like "$RuleName*" -and $_.Action -eq 'Allow' }).Count
        Block = @($rules | Where-Object { $_.Action -eq 'Block' }).Count
    }
}

if (-not $SkipFirewall -and -not $Quick) {
    Step 'Cấu hình Windows Firewall cho Java'
    $state = Get-FirewallState $JavaExe
    if ($state.Allow -gt 0 -and $state.Block -eq 0) {
        Ok 'Tường lửa đã cho phép Java'
        Add-Result 'Tường lửa' 'OK' 'Đã có rule cho java.exe'
    } else {
        Warn 'Chưa có rule cho Java, Windows sẽ hỏi quyền Admin (bấm Yes)'
        $programs = @($JavaExe, (Join-Path $jdk.Home 'bin\javaw.exe')) | ForEach-Object { "'" + ($_ -replace "'", "''") + "'" }
        $script = "foreach (`$p in @($($programs -join ','))) {`n" +
                  "  Get-NetFirewallApplicationFilter -Program `$p -ErrorAction SilentlyContinue | Get-NetFirewallRule -ErrorAction SilentlyContinue |" +
                  " Where-Object { `$_.Direction -eq 'Inbound' -and (`$_.Action -eq 'Block' -or `$_.DisplayName -eq '$RuleName') } | Remove-NetFirewallRule`n" +
                  "  New-NetFirewallRule -DisplayName '$RuleName' -Direction Inbound -Program `$p -Action Allow -Profile Any | Out-Null`n}"
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
        try {
            Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden `
                -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded
        } catch {
            Warn 'Đã bỏ qua hộp thoại quyền Admin'
        }
        $state = Get-FirewallState $JavaExe
        if ($state.Allow -gt 0 -and $state.Block -eq 0) {
            Ok 'Đã thêm rule tường lửa'
            Add-Result 'Tường lửa' 'Đã cấu hình' "Rule '$RuleName' cho java.exe / javaw.exe"
        } else {
            Warn 'Chưa cấu hình được tường lửa, máy khác có thể không thấy máy này (có thể làm lại bằng nút Tường lửa trong app)'
            Add-Result 'Tường lửa' 'CẢNH BÁO' 'Chưa có rule, chạy lại setup và bấm Yes'
        }
    }
}

# ---------------------------------------------------------------- 5. Mạng LAN
if (-not $Quick) {
    Step 'Thông tin mạng LAN'
    $nets = @(Get-NetIPConfiguration -ErrorAction SilentlyContinue | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' })
    if ($nets.Count -eq 0) {
        Warn 'Máy chưa kết nối mạng nào có gateway (Wi-Fi/LAN)'
        Add-Result 'Mạng LAN' 'CẢNH BÁO' 'Chưa kết nối mạng'
    }
    foreach ($n in $nets) {
        $ip = ($n.IPv4Address | Select-Object -First 1).IPAddress
        $prefix = ($n.IPv4Address | Select-Object -First 1).PrefixLength
        $category = (Get-NetConnectionProfile -InterfaceIndex $n.InterfaceIndex -ErrorAction SilentlyContinue).NetworkCategory
        Ok "$($n.InterfaceAlias): IP $ip/$prefix, gateway $($n.IPv4DefaultGateway.NextHop), loại mạng $category"
        Add-Result 'Mạng LAN' 'OK' "$($n.InterfaceAlias): $ip/$prefix"
    }
    Write-Host '    -> Hai máy phải cùng mạng (cùng gateway, IP cùng dải). Nếu quét không thấy, dùng nút "Thêm theo IP" với IP ở trên.'
}

Show-Summary

# ---------------------------------------------------------------- Chạy app
if ($Run) {
    $n = [Math]::Max(1, $Instances)
    Write-Host "Đang mở $n cửa sổ P2PFileShare ..." -ForegroundColor Cyan
    for ($i = 1; $i -le $n; $i++) {
        # mỗi cửa sổ console giữ log của 1 peer; đóng console = tắt peer đó
        Start-Process -FilePath "$ProjectRoot\mvnw.cmd" -ArgumentList '-q', 'exec:java' -WorkingDirectory $ProjectRoot
        if ($i -lt $n) { Start-Sleep -Seconds 4 }
    }
} else {
    Write-Host 'Cài đặt xong. Chạy app bằng run.bat' -ForegroundColor Green
}
