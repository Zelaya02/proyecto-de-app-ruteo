import java.sql.*;
import java.nio.file.*;
import java.util.List;
import java.io.*;

public class CreacionDB {
    public static void main(String[] args) {
        int port = 5432; 
        String dbName = "ruteo_db";
        String user = "postgres";
        String pass = "Zelaya1103";
        
        System.out.println("Iniciando creacion de Base de Datos...");

        try {
            String rootUrl = "jdbc:postgresql://localhost:" + port + "/postgres";
            try (Connection rootConn = DriverManager.getConnection(rootUrl, user, pass);
                 Statement stmt = rootConn.createStatement()) {
                
                stmt.executeUpdate("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
                stmt.executeUpdate("CREATE DATABASE " + dbName);
                System.out.println("✅ Base de datos '" + dbName + "' creada.");
            }

            String dbUrl = "jdbc:postgresql://localhost:" + port + "/" + dbName;
            try (Connection conn = DriverManager.getConnection(dbUrl, user, pass);
                 Statement dbStmt = conn.createStatement()) {
                
                System.out.println("Creando tablas...");
                dbStmt.executeUpdate("CREATE TABLE usuarios (id SERIAL PRIMARY KEY, username TEXT UNIQUE, password TEXT, nombre TEXT, rol TEXT)");
                dbStmt.executeUpdate("CREATE TABLE clientes (id SERIAL PRIMARY KEY, nombre TEXT, tipo_cliente TEXT, latitud DOUBLE PRECISION, longitud DOUBLE PRECISION, ciudad TEXT, cadena TEXT, activo BOOLEAN DEFAULT TRUE)");
                dbStmt.executeUpdate("CREATE TABLE rutas_generadas (token TEXT PRIMARY KEY, movil_numero INTEGER, clientes_json TEXT, distancia_total DOUBLE PRECISION, tiempo_estimado INTEGER, fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                dbStmt.executeUpdate("CREATE TABLE entregas (id SERIAL PRIMARY KEY, ruta_token TEXT REFERENCES rutas_generadas(token), cliente_id INTEGER REFERENCES clientes(id), estado TEXT, observacion TEXT, orden_en_ruta INTEGER, fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                dbStmt.executeUpdate("CREATE TABLE reglas_ruteo (id SERIAL PRIMARY KEY, categoria TEXT UNIQUE, limite_por_movil INTEGER, activo BOOLEAN DEFAULT TRUE)");
                
                dbStmt.executeUpdate("INSERT INTO usuarios (username, password, nombre, rol) VALUES ('admin', 'admin', 'Administrador', 'admin')");
                
                Path sqlPath = Paths.get("database/import.sql");
                if (Files.exists(sqlPath)) {
                    System.out.println("Cargando clientes...");
                    List<String> lines = Files.readAllLines(sqlPath);
                    int count = 0;
                    for (String line : lines) {
                        if (!line.trim().isEmpty() && !line.startsWith("--") && !line.contains("TRUNCATE")) {
                            try {
                                String sql = line.replace(");", ", 'minorista/gastronómico', 'NINGUNO');");
                                if (!sql.contains("tipo_cliente")) {
                                    sql = line.replace("INSERT INTO clientes (nombre, latitud, longitud, activo, ciudad) VALUES", 
                                                     "INSERT INTO clientes (nombre, latitud, longitud, activo, ciudad, tipo_cliente, cadena) VALUES");
                                }
                                dbStmt.executeUpdate(sql);
                                count++;
                            } catch (SQLException e) {
                                try { dbStmt.executeUpdate(line); count++; } catch(Exception e2) {}
                            }
                        }
                    }
                    System.out.println("✅ " + count + " clientes cargados.");

                    // --- DATOS DE PRUEBA PARA ESTADISTICAS ---
                    System.out.println("Migrando datos de ejemplo para estadisticas...");
                    dbStmt.executeUpdate("INSERT INTO rutas_generadas (token, movil_numero, clientes_json, distancia_total, tiempo_estimado) " +
                                       "VALUES ('TOKEN-PROCESADO', 1, '[]', 25.4, 60)");
                    
                    for (int i = 1; i <= 10; i++) {
                        String estado = (i % 4 == 0) ? "rechazado" : "entregado";
                        dbStmt.executeUpdate("INSERT INTO entregas (ruta_token, cliente_id, estado, observacion, orden_en_ruta) " +
                                           "VALUES ('TOKEN-PROCESADO', " + i + ", '" + estado + "', 'Entrega realizada con exito', " + i + ")");
                    }
                    System.out.println("✅ Datos de entregas migrados correctamente.");
                }

                System.out.println("\nPROCESO FINALIZADO");
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
