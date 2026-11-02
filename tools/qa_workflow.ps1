param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [string]$Serial = "emulator-5554"
)

$ErrorActionPreference = "Stop"
if ($Serial -notmatch '^emulator-\d+$') { throw "This test only accepts an emulator, never a physical phone." }
$repo = Split-Path $PSScriptRoot -Parent
$run = "DeviceSorter-QA-" + (Get-Date -Format "yyyyMMdd-HHmmss")
$local = Join-Path $repo "app/build/workflow/$run"
$fixture = Join-Path $local "phone"
$remote = "/sdcard/Documents/$run"
$assets = Join-Path $repo "app/src/androidTest/assets"
New-Item -ItemType Directory -Path $local -Force | Out-Null

function Adb-Run {
    $result = & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $args" }
    return $result
}

function Read-Ui {
    Adb-Run shell uiautomator dump /data/local/tmp/devicesorter-qa-ui.xml | Out-Null
    [xml]$ui = (Adb-Run shell cat /data/local/tmp/devicesorter-qa-ui.xml) -join "`n"
    return $ui
}

function Find-Node([string]$Text, [string]$Description = "") {
    $ui = Read-Ui
    return $ui.SelectNodes('//node') | Where-Object {
        if ($Description) { $_.'content-desc' -eq $Description } else { $_.text -eq $Text }
    } | Select-Object -Last 1
}

function Click-Node($Node) {
    if ($null -eq $Node) { throw "Expected UI control was not found." }
    $n = [regex]::Matches($Node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    Adb-Run shell input tap ([int](($n[0] + $n[2]) / 2)) ([int](($n[1] + $n[3]) / 2)) | Out-Null
}

function Click([string]$Text, [string]$Description = "") {
    $node = Find-Node $Text $Description
    for ($attempt = 0; !$node -and $attempt -lt 8; $attempt++) {
        Adb-Run shell input swipe 360 1050 360 400 150 | Out-Null
        $node = Find-Node $Text $Description
    }
    if (!$node) { throw "Missing UI control: text='$Text', description='$Description'" }
    Click-Node $node
}

function Scroll-Top {
    1..4 | ForEach-Object { Adb-Run shell input swipe 360 400 360 1050 120 | Out-Null }
}

function Scroll-Bottom {
    1..4 | ForEach-Object { Adb-Run shell input swipe 360 1050 360 400 120 | Out-Null }
}

function Capture([string]$Name) {
    Adb-Run shell screencap -p /data/local/tmp/devicesorter-qa.png | Out-Null
    Adb-Run pull /data/local/tmp/devicesorter-qa.png (Join-Path $local "$Name.png") | Out-Null
    (Read-Ui).Save((Join-Path $local "$Name.xml"))
}

# The app now opens on a feature hub. Tests always enter the classification
# feature and pin the single-page view so scroll-based steps remain stable.
function Ensure-Scroll {
    $ui = Read-Ui
    $node = $ui.SelectNodes('//node') | Where-Object { $_.text -eq "切换：单页" } | Select-Object -First 1
    if ($node) { Click-Node $node; Start-Sleep -Milliseconds 600 }
}

function Open-Sort {
    $ui = Read-Ui
    $node = $ui.SelectNodes('//node') | Where-Object {
        $_.text -in @("继续未完成设置", "查看任务进度", "开始设置")
    } | Select-Object -First 1
    if (!$node) { throw "Classification entry was not found on the feature hub." }
    Click-Node $node
    Start-Sleep -Milliseconds 800
    Ensure-Scroll
}

function Select-Device-Rule {
    Scroll-Top
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        $ui = Read-Ui
        $node = $ui.SelectNodes('//node') | Where-Object {
            $_.class -eq "android.widget.Button" -and $_.text -in @("设备 / 拍摄年月", "设备", "拍摄年月", "拍摄日期")
        } | Select-Object -First 1
        if ($node) {
            Click-Node $node
            Click "仅按设备"
            return
        }
        Adb-Run shell input swipe 360 1050 360 400 150 | Out-Null
    }
    throw "Classification strategy control not found"
}

function Choose-Folder([string]$Description, [string[]]$Parts) {
    Scroll-Top
    Click "" $Description
    $roots = Find-Node "" "Show roots"
    if ($roots) { Click-Node $roots }
    $ui = Read-Ui
    $root = $ui.SelectNodes('//node') | Where-Object { $_.text -like "sdk_gphone*" } | Select-Object -First 1
    if ($roots) { Click-Node $root }
    foreach ($part in $Parts) {
        $node = Find-Node $part
        for ($attempt = 0; !$node -and $attempt -lt 6; $attempt++) {
            Adb-Run shell input swipe 360 1000 360 400 180 | Out-Null
            $node = Find-Node $part
        }
        if (!$node) { throw "Folder is not visible in picker: $part" }
        Click-Node $node
    }
    Click "USE THIS FOLDER"
    Click "ALLOW"
}

