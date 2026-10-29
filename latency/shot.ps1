param([int]$n=5, [string]$dir = (Join-Path $PSScriptRoot "shots"))
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public class W { [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h,int x,int y,int w,int ht,bool r);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h,int c);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h,IntPtr a,int x,int y,int cx,int cy,uint f);
 [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }
"@
[W]::SetProcessDPIAware() | Out-Null
$scr=[System.Windows.Forms.Screen]::PrimaryScreen.Bounds
$W=$scr.Width; $H=$scr.Height-48
$o=Get-Process obs64 | Select -First 1
[W]::ShowWindow($o.MainWindowHandle,1) | Out-Null
[W]::MoveWindow($o.MainWindowHandle,0,0,[int]($W*0.62),$H,$true) | Out-Null
$clk=Get-Process msedge -ErrorAction SilentlyContinue | ? {$_.MainWindowTitle -like '*latency clock*'} | Select -First 1
if($clk){ [W]::SetWindowPos($clk.MainWindowHandle,[IntPtr](-1),[int]($W*0.62),0,[int]($W*0.38),$H,0x40) | Out-Null } else { "clock window not found" }
Start-Sleep 3
New-Item -ItemType Directory -Force $dir | Out-Null
for($i=1;$i -le $n;$i++){
 $b=New-Object System.Drawing.Bitmap $scr.Width,$scr.Height
 $g=[System.Drawing.Graphics]::FromImage($b); $g.CopyFromScreen(0,0,0,0,$b.Size)
 $b.Save("$dir\shot$i.png",[System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $b.Dispose()
 Start-Sleep -Milliseconds 700 }
"$($scr.Width)x$($scr.Height) saved $n"
