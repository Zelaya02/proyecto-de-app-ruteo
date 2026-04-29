import java.sql.*;

public class UpdateTipos {
    public static void main(String[] args) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5000/ruteo_db", "postgres", "Zelaya1103");
        Statement stmt = conn.createStatement();
        
        try {
            stmt.executeUpdate("ALTER TABLE clientes ADD COLUMN tipo_cliente VARCHAR(100)");
            System.out.println("Columna agregada.");
        } catch(Exception e) {
            System.out.println("La columna posiblemente ya existe.");
        }
        
        stmt.executeUpdate("UPDATE clientes SET tipo_cliente = 'minorista/gastronómico'");
        
        // Mayoristas: Encina, Fraxa, Kiko
        stmt.executeUpdate("UPDATE clientes SET tipo_cliente = 'mayorista' WHERE nombre ILIKE '%Encina%' OR nombre ILIKE '%Fraxa%' OR nombre ILIKE '%Kiko%'");
        
        // Supermercados: contienen 'super', 'seis', 'box', 'casa rica', 'punto carne' o 'ahorrazo'
        stmt.executeUpdate("UPDATE clientes SET tipo_cliente = 'supermercado' WHERE nombre ILIKE '%super%' OR nombre ILIKE '%seis%' OR nombre ILIKE '%box%' OR nombre ILIKE '%casa rica%' OR nombre ILIKE '%punto carne%' OR nombre ILIKE '%ahorrazo%'");
        
        // Regla específica para asegurar que Salemma, Real, Arete y Gran Vía también sean supermercados
        stmt.executeUpdate("UPDATE clientes SET tipo_cliente = 'supermercado' WHERE nombre ILIKE '%salemma%' OR nombre ILIKE '%real%' OR nombre ILIKE '%areté%' OR nombre ILIKE '%gran via%' OR nombre ILIKE '%gran vía%'");

        
        // El usuario dijo "los que sucen super son supermercados", asumo que se refiere a "dicen super".
        
        System.out.println("Tipos actualizados.");
        conn.close();
    }
}
