import java.sql.*;

public class FixDatabase {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5000/ruteo_db";
        String user = "postgres";
        String pass = "Zelaya1103";
        
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            Statement stmt = conn.createStatement();
            System.out.println("Añadiendo columna 'cadena'...");
            stmt.executeUpdate("ALTER TABLE clientes ADD COLUMN IF NOT EXISTS cadena VARCHAR(100)");
            System.out.println("¡Columna añadida con éxito!");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
