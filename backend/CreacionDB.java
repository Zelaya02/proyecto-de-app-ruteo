import java.sql.*;
import org.mindrot.jbcrypt.BCrypt;

public class CreacionDB {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5000/ruteo_db";
        String user = "postgres";
        String pass = "Zelaya1103";

        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {

            System.out.println("--- Iniciando creación de estructura de base de datos ---");

            // 1. Tabla Usuarios
            String sqlUsuarios = "CREATE TABLE IF NOT EXISTS usuarios (" +
                "id SERIAL PRIMARY KEY," +
                "username VARCHAR(50) UNIQUE NOT NULL," +
                "password TEXT NOT NULL," +
                "nombre VARCHAR(100)," +
                "rol VARCHAR(20) DEFAULT 'admin'" +
                ")";
            stmt.executeUpdate(sqlUsuarios);
            System.out.println("✅ Tabla 'usuarios' lista.");

            // 2. Tabla Clientes
            String sqlClientes = "CREATE TABLE IF NOT EXISTS clientes (" +
                "id SERIAL PRIMARY KEY," +
                "nombre VARCHAR(255) NOT NULL," +
                "latitud DOUBLE PRECISION," +
                "longitud DOUBLE PRECISION," +
                "activo BOOLEAN DEFAULT true," +
                "ciudad VARCHAR(255)," +
                "tipo_cliente VARCHAR(100)," +
                "cadena VARCHAR(100)" +
                ")";
            stmt.executeUpdate(sqlClientes);
            System.out.println("✅ Tabla 'clientes' lista (estructura vacía).");

            // 3. Tabla Rutas Generadas
            String sqlRutas = "CREATE TABLE IF NOT EXISTS rutas_generadas (" +
                "id SERIAL PRIMARY KEY," +
                "fecha DATE DEFAULT CURRENT_DATE," +
                "token VARCHAR(50) UNIQUE NOT NULL," +
                "movil_numero INTEGER," +
                "clientes_json TEXT," +
                "distancia_total DECIMAL(10,2)," +
                "tiempo_estimado INTEGER," +
                "reporte_finalizado BOOLEAN DEFAULT false" +
                ")";
            stmt.executeUpdate(sqlRutas);
            System.out.println("✅ Tabla 'rutas_generadas' lista.");

            // 4. Tabla Entregas (Seguimiento)
            String sqlEntregas = "CREATE TABLE IF NOT EXISTS entregas (" +
                "id SERIAL PRIMARY KEY," +
                "ruta_token VARCHAR(50) REFERENCES rutas_generadas(token)," +
                "cliente_id INTEGER REFERENCES clientes(id)," +
                "estado VARCHAR(20) DEFAULT 'pendiente'," +
                "observacion TEXT," +
                "fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "orden_en_ruta INTEGER" +
                ")";
            stmt.executeUpdate(sqlEntregas);
            System.out.println("✅ Tabla 'entregas' lista.");

            // 5. Tabla Reglas de Ruteo
            String sqlReglas = "CREATE TABLE IF NOT EXISTS reglas_ruteo (" +
                "id SERIAL PRIMARY KEY," +
                "categoria VARCHAR(50) UNIQUE NOT NULL," +
                "limite_por_movil INTEGER DEFAULT 0," +
                "activo BOOLEAN DEFAULT true" +
                ")";
            stmt.executeUpdate(sqlReglas);
            System.out.println("✅ Tabla 'reglas_ruteo' lista.");

            // Insertar usuario administrador por defecto si no existe
            String checkAdmin = "SELECT COUNT(*) FROM usuarios WHERE username = 'admin'";
            ResultSet rs = stmt.executeQuery(checkAdmin);
            rs.next();
            if (rs.getInt(1) == 0) {
                String hashedPass = BCrypt.hashpw("nexo2025", BCrypt.gensalt());
                String insertAdmin = "INSERT INTO usuarios (username, password, nombre, rol) VALUES (?, ?, ?, ?)";
                try (PreparedStatement pstmt = conn.prepareStatement(insertAdmin)) {
                    pstmt.setString(1, "admin");
                    pstmt.setString(2, hashedPass);
                    pstmt.setString(3, "Administrador Nexo");
                    pstmt.setString(4, "admin");
                    pstmt.executeUpdate();
                    System.out.println("✅ Usuario 'admin' creado con contraseña encriptada.");
                }
            }

            System.out.println("\n🎉 Estructura de base de datos creada exitosamente.");
            System.out.println("No se han importado clientes, la tabla está lista para nuevos datos.");

        } catch (SQLException e) {
            System.err.println("❌ Error al crear la base de datos: " + e.getMessage());
        }
    }
}
