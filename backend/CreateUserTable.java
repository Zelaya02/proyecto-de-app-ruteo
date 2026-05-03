import java.sql.*;

public class CreateUserTable {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5000/ruteo_db";
        String user = "postgres";
        String pass = "Zelaya1103";

        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {
            
            String sql = "CREATE TABLE IF NOT EXISTS usuarios (" +
                         "id SERIAL PRIMARY KEY, " +
                         "username VARCHAR(50) UNIQUE NOT NULL, " +
                         "password VARCHAR(100) NOT NULL, " +
                         "nombre VARCHAR(100), " +
                         "rol VARCHAR(20) DEFAULT 'admin'" +
                         ")";
            stmt.executeUpdate(sql);
            System.out.println("Tabla 'usuarios' creada.");

            // Insertar usuario por defecto si no existe
            String checkSql = "SELECT COUNT(*) FROM usuarios WHERE username = 'admin'";
            ResultSet rs = stmt.executeQuery(checkSql);
            if (rs.next() && rs.getInt(1) == 0) {
                String insertSql = "INSERT INTO usuarios (username, password, nombre, rol) VALUES ('admin', 'nexo2025', 'Administrador', 'admin')";
                stmt.executeUpdate(insertSql);
                System.out.println("Usuario 'admin' insertado.");
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
