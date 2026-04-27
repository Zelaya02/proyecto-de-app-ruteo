import java.sql.*;
import java.nio.file.*;
import java.util.List;

public class ImportData {
    public static void main(String[] args) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5000/ruteo_db", "postgres", "Zelaya1103");
        Statement stmt = conn.createStatement();
        
        // Agregar columna ciudad si no existe
        try {
            stmt.executeUpdate("ALTER TABLE clientes ADD COLUMN ciudad VARCHAR(255)");
            System.out.println("Columna 'ciudad' agregada.");
        } catch (Exception e) {
            System.out.println("La columna 'ciudad' posiblemente ya existe.");
        }
        
        // Vaciar la base de datos (CASCADE borra dependencias en entregas y rutas)
        stmt.executeUpdate("TRUNCATE TABLE clientes CASCADE");
        stmt.executeUpdate("TRUNCATE TABLE rutas_generadas CASCADE");
        stmt.executeUpdate("TRUNCATE TABLE entregas CASCADE");
        System.out.println("Tablas vaciadas.");
        
        // Leer y ejecutar import.sql
        List<String> lines = Files.readAllLines(Paths.get("../database/import.sql"));
        int count = 0;
        for(String line : lines) {
            line = line.trim();
            if(!line.isEmpty() && !line.startsWith("--")) {
                try {
                    stmt.executeUpdate(line);
                    count++;
                } catch (Exception e) {
                    System.out.println("Error en linea: " + line);
                    e.printStackTrace();
                }
            }
        }
        
        System.out.println("Importación finalizada. Filas insertadas: " + count);
        conn.close();
    }
}
