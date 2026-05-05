# Script de Sincronizacion Automatica para Git
Write-Host "Iniciando sincronizacion automatica cada 60 segundos..." -ForegroundColor Cyan

while ($true) {
    # 1. Intentar traer cambios remotos
    Write-Host "Revisando cambios remotos..." -ForegroundColor Gray
    git pull origin main --quiet

    # 2. Revisar si hay cambios locales
    $status = git status --porcelain
    if ($status) {
        Write-Host "Cambios detectados. Sincronizando..." -ForegroundColor Yellow
        git add .
        git commit -m "Auto-sync: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" --quiet
        git push origin main --quiet
        Write-Host "Sincronizacion completada con exito." -ForegroundColor Green
    }
    else {
        Write-Host "Sin cambios locales." -ForegroundColor Gray
    }

    # Esperar 60 segundos antes de la proxima revision
    Start-Sleep -Seconds 30
}
