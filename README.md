# NEXO - Sistema de Ruteo Inteligente 🚚

Bienvenido a **NEXO**, una plataforma diseñada para optimizar la logística de reparto de PyMEs en Gran Asunción. Este sistema permite gestionar clientes, generar rutas óptimas para múltiples móviles y realizar un seguimiento en tiempo real de las entregas.

---

## 🛠️ Requisitos Previos

Antes de comenzar, asegúrate de tener instalado:
1. **Java JDK 17 o superior**.
2. **PostgreSQL** (el sistema usa el puerto `5000` por defecto).
3. **Navegador Web** (Chrome, Edge o Firefox).

---

## 🚀 Guía de Instalación y Ejecución

Sigue estos pasos en orden para poner en marcha el software:

### 1. Configuración de la Base de Datos
El sistema necesita una base de datos llamada `ruteo_db`. 
1. Abre tu terminal de PostgreSQL y crea la base de datos:
   ```sql
   CREATE DATABASE ruteo_db;
   ```
2. Ve a la carpeta `backend/`.
3. Ejecuta el archivo **`inicializar_db.bat`**. 
   *Esto creará todas las tablas necesarias y configurará el usuario administrador por defecto.*

### 2. Compilación y Arranque del Servidor (Backend)
1. Permanece en la carpeta `backend/`.
2. Ejecuta el archivo **`compilar.bat`**.
   *Este script descargará automáticamente las librerías necesarias (PostgreSQL, Gson, BCrypt), compilará el código Java y levantará el servidor en `http://localhost:8080`.*

### 3. Acceso al Sistema (Frontend)
1. Una vez que el backend esté corriendo (verás un mensaje de "Servidor iniciado"), ve a la carpeta `frontend/`.
2. Abre el archivo **`login.html`** en tu navegador.

---

## 🔑 Credenciales por Defecto

Para el primer acceso, utiliza las siguientes credenciales:
*   **Usuario**: `admin`
*   **Contraseña**: `nexo2025`

> [!IMPORTANT]
> El sistema cerrará la sesión automáticamente tras **5 minutos de inactividad** para proteger la información.

---

## 📂 Estructura del Proyecto

*   `/backend`: Código fuente en Java, librerías (`lib/`) y scripts de automatización (`.bat`).
*   `/frontend`: Interfaz de usuario (HTML, CSS, JS).
    *   `index.html`: Panel principal de gestión.
    *   `seguimiento.html`: Monitoreo en tiempo real por móvil.
    *   `estadisticas.html`: Dashboard de métricas y rendimiento.
*   `/database`: Scripts SQL adicionales y migraciones.

---

## 🌓 Características Especiales

*   **Modo Oscuro**: Activable desde el panel de Configuraciones.
*   **Seguridad**: Contraseñas encriptadas con BCrypt y protección por tokens de sesión.
*   **Integración WhatsApp**: Envío directo de rutas optimizadas a los choferes.

---
*Desarrollado para el proyecto UNIDA - Ruteo Inteligente 2026*
