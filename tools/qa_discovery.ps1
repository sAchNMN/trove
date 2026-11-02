param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [string]$Serial = "emulator-5554"
)

$ErrorActionPreference = "Stop"
if ($Serial -notmatch '^emulator-\d+$') { throw "Emulator required; physical phones are never accepted." }
$repo = Split-Path $PSScriptRoot -Parent
$run = "DeviceSorter-GlobalQA-" + (Get-Date -Format "yyyyMMdd-HHmmss")
$output = Join-Path $repo "app/build/discovery-qa/$run"
New-Item -ItemType Directory -Path $output -Force | Out-Null

function Adb-Run {
    $result = & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $args" }
    return $result
}

function Ui {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $dump = (Adb-Run shell uiautomator dump /data/local/tmp/discovery-qa.xml) -join "`n"
        if ($dump -like "*dumped to*") { break }
        Start-Sleep -Milliseconds 500
    }
    if ($dump -notlike "*dumped to*") { throw "Could not get a stable UI hierarchy: $dump" }
    [xml]$result = (Adb-Run shell cat /data/local/tmp/discovery-qa.xml) -join "`n"
    return $result
}

function Find([string]$Text, [string]$Description = "") {
    return (Ui).SelectNodes("//node") | Where-Object {
        if ($Description) { $_.'content-desc' -eq $Description } else { $_.text -eq $Text }
    } | Select-Object -Last 1
}

