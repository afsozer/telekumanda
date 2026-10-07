<#
.SYNOPSIS
    Gorseldeki metni Windows'un YERLESIK OCR motoruyla okur (yerel, aga cikmaz,
    anahtar istemez).

.DESCRIPTION
    Gorme yetenegi olmayan modeller icin "ekran goruntusunu okuma" kanali.
    Vision destegi olmayan bir model gorsel eki aldiginda arac katmani gorseli
    duzgun iletse bile model onu goremez; bu betik gorseldeki METNI cikarir.

    Windows.Media.Ocr WinRT API'sini kullanir. Bu tipler YALNIZ Windows
    PowerShell 5.1'de projekte edilir; PowerShell 7 altinda calistirilirsa betik
    kendini 5.1 ile yeniden baslatir (canli vakada PS7 "Unable to find type
    [Windows.Media.Ocr.OcrEngine]" ile patlamisti).

    Aciklama/yorum degil, DUZ METIN dondurur — "bu gorselde ne var" sorusu icin
    vision destekli bir modele ihtiyac vardir.

.PARAMETER Path
    Bir veya daha fazla gorsel dosyasi (.png/.jpg/.jpeg/.bmp/.gif/.tif).

.PARAMETER Language
    Opsiyonel BCP-47 dil etiketi (or. "tr-TR", "en-US"). Verilmezse kullanicinin
    profil dilleri denenir.

.PARAMETER WithBoxes
    Her satirin onune piksel kutusunu koyar: "x,y,g,y<TAB>metin". Ekran duzeni
    sorularinda (hangi cip secili, hangi tus nerede, ust/alt cubuk hangisi)
    duz metin yetmiyor — canlida bir model tam da bunun icin kendi WinRT
    betigini yazmaya kalkip dakikalarca ugrasti.

.EXAMPLE
    .\ocr.ps1 -Path "C:\...\ekran.jpg"
    .\ocr.ps1 -Path "C:\...\ekran.jpg" -WithBoxes
    .\ocr.ps1 -Path a.png, b.png -Language tr-TR
#>
param(
    [Parameter(Mandatory = $true, Position = 0)][string[]]$Path,
    [string]$Language = '',
    [switch]$WithBoxes
)

$ErrorActionPreference = 'Stop'

# PS7'de WinRT tip projeksiyonu yok — kendimizi 5.1 ile yeniden calistiriyoruz.
if ($PSVersionTable.PSEdition -eq 'Core') {
    $ps5 = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $ps5)) { Write-Error 'Windows PowerShell 5.1 bulunamadi; WinRT OCR kullanilamaz.'; exit 1 }
    $argv = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath) + $Path
    if ($Language) { $argv += @('-Language', $Language) }
    if ($WithBoxes) { $argv += '-WithBoxes' }
    & $ps5 @argv
    exit $LASTEXITCODE
}

Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Globalization.Language, Windows.Foundation, ContentType = WindowsRuntime]

# WinRT IAsyncOperation<T> -> Task<T> koprusu: PowerShell await bilmiyor.
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]

function Wait-WinRt($operation, [Type]$resultType) {
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($operation))
    $task.Wait(-1) | Out-Null
    $task.Result
}

$engine = if ($Language) {
    [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new($Language))
} else {
    [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
}
if (-not $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('en-US')) }
if (-not $engine) { Write-Error 'OCR motoru olusturulamadi (yuklu dil paketi yok).'; exit 1 }

$multi = $Path.Count -gt 1
foreach ($p in $Path) {
    if (-not (Test-Path -LiteralPath $p)) { Write-Error "Dosya yok: $p"; continue }
    $full = (Resolve-Path -LiteralPath $p).ProviderPath
    if ($multi) { Write-Output "=== $full" }

    $file = Wait-WinRt ([Windows.Storage.StorageFile]::GetFileFromPathAsync($full)) ([Windows.Storage.StorageFile])
    $stream = Wait-WinRt ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    try {
        $decoder = Wait-WinRt ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Wait-WinRt ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        try {
            $result = Wait-WinRt ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
            if ($WithBoxes) { Write-Output "# $($decoder.PixelWidth)x$($decoder.PixelHeight) px" }
            # Satir sirasi okuma sirasidir; bosluklu tek metin yerine satirlari
            # ayri veriyoruz ki ekran duzeni (basliklar, tuslar) ayirt edilebilsin.
            foreach ($line in $result.Lines) {
                if (-not $WithBoxes) { Write-Output $line.Text; continue }
                # Satirin kutusu = kelime kutularinin birlesimi (WinRT satir kutusu vermiyor).
                $rects = @($line.Words | ForEach-Object { $_.BoundingRect })
                if (-not $rects.Count) { Write-Output "`t$($line.Text)"; continue }
                $x1 = ($rects | ForEach-Object { $_.X } | Measure-Object -Minimum).Minimum
                $y1 = ($rects | ForEach-Object { $_.Y } | Measure-Object -Minimum).Minimum
                $x2 = ($rects | ForEach-Object { $_.X + $_.Width } | Measure-Object -Maximum).Maximum
                $y2 = ($rects | ForEach-Object { $_.Y + $_.Height } | Measure-Object -Maximum).Maximum
                $box = '{0},{1},{2},{3}' -f [int]$x1, [int]$y1, [int]($x2 - $x1), [int]($y2 - $y1)
                Write-Output "$box`t$($line.Text)"
            }
        } finally { $bitmap.Dispose() }
    } finally { $stream.Dispose() }
}
