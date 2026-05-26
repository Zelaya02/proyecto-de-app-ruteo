import java.sql.*;

public class CheckDB {
    public static void main(String[] args) {
        String dbUrl = "jdbc:postgresql://localhost:5000/ruteo_db";
        String user = "postgres";
        String pass = "Zelaya1103";
        try (Connection conn = DriverManager.getConnection(dbUrl, user, pass);
             Statement stmt = conn.createStatement()) {
            
            int deleted = stmt.executeUpdate("DELETE FROM reglas_ruteo WHERE categoria = 'minorista'");
            System.out.println("Deleted " + deleted + " rows.");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
