[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$appRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $candidate = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $candidate) { $AndroidSdk = $candidate }
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { throw 'ANDROID_SDK_REQUIRED' }
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) { throw 'ADB_NOT_FOUND' }
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$gradle = Join-Path $appRoot 'gradlew.bat'
$javaHome = if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $env:JAVA_HOME } else { Split-Path -Parent (Split-Path -Parent (Get-Command java -ErrorAction Stop).Source) }
$java = Join-Path $javaHome 'bin\java.exe'
$javac = Join-Path $javaHome 'bin\javac.exe'
if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $javac)) { throw 'JDK_TOOLS_REQUIRED' }
$package = 'com.patmanak.contako'
$testPackage = 'com.patmanak.contako.test'
$hostilePackage = 'com.patmanak.contako.hostile'
$runner = "$testPackage/com.patmanak.contako.qa.ContakoInstrumentationRunner"
$securityClass = 'com.patmanak.contako.qa.security.SecurityBoundaryDeviceTest'
$canaries = @('FX08_AUTH_CANARY_NOT_PERSONAL', 'FX08_TOKEN_CANARY_NOT_PERSONAL', 'FX08_CONTACT_CANARY_NOT_PERSONAL')

function Invoke-Checked([string]$Executable, [string[]]$Arguments, [string]$Failure) {
    $output = & $Executable @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$Failure`n$($output -join "`n")" }
    return @($output)
}

if (-not $SkipBuild) {
    Invoke-Checked $gradle @('--offline', '--dependency-verification', 'strict', '--console=plain', '--no-daemon',
        'assembleBenchmark', 'assembleBenchmarkAndroidTest', ':hostile-test-app:assembleDebug') 'SECURITY_APK_BUILD_FAILED' | Out-Null
}
$devices = @(& $adb devices | Select-String "`tdevice$")
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    if ($devices.Count -eq 0) {
        throw 'NO_CONNECTED_API35_DEVICE'
    }
    if ($devices.Count -ne 1) { throw 'DEVICE_SERIAL_REQUIRED' }
    $DeviceSerial = ($devices[0].Line -split "`t")[0]
}
$api = (& $adb -s $DeviceSerial shell getprop ro.build.version.sdk | Out-String).Trim()
if ($api -ne '35') { throw "API35_REQUIRED:FOUND_$api" }

