import java.sql.*;
import java.nio.file.*;
import java.util.List;

public class CreacionDB {
    public static void main(String[] args) {
        int port = 5432; 
        String dbName = "ruteo_db";
        String user = System.getenv().getOrDefault("DB_USER", "postgres");
        String pass = System.getenv().getOrDefault("DB_PASSWORD", "");
        
        System.out.println("Iniciando creacion de Base de Datos...");

        for (int p : new int[]{5000, 5432}) {
            try (Connection test = DriverManager.getConnection("jdbc:postgresql://localhost:" + p + "/postgres", user, pass)) {
                port = p;
                System.out.println("✅ Puerto detectado: " + port);
                break;
            } catch (Exception e) {}
        }

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

                dbStmt.executeUpdate("INSERT INTO usuarios (username, password, nombre, rol) VALUES ('admin', 'nexo2025', 'Administrador', 'admin')");
                
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
                    dbStmt.executeUpdate("INSERT INTO rutas_generadas (token, movil_numero, chofer_nombre, vehiculo_nombre, clientes_json, distancia_total, tiempo_estimado) " +
                                       "VALUES ('TOKEN-PROCESADO', 1, 'Sin asignar', 'Sin asignar', '[]', 25.4, 60)");
                    
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
