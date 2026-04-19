$ErrorActionPreference = "Stop"

$kmlPath = "c:\Users\Admin\OneDrive\Escritorio\UNIDA\proyecto de app ruteo\database\Mapa de entregas.kml"
$outPath = "c:\Users\Admin\OneDrive\Escritorio\UNIDA\proyecto de app ruteo\database\import.sql"

$xml = [xml](Get-Content $kmlPath -Raw -Encoding UTF8)
$ns = @{kml="http://www.opengis.net/kml/2.2"}
$placemarks = Select-Xml -Xml $xml -XPath "//kml:Placemark" -Namespace $ns

"TRUNCATE TABLE clientes RESTART IDENTITY;" | Out-File $outPath -Encoding UTF8

$count = 0
foreach ($pm in $placemarks) {
    $name = $pm.Node.name
    if ($null -ne $name) {
        $name = $name -replace "'", "''"
    } else {
        $name = "Sin nombre"
    }
    
    $coords = $pm.Node.Point.coordinates
    if ($null -ne $coords) {
        $coords = $coords.Trim()
        $parts = $coords -split ','
        if ($parts.Length -ge 2) {
            $lon = $parts[0].Trim()
            $lat = $parts[1].Trim()
            
            # Formatear números para asegurar que usan punto como decimal
            $lat = $lat -replace ",", "."
            $lon = $lon -replace ",", "."
            
            $sql = "INSERT INTO clientes (nombre, latitud, longitud, activo) VALUES ('$name', $lat, $lon, true);"
            $sql | Out-File $outPath -Append -Encoding UTF8
            $count++
        }
    }
}

Write-Host "Procesados $count clientes. Script SQL generado en $outPath"
