# Script de Sincronizacion Automatica para Git
Write-Host "Iniciando sincronizacion automatica cada 30 segundos en rama DESARROLLO..." -ForegroundColor Cyan

while ($true) {
    # 1. Intentar traer cambios remotos de la rama de desarrollo
    Write-Host "Revisando cambios remotos (desarrollo)..." -ForegroundColor Gray
    git pull origin desarrollo --quiet

    # 2. Revisar si hay cambios locales
    $status = git status --porcelain
    if ($status) {
        Write-Host "Cambios detectados. Sincronizando en DESARROLLO..." -ForegroundColor Yellow
        git add .
        git commit -m "Auto-sync Desarrollo: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" --quiet
        git push origin desarrollo --quiet
        Write-Host "Sincronizacion en DESARROLLO completada con exito." -ForegroundColor Green
    }
    else {
        Write-Host "Sin cambios locales en desarrollo." -ForegroundColor Gray
    }

    # Esperar 30 segundos antes de la proxima revision (segun tu preferencia)
    Start-Sleep -Seconds 30
}
