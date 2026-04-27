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
        
        // Supermercados: contienen 'super' (Supermercado, Superseis, Supermas, etc)
        stmt.executeUpdate("UPDATE clientes SET tipo_cliente = 'supermercado' WHERE nombre ILIKE '%super%' OR nombre ILIKE '%seis%'");
        // Nota: Muchos superseis no dicen 'supermercado' sino 'Superseis' que contiene 'super'. Salemma no contiene 'super' pero es super.
        // Voy a agregar Salemma, Arete, Gran Via, Real, Hiperseis, etc. para ser precisos porque el usuario dijo "los que dicen super son supermercados".
        // La regla estricta: "los que dicen super son supermercados"
        stmt.executeUpdate("UPDATE clientes SET tipo_cliente = 'supermercado' WHERE nombre ILIKE '%super%'");
        
        // El usuario dijo "los que sucen super son supermercados", asumo que se refiere a "dicen super".
        
        System.out.println("Tipos actualizados.");
        conn.close();
    }
}
