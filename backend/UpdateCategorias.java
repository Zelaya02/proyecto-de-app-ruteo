import java.sql.*;

public class UpdateCategorias {
    public static void main(String[] args) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5000/ruteo_db", "postgres", "Zelaya1103");
        Statement stmt = conn.createStatement();
        
        // Salemma y Gran Via son supermercados
        int superCount = stmt.executeUpdate("UPDATE clientes SET tipo_cliente = 'supermercado' WHERE nombre ILIKE '%salemma%' OR nombre ILIKE '%gran via%' OR nombre ILIKE '%gran vía%'");
        System.out.println("Supermercados actualizados: " + superCount);
        
        // Bahia son mayoristas
        int mayoristasCount = stmt.executeUpdate("UPDATE clientes SET tipo_cliente = 'mayorista' WHERE nombre ILIKE '%bahia%' OR nombre ILIKE '%bahía%'");
        System.out.println("Mayoristas (Bahia) actualizados: " + mayoristasCount);
        
        conn.close();
    }
}
