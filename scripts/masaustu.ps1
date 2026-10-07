# Masaüstü kontrol yardımcısı — Windows oturumunu görsel olarak sür.
# Ekran görüntüsü, pencere yakalama, fare/klavye enjeksiyonu. Tek çağrı = tek eylem
# (PowerShell süreç-doğurma maliyeti operasyonun kendisinden büyük; her eylemi
# bağımsız ve hızlı tut).
#
# Kilit ekranı (secure desktop) erişilemez: yakalama "geçersiz işleyici" verir,
# girdi Default masaüstünde kalır. Bu durumda anlamlı hata döner. Makinenin
# kilitlenmemesi için ekran koruyucu kapatıldı (bkz. masaustu skill notları).
#
# Kullanım:
#   masaustu.ps1 screenshot [-Out yol]
#   masaustu.ps1 window -Title <parça> [-Out yol]     # görünmese/arkada olsa da yakalar
#   masaustu.ps1 windows                               # görünür pencereleri + konumları listele
#   masaustu.ps1 click -X <n> -Y <n> [-Right] [-Double]
#   masaustu.ps1 move  -X <n> -Y <n>
#   masaustu.ps1 type  -Text "<metin>"
#   masaustu.ps1 key   -Keys "^s"  |  "{ENTER}"  |  "%{F4}"   (SendKeys sözdizimi)
#   masaustu.ps1 focus -Title <parça>
#   masaustu.ps1 crop  -In <png> -X <n> -Y <n> -W <n> -H <n> [-Out yol]
#   masaustu.ps1 monitoron

param(
  [Parameter(Position = 0)][string]$Action = "screenshot",
  [string]$Out,
  [string]$Title,
  [string]$In,
  [string]$Text,
  [string]$Keys,
  [int]$X,
  [int]$Y,
  [int]$W,
  [int]$H,
  [int]$Amount,
  [switch]$Right,
  [switch]$Double,
  [switch]$Literal,
  [switch]$Horizontal
)

Add-Type -AssemblyName System.Drawing, System.Windows.Forms
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class Md {
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, IntPtr e);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
  [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
  public delegate bool EnumProc(IntPtr h, IntPtr p);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr pid);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  public const uint LEFTDOWN=0x0002, LEFTUP=0x0004, RIGHTDOWN=0x0008, RIGHTUP=0x0010;
  public const uint WHEEL=0x0800, HWHEEL=0x01000;
  // Windows odak-çalma korumasını AttachThreadInput ile aş: hedef pencerenin GUI
  // thread'ine bağlan, öne getir, çöz. SetForegroundWindow tek başına çoğu zaman
  // reddedilir (çağıran son girdi olayına sahip değilse).
  public static bool ForceForeground(IntPtr h) {
    uint fg = GetWindowThreadProcessId(h, IntPtr.Zero);
    uint me = GetCurrentThreadId();
    AttachThreadInput(me, fg, true);
    ShowWindow(h, 9); // SW_RESTORE
    BringWindowToTop(h);
    bool ok = SetForegroundWindow(h);
    AttachThreadInput(me, fg, false);
    return ok;
  }
}
"@

$vs = [System.Windows.Forms.SystemInformation]::VirtualScreen

# Eyleme geçmeden önce hedef pencereyi öne getir (aynı süreç içinde, odak yarışı olmasın).
function Ensure-Focus([string]$sub) {
  if (-not $sub) { return $true }
  $m = Find-Window $sub
  if ($m.Count -eq 0) { Write-Output "HATA: '$sub' iceren pencere yok"; return $false }
  [Md]::ForceForeground($m[0].H) | Out-Null
  Start-Sleep -Milliseconds 250
  return $true
}

function Find-Window([string]$sub) {
  $matches = New-Object System.Collections.ArrayList
  $cb = [Md+EnumProc]{
    param($h, $p)
    if ([Md]::IsWindowVisible($h)) {
      $sb = New-Object System.Text.StringBuilder 512
      [Md]::GetWindowText($h, $sb, 512) | Out-Null
      $t = $sb.ToString()
      if ($t -and $t -like "*$sub*") { [void]$matches.Add([pscustomobject]@{ H = $h; T = $t }) }
    }
    return $true
  }
  [Md]::EnumWindows($cb, [IntPtr]::Zero) | Out-Null
  return $matches
}