function Wait-Status([string]$Expected) {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        Scroll-Bottom
        $ui = Read-Ui
        $permission = $ui.SelectNodes('//node') | Where-Object {
            $_.'resource-id' -eq "com.android.permissioncontroller:id/permission_allow_button"
        } | Select-Object -First 1
        if ($permission) { Click-Node $permission; continue }
        $node = $ui.SelectNodes('//node') | Where-Object { $_.text -like "*$Expected*" } | Select-Object -First 1
        if ($node) { return $node.text }
        Scroll-Top
        $ui = Read-Ui
        $node = $ui.SelectNodes('//node') | Where-Object { $_.text -like "*$Expected*" } | Select-Object -First 1
        if ($node) { return $node.text }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for: $Expected"
}

function Hash-Map([string]$Folder) {
    $map = @{}
    Get-ChildItem -LiteralPath $Folder -Recurse -File | ForEach-Object {
        $relative = [IO.Path]::GetRelativePath($Folder, $_.FullName)
        $map[$relative] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    }
    return $map
}

function Pull-Snapshot([string]$Stage) {
    $archive = Join-Path $local "$Stage.tar"
    $folder = Join-Path $local $Stage
    Adb-Run shell "tar -cf /data/local/tmp/devicesorter-workflow.tar -C /sdcard/Documents $run" | Out-Null
    Adb-Run pull /data/local/tmp/devicesorter-workflow.tar $archive | Out-Null
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    Add-Type -AssemblyName System.Formats.Tar
    [System.Formats.Tar.TarFile]::ExtractToDirectory($archive, $folder, $false)
}

function Check-Files([string]$Stage, [int]$OutputCount, [bool]$Moved) {
    $pulled = Join-Path $local "$Stage/$run"
    Pull-Snapshot $Stage
    $actual = Hash-Map (Join-Path $pulled "DCIM")
    foreach ($key in $before.Keys) {
        if ($Moved -and $recognized.Contains($key)) {
            if ($actual.ContainsKey($key)) { throw "Move retained a recognized source: $key" }
        } elseif (!$actual.ContainsKey($key) -or $actual[$key] -ne $before[$key]) {
            throw "Source missing or changed: $key"
        }
    }
    $output = @(Get-ChildItem -LiteralPath (Join-Path $pulled "Pictures/Archive") -Recurse -File)
    if ($Moved) {
        $archives = Get-ChildItem -LiteralPath (Join-Path $pulled "DCIM") -Recurse -Directory |
            Where-Object Name -eq "分类"
        foreach ($archive in $archives) {
            $output += @(Get-ChildItem -LiteralPath $archive.FullName -Recurse -File)
        }
    }
    if ($output.Count -ne $OutputCount) { throw "Expected $OutputCount outputs, got $($output.Count)" }
    $validHashes = @($recognized | ForEach-Object { $before[$_] })
    foreach ($file in $output) {
        if ($file.Name -like "*.partial") { throw "Unexpected partial file: $($file.Name)" }
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        if ($hash -notin $validHashes) { throw "Unexpected output content: $($file.FullName)" }
    }
    foreach ($key in $recognized) {
        if ($before[$key] -notin @($output | ForEach-Object {
            (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        })) { throw "No destination for $key" }
    }
}

foreach ($dir in @("DCIM/Camera", "DCIM/Nested", "Pictures/Archive", "Download",
        "Documents", "Movies", "Music", "Recordings", "backups", "Android/media", "tencent")) {
    New-Item -ItemType Directory -Path (Join-Path $fixture $dir) -Force | Out-Null
}
$recognized = [Collections.Generic.List[string]]::new()
for ($i = 1; $i -le 4; $i++) {
    foreach ($media in @(@("canon.jpg", "canon-$i.jpg"), @("iphone.jpg", "iphone-$i.jpg"),
            @("iphone.mov", "video-$i.mov"))) {
        Copy-Item -LiteralPath (Join-Path $assets $media[0]) -Destination (Join-Path $fixture "DCIM/Camera/$($media[1])")
        $recognized.Add((Join-Path "Camera" $media[1]))
    }
}
Copy-Item -LiteralPath (Join-Path $assets "canon.jpg") -Destination (Join-Path $fixture "DCIM/Nested/canon-1.jpg")
$recognized.Add((Join-Path "Nested" "canon-1.jpg"))
foreach ($name in @("unknown.jpg", "unknown.mp4", "android.mp4")) {
    Copy-Item -LiteralPath (Join-Path $assets $name) -Destination (Join-Path $fixture "DCIM/Camera/$name")
}
Set-Content -LiteralPath (Join-Path $fixture "DCIM/Camera/broken.jpg") -Value "deliberately invalid media" -NoNewline
Set-Content -LiteralPath (Join-Path $fixture "DCIM/Camera/keep.xmp") -Value "sidecar must stay" -NoNewline
$before = Hash-Map (Join-Path $fixture "DCIM")
$before | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $local "source-hashes.json")
Adb-Run push $fixture $remote | Out-Null
foreach ($dir in @("Pictures/Archive", "Download", "Documents", "Movies", "Music",
        "Recordings", "backups", "Android/media", "tencent")) {
    Adb-Run shell "mkdir -p $remote/$dir" | Out-Null
}
Adb-Run shell am force-stop com.google.android.documentsui | Out-Null
Adb-Run shell am force-stop app.devicesorter | Out-Null
Adb-Run shell am start -n app.devicesorter/.MainActivity | Out-Null
Start-Sleep -Seconds 2
Open-Sort

