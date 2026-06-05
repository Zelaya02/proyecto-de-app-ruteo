import java.sql.*;

public class CheckDB {
    public static void main(String[] args) {
        String dbUrl = "jdbc:postgresql://localhost:5000/ruteo_db";
        String user = "postgres";
        String pass = "Zelaya1103";
        try (Connection conn = DriverManager.getConnection(dbUrl, user, pass);
             Statement stmt = conn.createStatement()) {
            
            try {
                stmt.executeUpdate("ALTER TABLE vehiculos ADD COLUMN tipo TEXT DEFAULT 'camion mediano'");
                System.out.println("Columna 'tipo' agregada a tabla vehiculos exitosamente.");
            } catch (Exception ex) {
                System.out.println("La columna 'tipo' probablemente ya existe o hubo un error: " + ex.getMessage());
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
