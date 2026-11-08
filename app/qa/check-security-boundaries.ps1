[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) { throw 'DEVICE_SERIAL_REQUIRED' }
$DeviceSerial = $DeviceSerial.Trim()
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
$package = 'com.patmanak.contako.securitycandidate'
$testPackage = "$package.test"
$hostilePackage = "$package.hostile"
$fixturePackages = @($package, $testPackage, $hostilePackage)
$runner = "$testPackage/com.patmanak.contako.qa.ContakoInstrumentationRunner"
$securityClass = 'com.patmanak.contako.qa.security.SecurityBoundaryDeviceTest'
$canaries = @('FX08_AUTH_CANARY_NOT_PERSONAL', 'FX08_TOKEN_CANARY_NOT_PERSONAL', 'FX08_CONTACT_CANARY_NOT_PERSONAL')

function Invoke-Checked([string]$Executable, [string[]]$Arguments, [string]$Failure) {
    $output = & $Executable @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw $Failure }
    return @($output)
}

function Assert-FixturePackage([string]$Name) {
    if ($Name -cnotin $fixturePackages) { throw 'UNSAFE_SECURITY_PACKAGE' }
}

function Assert-FixtureAbsent([string]$Name) {
    Assert-FixturePackage $Name
    # Include retained/uninstalled packages: their existing data is not this run's fixture.
    $inventory = @(Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'pm', 'list', 'packages', '-u') 'PACKAGE_INVENTORY_FAILED')
    if ($inventory.Count -eq 0 -or @($inventory | Where-Object { $_ -cnotmatch '^package:[A-Za-z0-9_.]+$' }).Count -ne 0) {
        throw 'PACKAGE_INVENTORY_UNVERIFIED'
    }
    if ($inventory -ccontains "package:$Name") { throw 'SECURITY_FIXTURE_ALREADY_PRESENT' }
}

function Install-SecurityFixture([string]$Apk, [string]$Name, [Collections.Generic.List[string]]$Installed) {
    Assert-FixtureAbsent $Name
    # Never replace an installation that appeared after the preflight inventory.
    $output = @(Invoke-Checked $adb @('-s', $DeviceSerial, 'install', $Apk) 'SECURITY_FIXTURE_INSTALL_FAILED')
    if (($output -join "`n") -notmatch '(?m)^Success\s*$') { throw 'SECURITY_FIXTURE_INSTALL_UNCONFIRMED' }
    $Installed.Add($Name)
}

function Clear-SecurityFixture([string]$Name, [Collections.Generic.List[string]]$Installed) {
    Assert-FixturePackage $Name
    if (-not $Installed.Contains($Name)) { throw 'SECURITY_FIXTURE_NOT_OWNED' }
    $output = @(Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'pm', 'clear', $Name) 'APP_CLEAR_FAILED')
    if (($output -join "`n") -notmatch '(?m)^Success\s*$') { throw 'APP_CLEAR_UNCONFIRMED' }
}

function Remove-SecurityFixtures([Collections.Generic.List[string]]$Installed) {
    $status = 'PASS'
    for ($index = $Installed.Count - 1; $index -ge 0; $index--) {
        $name = $Installed[$index]
        try {
            Assert-FixturePackage $name
            $output = @(Invoke-Checked $adb @('-s', $DeviceSerial, 'uninstall', $name) 'SECURITY_FIXTURE_UNINSTALL_FAILED')
            if (($output -join "`n") -notmatch '(?m)^Success\s*$') { throw 'SECURITY_FIXTURE_UNINSTALL_UNCONFIRMED' }
        } catch { $status = 'FAIL' }
    }
    return $status
}