function Ensure-OutDir($path) {
  $dir = Split-Path -Parent $path
  if ($dir -and -not (Test-Path -LiteralPath $dir)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
  }
}

function Capture-Rect($x, $y, $w, $h, $path) {
  Ensure-OutDir $path
  $bmp = New-Object System.Drawing.Bitmap $w, $h
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  # Kilit ekraninda patlayan tek cagri bu. Save'in hatasi disk kaynaklidir;
  # ikisini ayni catch'e koyarsak eksik klasor "makine kilitli" diye raporlanir
  # ve yanlis teshise goturur (canli goruldu).
  try { $g.CopyFromScreen($x, $y, 0, 0, (New-Object System.Drawing.Size $w, $h)) }
  catch { $g.Dispose(); $bmp.Dispose(); throw "KILIT" }
  $g.Dispose()
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
}

switch ($Action.ToLower()) {

  "screenshot" {
    if (-not $Out) { $Out = "$env:TEMP\masaustu.png" }
    try { Capture-Rect $vs.X $vs.Y $vs.Width $vs.Height $Out }
    catch {
      if ($_.Exception.Message -eq "KILIT") {
        Write-Output "KILITLI: ekran yakalanamadi (secure desktop). Makine kilitli olabilir."
        exit 3
      }
      Write-Output "HATA: ekran kaydedilemedi -> $($_.Exception.Message)"
      exit 1
    }
    Write-Output "OK screenshot -> $Out  ($($vs.Width)x$($vs.Height), origin $($vs.X),$($vs.Y))"
  }

  "window" {
    if (-not $Title) { Write-Output "HATA: -Title gerekli"; exit 1 }
    $m = Find-Window $Title
    if ($m.Count -eq 0) { Write-Output "HATA: '$Title' iceren gorunur pencere yok"; exit 1 }
    $win = $m[0]
    $r = New-Object Md+RECT; [Md]::GetWindowRect($win.H, [ref]$r) | Out-Null
    $w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
    if ($w -le 0 -or $h -le 0) { Write-Output "HATA: pencere olculemedi (simge durumunda olabilir)"; exit 1 }
    if (-not $Out) { $Out = "$env:TEMP\masaustu-win.png" }
    Ensure-OutDir $Out
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $dc = $g.GetHdc(); [Md]::PrintWindow($win.H, $dc, 2) | Out-Null; $g.ReleaseHdc($dc)
    $g.Dispose(); $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
    Write-Output "OK window '$($win.T)' -> $Out  (${w}x${h} @ $($r.Left),$($r.Top))"
  }

  "windows" {
    Find-Window "" | Where-Object { $_.T.Trim() } | ForEach-Object {
      $r = New-Object Md+RECT; [Md]::GetWindowRect($_.H, [ref]$r) | Out-Null
      "{0,-45} {1},{2} {3}x{4}" -f $_.T.Substring(0, [Math]::Min(45, $_.T.Length)),
        $r.Left, $r.Top, ($r.Right - $r.Left), ($r.Bottom - $r.Top)
    }
  }

  "click" {
    if (-not (Ensure-Focus $Title)) { exit 1 }
    [Md]::SetCursorPos($X, $Y) | Out-Null
    Start-Sleep -Milliseconds 60
    if ($Right) { $down = [Md]::RIGHTDOWN; $up = [Md]::RIGHTUP } else { $down = [Md]::LEFTDOWN; $up = [Md]::LEFTUP }
    [Md]::mouse_event($down, 0, 0, 0, [IntPtr]::Zero); [Md]::mouse_event($up, 0, 0, 0, [IntPtr]::Zero)
    if ($Double) {
      Start-Sleep -Milliseconds 60
      [Md]::mouse_event($down, 0, 0, 0, [IntPtr]::Zero); [Md]::mouse_event($up, 0, 0, 0, [IntPtr]::Zero)
    }
    Write-Output "OK click $X,$Y$(if($Right){' (sag)'})$(if($Double){' (cift)'})"
  }

  "move" {
    [Md]::SetCursorPos($X, $Y) | Out-Null
    Write-Output "OK move $X,$Y"
  }

  # Tekerlek: PageUp/Down tuşlarından stabil — odak/pencere durumunu bozmuyor,
  # imlecin altındaki panele gider (yan panelleri de kaydırabilirsin).
  # -Amount: pozitif = yukarı, negatif = aşağı (tık sayısı, 1 tık = 120 birim).
  "scroll" {
    if (-not (Ensure-Focus $Title)) { exit 1 }
    if ($X -or $Y) { [Md]::SetCursorPos($X, $Y) | Out-Null; Start-Sleep -Milliseconds 80 }
    if ($Amount -eq 0) { $Amount = -3 }
    $dir = if ($Horizontal) { [Md]::HWHEEL } else { [Md]::WHEEL }
    # mouse_event delta'yı uint32 alır; aşağı kaydırma -120'nin işaretsiz
    # karşılığıdır (2^32-120). Doğrudan -120 vermek dönüşümde patlar.
    $delta = if ($Amount -gt 0) { [uint32]120 } else { [uint32]4294967176 }
    for ($i = 0; $i -lt [Math]::Abs($Amount); $i++) {
      [Md]::mouse_event($dir, 0, 0, $delta, [IntPtr]::Zero)
      Start-Sleep -Milliseconds 40
    }
    Write-Output "OK scroll $Amount tik$(if($Horizontal){' (yatay)'})$(if($X -or $Y){" @ $X,$Y"})"
  }

  "type" {
    if ($null -eq $Text) { Write-Output "HATA: -Text gerekli"; exit 1 }
    if (-not (Ensure-Focus $Title)) { exit 1 }
    if ($Literal) {
      # SendKeys metakarakterleri: + ^ % ~ ( ) { } [ ]  — hepsi süslü içine alınır.
      # URL'ler (%20), formüller, parolalar bunlarsız SendWait'te patlar.
      $Text = [regex]::Replace($Text, '[+^%~(){}\[\]]', { param($m) '{' + $m.Value + '}' })
    }
    [System.Windows.Forms.SendKeys]::SendWait($Text)
    Write-Output "OK type ($($Text.Length) karakter)$(if($Title){" -> '$Title'"})"
  }

  "key" {
    if (-not $Keys) { Write-Output "HATA: -Keys gerekli (orn '^s', '{ENTER}')"; exit 1 }
    if (-not (Ensure-Focus $Title)) { exit 1 }
    [System.Windows.Forms.SendKeys]::SendWait($Keys)
    Write-Output "OK key $Keys$(if($Title){" -> '$Title'"})"
  }

  "focus" {
    if (-not $Title) { Write-Output "HATA: -Title gerekli"; exit 1 }
    $m = Find-Window $Title
    if ($m.Count -eq 0) { Write-Output "HATA: '$Title' iceren pencere yok"; exit 1 }
    [Md]::ShowWindow($m[0].H, 9) | Out-Null   # SW_RESTORE
    [Md]::SetForegroundWindow($m[0].H) | Out-Null
    Write-Output "OK focus '$($m[0].T)'"
  }

  "crop" {
    if (-not $In) { Write-Output "HATA: -In gerekli"; exit 1 }
    if (-not $Out) { $Out = "$env:TEMP\masaustu-crop.png" }
    Ensure-OutDir $Out
    $src = [System.Drawing.Image]::FromFile($In)
    $b = New-Object System.Drawing.Bitmap $W, $H
    $g = [System.Drawing.Graphics]::FromImage($b)
    $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0, 0, $W, $H),
      (New-Object System.Drawing.Rectangle $X, $Y, $W, $H), [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose(); $b.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png); $b.Dispose(); $src.Dispose()
    Write-Output "OK crop -> $Out  (${W}x${H} @ $X,$Y)"
  }

  "monitoron" {
    [Md]::SendMessage([IntPtr]0xFFFF, 0x0112, [IntPtr]0xF170, [IntPtr](-1)) | Out-Null
    Start-Sleep -Milliseconds 300
    Write-Output "OK monitoron"
  }

  default { Write-Output "Bilinmeyen eylem: $Action" }
}