function Tap($Node) {
    if (!$Node) { throw "Expected UI control not found" }
    $n = [regex]::Matches($Node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    Adb-Run shell input tap ([int](($n[0] + $n[2]) / 2)) ([int](($n[1] + $n[3]) / 2)) | Out-Null
}

function Click([string]$Text, [string]$Description = "") { Tap (Find $Text $Description) }
function Top {
    1..8 | ForEach-Object { Adb-Run shell input swipe 360 400 360 1060 100 | Out-Null }
}
function Bottom {
    1..8 | ForEach-Object { Adb-Run shell input swipe 360 1050 360 400 100 | Out-Null }
}
function Capture([string]$Name) {
    Adb-Run shell "screencap -p /data/local/tmp/discovery-qa.png" | Out-Null
    Adb-Run pull /data/local/tmp/discovery-qa.png (Join-Path $output "$Name.png") | Out-Null
    (Ui).Save((Join-Path $output "$Name.xml"))
}

# 0.4.0 opens on a feature hub; enter the classification feature and use the
# single-page view because this script drives the page by screen position.
function Ensure-Scroll {
    $node = Find "切换：单页"
    if ($node) { Tap $node; Start-Sleep -Milliseconds 600 }
}

function Open-Sort {
    $node = (Ui).SelectNodes("//node") | Where-Object {
        $_.text -in @("继续未完成设置", "查看任务进度", "开始设置")
    } | Select-Object -First 1
    if (!$node) { throw "Classification entry was not found on the feature hub." }
    Tap $node
    Start-Sleep -Milliseconds 800
    Ensure-Scroll
}
function Instrument([string]$Class, [string]$Name) {
    $result = Adb-Run shell am instrument -w -e class $Class app.devicesorter.test/android.test.InstrumentationTestRunner
    $text = $result -join "`n"
    $text | Set-Content -LiteralPath (Join-Path $output "$Name.txt")
    if ($text -notmatch "OK \(\d+ tests?\)") { throw "Instrumentation failed: $text" }
    Write-Output $text
}

Adb-Run install -r (Join-Path $repo "app/build/outputs/apk/debug/app-debug.apk") | Out-Null
Adb-Run install -r (Join-Path $repo "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk") | Out-Null
Adb-Run shell "mkdir -p /sdcard/Pictures/$run/Camera" | Out-Null
Adb-Run push (Join-Path $repo "app/src/androidTest/assets/canon.jpg") "/sdcard/Pictures/$run/Camera/qa-photo.jpg" | Out-Null
Adb-Run shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///storage/emulated/0/Pictures/$run/Camera/qa-photo.jpg" | Out-Null
Adb-Run shell am force-stop com.google.android.documentsui | Out-Null
Adb-Run shell am force-stop app.devicesorter | Out-Null
Adb-Run shell am start -n app.devicesorter/.MainActivity | Out-Null
Start-Sleep -Seconds 2
Open-Sort
Top
Click "" "添加来源目录"
$roots = Find "" "Show roots"
if ($roots) { Tap $roots }
$device = (Ui).SelectNodes("//node") | Where-Object { $_.text -like "sdk_gphone*" } | Select-Object -First 1
if ($device) { Tap $device }
foreach ($part in @("Pictures", $run)) {
    $node = Find $part
    for ($i = 0; !$node -and $i -lt 8; $i++) {
        Adb-Run shell input swipe 360 1000 360 400 150 | Out-Null
        $node = Find $part
    }
    Tap $node
}
Click "USE THIS FOLDER"
Click "ALLOW"
Capture "01-authorization"

# Only the emulator's test app permissions are changed.
Adb-Run shell pm grant app.devicesorter android.permission.READ_MEDIA_IMAGES | Out-Null
Adb-Run shell pm grant app.devicesorter android.permission.READ_MEDIA_VIDEO | Out-Null
Instrument "app.devicesorter.MediaDiscoveryDeviceTest" "02-discovery"
Instrument "app.devicesorter.InPlaceDeviceTest" "03-in-place"
Instrument "app.devicesorter.ClassificationDeviceTest,app.devicesorter.MetadataDeviceTest,app.devicesorter.StorageDeviceTest,app.devicesorter.MainActivityDeviceTest" "04-existing"

Adb-Run shell am force-stop app.devicesorter | Out-Null
Adb-Run shell am start -n app.devicesorter/.MainActivity | Out-Null
Start-Sleep -Seconds 2
Open-Sort
Top
Click "扫描全部照片 / 视频"
$deadline = (Get-Date).AddSeconds(120)
do {
    Bottom
    $ui = Ui
    $permission = $ui.SelectNodes("//node") | Where-Object {
        $_.'resource-id' -eq "com.android.permissioncontroller:id/permission_allow_button"
    } | Select-Object -First 1
    if ($permission) { Tap $permission; continue }
    $status = $ui.SelectNodes("//node") | Where-Object { $_.text -like "媒体库查找完成*" } | Select-Object -First 1
    if ($status) { break }
} while ((Get-Date) -lt $deadline)
if (!$status) { throw "Media discovery did not complete" }
$statusText = $status.text
Capture "05-discovery-complete"
Top
Capture "06-source-folders"

# A partial-access run is separate so revocation cannot kill an active test process.
Adb-Run shell am force-stop app.devicesorter | Out-Null
Adb-Run shell pm revoke app.devicesorter android.permission.READ_MEDIA_IMAGES | Out-Null
Adb-Run shell pm revoke app.devicesorter android.permission.READ_MEDIA_VIDEO | Out-Null
Adb-Run shell pm grant app.devicesorter android.permission.READ_MEDIA_VISUAL_USER_SELECTED | Out-Null
$partial = Adb-Run shell am instrument -w -e class "app.devicesorter.MediaDiscoveryDeviceTest#testPermissionScopeMatchesActualGrants" -e expectedScope partial app.devicesorter.test/android.test.InstrumentationTestRunner
$partialText = $partial -join "`n"
$partialText | Set-Content -LiteralPath (Join-Path $output "07-partial-permission.txt")
if ($partialText -notmatch "OK \(1 test\)") { throw "Partial permission test failed: $partialText" }
Adb-Run shell pm grant app.devicesorter android.permission.READ_MEDIA_IMAGES | Out-Null
Adb-Run shell pm grant app.devicesorter android.permission.READ_MEDIA_VIDEO | Out-Null
Adb-Run shell am start -n app.devicesorter/.MainActivity | Out-Null
Adb-Run shell "tar -cf /data/local/tmp/discovery-qa.tar -C /sdcard/Pictures $run" | Out-Null
Adb-Run pull /data/local/tmp/discovery-qa.tar (Join-Path $output "phone.tar") | Out-Null
$packagePath = ((Adb-Run shell pm path app.devicesorter) | Select-Object -First 1) -replace '^package:', ''
$installedHash = ((Adb-Run shell sha256sum $packagePath) -split '\s+')[0]
$builtHash = (Get-FileHash -LiteralPath (Join-Path $repo "app/build/outputs/apk/debug/app-debug.apk") -Algorithm SHA256).Hash
if ($installedHash -ne $builtHash) { throw "Installed APK does not match the tested build" }
@{ verdict = "PASS"; serial = $Serial; run = $run; discovery = $statusText; apkSha256 = $installedHash } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output "result.json")
Write-Output "PASS: $output"