function Assert-ApkIdentity([string]$Apk, [string]$ExpectedPackage, [string]$ExpectedTarget = '') {
    Assert-FixturePackage $ExpectedPackage
    $badging = (Invoke-Checked $aapt @('dump', 'badging', $Apk) 'APK_METADATA_FAILED') -join "`n"
    $identity = [regex]::Match($badging, "(?m)^package: name='([^']+)'")
    if (-not $identity.Success -or $identity.Groups[1].Value -cne $ExpectedPackage) { throw 'SECURITY_APK_PACKAGE_MISMATCH' }
    if ($ExpectedTarget) {
        $manifest = (Invoke-Checked $aapt @('dump', 'xmltree', $Apk, 'AndroidManifest.xml') 'APK_MANIFEST_READ_FAILED') -join "`n"
        $target = [regex]::Match($manifest, 'android:targetPackage(?:\([^)]*\))?="([^"]+)"')
        if (-not $target.Success -or $target.Groups[1].Value -cne $ExpectedTarget) { throw 'SECURITY_TEST_TARGET_MISMATCH' }
    }
}

if (-not $SkipBuild) {
    Push-Location $appRoot
    try {
        Invoke-Checked $gradle @('--dependency-verification', 'strict', '--console=plain', '--no-daemon',
            '-PcontakoCandidatePackageSuffix=securitycandidate',
            'assembleBenchmark', 'assembleBenchmarkAndroidTest', ':hostile-test-app:assembleDebug') 'SECURITY_APK_BUILD_FAILED' | Out-Null
    } finally { Pop-Location }
}
$state = (Invoke-Checked $adb @('-s', $DeviceSerial, 'get-state') 'SELECTED_DEVICE_UNAVAILABLE') -join ''
if ($state.Trim() -ne 'device') { throw 'SELECTED_DEVICE_UNAVAILABLE' }
$api = ((Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'getprop', 'ro.build.version.sdk') 'API_READ_FAILED') -join '').Trim()
if ($api -ne '35') { throw "API35_REQUIRED:FOUND_$api" }
$aapt = @(Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory | Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName 'aapt.exe' } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    Select-Object -First 1)
if ($aapt.Count -ne 1) { throw 'AAPT_NOT_FOUND' }
$aapt = $aapt[0]

$appApk = Join-Path $appRoot 'build\outputs\apk\benchmark\Contako-benchmark.apk'
$testApks = @(Get-ChildItem -LiteralPath (Join-Path $appRoot 'build\outputs\apk\androidTest\benchmark') -Filter '*.apk' -File -ErrorAction SilentlyContinue)
if ($testApks.Count -ne 1) { throw 'SECURITY_TEST_APK_NOT_UNIQUE' }
$testApk = $testApks[0].FullName
$hostileApk = Join-Path $appRoot 'hostile-test-app\build\outputs\apk\debug\hostile-test-app-debug.apk'
foreach ($apk in @($appApk, $testApk, $hostileApk)) {
    if ([string]::IsNullOrWhiteSpace($apk) -or -not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "APK_NOT_FOUND:$([IO.Path]::GetFileName($apk))" }
}
Assert-ApkIdentity $appApk $package
Assert-ApkIdentity $testApk $testPackage $package
Assert-ApkIdentity $hostileApk $hostilePackage
foreach ($name in $fixturePackages) { Assert-FixtureAbsent $name }

