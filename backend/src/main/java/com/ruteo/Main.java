package com.ruteo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.sql.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.UUID;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.ruteo.model.Usuario;
import com.ruteo.repository.UsuarioRepository;

public class Main {
    private static final Gson gson = new Gson();
    private static String DB_URL = "jdbc:postgresql://localhost:5000/ruteo_db"; // se auto-detecta al inicio
    private static String DB_USER = getEnvOrDefault("DB_USER", "postgres");
    private static String DB_PASSWORD = getEnvOrDefault("DB_PASSWORD", "Zelaya1103");
    private static final String ORS_KEY = getEnvOrDefault("ORS_API_KEY", "");
    private static final String FRONTEND_DIR = getEnvOrDefault("FRONTEND_DIR", "../frontend");

    // Modelos de Reglas
    static class Regla {
        int id;
        String categoria;
        int limite_por_movil;
        boolean activo;

        Regla(int id, String categoria, int limite, boolean activo) {
            this.id = id;
            this.categoria = categoria;
            this.limite_por_movil = limite;
            this.activo = activo;
        }
    }

    private static UsuarioRepository usuarioRepo; // se inicializa después de auto-detectar puerto
    private static final Map<String, Long> activeTokens = new ConcurrentHashMap<>();
    private static final long TOKEN_TTL_MS = 8 * 60 * 60 * 1000; // 8 horas

    /** Parsea DATABASE_URL (formato Render o estandar de heroku) a JDBC */
    private static void parseDatabaseUrl() {
        String dbUrlEnv = System.getenv("DATABASE_URL");
        if (dbUrlEnv == null || dbUrlEnv.isEmpty()) {
            dbUrlEnv = System.getenv("DB_URL");
        }
        if (dbUrlEnv != null && !dbUrlEnv.isEmpty()) {
            try {
                if (dbUrlEnv.startsWith("postgres://") || dbUrlEnv.startsWith("postgresql://")) {
                    String cleanUrl = dbUrlEnv.substring(dbUrlEnv.indexOf("//") + 2);
                    String userInfo = "";
                    String hostPortDb = cleanUrl;
                    if (cleanUrl.contains("@")) {
                        int atIndex = cleanUrl.indexOf("@");
                        userInfo = cleanUrl.substring(0, atIndex);
                        hostPortDb = cleanUrl.substring(atIndex + 1);
                    }
                    
                    if (!userInfo.isEmpty() && userInfo.contains(":")) {
                        String[] parts = userInfo.split(":", 2);
                        DB_USER = parts[0];
                        DB_PASSWORD = parts[1];
                    }
                    
                    DB_URL = "jdbc:postgresql://" + hostPortDb;
                    System.out.println("✅ DATABASE_URL parseada correctamente.");
                } else {
                    DB_URL = dbUrlEnv;
                }
            } catch (Exception e) {
                System.err.println("⚠️ Error al parsear DATABASE_URL, usando valores por defecto: " + e.getMessage());
            }
        } else {
            DB_URL = detectDbUrl();
        }
    }

    /** Inicializa el esquema de la base de datos si las tablas no existen */
    private static void initializeDatabaseSchema() {
        System.out.println("Comprobando esquema de base de datos...");
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement stmt = conn.createStatement()) {
            
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS usuarios (" +
                    "id SERIAL PRIMARY KEY, " +
                    "username TEXT UNIQUE, " +
                    "password TEXT, " +
                    "nombre TEXT, " +
                    "rol TEXT)");
            
            ResultSet rsAdmin = stmt.executeQuery("SELECT COUNT(*) FROM usuarios WHERE username = 'admin'");
            if (rsAdmin.next() && rsAdmin.getInt(1) == 0) {
                stmt.executeUpdate("INSERT INTO usuarios (username, password, nombre, rol) VALUES ('admin', 'nexo2025', 'Administrador', 'admin')");
                System.out.println("✅ Usuario administrador por defecto creado (admin/nexo2025).");
            }
            
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS clientes (" +
                    "id SERIAL PRIMARY KEY, " +
                    "nombre TEXT, " +
                    "tipo_cliente TEXT, " +
                    "latitud DOUBLE PRECISION, " +
                    "longitud DOUBLE PRECISION, " +
                    "ciudad TEXT, " +
                    "cadena TEXT, " +
                    "activo BOOLEAN DEFAULT true, " +
                    "url_google TEXT)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS reglas_ruteo (" +
                    "id SERIAL PRIMARY KEY, " +
                    "categoria TEXT UNIQUE, " +
                    "limite_por_movil INTEGER, " +
                    "activo BOOLEAN DEFAULT true)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS choferes (" +
                    "id SERIAL PRIMARY KEY, " +
                    "nombre TEXT, " +
                    "telefono TEXT, " +
                    "activo BOOLEAN DEFAULT true)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS vehiculos (" +
                    "id SERIAL PRIMARY KEY, " +
                    "nombre TEXT, " +
                    "chapa TEXT, " +
                    "tipo TEXT DEFAULT 'camion mediano', " +
                    "activo BOOLEAN DEFAULT true)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS rutas_generadas (" +
                    "token TEXT PRIMARY KEY, " +
                    "movil_numero INTEGER, " +
                    "clientes_json TEXT, " +
                    "distancia_total DOUBLE PRECISION, " +
                    "tiempo_estimado INTEGER, " +
                    "chofer_id INTEGER, " +
                    "vehiculo_id INTEGER, " +
                    "chofer_nombre TEXT, " +
                    "vehiculo_nombre TEXT, " +
                    "fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS entregas (" +
                    "id SERIAL PRIMARY KEY, " +
                    "ruta_token TEXT, " +
                    "cliente_id INTEGER, " +
                    "estado TEXT DEFAULT 'pendiente', " +
                    "observacion TEXT, " +
                    "orden_en_ruta INTEGER, " +
                    "fecha_actualizacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            System.out.println("✅ Esquema de base de datos verificado/creado.");
            
            ResultSet rsClientes = stmt.executeQuery("SELECT COUNT(*) FROM clientes");
            if (rsClientes.next() && rsClientes.getInt(1) == 0) {
                loadDefaultClients(conn);
            }
        } catch (SQLException e) {
            System.err.println("❌ Error al inicializar el esquema de base de datos: " + e.getMessage());
        }
    }