# Remove prior source grants from the app's list through its normal UI.
Scroll-Top
while ($node = Find-Node "" "移除来源目录") { Click-Node $node }
$location = Find-Node "就地分类"
if ($location -and $location.checked -eq "true") { Click-Node $location }
Choose-Folder "添加来源目录" @("Documents", $run, "DCIM")
Choose-Folder "选择目标目录" @("Documents", $run, "DCIM")
if (!(Find-Node "来源和目标不能相同或互相包含，请选择独立目录")) {
    throw "Overlapping source/destination was not rejected"
}
Capture "00-overlap-rejected"
Write-Output "PASS: overlapping directories rejected"
Click "确定"
Choose-Folder "选择目标目录" @("Documents", $run, "Pictures", "Archive")
Click "扫描设备"
$scan = Wait-Status "扫描完成"
if ($scan -notlike "*17 个媒体文件*13 个已识别*1 个非媒体文件*") { throw "Unexpected scan: $scan" }
Select-Device-Rule
Scroll-Bottom
Capture "01-scan"
Write-Output "PASS: scan recognized 13 of 17 media files"

Scroll-Bottom
Click "小批量试跑"
Click "开始"
$trial = Wait-Status "整理结束"
if ($trial -notlike "*成功 9*未完成 0*") { throw "Unexpected trial: $trial" }
# The trial intentionally copies only a subset, so check source hashes and count separately.
Pull-Snapshot "trial"
$trialSources = Hash-Map (Join-Path $local "trial/$run/DCIM")
foreach ($key in $before.Keys) {
    if ($trialSources[$key] -ne $before[$key]) { throw "Trial changed source: $key" }
}
$trialOutputs = @(Get-ChildItem -LiteralPath (Join-Path $local "trial/$run/Pictures/Archive") -Recurse -File)
if ($trialOutputs.Count -ne 9) { throw "Trial must produce 9 copies" }
foreach ($file in $trialOutputs) {
    if ((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -notin $before.Values) {
        throw "Trial destination corrupt"
    }
}
Capture "02-trial"
Write-Output "PASS: trial created 9 copies and preserved every source"

Scroll-Top
Click "扫描设备"
Wait-Status "扫描完成" | Out-Null
Scroll-Bottom
Click "批量复制"
Click "开始"
$copy = Wait-Status "整理结束"
if ($copy -notlike "*成功 13*未完成 0*") { throw "Unexpected copy: $copy" }
Check-Files "copy" 22 $false
Capture "03-copy"
Write-Output "PASS: copy created 13 more copies without overwriting"

Scroll-Top
Click "扫描设备"
Wait-Status "扫描完成" | Out-Null
Scroll-Bottom
Scroll-Top
Click "就地分类"
Scroll-Bottom
Click "系统移动"
Click "批量移动"
Click "开始"
$move = Wait-Status "整理结束"
if ($move -notlike "*成功 13*未完成 0*") { throw "Unexpected move: $move" }
Check-Files "move" 35 $true
Capture "04-move"
Write-Output "PASS: move removed only the 13 verified recognized sources"

Scroll-Top
Click "扫描设备"
$after = Wait-Status "扫描完成"
if ($after -notlike "*4 个媒体文件*0 个已识别*1 个非媒体文件*") {
    throw "Unrecognized files/sidecar were not preserved: $after"
}
Capture "05-rescan"
Adb-Run shell am force-stop app.devicesorter | Out-Null
Adb-Run shell am start -n app.devicesorter/.MainActivity | Out-Null
Start-Sleep -Seconds 2
$restored = Wait-Status "已恢复上次扫描"
Open-Sort
if ((Find-Node "复制，保留原件").checked -ne "true") { throw "Restart did not default to copy mode" }
Capture "06-restart"
$logs = Adb-Run shell "run-as app.devicesorter sh -c 'cat files/logs/*.jsonl'"
$logs | Set-Content -LiteralPath (Join-Path $local "journal.jsonl")
$events = @($logs | Where-Object { $_ -match '^\{' } | ForEach-Object { $_ | ConvertFrom-Json } |
    Where-Object { $_.target -like "*$run*" })
if (@($events | Where-Object status -eq "FAILED").Count -ne 0) { throw "Journal contains failures" }
if (@($events | Where-Object status -eq "NATIVE_MOVED").Count -ne 13) { throw "Expected 13 NATIVE_MOVED events" }
$packagePath = ((Adb-Run shell pm path app.devicesorter) | Select-Object -First 1) -replace '^package:', ''
$installedHash = ((Adb-Run shell sha256sum $packagePath) -split '\s+')[0]
@{
    run = $run; serial = $Serial; scan = $scan; trial = $trial; copy = $copy; move = $move
    rescan = $after; restart = $restored; sourceFiles = $before.Count; outputFiles = 35
    verdict = "PASS"; apkSha256 = $installedHash
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $local "result.json")
Write-Output "PASS: $local"
