param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [string[]]$Texts = @(),
    [string]$Description = ""
)

$ErrorActionPreference = "Stop"
function Read-Ui {
    & $Adb -s emulator-5554 shell uiautomator dump /sdcard/Download/sorter-ui.xml | Out-Null
    [xml]$document = (& $Adb -s emulator-5554 shell cat /sdcard/Download/sorter-ui.xml) -join "`n"
    return $document
}

function Click-Node($node) {
    if ($null -eq $node) { throw "Expected UI control was not found" }
    $numbers = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    $x = [int](($numbers[0] + $numbers[2]) / 2)
    $y = [int](($numbers[1] + $numbers[3]) / 2)
    & $Adb -s emulator-5554 shell input tap $x $y
}

if ($Description) {
    $ui = Read-Ui
    Click-Node ($ui.SelectNodes('//node') | Where-Object { $_.'content-desc' -eq $Description } | Select-Object -First 1)
}
foreach ($text in $Texts) {
    $ui = Read-Ui
    Click-Node ($ui.SelectNodes('//node') | Where-Object { $_.text -eq $text } | Select-Object -First 1)
}
$ui = Read-Ui
$ui.SelectNodes('//node[@text!="" or @content-desc!=""]') |
    Select-Object text, content-desc, bounds | Format-Table -AutoSize