$appApk = Join-Path $appRoot 'build\outputs\apk\benchmark\Contako-benchmark.apk'
$testApk = @(Get-ChildItem -LiteralPath (Join-Path $appRoot 'build\outputs\apk\androidTest\benchmark') -Filter '*.apk' -File -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
$hostileApk = Join-Path $appRoot 'hostile-test-app\build\outputs\apk\debug\hostile-test-app-debug.apk'
foreach ($apk in @($appApk, $testApk, $hostileApk)) {
    if ([string]::IsNullOrWhiteSpace($apk) -or -not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "APK_NOT_FOUND:$([IO.Path]::GetFileName($apk))" }
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ("contako-security-" + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temp) | Out-Null
$fixture = $null
$primaryFailure = $null
$cleanup = 'PASS'
try {
    Invoke-Checked $adb @('-s', $DeviceSerial, 'install', '-r', $appApk) 'APP_INSTALL_FAILED' | Out-Null
    Invoke-Checked $adb @('-s', $DeviceSerial, 'install', '-r', $testApk) 'TEST_INSTALL_FAILED' | Out-Null
    Invoke-Checked $adb @('-s', $DeviceSerial, 'install', '-r', $hostileApk) 'HOSTILE_INSTALL_FAILED' | Out-Null

    Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', "$securityClass#seedSyntheticBackupData", $runner) 'BACKUP_SEED_FAILED' | Out-Null
    $bmgr = @(& $adb -s $DeviceSerial shell bmgr backupnow $package 2>&1)
    $backupStatus = if ($LASTEXITCODE -eq 0 -and ($bmgr -join "`n") -notmatch '(?i)not allowed|unavailable|error') { 'PROBE_COMPLETED_NO_DATASET_EXPECTED' } else { 'BEST_EFFORT_UNAVAILABLE_OR_REJECTED' }
    Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'pm', 'clear', $package) 'APP_CLEAR_FAILED' | Out-Null
    & $adb -s $DeviceSerial shell bmgr restore 1 $package 2>&1 | Out-Null
    Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', "$securityClass#verifySyntheticBackupDataAbsent", $runner) 'BACKUP_TRANSFER_DETECTED' | Out-Null

    $password = [Guid]::NewGuid().ToString('N')
    $store = Join-Path $temp 'fixture.p12'
    Invoke-Checked 'keytool' @('-genkeypair', '-alias', 'fixture', '-keyalg', 'RSA', '-storetype', 'PKCS12', '-keystore', $store,
        '-storepass', $password, '-keypass', $password, '-dname', 'CN=127.0.0.1', '-validity', '1', '-noprompt') 'TLS_CERT_GENERATION_FAILED' | Out-Null
    $classes = Join-Path $temp 'classes'
    [IO.Directory]::CreateDirectory($classes) | Out-Null
    Invoke-Checked $javac @('-d', $classes, (Join-Path $PSScriptRoot 'fixtures\LocalTlsFixture.java')) 'TLS_FIXTURE_COMPILE_FAILED' | Out-Null
    $stdout = Join-Path $temp 'fixture.out'
    $stderr = Join-Path $temp 'fixture.err'
    $fixture = Start-Process -FilePath $java -ArgumentList @('-cp', $classes, 'LocalTlsFixture', $store, $password) -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -NoNewWindow
    $ready = $null
    for ($i = 0; $i -lt 50 -and $null -eq $ready; $i++) {
        Start-Sleep -Milliseconds 100
        if (Test-Path -LiteralPath $stdout) { $ready = Get-Content -LiteralPath $stdout | Where-Object { $_ -match '^READY \d+ \d+$' } | Select-Object -First 1 }
    }
    if ($null -eq $ready) { throw 'TLS_FIXTURE_NOT_READY' }
    $ports = $ready -split ' '
    Invoke-Checked $adb @('-s', $DeviceSerial, 'reverse', "tcp:$($ports[1])", "tcp:$($ports[1])") 'CLEARTEXT_REVERSE_FAILED' | Out-Null
    Invoke-Checked $adb @('-s', $DeviceSerial, 'reverse', "tcp:$($ports[2])", "tcp:$($ports[2])") 'TLS_REVERSE_FAILED' | Out-Null
    Invoke-Checked $adb @('-s', $DeviceSerial, 'logcat', '-c') 'LOGCAT_CLEAR_FAILED' | Out-Null
    Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', "$securityClass#cleartextAndUntrustedTlsAreRejected",
        '-e', 'clearPort', $ports[1], '-e', 'tlsPort', $ports[2], $runner) 'NETWORK_NEGATIVE_TEST_FAILED' | Out-Null
    Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'am', 'broadcast', '-a', 'com.patmanak.contako.hostile.RUN', '-p', $hostilePackage) 'HOSTILE_PROBE_START_FAILED' | Out-Null
    Start-Sleep -Seconds 2
    $logcat = (& $adb -s $DeviceSerial logcat -d -v brief 2>&1 | Out-String)
    if ($logcat -notmatch 'ContakoHostileProbe.*RESULT=PASS cases=6') { throw 'HOSTILE_COMPONENT_MATRIX_FAILED' }
    foreach ($canary in $canaries) { if ($logcat.Contains($canary)) { throw 'RELEASE_LOGCAT_CANARY_FOUND' } }
} catch {
    $primaryFailure = $_
} finally {
    if ($null -ne $fixture -and -not $fixture.HasExited) { Stop-Process -Id $fixture.Id -Force -ErrorAction SilentlyContinue }
    & $adb -s $DeviceSerial reverse --remove-all 2>&1 | Out-Null
    foreach ($name in @($hostilePackage, $testPackage, $package)) { & $adb -s $DeviceSerial uninstall $name 2>&1 | Out-Null }
    try { Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction Stop } catch { $cleanup = 'FAIL' }
}
if ($null -ne $primaryFailure) { throw $primaryFailure }
[pscustomobject]@{
    Result = 'PASS'; Scope = 'T04_T11_T12_API35'; Api = 35; BackupProbe = $backupStatus
    D2D = 'EXPLICITLY_BLOCKED_PACKAGED_POLICY'; HostileUidCases = 6; ExternalNetwork = 'NONE'; Cleanup = $cleanup
    AppApkSha256 = (Get-FileHash -LiteralPath $appApk -Algorithm SHA256).Hash
    HostileApkSha256 = (Get-FileHash -LiteralPath $hostileApk -Algorithm SHA256).Hash
}
