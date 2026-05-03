@echo off
echo ========================================
echo Inicializando Base de Datos NEXO
echo ========================================
echo.

REM Verificar driver
if not exist "lib\postgresql-42.6.0.jar" (
    echo ERROR: No se encuentra el driver de PostgreSQL en lib/
    pause
    exit /b 1
)

REM Compilar
echo Compilando script de creacion...
javac -cp "lib/*" CreacionDB.java
if %errorlevel% neq 0 (
    echo ERROR en la compilacion.
    pause
    exit /b 1
)

REM Ejecutar
echo Ejecutando script...
java -cp ".;lib/*" CreacionDB
if %errorlevel% neq 0 (
    echo ERROR en la ejecucion.
    pause
    exit /b 1
)

echo.
echo ========================================
echo ✅ Proceso finalizado correctamente
echo ========================================
pause
