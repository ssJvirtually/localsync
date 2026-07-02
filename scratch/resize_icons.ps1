Add-Type -AssemblyName System.Drawing

function Resize-Image {
    param (
        [string]$SourcePath,
        [string]$TargetPath,
        [int]$Width,
        [int]$Height
    )
    $src = [System.Drawing.Image]::FromFile($SourcePath)
    $dest = New-Object System.Drawing.Bitmap($Width, $Height)
    $g = [System.Drawing.Graphics]::FromImage($dest)
    
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    
    $g.DrawImage($src, 0, 0, $Width, $Height)
    
    # Ensure parent directory exists
    $parentDir = Split-Path -Path $TargetPath
    if (!(Test-Path -Path $parentDir)) {
        New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
    }
    
    $dest.Save($TargetPath, [System.Drawing.Imaging.ImageFormat]::Png)
    
    $g.Dispose()
    $dest.Dispose()
    $src.Dispose()
    Write-Host "Resized and saved: $TargetPath ($Width x $Height)"
}

$source = "C:\Users\jskr4\Downloads\28262df9-b9c0-4188-9c53-59ddaeb45e7e.png"

# Resize Desktop icon.png
$desktopPng = "E:\localsync\desktop\src\main\resources\icon.png"
Resize-Image -SourcePath $source -TargetPath $desktopPng -Width 256 -Height 256

# Write desktop/src/main/resources/icon.ico using the 256x256 PNG
$pngBytes = [System.IO.File]::ReadAllBytes($desktopPng)
$pngSize = $pngBytes.Length

# Create 22-byte ICO header for a single 256x256 image
$icoHeader = New-Object byte[] 22
$icoHeader[0] = 0   # Reserved
$icoHeader[1] = 0
$icoHeader[2] = 1   # Type (1 = ICO)
$icoHeader[3] = 0
$icoHeader[4] = 1   # Image count (1)
$icoHeader[5] = 0
$icoHeader[6] = 0   # Width (0 means 256)
$icoHeader[7] = 0   # Height (0 means 256)
$icoHeader[8] = 0   # Color count (0)
$icoHeader[9] = 0   # Reserved
$icoHeader[10] = 1  # Color planes (1)
$icoHeader[11] = 0
$icoHeader[12] = 32 # Bits per pixel (32)
$icoHeader[13] = 0

# Image size (4 bytes, little endian)
$icoHeader[14] = ($pngSize -band 0xFF)
$icoHeader[15] = (($pngSize -shr 8) -band 0xFF)
$icoHeader[16] = (($pngSize -shr 16) -band 0xFF)
$icoHeader[17] = (($pngSize -shr 24) -band 0xFF)

# Image offset (4 bytes, little-endian, always 22 bytes = 0x16)
$icoHeader[18] = 22
$icoHeader[19] = 0
$icoHeader[20] = 0
$icoHeader[21] = 0

# Write header + png data to icon.ico
$desktopIco = "E:\localsync\desktop\src\main\resources\icon.ico"
$stream = New-Object System.IO.FileStream($desktopIco, [System.IO.FileMode]::Create)
$stream.Write($icoHeader, 0, $icoHeader.Length)
$stream.Write($pngBytes, 0, $pngBytes.Length)
$stream.Close()
Write-Host "Created ICO file: $desktopIco"

# Resize Android icons
$androidRes = "E:\localsync\android\app\src\main\res"
$sizes = @{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
}

foreach ($folder in $sizes.Keys) {
    $dim = $sizes[$folder]
    $outPath = Join-Path -Path $androidRes -ChildPath "$folder\ic_launcher.png"
    Resize-Image -SourcePath $source -TargetPath $outPath -Width $dim -Height $dim
    
    $outPathRound = Join-Path -Path $androidRes -ChildPath "$folder\ic_launcher_round.png"
    Resize-Image -SourcePath $source -TargetPath $outPathRound -Width $dim -Height $dim
}
