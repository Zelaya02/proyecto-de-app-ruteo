import java.sql.*;

public class ResetUsers {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5000/ruteo_db";
        String user = "postgres";
        String pass = "Zelaya1103";
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            Statement stmt = conn.createStatement();
            // Limpiar y resetear admin
            stmt.executeUpdate("DELETE FROM usuarios WHERE username = 'admin'");
            stmt.executeUpdate("INSERT INTO usuarios (username, password, nombre, rol) VALUES ('admin', 'admin', 'Administrador', 'admin')");
            
            // Crear usuario de respaldo
            stmt.executeUpdate("DELETE FROM usuarios WHERE username = 'nexo'");
            stmt.executeUpdate("INSERT INTO usuarios (username, password, nombre, rol) VALUES ('nexo', 'nexo123', 'Soporte Nexo', 'admin')");
            
            System.out.println("✅ Usuarios reseteados correctamente.");
            System.out.println("1. admin / admin");
            System.out.println("2. nexo / nexo123");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
