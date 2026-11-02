param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [Parameter(Mandatory = $true)][string]$Serial
)

# Real-device UI helpers. Guard rail: never accept an emulator serial here.
if ($Serial -match '^emulator-\d+$') { throw "This helper is for physical devices only." }
$ErrorActionPreference = "Stop"

function Adb-Run {
    $result = & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $args" }
    return $result
}

function Ui {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $dump = (Adb-Run shell uiautomator dump /data/local/tmp/real-qa.xml) -join "`n"
        if ($dump -like "*dumped to*") { break }
        Start-Sleep -Milliseconds 500
    }
    if ($dump -notlike "*dumped to*") { throw "Could not get a stable UI hierarchy: $dump" }
    [xml]$result = (Adb-Run shell cat /data/local/tmp/real-qa.xml) -join "`n"
    return $result
}

function Nodes { return (Ui).SelectNodes("//node") }

function Find([string]$Text, [string]$Description = "", [string]$Id = "") {
    return Nodes | Where-Object {
        if ($Id) { $_.'resource-id' -like "*$Id*" }
        elseif ($Description) { $_.'content-desc' -eq $Description }
        else { $_.text -eq $Text }
    } | Select-Object -Last 1
}

function Tap($Node) {
    if (!$Node) { throw "Expected UI control not found" }
    $n = [regex]::Matches($Node.bounds, '\d+') | ForEach-Object { [int]::Parse($_.Value) }
    Adb-Run shell input tap ([int](($n[0] + $n[2]) / 2)) ([int](($n[1] + $n[3]) / 2)) | Out-Null
}

function Click([string]$Text, [string]$Description = "", [string]$Id = "") {
    Tap (Find $Text $Description $Id)
}

function Screen { return (& $Adb -s $Serial shell "wm size" | Out-String).Trim() }

function SwipeUp {
    $size = (Adb-Run shell "wm size") -join ""
    $m = [regex]::Match($size, '(\d+)x(\d+)')
    $w = [int]$m.Groups[1].Value; $h = [int]$m.Groups[2].Value
    Adb-Run shell input swipe ([int]($w / 2)) ([int]($h * 0.75)) ([int]($w / 2)) ([int]($h * 0.3)) 150 | Out-Null
}

function SwipeDown {
    $size = (Adb-Run shell "wm size") -join ""
    $m = [regex]::Match($size, '(\d+)x(\d+)')
    $w = [int]$m.Groups[1].Value; $h = [int]$m.Groups[2].Value
    Adb-Run shell input swipe ([int]($w / 2)) ([int]($h * 0.3)) ([int]($w / 2)) ([int]($h * 0.75)) 150 | Out-Null
}

function Capture([string]$Path) {
    Adb-Run shell "screencap -p /data/local/tmp/real-qa.png" | Out-Null
    Adb-Run pull /data/local/tmp/real-qa.png $Path | Out-Null
}

function Dump-Text {
    Nodes | Where-Object { $_.text -ne "" -or $_.'content-desc' -ne "" } | ForEach-Object {
        "{0,-50} | {1,-34} | {2}" -f $_.text, $_.'content-desc', $_.bounds
    }
}