import java.sql.*;
import java.nio.file.*;
import java.util.List;

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
                dbStmt.executeUpdate("CREATE TABLE clientes (id SERIAL PRIMARY KEY, nombre TEXT UNIQUE, tipo_cliente TEXT, latitud DOUBLE PRECISION, longitud DOUBLE PRECISION, ciudad TEXT, cadena TEXT, url_google TEXT, activo BOOLEAN DEFAULT TRUE)");
                
                dbStmt.executeUpdate("CREATE TABLE choferes (id SERIAL PRIMARY KEY, nombre TEXT, telefono TEXT, activo BOOLEAN DEFAULT TRUE)");
                dbStmt.executeUpdate("CREATE TABLE vehiculos (id SERIAL PRIMARY KEY, nombre TEXT, chapa TEXT, activo BOOLEAN DEFAULT TRUE)");
                
                dbStmt.executeUpdate("CREATE TABLE rutas_generadas (token TEXT PRIMARY KEY, movil_numero INTEGER, chofer_id INTEGER REFERENCES choferes(id), vehiculo_id INTEGER REFERENCES vehiculos(id), clientes_json TEXT, distancia_total DOUBLE PRECISION, tiempo_estimado INTEGER, fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                dbStmt.executeUpdate("CREATE TABLE entregas (id SERIAL PRIMARY KEY, ruta_token TEXT REFERENCES rutas_generadas(token), cliente_id INTEGER REFERENCES clientes(id), estado TEXT, observacion TEXT, orden_en_ruta INTEGER, fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                dbStmt.executeUpdate("CREATE TABLE reglas_ruteo (id SERIAL PRIMARY KEY, categoria TEXT UNIQUE, limite_por_movil INTEGER, activo BOOLEAN DEFAULT TRUE)");
                
                dbStmt.executeUpdate("INSERT INTO choferes (nombre, telefono) VALUES ('Juan Pérez', '0981123456'), ('Carlos Gómez', '0972654321'), ('Luis Torres', '0991999888')");
                dbStmt.executeUpdate("INSERT INTO vehiculos (nombre, chapa) VALUES ('Camión Isuzu 01', 'ABC 123'), ('Furgoneta Toyota 02', 'XYZ 789'), ('Moto Carga 03', 'RUT 456')");
                
                // Reglas por defecto
                dbStmt.executeUpdate("INSERT INTO reglas_ruteo (categoria, limite_por_movil) VALUES ('supermercado', 5), ('mayorista / distribuidor', 8), ('minorista/gastronómico', 15)");
                
                dbStmt.executeUpdate("INSERT INTO usuarios (username, password, nombre, rol) VALUES ('admin', 'admin', 'Administrador', 'admin')");
                
                Path sqlPath = Paths.get("import.sql");
                if (Files.exists(sqlPath)) {
                    System.out.println("Cargando clientes...");
                    List<String> lines = Files.readAllLines(sqlPath);
                    int count = 0;
                    for (String line : lines) {
                        if (!line.trim().isEmpty() && !line.startsWith("--") && !line.contains("TRUNCATE")) {
                            dbStmt.executeUpdate(line);
                            count++;
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
