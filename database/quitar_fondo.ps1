Add-Type -AssemblyName System.Drawing
$path = "c:\Users\domin\OneDrive\Desktop\software de ruteo 2\frontend\assets\img\LogoNexo.png"
$tmpPath = "c:\Users\domin\OneDrive\Desktop\software de ruteo 2\frontend\assets\img\LogoNexo_tmp.png"

$img = [System.Drawing.Image]::FromFile($path)
$bmp = New-Object System.Drawing.Bitmap($img.Width, $img.Height)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.DrawImage($img, 0, 0)

# Hacer transparente el blanco
$bmp.MakeTransparent([System.Drawing.Color]::White)

$bmp.Save($tmpPath, [System.Drawing.Imaging.ImageFormat]::Png)

$img.Dispose()
$bmp.Dispose()
$g.Dispose()

Move-Item -Path $tmpPath -Destination $path -Force
Write-Host "✅ Fondo eliminado con éxito."
