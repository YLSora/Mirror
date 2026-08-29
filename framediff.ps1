param([string]$Path1,[string]$Path2,[int]$Step=2)
Add-Type -AssemblyName System.Drawing
$b1 = New-Object System.Drawing.Bitmap $Path1
$b2 = New-Object System.Drawing.Bitmap $Path2
$w = [Math]::Min($b1.Width,$b2.Width); $h = [Math]::Min($b1.Height,$b2.Height)
$sum=0.0; $n=0; $changed=0; $strong=0
for($y=0;$y -lt $h;$y+=$Step){ for($x=0;$x -lt $w;$x+=$Step){
  $pa=$b1.GetPixel($x,$y); $pb=$b2.GetPixel($x,$y); $n++
  $d=[Math]::Abs($pa.R-$pb.R)+[Math]::Abs($pa.G-$pb.G)+[Math]::Abs($pa.B-$pb.B)
  $sum+=$d; if($d -gt 12){$changed++}; if($d -gt 60){$strong++}
}}
$mean=$sum/$n
$n1=Split-Path $Path1 -Leaf; $n2=Split-Path $Path2 -Leaf
Write-Output ("{0} vs {1}: {2}x{3} meanAbsDiff={4:F2} changedPct={5:F2} strongPct={6:F2}" -f $n1,$n2,$w,$h,$mean,(100.0*$changed/$n),(100.0*$strong/$n))
$b1.Dispose(); $b2.Dispose()
