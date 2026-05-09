import java.sql.*;

public class ActualizarDB_Finalizar {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5000/ruteo_db";
        String user = "postgres";
        String pass = "Zelaya1103";
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.createStatement().executeUpdate("ALTER TABLE rutas_generadas ADD COLUMN IF NOT EXISTS finalizada BOOLEAN DEFAULT false");
            System.out.println("✅ Columna 'finalizada' añadida correctamente.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
