import java.sql.*;

public class VerificarTablas {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5000/ruteo_db";
        String user = "postgres";
        String pass = "Zelaya1103";
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            System.out.println("--- LISTA DE USUARIOS ---");
            ResultSet rs = conn.createStatement().executeQuery("SELECT id, username, password, rol FROM usuarios");
            while (rs.next()) {
                System.out.println("ID: " + rs.getInt("id") + " | User: " + rs.getString("username") + " | Pass: " + rs.getString("password"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