    private static void loadDefaultClients(Connection conn) {
        String[] paths = {"database/import.sql", "import.sql", "../database/import.sql"};
        File sqlFile = null;
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) {
                sqlFile = f;
                break;
            }
        }
        
        if (sqlFile == null) {
            System.out.println("ℹ️ Archivo import.sql no encontrado. No se inicializaron clientes por defecto.");
            return;
        }

        System.out.println("Cargando clientes por defecto desde " + sqlFile.getPath() + "...");
        try (BufferedReader reader = new BufferedReader(new FileReader(sqlFile, StandardCharsets.UTF_8));
             Statement stmt = conn.createStatement()) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty() && !line.startsWith("--") && !line.contains("TRUNCATE")) {
                    try {
                        stmt.executeUpdate(line);
                        count++;
                    } catch (SQLException e) {
                        // Ignorar errores individuales (como duplicados)
                    }
                }
            }
            System.out.println("✅ " + count + " clientes cargados exitosamente.");
        } catch (Exception e) {
            System.err.println("⚠️ Error al cargar los clientes por defecto: " + e.getMessage());
        }
    }

    private static String getEnvOrDefault(String key, String def) {
        String val = System.getenv(key);
        return val != null && !val.isEmpty() ? val : def;
    }

    /** Detecta el puerto PostgreSQL disponible (prueba 5000 primero, luego 5432). */
    private static String detectDbUrl() {
        String[] candidatos = {
            "jdbc:postgresql://localhost:5000/ruteo_db",
            "jdbc:postgresql://localhost:5432/ruteo_db"
        };
        for (String url : candidatos) {
            try (Connection c = DriverManager.getConnection(url, DB_USER, DB_PASSWORD)) {
                System.out.println("✅ Conectado a PostgreSQL en: " + url);
                return url;
            } catch (SQLException e) {
                System.out.println("⚠️  Puerto no disponible: " + url + " (" + e.getMessage().split("\n")[0] + ")");
            }
        }
        System.err.println("❌ No se pudo conectar a PostgreSQL en ningún puerto conocido.");
        return candidatos[candidatos.length - 1]; // fallback
    }

    public static void main(String[] args) throws IOException {
        // Auto-detección del puerto de PostgreSQL
        parseDatabaseUrl();
        initializeDatabaseSchema();

        if (System.getenv("DB_PASSWORD") == null || System.getenv("DB_PASSWORD").isEmpty()) {
            System.out.println("⚠️  ADVERTENCIA: Usando contraseña por defecto. Configure DB_PASSWORD como variable de entorno para producción.");
        }
        if (System.getenv("ORS_API_KEY") == null || System.getenv("ORS_API_KEY").isEmpty()) {
            System.out.println("ℹ️  ORS_API_KEY no configurada. Las distancias usarán Haversine (estimación lineal).");
        }

        usuarioRepo = new UsuarioRepository(DB_URL, DB_USER, DB_PASSWORD);

        int port = Integer.parseInt(getEnvOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        System.out.println("🚀 Servidor iniciado en el puerto: " + port);

        // Archivos estaticos
        server.createContext("/", new StaticHandler());

        // Configurar API
        server.createContext("/api/clientes", new ClientesHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/generar-rutas", new RutasHandler());
        server.createContext("/api/asignar-recursos", new AsignarRecursosHandler());
        server.createContext("/api/ruta", new RutaTokenHandler());
        server.createContext("/api/actualizar-estado", new EstadoHandler());
        server.createContext("/api/estadisticas", new EstadisticasHandler());
        server.createContext("/api/reportes", new ReportesHandler());
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/reglas", new ReglasHandler());
        server.createContext("/api/choferes", new ChoferesHandler());
        server.createContext("/api/vehiculos", new VehiculosHandler());
        server.createContext("/api/kml/importar", new KmlImportHandler());
        server.createContext("/api/kml/exportar", new KmlExportHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("Servidor iniciado en http://localhost:8080");
        System.out.println("Presiona Ctrl+C para detener");
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            path = path.replaceAll("\\.\\./", "").replaceAll("\\.\\.", "").replaceAll("//+", "/");
            if (path.contains("..") || path.contains("%") || path.contains(":") || path.contains("~")) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            File file = new File(FRONTEND_DIR, path);
            String canonicalPath = file.getCanonicalPath();
            String frontendCanonical = new File(FRONTEND_DIR).getCanonicalPath();
            if (!canonicalPath.startsWith(frontendCanonical)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            if (file.exists() && !file.isDirectory()) {
                String contentType = "text/html";
                if (path.endsWith(".css"))
                    contentType = "text/css";
                else if (path.endsWith(".js"))
                    contentType = "application/javascript";
                else if (path.endsWith(".png"))
                    contentType = "image/png";

                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, file.length());
                try (OutputStream os = exchange.getResponseBody();
                        FileInputStream fs = new FileInputStream(file)) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = fs.read(buffer)) != -1) {
                        os.write(buffer, 0, count);
                    }
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    static class ClientesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT * FROM clientes WHERE activo = true ORDER BY id DESC";
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql);

                    List<Map<String, Object>> clientes = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> cliente = new HashMap<>();
                        cliente.put("id", rs.getInt("id"));
                        cliente.put("nombre", rs.getString("nombre"));
                        cliente.put("direccion", rs.getString("ciudad"));
                        cliente.put("tipo_cliente", rs.getString("tipo_cliente"));
                        cliente.put("latitud", rs.getDouble("latitud"));
                        cliente.put("longitud", rs.getDouble("longitud"));
                        cliente.put("cadena", rs.getString("cadena"));
                        cliente.put("url_google", rs.getString("url_google"));
                        cliente.put("seleccionado", false);
                        clientes.add(cliente);
                    }
                    sendResponse(exchange, 200, gson.toJson(clientes));
                } catch (SQLException e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error de base de datos");
                }
            } else if ("POST".equals(exchange.getRequestMethod()) || "PUT".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);

                    String nombre = (String) req.get("nombre");
                    String tipo = (String) req.get("tipo_cliente");
                    String url = (String) req.get("url");
                    String cadena = (String) req.get("cadena");

                    double lat = 0, lon = 0;
                    if (url != null && !url.isEmpty()) {
                        double[] coords = parseGoogleMapsUrl(url);
                        lat = coords[0];
                        lon = coords[1];
                    } else if (req.containsKey("latitud")) {
                        lat = (Double) req.get("latitud");
                        lon = (Double) req.get("longitud");
                    }

                    String ciudad = determinarCiudad(lat, lon);

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        if ("POST".equals(exchange.getRequestMethod())) {
                            String sql = "INSERT INTO clientes (nombre, tipo_cliente, latitud, longitud, ciudad, cadena, activo) VALUES (?, ?, ?, ?, ?, ?, true)";
                            PreparedStatement pstmt = conn.prepareStatement(sql);
                            pstmt.setString(1, nombre);
                            pstmt.setString(2, tipo);
                            pstmt.setDouble(3, lat);
                            pstmt.setDouble(4, lon);
                            pstmt.setString(5, ciudad);
                            pstmt.setString(6, cadena);
                            pstmt.executeUpdate();
                            sendResponse(exchange, 201, "{\"status\":\"created\"}");
                        } else {
                            int id = ((Double) req.get("id")).intValue();
                            String sql = "UPDATE clientes SET nombre=?, tipo_cliente=?, latitud=?, longitud=?, ciudad=?, cadena=? WHERE id=?";
                            PreparedStatement pstmt = conn.prepareStatement(sql);
                            pstmt.setString(1, nombre);
                            pstmt.setString(2, tipo);
                            pstmt.setDouble(3, lat);
                            pstmt.setDouble(4, lon);
                            pstmt.setString(5, ciudad);
                            pstmt.setString(6, cadena);
                            pstmt.setInt(7, id);
                            pstmt.executeUpdate();
                            sendResponse(exchange, 200, "{\"status\":\"updated\"}");
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String query = exchange.getRequestURI().getQuery();
                    if (query == null || !query.contains("id=")) {
                        sendError(exchange, 400, "ID requerido");
                        return;
                    }
                    int id = Integer.parseInt(query.split("id=")[1].split("&")[0]);

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "UPDATE clientes SET activo = false WHERE id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setInt(1, id);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"deleted\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    static class RutasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));

                    Map<String, Object> request = gson.fromJson(body, Map.class);
                    System.out.println("Generando rutas con: " + body);

                    List<Double> cIds = (List<Double>) request.get("cliente_ids");
                    Object numMovilesObj = request.get("num_moviles");
                    int numMoviles = 1;
                    if (numMovilesObj instanceof Double)
                        numMoviles = ((Double) numMovilesObj).intValue();
                    else if (numMovilesObj instanceof Integer)
                        numMoviles = (Integer) numMovilesObj;

                    if (cIds == null || cIds.isEmpty()) {
                        sendError(exchange, 400, "cliente_ids es requerido");
                        return;
                    }
                    List<Integer> clienteIds = cIds.stream().map(Double::intValue).collect(Collectors.toList());

                    List<Cliente> clientes = new ArrayList<>();
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String placeholders = clienteIds.stream().map(id -> "?").collect(Collectors.joining(","));
                        String sql = "SELECT id, nombre, latitud, longitud, tipo_cliente FROM clientes WHERE activo = true AND id IN ("
                                + placeholders + ")";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        for (int i = 0; i < clienteIds.size(); i++) {
                            pstmt.setInt(i + 1, clienteIds.get(i));
                        }
                        ResultSet rs = pstmt.executeQuery();
                        while (rs.next()) {
                            clientes.add(new Cliente(rs.getInt("id"), rs.getString("nombre"), rs.getDouble("latitud"),
                                    rs.getDouble("longitud"), rs.getString("tipo_cliente")));
                        }
                    }

                    String prioridad = (String) request.getOrDefault("prioridad", "ninguna");
                    Object usarReglasObj = request.get("usar_reglas");
                    boolean usarReglas = true;
                    if (usarReglasObj instanceof Boolean)
                        usarReglas = (Boolean) usarReglasObj;

                    // Obtener reglas activas (solo si el usuario quiere aplicarlas)
                    Map<String, Integer> reglasActivas = new HashMap<>();
                    if (usarReglas) {
                        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                            String sqlReg = "SELECT categoria, limite_por_movil FROM reglas_ruteo WHERE activo = true";
                            Statement stmtReg = conn.createStatement();
                            ResultSet rsReg = stmtReg.executeQuery(sqlReg);
                            while (rsReg.next()) {
                                reglasActivas.put(rsReg.getString("categoria").toLowerCase(),
                                        rsReg.getInt("limite_por_movil"));
                            }
                        }
                    }

                    // K-Means adaptado con REGLAS
                    List<List<Cliente>> clusters = kmeans(clientes, numMoviles, reglasActivas);

                    List<Map<String, Object>> movilesRespuesta = new ArrayList<>();

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        for (int i = 0; i < clusters.size(); i++) {
                            List<Cliente> cluster = clusters.get(i);
                            if (cluster.isEmpty())
                                continue;

                            // Vecino mas cercano para ordenar con prioridad
                            List<Cliente> ordenados = nearestNeighborWithPriority(cluster, prioridad);

                            double distTotal = 0;
                            List<Map<String, Object>> clientesJson = new ArrayList<>();
                            for (int j = 0; j < ordenados.size(); j++) {
                                Cliente c = ordenados.get(j);
                                double dist = 0;
                                if (j < ordenados.size() - 1) {
                                    Cliente siguiente = ordenados.get(j + 1);
                                    try {
                                        // Intentar usar OpenRouteService (Distancia real por carretera)
                                        System.out.printf("  📍 ORS: %s -> %s ...%n", c.nombre, siguiente.nombre);
                                        RouteService.RouteInfo info = RouteService.getRoute(c.lat, c.lon, siguiente.lat,
                                                siguiente.lon);
                                        dist = info.distanceKm;
                                    } catch (Exception e) {
                                        // Fallback a Haversine si falla el servicio o no hay API Key
                                        dist = haversine(c.lat, c.lon, siguiente.lat, siguiente.lon);
                                    }
                                    distTotal += dist;
                                }
                                Map<String, Object> cmap = new HashMap<>();
                                cmap.put("id", c.id);
                                cmap.put("nombre", c.nombre);
                                cmap.put("latitud", c.lat);
                                cmap.put("longitud", c.lon);
                                cmap.put("distancia_siguiente", Math.round(dist * 100.0) / 100.0);
                                clientesJson.add(cmap);
                            }

                            distTotal = Math.round(distTotal * 100.0) / 100.0;
                            int tiempoEstimado = (int) Math.ceil((distTotal / 40.0) * 60.0);
                            String token = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

                            // Buscar asignación para este móvil
                            Integer choferId = null;
                            Integer vehiculoId = null;
                            String choferNombre = "";
                            String vehiculoNombre = "";

                            if (request.containsKey("asignaciones")) {
                                List<Map<String, Object>> asigs = (List<Map<String, Object>>) request.get("asignaciones");
                                for (Map<String, Object> asig : asigs) {
                                    Object movObj = asig.get("movil");
                                    int movNum = 0;
                                    if (movObj instanceof Double) movNum = ((Double) movObj).intValue();
                                    else if (movObj instanceof Integer) movNum = (Integer) movObj;

                                    if (movNum == i + 1) {
                                        Object cId = asig.get("chofer_id");
                                        Object vId = asig.get("vehiculo_id");
                                        if (cId != null) choferId = ((Double) cId).intValue();
                                        if (vId != null) vehiculoId = ((Double) vId).intValue();
                                        
                                        // Buscar nombres
                                        if (choferId != null) {
                                            try (PreparedStatement pName = conn.prepareStatement("SELECT nombre FROM choferes WHERE id = ?")) {
                                                pName.setInt(1, choferId);
                                                ResultSet rsName = pName.executeQuery();
                                                if (rsName.next()) choferNombre = rsName.getString("nombre");
                                            }
                                        }
                                        if (vehiculoId != null) {
                                            try (PreparedStatement pName = conn.prepareStatement("SELECT nombre FROM vehiculos WHERE id = ?")) {
                                                pName.setInt(1, vehiculoId);
                                                ResultSet rsName = pName.executeQuery();
                                                if (rsName.next()) vehiculoNombre = rsName.getString("nombre");
                                            }
                                        }
                                        break;
                                    }
                                }
                            }

                            // Insertar ruta
                            String insertRuta = "INSERT INTO rutas_generadas (token, movil_numero, clientes_json, distancia_total, tiempo_estimado, chofer_id, vehiculo_id, chofer_nombre, vehiculo_nombre) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                            PreparedStatement pstmt = conn.prepareStatement(insertRuta);
                            pstmt.setString(1, token);
                            pstmt.setInt(2, i + 1);
                            pstmt.setString(3, gson.toJson(clientesJson));
                            pstmt.setDouble(4, distTotal);
                            pstmt.setInt(5, tiempoEstimado);
                            if (choferId != null) pstmt.setInt(6, choferId); else pstmt.setNull(6, java.sql.Types.INTEGER);
                            if (vehiculoId != null) pstmt.setInt(7, vehiculoId); else pstmt.setNull(7, java.sql.Types.INTEGER);
                            pstmt.setString(8, choferNombre);
                            pstmt.setString(9, vehiculoNombre);
                            pstmt.executeUpdate();

                            // Insertar entregas
                            String insertEntrega = "INSERT INTO entregas (ruta_token, cliente_id, estado, orden_en_ruta) VALUES (?, ?, 'pendiente', ?)";
                            PreparedStatement pstmt2 = conn.prepareStatement(insertEntrega);
                            for (int j = 0; j < ordenados.size(); j++) {
                                pstmt2.setString(1, token);
                                pstmt2.setInt(2, ordenados.get(j).id);
                                pstmt2.setInt(3, j + 1);
                                pstmt2.executeUpdate();
                            }

                            Map<String, Object> movil = new HashMap<>();
                            movil.put("movil", i + 1);
                            movil.put("token", token);
                            movil.put("clientes", clientesJson);
                            movil.put("distancia_total", distTotal);
                            movil.put("tiempo_estimado", tiempoEstimado);
                            movil.put("chofer_id", choferId);
                            movil.put("vehiculo_id", vehiculoId);
                            movil.put("chofer_nombre", choferNombre);
                            movil.put("vehiculo_nombre", vehiculoNombre);
                            movilesRespuesta.add(movil);
                        }
                    }

                    Map<String, Object> respuestaFinal = new HashMap<>();
                    respuestaFinal.put("moviles", movilesRespuesta);
                    sendResponse(exchange, 200, gson.toJson(respuestaFinal));

                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error generando rutas");
                }
            }
        }
    }

    static class RutaTokenHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod()))
                return;

            if ("GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                if (query == null || !query.contains("token=")) {
                    sendError(exchange, 400, "Token requerido");
                    return;
                }
                String token = query.split("token=")[1].split("&")[0];

                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT * FROM rutas_generadas WHERE token = ?";
                    PreparedStatement pstmt = conn.prepareStatement(sql);
                    pstmt.setString(1, token);
                    ResultSet rs = pstmt.executeQuery();

                    if (rs.next()) {
                        java.sql.Timestamp fechaRuta = rs.getTimestamp("fecha");
                        if (fechaRuta != null
                                && (System.currentTimeMillis() - fechaRuta.getTime()) > 2L * 24 * 60 * 60 * 1000) {
                            sendError(exchange, 403, "Esta ruta ha expirado (han pasado más de 2 días).");
                            return;
                        }

                        Map<String, Object> ruta = new HashMap<>();
                        ruta.put("movil", rs.getInt("movil_numero"));

                        ruta.put("distancia_total", rs.getDouble("distancia_total"));
                        ruta.put("tiempo_estimado", rs.getInt("tiempo_estimado"));
                        List<Map<String, Object>> clientes = gson.fromJson(rs.getString("clientes_json"), List.class);

                        // Agregar estados actuales de entregas
                        String sql2 = "SELECT cliente_id, estado, observacion FROM entregas WHERE ruta_token = ?";
                        PreparedStatement pstmt2 = conn.prepareStatement(sql2);
                        pstmt2.setString(1, token);
                        ResultSet rs2 = pstmt2.executeQuery();
                        Map<Integer, String> estados = new HashMap<>();
                        Map<Integer, String> obs = new HashMap<>();
                        while (rs2.next()) {
                            estados.put(rs2.getInt("cliente_id"), rs2.getString("estado"));
                            obs.put(rs2.getInt("cliente_id"), rs2.getString("observacion"));
                        }

                        for (Map<String, Object> c : clientes) {
                            int cid = ((Double) c.get("id")).intValue();
                            c.put("estado", estados.getOrDefault(cid, "pendiente"));
                            c.put("observacion", obs.getOrDefault(cid, ""));
                        }

                        ruta.put("clientes", clientes);
                        ruta.put("chofer_id", rs.getInt("chofer_id"));
                        ruta.put("vehiculo_id", rs.getInt("vehiculo_id"));
                        ruta.put("chofer_nombre", rs.getString("chofer_nombre"));
                        ruta.put("vehiculo_nombre", rs.getString("vehiculo_nombre"));
                        sendResponse(exchange, 200, gson.toJson(ruta));
                    } else {
                        sendError(exchange, 404, "Ruta no encontrada");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }

    static class EstadoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod()))
                return;

            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines()
                            .collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    String token = (String) req.get("token");
                    int clienteId = ((Double) req.get("cliente_id")).intValue();
                    String estado = (String) req.get("estado");
                    String observacion = req.containsKey("observacion") ? (String) req.get("observacion") : "";

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String checkSql = "SELECT fecha FROM rutas_generadas WHERE token = ?";
                        PreparedStatement pCheck = conn.prepareStatement(checkSql);
                        pCheck.setString(1, token);
                        ResultSet rsCheck = pCheck.executeQuery();
                        if (rsCheck.next()) {
                            java.sql.Timestamp fechaRuta = rsCheck.getTimestamp("fecha");
                            if (fechaRuta != null
                                    && (System.currentTimeMillis() - fechaRuta.getTime()) > 2L * 24 * 60 * 60 * 1000) {
                                sendError(exchange, 403, "Esta ruta ha expirado (han pasado más de 2 días).");
                                return;
                            }
                        } else {
                            sendError(exchange, 404, "Ruta no encontrada");
                            return;
                        }

                        String sql = "UPDATE entregas SET estado = ?, observacion = ?, fecha_actualizacion = CURRENT_TIMESTAMP WHERE ruta_token = ? AND cliente_id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, estado);
                        pstmt.setString(2, observacion);
                        pstmt.setString(3, token);
                        pstmt.setInt(4, clienteId);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }

    static class ReglasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT * FROM reglas_ruteo ORDER BY categoria";
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql);
                    List<Regla> reglas = new ArrayList<>();
                    while (rs.next()) {
                        reglas.add(new Regla(rs.getInt("id"), rs.getString("categoria"), rs.getInt("limite_por_movil"),
                                rs.getBoolean("activo")));
                    }
                    sendResponse(exchange, 200, gson.toJson(reglas));
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    List<Map<String, Object>> reqReglas = gson.fromJson(body, List.class);

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        for (Map<String, Object> r : reqReglas) {
                            String cat = (String) r.get("categoria");
                            int lim = ((Double) r.get("limite_por_movil")).intValue();
                            boolean act = (boolean) r.get("activo");

                            if (lim < 0) {
                                sendError(exchange, 400, "El limite por movil no puede ser negativo");
                                return;
                            }

                            String sql = "INSERT INTO reglas_ruteo (categoria, limite_por_movil, activo) " +
                                    "VALUES (?, ?, ?) ON CONFLICT (categoria) " +
                                    "DO UPDATE SET limite_por_movil = EXCLUDED.limite_por_movil, activo = EXCLUDED.activo";
                            PreparedStatement pstmt = conn.prepareStatement(sql);
                            pstmt.setString(1, cat);
                            pstmt.setInt(2, lim);
                            pstmt.setBoolean(3, act);
                            pstmt.executeUpdate();
                        }
                        sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String query = exchange.getRequestURI().getQuery();
                    if (query == null || !query.contains("categoria=")) {
                        sendError(exchange, 400, "Categoría requerida");
                        return;
                    }
                    String cat = java.net.URLDecoder.decode(query.split("categoria=")[1].split("&")[0], "UTF-8")
                            .toLowerCase();
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "DELETE FROM reglas_ruteo WHERE LOWER(categoria) = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, cat);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"deleted\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }

    }

    static class EstadisticasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                String periodo = "dia";
                if (query != null && query.contains("periodo=")) {
                    periodo = query.split("periodo=")[1].split("&")[0];
                }

                String dateFilter = switch (periodo) {
                    case "semana" -> "r.fecha >= CURRENT_DATE - INTERVAL '7 days'";
                    case "mes" -> "r.fecha >= CURRENT_DATE - INTERVAL '30 days'";
                    default -> "CAST(r.fecha AS DATE) = CURRENT_DATE";
                };

                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    // Totales por Estado
                    String sql = "SELECT estado, COUNT(*) as cantidad FROM entregas e JOIN rutas_generadas r ON e.ruta_token = r.token WHERE "
                            + dateFilter + " GROUP BY estado";
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql);

                    int entregados = 0, pendientes = 0, rechazados = 0;
                    while (rs.next()) {
                        String estado = rs.getString("estado");
                        if (estado.equals("entregado"))
                            entregados = rs.getInt("cantidad");
                        else if (estado.equals("rechazado"))
                            rechazados = rs.getInt("cantidad");
                        else
                            pendientes = rs.getInt("cantidad");
                    }
                    int total = entregados + pendientes + rechazados;
                    double exito = total > 0 ? (entregados * 100.0 / total) : 0;

                    Map<String, Object> res = new HashMap<>();
                    res.put("total_entregas", total);
                    res.put("entregados", entregados);
                    res.put("rechazados", rechazados);
                    res.put("pendientes", pendientes);
                    res.put("porcentaje_exito", Math.round(exito * 100.0) / 100.0);

                    // Rendimiento por móvil
                    String sqlRend = "SELECT r.movil_numero, " +
                            "ch.nombre as chofer, " +
                            "v.nombre as vehiculo, " +
                            "SUM(CASE WHEN e.estado = 'entregado' THEN 1 ELSE 0 END) as ent, " +
                            "COUNT(e.id) as tot " +
                            "FROM rutas_generadas r " +
                            "JOIN entregas e ON r.token = e.ruta_token " +
                            "LEFT JOIN choferes ch ON r.chofer_id = ch.id " +
                            "LEFT JOIN vehiculos v ON r.vehiculo_id = v.id " +
                            "WHERE " + dateFilter + " " +
                            "GROUP BY r.movil_numero, ch.nombre, v.nombre";
                    ResultSet rsRend = stmt.executeQuery(sqlRend);
                    List<Map<String, Object>> rendimientos = new ArrayList<>();
                    while (rsRend.next()) {
                        Map<String, Object> rm = new HashMap<>();
                        rm.put("movil", rsRend.getInt("movil_numero"));
                        rm.put("chofer", rsRend.getString("chofer"));
                        rm.put("vehiculo", rsRend.getString("vehiculo"));
                        rm.put("entregados", rsRend.getInt("ent"));
                        rm.put("total", rsRend.getInt("tot"));
                        rendimientos.add(rm);
                    }
                    res.put("rendimiento_por_movil", rendimientos);

                    // Historial (Tendencia)
                    String sqlHist = "SELECT r.fecha, " +
                            "SUM(CASE WHEN e.estado = 'entregado' THEN 1 ELSE 0 END) as ent " +
                            "FROM rutas_generadas r JOIN entregas e ON r.token = e.ruta_token " +
                            "WHERE " + dateFilter + " GROUP BY r.fecha ORDER BY r.fecha ASC";
                    ResultSet rsHist = stmt.executeQuery(sqlHist);
                    List<Map<String, Object>> historial = new ArrayList<>();
                    while (rsHist.next()) {
                        Map<String, Object> h = new HashMap<>();
                        h.put("fecha", rsHist.getDate("fecha").toString());
                        h.put("entregados", rsHist.getInt("ent"));
                        historial.add(h);
                    }
                    res.put("historial", historial);

                    sendResponse(exchange, 200, gson.toJson(res));
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }

    static class ReportesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT e.id, c.nombre as cliente, e.estado, e.observacion, e.fecha_actualizacion, r.movil_numero, "
                            +
                            "ch.nombre as chofer, v.nombre as vehiculo " +
                            "FROM entregas e " +
                            "JOIN clientes c ON e.cliente_id = c.id " +
                            "JOIN rutas_generadas r ON e.ruta_token = r.token " +
                            "LEFT JOIN choferes ch ON r.chofer_id = ch.id " +
                            "LEFT JOIN vehiculos v ON r.vehiculo_id = v.id " +
                            "WHERE e.estado != 'pendiente' " +
                            "ORDER BY e.fecha_actualizacion DESC LIMIT 50";
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql);

                    List<Map<String, Object>> reportes = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> rep = new HashMap<>();
                        rep.put("id", rs.getInt("id"));
                        rep.put("cliente", rs.getString("cliente"));
                        rep.put("estado", rs.getString("estado"));
                        rep.put("observacion", rs.getString("observacion"));
                        rep.put("fecha", rs.getTimestamp("fecha_actualizacion").toString());
                        rep.put("movil", rs.getInt("movil_numero"));
                        rep.put("chofer", rs.getString("chofer"));
                        rep.put("vehiculo", rs.getString("vehiculo"));
                        reportes.add(rep);
                    }
                    sendResponse(exchange, 200, gson.toJson(reportes));
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            sendResponse(exchange, 200, "{\"status\":\"OK\"}");
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(
                            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));

                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    String username = "";
                    String password = "";

                    if (json.has("auth")) {
                        // Soporte para el nuevo formato del frontend (Base64)
                        try {
                            String authPayload = json.get("auth").getAsString();
                            byte[] decodedBytes = java.util.Base64.getDecoder().decode(authPayload);
                            String decodedJson = new String(decodedBytes, StandardCharsets.UTF_8);
                            JsonObject authJson = JsonParser.parseString(decodedJson).getAsJsonObject();
                            username = authJson.has("username") ? authJson.get("username").getAsString() : "";
                            password = authJson.has("password") ? authJson.get("password").getAsString() : "";
                        } catch (Exception e) {
                            System.err.println("Error decodificando auth payload: " + e.getMessage());
                        }
                    } else {
                        // Formato tradicional
                        username = json.has("username") ? json.get("username").getAsString() : "";
                        password = json.has("password") ? json.get("password").getAsString() : "";
                    }

                    Map<String, Object> response = new HashMap<>();

                    // Consultar credenciales reales en la base de datos
                    Usuario usuario = usuarioRepo.login(username, password);

                    if (usuario != null) {
                        String sessionToken = java.util.UUID.randomUUID().toString();
                        activeTokens.put(sessionToken, System.currentTimeMillis());
                        response.put("success", true);
                        response.put("message", "Login exitoso");
                        response.put("usuario", usuario.getNombre());
                        response.put("token", sessionToken);
                        response.put("redirect", "index.html");
                        sendResponse(exchange, 200, gson.toJson(response));
                    } else {
                        response.put("success", false);
                        response.put("message", "Usuario o contraseña incorrectos");
                        sendResponse(exchange, 401, gson.toJson(response));
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private static void setCORS(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private static boolean isAuthorized(HttpExchange exchange) {
        List<String> authHeaders = exchange.getRequestHeaders().get("Authorization");
        if (authHeaders == null || authHeaders.isEmpty()) {
            return false;
        }
        String authHeader = authHeaders.get(0);
        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            Long creationTime = activeTokens.get(token);
            if (creationTime == null) return false;
            if (System.currentTimeMillis() - creationTime > TOKEN_TTL_MS) {
                activeTokens.remove(token);
                return false;
            }
            return true;
        }
        return false;
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, response.getBytes(StandardCharsets.UTF_8).length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes(StandardCharsets.UTF_8));
        os.close();
    }

    private static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        sendResponse(exchange, statusCode, gson.toJson(error));
    }

    // -- New RouteService class for OpenRouteService integration --
    static class RouteService {
        private static final String ORS_API_KEY = ORS_KEY.equals("PONER_AQUI_TU_API_KEY") ? System.getenv("ORS_API_KEY")
                : ORS_KEY;
        private static final String ORS_BASE_URL = "https://api.openrouteservice.org/v2/directions/driving-car";
        private static final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();

        static class RouteInfo {
            double distanceKm;
            double durationSec;

            RouteInfo(double distanceKm, double durationSec) {
                this.distanceKm = distanceKm;
                this.durationSec = durationSec;
            }
        }

        /**
         * Calls OpenRouteService for a single leg (start -> end).
         */
        public static RouteInfo getRoute(double startLat, double startLon, double endLat, double endLon)
                throws IOException, InterruptedException {
            if (ORS_API_KEY == null || ORS_API_KEY.isEmpty()) {
                throw new IllegalStateException("OpenRouteService API key not set in environment variable ORS_API_KEY");
            }
            String url = String.format(java.util.Locale.US, "%s?api_key=%s&start=%f,%f&end=%f,%f",
                    ORS_BASE_URL, ORS_API_KEY, startLon, startLat, endLon, endLat);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .header("Accept",
                            "application/json, application/geo+json, application/gpx+xml, img/png; charset=utf-8")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("OpenRouteService error: " + response.statusCode() + " " + response.body());
            }
            // Small delay to respect API rate limits (40 req/min on free tier)
            Thread.sleep(150);
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject summary = json.getAsJsonArray("features").get(0).getAsJsonObject()
                    .getAsJsonObject("properties").getAsJsonObject("summary");
            double distanceMeters = summary.get("distance").getAsDouble();
            double durationSeconds = summary.get("duration").getAsDouble();
            return new RouteInfo(distanceMeters / 1000.0, durationSeconds);
        }
    }

    // Existing handler classes continue below ...
    static class Cliente {
        int id;
        String nombre;
        double lat, lon;
        String tipo;

        Cliente(int id, String nombre, double lat, double lon, String tipo) {
            this.id = id;
            this.nombre = nombre;
            this.lat = lat;
            this.lon = lon;
            this.tipo = tipo;
        }
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371; // km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private static List<List<Cliente>> kmeans(List<Cliente> clientes, int k, Map<String, Integer> reglas) {
        if (clientes.size() <= k) {
            List<List<Cliente>> res = new ArrayList<>();
            for (Cliente c : clientes)
                res.add(new ArrayList<>(Arrays.asList(c)));
            return res;
        }

        List<double[]> centroids = new ArrayList<>();
        Random rand = new Random(42);
        for (int i = 0; i < k; i++) {
            Cliente c = clientes.get(rand.nextInt(clientes.size()));
            centroids.add(new double[] { c.lat, c.lon });
        }

        List<List<Cliente>> clusters = new ArrayList<>();
        for (int i = 0; i < k; i++)
            clusters.add(new ArrayList<>());

        boolean changed = true;
        int maxIter = 50;
        while (changed && maxIter-- > 0) {
            for (List<Cliente> cl : clusters)
                cl.clear();

            for (Cliente c : clientes) {
                int bestK = -1;
                double bestDist = Double.MAX_VALUE;

                // Intentar asignar al más cercano que cumpla las reglas
                for (int i = 0; i < k; i++) {
                    double d = haversine(c.lat, c.lon, centroids.get(i)[0], centroids.get(i)[1]);
                    if (d < bestDist && validarRegla(c, clusters.get(i), reglas)) {
                        bestDist = d;
                        bestK = i;
                    }
                }

                // Si ninguna cumple, asignar al más cercano por fuerza bruta (o manejar
                // excepción)
                if (bestK == -1) {
                    for (int i = 0; i < k; i++) {
                        double d = haversine(c.lat, c.lon, centroids.get(i)[0], centroids.get(i)[1]);
                        if (d < bestDist) {
                            bestDist = d;
                            bestK = i;
                        }
                    }
                }

                clusters.get(bestK).add(c);
            }

            changed = false;
            for (int i = 0; i < k; i++) {
                if (clusters.get(i).isEmpty())
                    continue;
                double sumLat = 0, sumLon = 0;
                for (Cliente c : clusters.get(i)) {
                    sumLat += c.lat;
                    sumLon += c.lon;
                }
                double newLat = sumLat / clusters.get(i).size();
                double newLon = sumLon / clusters.get(i).size();
                if (Math.abs(centroids.get(i)[0] - newLat) > 0.0001
                        || Math.abs(centroids.get(i)[1] - newLon) > 0.0001) {
                    changed = true;
                }
                centroids.get(i)[0] = newLat;
                centroids.get(i)[1] = newLon;
            }
        }
        return clusters;
    }

    private static boolean validarRegla(Cliente c, List<Cliente> cluster, Map<String, Integer> reglas) {
        if (c.tipo == null)
            return true;
        String cat = c.tipo.toLowerCase();
        if (!reglas.containsKey(cat))
            return true;

        int limite = reglas.get(cat);
        if (limite <= 0)
            return true; // Sin límite

        long actual = cluster.stream().filter(cl -> cl.tipo != null && cl.tipo.toLowerCase().equals(cat)).count();
        return actual < limite;
    }

    private static List<Cliente> nearestNeighborWithPriority(List<Cliente> cluster, String prioridad) {
        if (cluster.isEmpty())
            return cluster;
        List<Cliente> unvisitedPriority = new ArrayList<>();
        List<Cliente> unvisitedNormal = new ArrayList<>();

        for (Cliente c : cluster) {
            if (c.tipo != null && c.tipo.equalsIgnoreCase(prioridad))
                unvisitedPriority.add(c);
            else
                unvisitedNormal.add(c);
        }

        List<Cliente> result = new ArrayList<>();
        double currentLat = -25.3396; // Base Central
        double currentLon = -57.5173;

        // Primero los prioritarios
        while (!unvisitedPriority.isEmpty()) {
            Cliente best = findNearest(unvisitedPriority, currentLat, currentLon);
            result.add(best);
            currentLat = best.lat;
            currentLon = best.lon;
            unvisitedPriority.remove(best);
        }

        // Luego los normales
        while (!unvisitedNormal.isEmpty()) {
            Cliente best = findNearest(unvisitedNormal, currentLat, currentLon);
            result.add(best);
            currentLat = best.lat;
            currentLon = best.lon;
            unvisitedNormal.remove(best);
        }

        return result;
    }

    private static Cliente findNearest(List<Cliente> lista, double lat, double lon) {
        double bestDist = Double.MAX_VALUE;
        Cliente bestNext = null;
        for (Cliente c : lista) {
            double d = haversine(lat, lon, c.lat, c.lon);
            if (d < bestDist) {
                bestDist = d;
                bestNext = c;
            }
        }
        return bestNext;
    }

    private static double[] parseGoogleMapsUrl(String url) {
        try {
            url = url.trim();
            // Soporte para formato DMS: 25°07'53.7"S 57°20'51.7"W
            if (url.contains("°")) {
                return parseDMS(url);
            }
            // Soporte para coordenadas decimales simples: -25.123, -57.123
            if (url.contains(",") && !url.contains("http")) {
                String[] p = url.split(",");
                return new double[] { Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()) };
            }
            // Formato estándar @lat,lon de Google Maps
            if (url.contains("@")) {
                String part = url.split("@")[1];
                String[] coords = part.split(",");
                return new double[] { Double.parseDouble(coords[0]), Double.parseDouble(coords[1]) };
            }
            // Otros parámetros de búsqueda
            String[] patterns = { "q=", "ll=", "query=" };
            for (String p : patterns) {
                if (url.contains(p)) {
                    String part = url.split(p)[1].split("&")[0];
                    if (part.contains(",")) {
                        String[] coords = part.split(",");
                        return new double[] { Double.parseDouble(coords[0]), Double.parseDouble(coords[1]) };
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error parseando: " + url);
        }
        return new double[] { -25.286, -57.611 };
    }

    private static double[] parseDMS(String dms) {
        try {
            // Ejemplo: 25°07'53.7"S 57°20'51.7"W
            String[] parts = dms.split(" ");
            double lat = convertDMSToDecimal(parts[0]);
            double lon = convertDMSToDecimal(parts[1]);
            return new double[] { lat, lon };
        } catch (Exception e) {
            return new double[] { -25.286, -57.611 };
        }
    }

    private static double convertDMSToDecimal(String part) {
        // 25°07'53.7"S
        String degrees = part.split("°")[0];
        String minutes = part.split("°")[1].split("'")[0];
        String seconds = part.split("'")[1].split("\"")[0];
        String direction = part.substring(part.length() - 1);

        double dd = Math.abs(Double.parseDouble(degrees)) +
                (Double.parseDouble(minutes) / 60.0) +
                (Double.parseDouble(seconds) / 3600.0);

        if (direction.equalsIgnoreCase("S") || direction.equalsIgnoreCase("W")) {
            dd = dd * -1;
        }
        return dd;
    }

    private static String determinarCiudad(double lat, double lon) {
        Object[][] centros = {
                { "Asunción", -25.2864, -57.6115 },
                { "San Lorenzo", -25.3396, -57.5173 },
                { "Luque", -25.2691, -57.4851 },
                { "Lambaré", -25.3458, -57.6064 },
                { "Fernando de la Mora", -25.3261, -57.5544 },
                { "Capiatá", -25.3533, -57.4261 },
                { "Ñemby", -25.3941, -57.5352 },
                { "Mariano Roque Alonso", -25.2161, -57.5323 },
                { "Villa Elisa", -25.3671, -57.5901 },
                { "Itauguá", -25.3854, -57.3342 },
                { "Limpio", -25.1661, -57.4761 },
                { "Villa Hayes", -25.0931, -57.5250 },
                { "Benjamín Aceval", -25.0111, -57.3300 },
                { "Emboscada", -25.1141, -57.3481 },
                { "Arroyos y Esteros", -25.0661, -56.9331 }
        };
        String mejorCiudad = "Gran Asunción";
        double menorDistancia = 15.0; // Radio de búsqueda
        for (Object[] centro : centros) {
            double dist = haversine(lat, lon, (double) centro[1], (double) centro[2]);
            if (dist < menorDistancia) {
                menorDistancia = dist;
                mejorCiudad = (String) centro[0];
            }
        }
        return mejorCiudad;
    }

    static class ChoferesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT * FROM choferes WHERE activo = true ORDER BY nombre";
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql);
                    List<Map<String, Object>> choferes = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> c = new HashMap<>();
                        c.put("id", rs.getInt("id"));
                        c.put("nombre", rs.getString("nombre"));
                        c.put("telefono", rs.getString("telefono"));
                        choferes.add(c);
                    }
                    sendResponse(exchange, 200, gson.toJson(choferes));
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    String nombre = (String) req.get("nombre");
                    String telefono = (String) req.get("telefono");

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "INSERT INTO choferes (nombre, telefono) VALUES (?, ?)";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, nombre);
                        pstmt.setString(2, telefono);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 201, "{\"status\":\"ok\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String query = exchange.getRequestURI().getQuery();
                    int id = Integer.parseInt(query.split("id=")[1].split("&")[0]);
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "UPDATE choferes SET activo = false WHERE id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setInt(1, id);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"deleted\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    int id = ((Double) req.get("id")).intValue();
                    String nombre = (String) req.get("nombre");
                    String telefono = (String) req.get("telefono");
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "UPDATE choferes SET nombre = ?, telefono = ? WHERE id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, nombre);
                        pstmt.setString(2, telefono);
                        pstmt.setInt(3, id);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"updated\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    static class VehiculosHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT * FROM vehiculos WHERE activo = true ORDER BY nombre";
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql);
                    List<Map<String, Object>> vehiculos = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> v = new HashMap<>();
                        v.put("id", rs.getInt("id"));
                        v.put("nombre", rs.getString("nombre"));
                        v.put("chapa", rs.getString("chapa"));
                        v.put("tipo", rs.getString("tipo"));
                        vehiculos.add(v);
                    }
                    sendResponse(exchange, 200, gson.toJson(vehiculos));
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    String nombre = (String) req.get("nombre");
                    String chapa = (String) req.get("chapa");
                    String tipo = (String) req.get("tipo");

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "INSERT INTO vehiculos (nombre, chapa, tipo) VALUES (?, ?, ?)";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, nombre);
                        pstmt.setString(2, chapa);
                        pstmt.setString(3, tipo);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 201, "{\"status\":\"ok\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String query = exchange.getRequestURI().getQuery();
                    int id = Integer.parseInt(query.split("id=")[1].split("&")[0]);
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "UPDATE vehiculos SET activo = false WHERE id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setInt(1, id);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"deleted\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
                    Map<String, Object> req = gson.fromJson(body, Map.class);
                    int id = ((Double) req.get("id")).intValue();
                    String nombre = (String) req.get("nombre");
                    String chapa = (String) req.get("chapa");
                    String tipo = (String) req.get("tipo");
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "UPDATE vehiculos SET nombre = ?, chapa = ?, tipo = ? WHERE id = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, nombre);
                        pstmt.setString(2, chapa);
                        pstmt.setString(3, tipo);
                        pstmt.setInt(4, id);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"updated\"}");
                    }
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }
    static class AsignarRecursosHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    List<Map<String, Object>> assignments = gson.fromJson(body, List.class);

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        conn.setAutoCommit(false); // Usar transaccion
                        try {
                            for (Map<String, Object> asig : assignments) {
                                String token = (String) asig.get("token");
                                int cId = ((Double) asig.get("chofer_id")).intValue();
                                int vId = ((Double) asig.get("vehiculo_id")).intValue();

                                String cNombre = "";
                                String vNombre = "";

                                // Obtener nombres
                                try (PreparedStatement p1 = conn.prepareStatement("SELECT nombre FROM choferes WHERE id = ?")) {
                                    p1.setInt(1, cId);
                                    ResultSet rs1 = p1.executeQuery();
                                    if (rs1.next()) cNombre = rs1.getString("nombre");
                                }
                                try (PreparedStatement p2 = conn.prepareStatement("SELECT nombre FROM vehiculos WHERE id = ?")) {
                                    p2.setInt(1, vId);
                                    ResultSet rs2 = p2.executeQuery();
                                    if (rs2.next()) vNombre = rs2.getString("nombre");
                                }

                                // Si viene la lista de clientes (por cambios en el ruteo manual), actualizar clientes_json y entregas
                                if (asig.containsKey("clientes")) {
                                    List<Map<String, Object>> clientes = (List<Map<String, Object>>) asig.get("clientes");
                                    double distTot = (Double) asig.getOrDefault("distancia_total", 0.0);
                                    int tiempoEst = ((Double) asig.getOrDefault("tiempo_estimado", 0.0)).intValue();

                                    String sqlUpdate = "UPDATE rutas_generadas SET chofer_id = ?, vehiculo_id = ?, chofer_nombre = ?, vehiculo_nombre = ?, clientes_json = ?, distancia_total = ?, tiempo_estimado = ? WHERE token = ?";
                                    PreparedStatement pstmt = conn.prepareStatement(sqlUpdate);
                                    pstmt.setInt(1, cId);
                                    pstmt.setInt(2, vId);
                                    pstmt.setString(3, cNombre);
                                    pstmt.setString(4, vNombre);
                                    pstmt.setString(5, gson.toJson(clientes));
                                    pstmt.setDouble(6, distTot);
                                    pstmt.setInt(7, tiempoEst);
                                    pstmt.setString(8, token);
                                    pstmt.executeUpdate();

                                    // Actualizar entregas: Borrar anteriores y reinsertar según el nuevo orden/lista
                                    try (PreparedStatement pDel = conn.prepareStatement("DELETE FROM entregas WHERE ruta_token = ?")) {
                                        pDel.setString(1, token);
                                        pDel.executeUpdate();
                                    }
                                    
                                    String insertEntregas = "INSERT INTO entregas (ruta_token, cliente_id, estado, orden_en_ruta) VALUES (?, ?, 'pendiente', ?)";
                                    try (PreparedStatement pIns = conn.prepareStatement(insertEntregas)) {
                                        for (int i = 0; i < clientes.size(); i++) {
                                            int clientId = ((Double) clientes.get(i).get("id")).intValue();
                                            pIns.setString(1, token);
                                            pIns.setInt(2, clientId);
                                            pIns.setInt(3, i + 1);
                                            pIns.addBatch();
                                        }
                                        pIns.executeBatch();
                                    }
                                } else {
                                    // Solo actualizar recursos (formato antiguo o sin cambios de ruteo)
                                    String sql = "UPDATE rutas_generadas SET chofer_id = ?, vehiculo_id = ?, chofer_nombre = ?, vehiculo_nombre = ? WHERE token = ?";
                                    PreparedStatement pstmt = conn.prepareStatement(sql);
                                    pstmt.setInt(1, cId);
                                    pstmt.setInt(2, vId);
                                    pstmt.setString(3, cNombre);
                                    pstmt.setString(4, vNombre);
                                    pstmt.setString(5, token);
                                    pstmt.executeUpdate();
                                }
                            }
                            conn.commit();
                        } catch (Exception e) {
                            conn.rollback();
                            throw e;
                        }
                    }
                    sendResponse(exchange, 200, "{\"status\":\"ok\"}");
                } catch (Exception e) {
                    e.printStackTrace();
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }

    static class KmlImportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    String url = json.has("url") ? json.get("url").getAsString() : "";
                    
                    if (url.isEmpty()) { sendError(exchange, 400, "URL requerida"); return; }
                    
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        sendError(exchange, 400, "URL inválida");
                        return;
                    }

                    java.net.URI parsedUri = java.net.URI.create(url);
                    String host = parsedUri.getHost();
                    if (host == null) { sendError(exchange, 400, "URL inválida"); return; }
                    String hostLower = host.toLowerCase();
                    if (hostLower.equals("localhost") || hostLower.equals("127.0.0.1") || hostLower.equals("[::1]")
                            || hostLower.startsWith("10.") || hostLower.startsWith("172.") || hostLower.startsWith("192.168.")
                            || hostLower.startsWith("0.") || hostLower.endsWith(".local") || hostLower.endsWith(".internal")) {
                        sendError(exchange, 400, "URL no permitida");
                        return;
                    }
                    
                    if (url.contains("google.com/maps/d/")) {
                        if (url.contains("mid=")) {
                            String mid = url.split("mid=")[1].split("&")[0];
                            url = "https://www.google.com/maps/d/u/0/kml?mid=" + mid + "&forcekml=1";
                        }
                    }

                    HttpClient client = HttpClient.newHttpClient();
                    HttpRequest request = HttpRequest.newBuilder().uri(java.net.URI.create(url)).build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() != 200) {
                        sendError(exchange, 500, "Error al descargar KML: " + response.statusCode());
                        return;
                    }

                    String kml = response.body();
                    List<Map<String, String>> points = parseKml(kml);

                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "INSERT INTO clientes (nombre, latitud, longitud, ciudad, tipo_cliente, activo) VALUES (?, ?, ?, ?, 'General', true)";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        for (Map<String, String> p : points) {
                            double lat = Double.parseDouble(p.get("lat"));
                            double lon = Double.parseDouble(p.get("lon"));
                            pstmt.setString(1, p.get("name"));
                            pstmt.setDouble(2, lat);
                            pstmt.setDouble(3, lon);
                            pstmt.setString(4, determinarCiudad(lat, lon));
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }
                    sendResponse(exchange, 200, "{\"status\":\"ok\", \"imported\":" + points.size() + "}");
                } catch (Exception e) {
                    e.printStackTrace();
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }

        private List<Map<String, String>> parseKml(String kml) {
            List<Map<String, String>> points = new ArrayList<>();
            try {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("<Placemark>(.*?)</Placemark>", java.util.regex.Pattern.DOTALL);
                java.util.regex.Matcher m = p.matcher(kml);
                while (m.find()) {
                    String content = m.group(1);
                    String name = "Sin nombre";
                    if (content.contains("<name>")) {
                        name = content.split("<name>")[1].split("</name>")[0].replaceAll("<!\\[CDATA\\[(.*)\\]\\]>", "$1");
                    }
                    if (content.contains("<coordinates>")) {
                        String coords = content.split("<coordinates>")[1].split("</coordinates>")[0].trim();
                        String[] parts = coords.split(",");
                        if (parts.length >= 2) {
                            Map<String, String> point = new HashMap<>();
                            point.put("name", name);
                            point.put("lon", parts[0].trim());
                            point.put("lat", parts[1].trim());
                            points.add(point);
                        }
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
            return points;
        }
    }

    static class KmlExportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, 401, "No autorizado");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT nombre, latitud, longitud FROM clientes WHERE activo = true";
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql);

                    StringBuilder kml = new StringBuilder();
                    kml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                    kml.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
                    kml.append("<Document>\n");
                    kml.append("  <name>Clientes Ruteo</name>\n");

                    while (rs.next()) {
                        kml.append("  <Placemark>\n");
                        kml.append("    <name>").append(rs.getString("nombre")).append("</name>\n");
                        kml.append("    <Point>\n");
                        kml.append("      <coordinates>").append(rs.getDouble("longitud")).append(",").append(rs.getDouble("latitud")).append(",0</coordinates>\n");
                        kml.append("    </Point>\n");
                        kml.append("  </Placemark>\n");
                    }

                    kml.append("</Document>\n");
                    kml.append("</kml>");

                    byte[] resp = kml.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/vnd.google-earth.kml+xml");
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"clientes_exportados.kml\"");
                    exchange.sendResponseHeaders(200, resp.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(resp);
                    os.close();
                } catch (Exception e) {
                    e.printStackTrace(); sendError(exchange, 500, "Error interno del servidor");
                }
            }
        }
    }
}