$temp = Join-Path ([IO.Path]::GetTempPath()) ("contako-security-" + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($temp) | Out-Null
$fixture = $null
$primaryFailure = $null
$cleanup = 'PASS'
$installed = [Collections.Generic.List[string]]::new()
$reverses = [Collections.Generic.List[string]]::new()
try {
    Install-SecurityFixture $appApk $package $installed
    Install-SecurityFixture $testApk $testPackage $installed
    Install-SecurityFixture $hostileApk $hostilePackage $installed

    Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', "$securityClass#seedSyntheticBackupData", $runner) 'BACKUP_SEED_FAILED' | Out-Null
    $bmgr = @(& $adb -s $DeviceSerial shell bmgr backupnow $package 2>&1)
    $backupStatus = if ($LASTEXITCODE -eq 0 -and ($bmgr -join "`n") -notmatch '(?i)not allowed|unavailable|error') { 'PROBE_COMPLETED_NO_DATASET_EXPECTED' } else { 'BEST_EFFORT_UNAVAILABLE_OR_REJECTED' }
    Clear-SecurityFixture $package $installed
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
    $fixture = Start-Process -FilePath $java -ArgumentList @('-cp', $classes, 'LocalTlsFixture', $store, $password) -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
    $ready = $null
    for ($i = 0; $i -lt 50 -and $null -eq $ready; $i++) {
        Start-Sleep -Milliseconds 100
        if (Test-Path -LiteralPath $stdout) { $ready = Get-Content -LiteralPath $stdout | Where-Object { $_ -match '^READY \d+ \d+$' } | Select-Object -First 1 }
    }
    if ($null -eq $ready) { throw 'TLS_FIXTURE_NOT_READY' }
    $ports = $ready -split ' '
    Invoke-Checked $adb @('-s', $DeviceSerial, 'reverse', "tcp:$($ports[1])", "tcp:$($ports[1])") 'CLEARTEXT_REVERSE_FAILED' | Out-Null
    $reverses.Add("tcp:$($ports[1])")
    Invoke-Checked $adb @('-s', $DeviceSerial, 'reverse', "tcp:$($ports[2])", "tcp:$($ports[2])") 'TLS_REVERSE_FAILED' | Out-Null
    $reverses.Add("tcp:$($ports[2])")
    Invoke-Checked $adb @('-s', $DeviceSerial, 'logcat', '-c') 'LOGCAT_CLEAR_FAILED' | Out-Null
    Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', "$securityClass#cleartextAndUntrustedTlsAreRejected",
        '-e', 'clearPort', $ports[1], '-e', 'tlsPort', $ports[2], $runner) 'NETWORK_NEGATIVE_TEST_FAILED' | Out-Null
    Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'am', 'broadcast', '-a', "$hostilePackage.RUN", '-p', $hostilePackage) 'HOSTILE_PROBE_START_FAILED' | Out-Null
    Start-Sleep -Seconds 2
    $logcat = (& $adb -s $DeviceSerial logcat -d -v brief 2>&1 | Out-String)
    if ($logcat -notmatch 'ContakoHostileProbe.*RESULT=PASS cases=6') { throw 'HOSTILE_COMPONENT_MATRIX_FAILED' }
    foreach ($canary in $canaries) { if ($logcat.Contains($canary)) { throw 'RELEASE_LOGCAT_CANARY_FOUND' } }
} catch {
    $primaryFailure = $_
} finally {
    if ($null -ne $fixture -and -not $fixture.HasExited) { Stop-Process -Id $fixture.Id -Force -ErrorAction SilentlyContinue }
    foreach ($reverse in $reverses) {
        try { Invoke-Checked $adb @('-s', $DeviceSerial, 'reverse', '--remove', $reverse) 'REVERSE_CLEANUP_FAILED' | Out-Null }
        catch { $cleanup = 'FAIL' }
    }
    if ((Remove-SecurityFixtures $installed) -ne 'PASS') { $cleanup = 'FAIL' }
    try {
        $resolvedTemp = [IO.Path]::GetFullPath($temp)
        $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedTemp.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolvedTemp) -notmatch '^contako-security-[a-f0-9]{32}$') { throw 'UNSAFE_SECURITY_CLEANUP_PATH' }
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction Stop
    } catch { $cleanup = 'FAIL' }
}
if ($null -ne $primaryFailure) { throw $primaryFailure }
if ($cleanup -ne 'PASS') { throw 'SECURITY_FIXTURE_CLEANUP_FAILED' }
[pscustomobject]@{
    Result = 'PASS'; Scope = 'T04_T11_T12_API35'; Api = 35; BackupProbe = $backupStatus
    D2D = 'EXPLICITLY_BLOCKED_PACKAGED_POLICY'; HostileUidCases = 6; ExternalNetwork = 'NONE'; Cleanup = $cleanup
    AppApkSha256 = (Get-FileHash -LiteralPath $appApk -Algorithm SHA256).Hash
    HostileApkSha256 = (Get-FileHash -LiteralPath $hostileApk -Algorithm SHA256).Hash
}
