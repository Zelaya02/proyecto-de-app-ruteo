import java.sql.*;

public class InitDatabase {
    public static void main(String[] args) {
        // Probamos los puertos comunes
        int[] ports = {5432, 5000}; 
        String dbName = "ruteo_db";
        String user = "postgres";
        String pass = "Zelaya1103";
        
        Connection rootConn = null;
        int activePort = -1;

        for (int port : ports) {
            try {
                String url = "jdbc:postgresql://localhost:" + port + "/postgres";
                rootConn = DriverManager.getConnection(url, user, pass);
                activePort = port;
                System.out.println("Conectado exitosamente al puerto: " + port);
                break;
            } catch (SQLException e) {
                System.out.println("No se pudo conectar al puerto " + port + ": " + e.getMessage());
            }
        }

        if (rootConn == null) {
            System.err.println("ERROR: No se pudo conectar a PostgreSQL en los puertos 5432 o 5000. Verifica que PostgreSQL este corriendo.");
            return;
        }

        try (Statement stmt = rootConn.createStatement()) {
            // 1. Crear base de datos
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + dbName); // Opcional: Limpiar si quieres empezar de cero
            stmt.executeUpdate("CREATE DATABASE " + dbName);
            System.out.println("Base de datos '" + dbName + "' creada.");
            rootConn.close();

            // 2. Conectar a la nueva DB y crear tablas
            String dbUrl = "jdbc:postgresql://localhost:" + activePort + "/" + dbName;
            try (Connection conn = DriverManager.getConnection(dbUrl, user, pass);
                 Statement dbStmt = conn.createStatement()) {
                
                System.out.println("Creando tablas...");
                
                dbStmt.executeUpdate("CREATE TABLE usuarios (id SERIAL PRIMARY KEY, username TEXT UNIQUE, password TEXT, nombre TEXT, rol TEXT)");
                dbStmt.executeUpdate("CREATE TABLE clientes (id SERIAL PRIMARY KEY, nombre TEXT, tipo_cliente TEXT, latitud DOUBLE PRECISION, longitud DOUBLE PRECISION, ciudad TEXT, cadena TEXT, activo BOOLEAN DEFAULT TRUE)");
                dbStmt.executeUpdate("CREATE TABLE rutas_generadas (token TEXT PRIMARY KEY, movil_numero INTEGER, clientes_json TEXT, distancia_total DOUBLE PRECISION, tiempo_estimado INTEGER, fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                dbStmt.executeUpdate("CREATE TABLE entregas (id SERIAL PRIMARY KEY, ruta_token TEXT REFERENCES rutas_generadas(token), cliente_id INTEGER REFERENCES clientes(id), estado TEXT, observacion TEXT, orden_en_ruta INTEGER, fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                dbStmt.executeUpdate("CREATE TABLE reglas_ruteo (id SERIAL PRIMARY KEY, categoria TEXT UNIQUE, limite_por_movil INTEGER, activo BOOLEAN DEFAULT TRUE)");
                
                // Usuario inicial
                dbStmt.executeUpdate("INSERT INTO usuarios (username, password, nombre, rol) VALUES ('admin', 'admin', 'Administrador', 'admin')");
                
                System.out.println("✅ Estructura de base de datos inicializada con exito.");
            }
        } catch (SQLException e) {
            System.err.println("Error durante la inicializacion: " + e.getMessage());
        }
    }
}
