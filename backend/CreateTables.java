import java.sql.*;
public class CreateTables {
    public static void main(String[] args) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5000/ruteo_db", "postgres", "Zelaya1103");
        Statement stmt = conn.createStatement();
        
        String sqlClientes = "CREATE TABLE IF NOT EXISTS clientes (" +
            "id SERIAL PRIMARY KEY," +
            "nombre VARCHAR(255)," +
            "latitud DOUBLE PRECISION," +
            "longitud DOUBLE PRECISION," +
            "activo BOOLEAN," +
            "ciudad VARCHAR(255)," +
            "tipo_cliente VARCHAR(100)" +
        ")";
        stmt.executeUpdate(sqlClientes);
        
        String sqlRutas = "CREATE TABLE IF NOT EXISTS rutas_generadas (" +
            "id SERIAL PRIMARY KEY," +
            "fecha DATE DEFAULT CURRENT_DATE," +
            "token VARCHAR(50) UNIQUE," +
            "movil_numero INTEGER," +
            "clientes_json TEXT," +
            "distancia_total DECIMAL(10,2)," +
            "tiempo_estimado INTEGER," +
            "reporte_finalizado BOOLEAN DEFAULT false" +
        ")";
        stmt.executeUpdate(sqlRutas);
        
        String sqlEntregas = "CREATE TABLE IF NOT EXISTS entregas (" +
            "id SERIAL PRIMARY KEY," +
            "ruta_token VARCHAR(50) REFERENCES rutas_generadas(token)," +
            "cliente_id INTEGER REFERENCES clientes(id)," +
            "estado VARCHAR(20)," +
            "observacion TEXT," +
            "fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "orden_en_ruta INTEGER" +
        ")";
        stmt.executeUpdate(sqlEntregas);
        
        System.out.println("Tablas creadas exitosamente.");
        conn.close();
    }
}
