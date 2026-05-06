import java.sql.*;
import java.nio.file.*;
import java.util.List;
import java.io.*;

public class CreacionDB {
    public static void main(String[] args) {
        // Configuracion
        int port = 5000; 
        String dbName = "ruteo_db";
        String user = "postgres";
        String pass = "Zelaya1103";
        
        System.out.println("Iniciando creacion de Base de Datos...");

        try {
            // 1. Conectar a postgres para crear la DB
            String rootUrl = "jdbc:postgresql://localhost:" + port + "/postgres";
            try (Connection rootConn = DriverManager.getConnection(rootUrl, user, pass);
                 Statement stmt = rootConn.createStatement()) {
                
                // Forzar recreacion para asegurar limpieza
                stmt.executeUpdate("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
                stmt.executeUpdate("CREATE DATABASE " + dbName);
                System.out.println("✅ Base de datos '" + dbName + "' creada.");
            }

            // 2. Conectar a la nueva DB y crear tablas
            String dbUrl = "jdbc:postgresql://localhost:" + port + "/" + dbName;
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
                
                // Cargar clientes desde import.sql
                Path sqlPath = Paths.get("import.sql");
                if (Files.exists(sqlPath)) {
                    System.out.println("Cargando clientes desde import.sql...");
                    List<String> lines = Files.readAllLines(sqlPath);
                    int count = 0;
                    for (String line : lines) {
                        if (!line.trim().isEmpty() && !line.startsWith("--") && !line.contains("TRUNCATE")) {
                            try {
                                // Asegurar que tenga valores para tipo_cliente y cadena si el SQL es viejo
                                String sql = line.replace(");", ", 'minorista/gastronómico', 'NINGUNO');");
                                if (!sql.contains("tipo_cliente")) {
                                    // Ajuste dinámico si el INSERT no tiene las columnas nuevas
                                    sql = line.replace("INSERT INTO clientes (nombre, latitud, longitud, activo, ciudad) VALUES", 
                                                     "INSERT INTO clientes (nombre, latitud, longitud, activo, ciudad, tipo_cliente, cadena) VALUES");
                                }
                                dbStmt.executeUpdate(sql);
                                count++;
                            } catch (SQLException e) {
                                // Si falla por formato, intentamos la linea original
                                try { dbStmt.executeUpdate(line); count++; } catch(Exception e2) {}
                            }
                        }
                    }
                    System.out.println("✅ " + count + " clientes cargados con exito.");
                }

                System.out.println("\n========================================");
                System.out.println("PROCESO FINALIZADO CON EXITO");
                System.out.println("========================================");
            }
        } catch (Exception e) {
            System.err.println("\n❌ ERROR DURANTE LA CREACION: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
