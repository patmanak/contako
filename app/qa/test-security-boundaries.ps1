# Host-only regressions. The trusted runner's functions call an in-memory fake,
# never ADB, Gradle, a phone, an account or a real package installation.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$runnerPath = Join-Path $PSScriptRoot 'check-security-boundaries.ps1'
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($runnerPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw 'SECURITY_RUNNER_PARSE_FAILED' }
foreach ($name in @('Invoke-Checked', 'Assert-FixturePackage', 'Assert-FixtureAbsent',
    'Install-SecurityFixture', 'Clear-SecurityFixture', 'Remove-SecurityFixtures', 'Assert-ApkIdentity')) {
    $definition = $ast.Find({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    if ($null -eq $definition) { throw "MISSING_RUNNER_FUNCTION:$name" }
    . ([scriptblock]::Create($definition.Extent.Text))
}

$package = 'com.patmanak.contako.securitycandidate'
$testPackage = "$package.test"
$hostilePackage = "$package.hostile"
$fixturePackages = @($package, $testPackage, $hostilePackage)
$DeviceSerial = 'synthetic-test-device'
$adb = 'Invoke-FakeAndroidTool'
$aapt = 'Invoke-FakeAndroidTool'
$script:calls = [Collections.Generic.List[string]]::new()
$script:present = [Collections.Generic.HashSet[string]]::new()
$script:installCount = 0
$script:failInstallNumber = 0
$script:unconfirmedInstall = $false
$script:failUninstall = $false
$script:invalidInventory = $false
$script:metadataPackage = $package
$script:metadataTarget = $package
$script:results = [Collections.Generic.List[string]]::new()

function Invoke-FakeAndroidTool {
    $arguments = @($args)
    $script:calls.Add(($arguments -join '|'))
    $global:LASTEXITCODE = 0
    if ($arguments[0] -eq 'dump' -and $arguments[1] -eq 'badging') {
        return "package: name='$script:metadataPackage' versionCode='1'"
    }
    if ($arguments[0] -eq 'dump' -and $arguments[1] -eq 'xmltree') {
        return "A: android:targetPackage(0x01010021)=`"$script:metadataTarget`" (Raw: `"$script:metadataTarget`")"
    }
    if ($arguments[0] -ne '-s' -or $arguments[1] -ne $DeviceSerial) { throw 'FAKE_REQUIRES_EXPLICIT_TARGET' }
    switch ($arguments[2]) {
        'install' {
            $script:installCount++
            if ($arguments -contains '-r') { throw 'FIXTURE_MUST_NOT_REPLACE_APPS' }
            if ($script:installCount -eq $script:failInstallNumber) {
                $global:LASTEXITCODE = 1
                return 'Failure: deliberately rejected'
            }
            if ($script:unconfirmedInstall) { return 'Streaming installation' }
            return 'Success'
        }
        'uninstall' {
            if ($script:failUninstall) { $global:LASTEXITCODE = 1; return 'Failure' }
            return 'Success'
        }
        'shell' {
            if ($arguments.Count -ge 7 -and ($arguments[3..6] -join '|') -eq 'pm|list|packages|-u') {
                if ($script:invalidInventory) { return 'Error: package inventory unavailable' }
                'package:android'
                foreach ($name in $script:present) { "package:$name" }
                return
            }
            if ($arguments[3] -eq 'pm' -and $arguments[4] -eq 'clear') { return 'Success' }
        }
    }
    throw 'UNEXPECTED_FAKE_ANDROID_OPERATION'
}

function Reset-Fake {
    $script:calls.Clear()
    $script:present.Clear()
    $script:installCount = 0
    $script:failInstallNumber = 0
    $script:unconfirmedInstall = $false
    $script:failUninstall = $false
    $script:invalidInventory = $false
    $script:metadataPackage = $package
    $script:metadataTarget = $package
}

function Assert-Equal($Actual, $Expected, [string]$Label) {
    if ($Actual -cne $Expected) { throw "HOST_ASSERTION_FAILED:$Label" }
}

function Expect-Failure([scriptblock]$Action, [string]$Expected) {
    try { & $Action; throw 'EXPECTED_FAILURE_MISSING' }
    catch { if ($_.Exception.Message -cne $Expected) { throw } }
}

Reset-Fake
Expect-Failure { & $runnerPath -SkipBuild -AndroidSdk 'nonexistent-sdk' } 'DEVICE_SERIAL_REQUIRED'
Assert-Equal $script:calls.Count 0 'missing-device-stops-before-tools'
$script:results.Add('explicit device required before build/tool lookup')

Reset-Fake
$script:metadataPackage = 'com.patmanak.contako'
Expect-Failure { Assert-ApkIdentity 'synthetic.apk' $package } 'SECURITY_APK_PACKAGE_MISMATCH'
$script:results.Add('production APK rejected before installation')

Reset-Fake
$script:metadataPackage = $testPackage
$script:metadataTarget = 'com.patmanak.contako'
Expect-Failure { Assert-ApkIdentity 'synthetic-test.apk' $testPackage $package } 'SECURITY_TEST_TARGET_MISMATCH'
$script:metadataTarget = $package
Assert-ApkIdentity 'synthetic-test.apk' $testPackage $package
$script:results.Add('test APK target checked independently of its package')

Reset-Fake
$installed = [Collections.Generic.List[string]]::new()
[void]$script:present.Add($package)
Expect-Failure { Install-SecurityFixture 'synthetic.apk' $package $installed } 'SECURITY_FIXTURE_ALREADY_PRESENT'
Assert-Equal (Remove-SecurityFixtures $installed) 'PASS' 'preexisting-cleanup'
Assert-Equal ($script:calls.Count) 1 'preexisting-app-never-installed-cleared-or-removed'
$script:results.Add('preexisting or retained-data candidate protected')

Reset-Fake
$installed = [Collections.Generic.List[string]]::new()
$script:invalidInventory = $true
Expect-Failure { Install-SecurityFixture 'synthetic.apk' $package $installed } 'PACKAGE_INVENTORY_UNVERIFIED'
Assert-Equal $installed.Count 0 'unverified-inventory-install-not-started'
Assert-Equal $script:installCount 0 'unverified-inventory-install-count'
$script:results.Add('unverified package inventory fails closed before installation')

Reset-Fake
$installed = [Collections.Generic.List[string]]::new()
$installed.Add('com.patmanak.contako')
Expect-Failure { Clear-SecurityFixture 'com.patmanak.contako' $installed } 'UNSAFE_SECURITY_PACKAGE'
Assert-Equal (Remove-SecurityFixtures $installed) 'FAIL' 'production-name-guard'
Assert-Equal $script:calls.Count 0 'production-app-never-cleared-or-removed'
$script:results.Add('production package rejected even in forged ownership list')

Reset-Fake
$installed = [Collections.Generic.List[string]]::new()
$script:failInstallNumber = 1
Expect-Failure { Install-SecurityFixture 'synthetic.apk' $package $installed } 'SECURITY_FIXTURE_INSTALL_FAILED'
Assert-Equal $installed.Count 0 'failed-install-not-owned'
Assert-Equal (Remove-SecurityFixtures $installed) 'PASS' 'failed-install-cleanup'
Assert-Equal (@($script:calls | Where-Object { $_ -like '*|uninstall|*' }).Count) 0 'failed-install-never-uninstalled'
$script:results.Add('failed first installation cannot trigger destructive cleanup')

Reset-Fake
$installed = [Collections.Generic.List[string]]::new()
$script:unconfirmedInstall = $true
Expect-Failure { Install-SecurityFixture 'synthetic.apk' $package $installed } 'SECURITY_FIXTURE_INSTALL_UNCONFIRMED'
Assert-Equal $installed.Count 0 'unconfirmed-install-not-owned'
Assert-Equal (Remove-SecurityFixtures $installed) 'PASS' 'unconfirmed-install-cleanup'
Assert-Equal (@($script:calls | Where-Object { $_ -like '*|uninstall|*' }).Count) 0 'unconfirmed-install-never-uninstalled'
$script:results.Add('missing install acknowledgement does not authorize removal')

Reset-Fake
$installed = [Collections.Generic.List[string]]::new()
Install-SecurityFixture 'synthetic-app.apk' $package $installed
$script:failInstallNumber = 2
Expect-Failure { Install-SecurityFixture 'synthetic-test.apk' $testPackage $installed } 'SECURITY_FIXTURE_INSTALL_FAILED'
Assert-Equal (Remove-SecurityFixtures $installed) 'PASS' 'partial-install-cleanup'
Assert-Equal (@($script:calls | Where-Object { $_ -like '*|uninstall|*' }) -join ',') "-s|$DeviceSerial|uninstall|$package" 'only-confirmed-app-cleaned'
$script:results.Add('partial setup removes only successfully created fixtures')

Reset-Fake
$installed = [Collections.Generic.List[string]]::new()
Expect-Failure { Clear-SecurityFixture $package $installed } 'SECURITY_FIXTURE_NOT_OWNED'
Assert-Equal $script:calls.Count 0 'unowned-candidate-never-cleared'
Install-SecurityFixture 'synthetic-app.apk' $package $installed
Install-SecurityFixture 'synthetic-test.apk' $testPackage $installed
Clear-SecurityFixture $package $installed
Assert-Equal (Remove-SecurityFixtures $installed) 'PASS' 'owned-fixture-cleanup'
Assert-Equal (@($script:calls | Where-Object { $_ -like '*|uninstall|*' }) -join ',') "-s|$DeviceSerial|uninstall|$testPackage,-s|$DeviceSerial|uninstall|$package" 'reverse-owned-cleanup'
$script:results.Add('clear requires run ownership; successful cleanup is scoped')

Reset-Fake
$installed = [Collections.Generic.List[string]]::new()
Install-SecurityFixture 'synthetic-app.apk' $package $installed
$script:failUninstall = $true
Assert-Equal (Remove-SecurityFixtures $installed) 'FAIL' 'cleanup-refusal-remains-failure'
$script:results.Add('cleanup failure is reported instead of passing silently')

[pscustomobject]@{ Result = 'PASS'; Cases = $script:results.Count; DeviceOperations = 'NONE'; Checks = @($script:results) }
