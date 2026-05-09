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
    private static final String DB_URL = "jdbc:postgresql://localhost:5000/ruteo_db";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "Zelaya1103";
    private static final String ORS_KEY = System.getenv("ORS_API_KEY") != null ? System.getenv("ORS_API_KEY") : "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6ImE2Y2NjNjBiOTNiYjRlMTZiNmY2MDQxZGI3NWYyZTljIiwiaCI6Im11cm11cjY0In0="; 
    private static final String FRONTEND_DIR = "../frontend";

    // Gestión de Sesiones (In-memory para esta demo/proyecto PyME)
    static class SessionManager {
        private static final Map<String, String> activeSessions = new ConcurrentHashMap<>();
        
        public static void addSession(String token, String username) {
            activeSessions.put(token, username);
        }
        
        public static boolean isValid(String token) {
            return token != null && activeSessions.containsKey(token);
        }
        
        public static void removeSession(String token) {
            activeSessions.remove(token);
        }
    }
    
    // Modelos de Reglas
    static class Regla {
        int id;
        String categoria;
        int limite_por_movil;
        boolean activo;
        Regla(int id, String categoria, int limite, boolean activo) {
            this.id = id; this.categoria = categoria; this.limite_por_movil = limite; this.activo = activo;
        }
    }
    private static final UsuarioRepository usuarioRepo = new UsuarioRepository(DB_URL, DB_USER, DB_PASSWORD);

    
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);
        
        // Archivos estaticos
        server.createContext("/", new StaticHandler());
        
        // Configurar API
        server.createContext("/api/clientes", new ClientesHandler());
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/generar-rutas", new RutasHandler());
        server.createContext("/api/ruta", new RutaTokenHandler());
        server.createContext("/api/actualizar-estado", new EstadoHandler());
        server.createContext("/api/estadisticas", new EstadisticasHandler());
        server.createContext("/api/reportes", new ReportesHandler());
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/reglas", new ReglasHandler());
        
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
            File file = new File(FRONTEND_DIR + path);
            if (file.exists() && !file.isDirectory()) {
                String contentType = "text/html";
                if (path.endsWith(".css")) contentType = "text/css";
                else if (path.endsWith(".js")) contentType = "application/javascript";
                else if (path.endsWith(".png")) contentType = "image/png";
                
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
            if (!isAuthorized(exchange)) return;
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
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
                        cliente.put("seleccionado", false);
                        clientes.add(cliente);
                    }
                    sendResponse(exchange, 200, gson.toJson(clientes));
                } catch (SQLException e) {
                    sendError(exchange, 500, "Error de base de datos");
                }
            } 
            else if ("POST".equals(exchange.getRequestMethod()) || "PUT".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
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
                    sendError(exchange, 500, "Error: " + e.getMessage());
                }
            } 
            else if ("DELETE".equals(exchange.getRequestMethod())) {
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
                    sendError(exchange, 500, "Error: " + e.getMessage());
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
            if (!isAuthorized(exchange)) return;
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining("\n"));
                    
                    Map<String, Object> request = gson.fromJson(body, Map.class);
                    System.out.println("Generando rutas con: " + body);

                    List<Double> cIds = (List<Double>) request.get("cliente_ids");
                    Object numMovilesObj = request.get("num_moviles");
                    int numMoviles = 1;
                    if (numMovilesObj instanceof Double) numMoviles = ((Double) numMovilesObj).intValue();
                    else if (numMovilesObj instanceof Integer) numMoviles = (Integer) numMovilesObj;
                    
                    if (cIds == null || cIds.isEmpty()) {
                        sendError(exchange, 400, "cliente_ids es requerido");
                        return;
                    }
                    List<Integer> clienteIds = cIds.stream().map(Double::intValue).collect(Collectors.toList());
                    
                    List<Cliente> clientes = new ArrayList<>();
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        // Usar PreparedStatement para evitar SQL Injection incluso con IDs numéricos
                        StringBuilder sqlBuilder = new StringBuilder("SELECT id, nombre, latitud, longitud, tipo_cliente FROM clientes WHERE id IN (");
                        for (int i = 0; i < clienteIds.size(); i++) {
                            sqlBuilder.append("?");
                            if (i < clienteIds.size() - 1) sqlBuilder.append(",");
                        }
                        sqlBuilder.append(")");
                        
                        PreparedStatement pstmt = conn.prepareStatement(sqlBuilder.toString());
                        for (int i = 0; i < clienteIds.size(); i++) {
                            pstmt.setInt(i + 1, clienteIds.get(i));
                        }
                        
                        ResultSet rs = pstmt.executeQuery();
                        while (rs.next()) {
                            clientes.add(new Cliente(rs.getInt("id"), rs.getString("nombre"), rs.getDouble("latitud"), rs.getDouble("longitud"), rs.getString("tipo_cliente")));
                        }
                    }
                    
                    String prioridad = (String) request.getOrDefault("prioridad", "ninguna");
                    String puntoInicio = (String) request.getOrDefault("punto_inicio", "Base Central - San Lorenzo");
                    double[] startCoords = parseGoogleMapsUrl(puntoInicio);
                    // Si el parseo devuelve el default genérico pero el texto es el de la base, forzar coordenadas de la base
                    if (puntoInicio.contains("Base Central") && startCoords[0] == -25.286) {
                        startCoords = new double[]{-25.3396, -57.5173};
                    }
                    
                    Object usarReglasObj = request.get("usar_reglas");
                    boolean usarReglas = true;
                    if (usarReglasObj instanceof Boolean) usarReglas = (Boolean) usarReglasObj;

                    // Obtener reglas activas (solo si el usuario quiere aplicarlas)
                    Map<String, Integer> reglasActivas = new HashMap<>();
                    if (usarReglas) {
                        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                            String sqlReg = "SELECT categoria, limite_por_movil FROM reglas_ruteo WHERE activo = true";
                            Statement stmtReg = conn.createStatement();
                            ResultSet rsReg = stmtReg.executeQuery(sqlReg);
                            while(rsReg.next()) {
                                reglasActivas.put(rsReg.getString("categoria").toLowerCase(), rsReg.getInt("limite_por_movil"));
                            }
                        }
                    }

                    // K-Means adaptado con REGLAS
                    List<List<Cliente>> clusters = kmeans(clientes, numMoviles, reglasActivas);
                    
                    List<Map<String, Object>> movilesRespuesta = new ArrayList<>();
                    
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        for (int i = 0; i < clusters.size(); i++) {
                            List<Cliente> cluster = clusters.get(i);
                            if (cluster.isEmpty()) continue;
                            
                            // Vecino mas cercano para ordenar con prioridad partiendo desde el punto configurado
                            List<Cliente> ordenados = nearestNeighborWithPriority(cluster, prioridad, startCoords[0], startCoords[1]);
                            
                            double distTotal = 0;
                            List<Map<String, Object>> clientesJson = new ArrayList<>();
                            for (int j = 0; j < ordenados.size(); j++) {
                                Cliente c = ordenados.get(j);
                                double dist = 0;
                                if (j < ordenados.size() - 1) {
                                    Cliente siguiente = ordenados.get(j+1);
                                    try {
                                        // Intentar usar OpenRouteService (Distancia real por carretera)
                                        RouteService.RouteInfo info = RouteService.getRoute(c.lat, c.lon, siguiente.lat, siguiente.lon);
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
                            
                            // Insertar ruta
                            String insertRuta = "INSERT INTO rutas_generadas (token, movil_numero, clientes_json, distancia_total, tiempo_estimado) VALUES (?, ?, ?, ?, ?)";
                            PreparedStatement pstmt = conn.prepareStatement(insertRuta);
                            pstmt.setString(1, token);
                            pstmt.setInt(2, i + 1);
                            pstmt.setString(3, gson.toJson(clientesJson));
                            pstmt.setDouble(4, distTotal);
                            pstmt.setInt(5, 0); // Tiempo estimado removido (seteado a 0)
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
                            movil.put("distancia_total", distTotal);
                            movilesRespuesta.add(movil);
                        }
                    }

                    Map<String, Object> finalRes = new HashMap<>();
                    finalRes.put("moviles", movilesRespuesta);
                    finalRes.put("startLat", startCoords[0]);
                    finalRes.put("startLon", startCoords[1]);
                    sendResponse(exchange, 200, gson.toJson(finalRes));
                    
                } catch (Exception e) {
                    e.printStackTrace();
                    sendError(exchange, 500, "Error generando rutas: " + e.getMessage());
                }
            }
        }
    }

    static class RutaTokenHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if (!isAuthorized(exchange)) return;
            if ("OPTIONS".equals(exchange.getRequestMethod())) return;
            
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
                        if (fechaRuta != null && (System.currentTimeMillis() - fechaRuta.getTime()) > 2L * 24 * 60 * 60 * 1000) {
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
                        while(rs2.next()){
                            estados.put(rs2.getInt("cliente_id"), rs2.getString("estado"));
                            obs.put(rs2.getInt("cliente_id"), rs2.getString("observacion"));
                        }
                        
                        for(Map<String, Object> c : clientes) {
                            int cid = ((Double)c.get("id")).intValue();
                            c.put("estado", estados.getOrDefault(cid, "pendiente"));
                            c.put("observacion", obs.getOrDefault(cid, ""));
                        }
                        
                        ruta.put("clientes", clientes);
                        sendResponse(exchange, 200, gson.toJson(ruta));
                    } else {
                        sendError(exchange, 404, "Ruta no encontrada");
                    }
                } catch (Exception e) {
                    sendError(exchange, 500, "Error " + e.getMessage());
                }
            }
        }
    }

    static class EstadoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if (!isAuthorized(exchange)) return;
            if ("OPTIONS".equals(exchange.getRequestMethod())) return;
            
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
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
                            if (fechaRuta != null && (System.currentTimeMillis() - fechaRuta.getTime()) > 2L * 24 * 60 * 60 * 1000) {
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
                    sendError(exchange, 500, e.getMessage());
                }
            }
        }
    }

    static class ReglasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if (!isAuthorized(exchange)) return;
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT * FROM reglas_ruteo ORDER BY categoria";
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql);
                    List<Regla> reglas = new ArrayList<>();
                    while (rs.next()) {
                        reglas.add(new Regla(rs.getInt("id"), rs.getString("categoria"), rs.getInt("limite_por_movil"), rs.getBoolean("activo")));
                    }
                    sendResponse(exchange, 200, gson.toJson(reglas));
                } catch (Exception e) {
                    sendError(exchange, 500, e.getMessage());
                }
            } else if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining("\n"));
                    List<Map<String, Object>> reqReglas = gson.fromJson(body, List.class);
                    
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        for (Map<String, Object> r : reqReglas) {
                            String cat = (String) r.get("categoria");
                            int lim = ((Double) r.get("limite_por_movil")).intValue();
                            boolean act = (boolean) r.get("activo");
                            
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
                    sendError(exchange, 500, e.getMessage());
                }
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                try {
                    String query = exchange.getRequestURI().getQuery();
                    if (query == null || !query.contains("categoria=")) {
                        sendError(exchange, 400, "Categoría requerida");
                        return;
                    }
                    String cat = java.net.URLDecoder.decode(query.split("categoria=")[1].split("&")[0], "UTF-8").toLowerCase();
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String sql = "DELETE FROM reglas_ruteo WHERE LOWER(categoria) = ?";
                        PreparedStatement pstmt = conn.prepareStatement(sql);
                        pstmt.setString(1, cat);
                        pstmt.executeUpdate();
                        sendResponse(exchange, 200, "{\"status\":\"deleted\"}");
                    }
                } catch (Exception e) {
                    sendError(exchange, 500, e.getMessage());
                }
            }
        }

    }

    static class EstadisticasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if (!isAuthorized(exchange)) return;
            if ("GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                String periodo = "dia";
                if (query != null && query.contains("periodo=")) {
                    periodo = query.split("periodo=")[1].split("&")[0];
                }

                String fecha = null;
                if (query != null && query.contains("fecha=")) {
                    fecha = query.split("fecha=")[1].split("&")[0];
                }

                String dateFilter;
                boolean useParam = false;
                if (fecha != null && !fecha.isEmpty()) {
                    dateFilter = "CAST(r.fecha AS DATE) = CAST(? AS DATE)";
                    useParam = true;
                } else {
                    if ("semana".equals(periodo)) {
                        dateFilter = "r.fecha >= CURRENT_DATE - INTERVAL '7 days'";
                    } else if ("mes".equals(periodo)) {
                        dateFilter = "r.fecha >= CURRENT_DATE - INTERVAL '30 days'";
                    } else {
                        dateFilter = "CAST(r.fecha AS DATE) = CURRENT_DATE";
                    }
                }

                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    // Totales por Estado
                    String sql = "SELECT estado, COUNT(*) as cantidad FROM entregas e JOIN rutas_generadas r ON e.ruta_token = r.token WHERE " + dateFilter + " GROUP BY estado";
                    PreparedStatement pstmt = conn.prepareStatement(sql);
                    if (useParam) pstmt.setString(1, fecha);
                    ResultSet rs = pstmt.executeQuery();
                    
                    int entregados = 0, pendientes = 0, rechazados = 0;
                    while(rs.next()){
                        String estado = rs.getString("estado");
                        if(estado.equals("entregado")) entregados = rs.getInt("cantidad");
                        else if(estado.equals("rechazado")) rechazados = rs.getInt("cantidad");
                        else pendientes = rs.getInt("cantidad");
                    }
                    int total = entregados + pendientes + rechazados;
                    double exito = total > 0 ? (entregados * 100.0 / total) : 0;
                    
                    Map<String, Object> res = new HashMap<>();
                    res.put("total_entregas", total);
                    res.put("entregados", entregados);
                    res.put("rechazados", rechazados);
                    res.put("pendientes", pendientes);
                    res.put("porcentaje_exito", Math.round(exito * 100.0)/100.0);
                    
                    // Rendimiento por móvil
                    String sqlRend = "SELECT r.movil_numero, " +
                        "SUM(CASE WHEN e.estado = 'entregado' THEN 1 ELSE 0 END) as ent, " +
                        "COUNT(e.id) as tot " +
                        "FROM rutas_generadas r JOIN entregas e ON r.token = e.ruta_token " +
                        "WHERE " + dateFilter + " GROUP BY r.movil_numero";
                    PreparedStatement pstmtRend = conn.prepareStatement(sqlRend);
                    if (useParam) pstmtRend.setString(1, fecha);
                    ResultSet rsRend = pstmtRend.executeQuery();
                    List<Map<String, Object>> rendimientos = new ArrayList<>();
                    while(rsRend.next()){
                        Map<String, Object> rm = new HashMap<>();
                        rm.put("movil", rsRend.getInt("movil_numero"));
                        rm.put("entregados", rsRend.getInt("ent"));
                        rm.put("total", rsRend.getInt("tot"));
                        rendimientos.add(rm);
                    }
                    res.put("rendimiento_por_movil", rendimientos);

                    // Historial (Tendencia)
                    String sqlHist = "SELECT CAST(r.fecha AS DATE) as f, " +
                        "SUM(CASE WHEN e.estado = 'entregado' THEN 1 ELSE 0 END) as ent " +
                        "FROM rutas_generadas r JOIN entregas e ON r.token = e.ruta_token " +
                        "WHERE " + dateFilter + " GROUP BY CAST(r.fecha AS DATE) ORDER BY CAST(r.fecha AS DATE) ASC";
                    PreparedStatement pstmtHist = conn.prepareStatement(sqlHist);
                    if (useParam) pstmtHist.setString(1, fecha);
                    ResultSet rsHist = pstmtHist.executeQuery();
                    List<Map<String, Object>> historial = new ArrayList<>();
                    while(rsHist.next()){
                        Map<String, Object> h = new HashMap<>();
                        h.put("fecha", rsHist.getDate("f").toString());
                        h.put("entregados", rsHist.getInt("ent"));
                        historial.add(h);
                    }
                    res.put("historial", historial);
                    
                    sendResponse(exchange, 200, gson.toJson(res));
                } catch (Exception e) {
                    sendError(exchange, 500, e.getMessage());
                }
            }
        }
    }

    static class ReportesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if (!isAuthorized(exchange)) return;
            if ("GET".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                String fecha = null;
                if (query != null && query.contains("fecha=")) {
                    fecha = query.split("fecha=")[1].split("&")[0];
                }

                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT e.id, c.nombre as cliente, e.estado, e.observacion, e.fecha_actualizacion, r.movil_numero " +
                                 "FROM entregas e " +
                                 "JOIN clientes c ON e.cliente_id = c.id " +
                                 "JOIN rutas_generadas r ON e.ruta_token = r.token " +
                                 "WHERE e.estado != 'pendiente' ";
                    
                    if (fecha != null && !fecha.isEmpty()) {
                        sql += " AND CAST(e.fecha_actualizacion AS DATE) = CAST(? AS DATE) ";
                    }
                    
                    sql += "ORDER BY e.fecha_actualizacion DESC LIMIT 50";
                    
                    PreparedStatement pstmt = conn.prepareStatement(sql);
                    if (fecha != null && !fecha.isEmpty()) {
                        pstmt.setString(1, fecha);
                    }
                    ResultSet rs = pstmt.executeQuery();
                    
                    List<Map<String, Object>> reportes = new ArrayList<>();
                    while(rs.next()){
                        Map<String, Object> rep = new HashMap<>();
                        rep.put("id", rs.getInt("id"));
                        rep.put("cliente", rs.getString("cliente"));
                        rep.put("estado", rs.getString("estado"));
                        rep.put("observacion", rs.getString("observacion"));
                        rep.put("fecha", rs.getTimestamp("fecha_actualizacion").toString());
                        rep.put("movil", rs.getInt("movil_numero"));
                        reportes.add(rep);
                    }
                    sendResponse(exchange, 200, gson.toJson(reportes));
                } catch (Exception e) {
                    sendError(exchange, 500, e.getMessage());
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
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                            .lines().collect(Collectors.joining("\n"));
                    
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    
                    // Decodificar payload ofuscado si existe
                    if (json.has("auth")) {
                        try {
                            String decoded = new String(java.util.Base64.getDecoder().decode(json.get("auth").getAsString()), StandardCharsets.UTF_8);
                            json = JsonParser.parseString(decoded).getAsJsonObject();
                        } catch (Exception ex) {
                            System.err.println("Error decodificando auth: " + ex.getMessage());
                        }
                    }

                    String username = json.has("username") ? json.get("username").getAsString() : "";
                    String password = json.has("password") ? json.get("password").getAsString() : "";
                    
                    System.out.println("Intento de login para usuario: " + username);

                    Map<String, Object> response = new HashMap<>();
                    
                    // Consultar credenciales reales en la base de datos
                    Usuario usuario = usuarioRepo.login(username, password);

                    if (usuario != null) {
                        String sessionToken = java.util.UUID.randomUUID().toString();
                        SessionManager.addSession(sessionToken, usuario.getUsername());
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
                    sendError(exchange, 500, "Error en el servidor: " + e.getMessage());
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private static void setCORS(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
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

    private static boolean isAuthorized(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String token = null;
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            // Fallback para query param si es necesario (ej: para los choferes en /api/ruta)
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("token=")) {
                token = query.split("token=")[1].split("&")[0];
            }
        }

        if (SessionManager.isValid(token)) {
            return true;
        }

        // Caso especial: Endpoints de choferes usan validación por token de base de datos
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/ruta") || path.equals("/api/actualizar-estado")) {
            return true; 
        }

        sendError(exchange, 401, "No autorizado. Sesión inválida o expirada.");
        return false;
    }

    // -- New RouteService class for OpenRouteService integration --
    static class RouteService {
        private static final String ORS_API_KEY = ORS_KEY.equals("PONER_AQUI_TU_API_KEY") ? System.getenv("ORS_API_KEY") : ORS_KEY;
        private static final String ORS_BASE_URL = "https://api.openrouteservice.org/v2/directions/driving-car";
        private static final HttpClient httpClient = HttpClient.newHttpClient();

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
        public static RouteInfo getRoute(double startLat, double startLon, double endLat, double endLon) throws IOException, InterruptedException {
            if (ORS_API_KEY == null || ORS_API_KEY.isEmpty()) {
                throw new IllegalStateException("OpenRouteService API key not set in environment variable ORS_API_KEY");
            }
            String url = String.format(java.util.Locale.US, "%s?api_key=%s&start=%f,%f&end=%f,%f",
                ORS_BASE_URL, ORS_API_KEY, startLon, startLat, endLon, endLat);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .header("Accept", "application/json, application/geo+json, application/gpx+xml, img/png; charset=utf-8")
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("OpenRouteService error: " + response.statusCode() + " " + response.body());
            }
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
        int id; String nombre; double lat, lon; String tipo;
        Cliente(int id, String nombre, double lat, double lon, String tipo){
            this.id = id; this.nombre = nombre; this.lat = lat; this.lon = lon; this.tipo = tipo;
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
            for (Cliente c : clientes) res.add(new ArrayList<>(Arrays.asList(c)));
            return res;
        }
        
        List<double[]> centroids = new ArrayList<>();
        Random rand = new Random(42);
        for (int i=0; i<k; i++) {
            Cliente c = clientes.get(rand.nextInt(clientes.size()));
            centroids.add(new double[]{c.lat, c.lon});
        }
        
        List<List<Cliente>> clusters = new ArrayList<>();
        for (int i=0; i<k; i++) clusters.add(new ArrayList<>());
        
        boolean changed = true;
        int maxIter = 50;
        while(changed && maxIter-- > 0) {
            for(List<Cliente> cl : clusters) cl.clear();
            
            for (Cliente c : clientes) {
                int bestK = -1;
                double bestDist = Double.MAX_VALUE;
                
                // Intentar asignar al más cercano que cumpla las reglas
                for (int i=0; i<k; i++) {
                    double d = haversine(c.lat, c.lon, centroids.get(i)[0], centroids.get(i)[1]);
                    if (d < bestDist && validarRegla(c, clusters.get(i), reglas)) {
                        bestDist = d;
                        bestK = i;
                    }
                }
                
                // Si ninguna cumple, asignar al más cercano por fuerza bruta (o manejar excepción)
                if (bestK == -1) {
                    for (int i=0; i<k; i++) {
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
            for (int i=0; i<k; i++) {
                if (clusters.get(i).isEmpty()) continue;
                double sumLat = 0, sumLon = 0;
                for (Cliente c : clusters.get(i)) {
                    sumLat += c.lat; sumLon += c.lon;
                }
                double newLat = sumLat / clusters.get(i).size();
                double newLon = sumLon / clusters.get(i).size();
                if (Math.abs(centroids.get(i)[0] - newLat) > 0.0001 || Math.abs(centroids.get(i)[1] - newLon) > 0.0001) {
                    changed = true;
                }
                centroids.get(i)[0] = newLat;
                centroids.get(i)[1] = newLon;
            }
        }
        return clusters;
    }

    private static boolean validarRegla(Cliente c, List<Cliente> cluster, Map<String, Integer> reglas) {
        if (c.tipo == null) return true;
        String cat = c.tipo.toLowerCase();
        if (!reglas.containsKey(cat)) return true;
        
        int limite = reglas.get(cat);
        if (limite <= 0) return true; // Sin límite

        long actual = cluster.stream().filter(cl -> cl.tipo != null && cl.tipo.toLowerCase().equals(cat)).count();
        return actual < limite;
    }

    private static List<Cliente> nearestNeighborWithPriority(List<Cliente> cluster, String prioridad, double startLat, double startLon) {
        if (cluster.isEmpty()) return cluster;
        List<Cliente> unvisitedPriority = new ArrayList<>();
        List<Cliente> unvisitedNormal = new ArrayList<>();
        
        for (Cliente c : cluster) {
            if (c.tipo != null && c.tipo.equalsIgnoreCase(prioridad)) unvisitedPriority.add(c);
            else unvisitedNormal.add(c);
        }
        
        List<Cliente> result = new ArrayList<>();
        double currentLat = startLat;
        double currentLon = startLon;

        // Primero los prioritarios
        while(!unvisitedPriority.isEmpty()) {
            Cliente best = findNearest(unvisitedPriority, currentLat, currentLon);
            result.add(best);
            currentLat = best.lat; currentLon = best.lon;
            unvisitedPriority.remove(best);
        }
        
        // Luego los normales
        while(!unvisitedNormal.isEmpty()) {
            Cliente best = findNearest(unvisitedNormal, currentLat, currentLon);
            result.add(best);
            currentLat = best.lat; currentLon = best.lon;
            unvisitedNormal.remove(best);
        }
        
        return result;
    }

    private static Cliente findNearest(List<Cliente> lista, double lat, double lon) {
        double bestDist = Double.MAX_VALUE;
        Cliente bestNext = null;
        for(Cliente c : lista) {
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
                return new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())};
            }
            // Formato estándar @lat,lon de Google Maps
            if (url.contains("@")) {
                String part = url.split("@")[1];
                String[] coords = part.split(",");
                return new double[]{Double.parseDouble(coords[0]), Double.parseDouble(coords[1])};
            }
            // Otros parámetros de búsqueda
            String[] patterns = {"q=", "ll=", "query="};
            for (String p : patterns) {
                if (url.contains(p)) {
                    String part = url.split(p)[1].split("&")[0];
                    if (part.contains(",")) {
                        String[] coords = part.split(",");
                        return new double[]{Double.parseDouble(coords[0]), Double.parseDouble(coords[1])};
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error parseando: " + url);
        }
        return new double[]{-25.286, -57.611};
    }

    private static double[] parseDMS(String dms) {
        try {
            // Ejemplo: 25°07'53.7"S 57°20'51.7"W
            String[] parts = dms.split(" ");
            double lat = convertDMSToDecimal(parts[0]);
            double lon = convertDMSToDecimal(parts[1]);
            return new double[]{lat, lon};
        } catch (Exception e) {
            return new double[]{-25.286, -57.611};
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
            {"Asunción", -25.2864, -57.6115},
            {"San Lorenzo", -25.3396, -57.5173},
            {"Luque", -25.2691, -57.4851},
            {"Lambaré", -25.3458, -57.6064},
            {"Fernando de la Mora", -25.3261, -57.5544},
            {"Capiatá", -25.3533, -57.4261},
            {"Ñemby", -25.3941, -57.5352},
            {"Mariano Roque Alonso", -25.2161, -57.5323},
            {"Villa Elisa", -25.3671, -57.5901},
            {"Itauguá", -25.3854, -57.3342},
            {"Limpio", -25.1661, -57.4761},
            {"Villa Hayes", -25.0931, -57.5250},
            {"Benjamín Aceval", -25.0111, -57.3300},
            {"Emboscada", -25.1141, -57.3481},
            {"Arroyos y Esteros", -25.0661, -56.9331}
        };
        String mejorCiudad = "Gran Asunción";
        double menorDistancia = 15.0; // Radio de búsqueda
        for (Object[] centro : centros) {
            double dist = haversine(lat, lon, (double)centro[1], (double)centro[2]);
            if (dist < menorDistancia) {
                menorDistancia = dist; mejorCiudad = (String)centro[0];
            }
        }
        return mejorCiudad;
    }
}