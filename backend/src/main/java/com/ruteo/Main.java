package com.ruteo;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.Gson;
import java.net.InetSocketAddress;
import java.sql.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    private static final Gson gson = new Gson();
    private static final String DB_URL = "jdbc:postgresql://localhost:5000/ruteo_db";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "Zelaya1103";
    private static final String FRONTEND_DIR = "../frontend";
    
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        
        // Archivos estaticos
        server.createContext("/", new StaticHandler());
        
        // Configurar API
        server.createContext("/api/clientes", new ClientesHandler());
        server.createContext("/api/generar-rutas", new RutasHandler());
        server.createContext("/api/ruta", new RutaTokenHandler());
        server.createContext("/api/actualizar-estado", new EstadoHandler());
        server.createContext("/api/estadisticas", new EstadisticasHandler());
        server.createContext("/api/health", new HealthHandler());
        
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
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT * FROM clientes WHERE activo = true";
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql);
                    
                    List<Map<String, Object>> clientes = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> cliente = new HashMap<>();
                        cliente.put("id", rs.getInt("id"));
                        cliente.put("nombre", rs.getString("nombre"));
                        cliente.put("direccion", rs.getString("ciudad")); // Usamos ciudad como direccion para el frontend
                        cliente.put("tipo_cliente", rs.getString("tipo_cliente"));
                        cliente.put("latitud", rs.getDouble("latitud"));
                        cliente.put("longitud", rs.getDouble("longitud"));
                        cliente.put("seleccionado", false);
                        clientes.add(cliente);
                    }
                    sendResponse(exchange, 200, gson.toJson(clientes));
                } catch (SQLException e) {
                    sendError(exchange, 500, "Error de base de datos");
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
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining("\n"));
                    
                    Map<String, Object> request = gson.fromJson(body, Map.class);
                    List<Double> cIds = (List<Double>) request.get("cliente_ids");
                    int numMoviles = ((Double) request.get("num_moviles")).intValue();
                    
                    if (cIds == null || cIds.isEmpty()) {
                        sendError(exchange, 400, "cliente_ids es requerido");
                        return;
                    }
                    List<Integer> clienteIds = cIds.stream().map(Double::intValue).collect(Collectors.toList());
                    
                    List<Cliente> clientes = new ArrayList<>();
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        String idList = clienteIds.stream().map(String::valueOf).collect(Collectors.joining(","));
                        String sql = "SELECT id, nombre, latitud, longitud FROM clientes WHERE id IN (" + idList + ")";
                        Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery(sql);
                        while (rs.next()) {
                            clientes.add(new Cliente(rs.getInt("id"), rs.getString("nombre"), rs.getDouble("latitud"), rs.getDouble("longitud")));
                        }
                    }

                    // K-Means adaptado simplificado para asignar a k moviles
                    List<List<Cliente>> clusters = kmeans(clientes, numMoviles);
                    
                    List<Map<String, Object>> movilesRespuesta = new ArrayList<>();
                    
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                        for (int i = 0; i < clusters.size(); i++) {
                            List<Cliente> cluster = clusters.get(i);
                            if (cluster.isEmpty()) continue;
                            
                            // Vecino mas cercano para ordenar
                            List<Cliente> ordenados = nearestNeighbor(cluster);
                            
                            double distTotal = 0;
                            List<Map<String, Object>> clientesJson = new ArrayList<>();
                            for (int j = 0; j < ordenados.size(); j++) {
                                Cliente c = ordenados.get(j);
                                double dist = 0;
                                if (j < ordenados.size() - 1) {
                                    dist = haversine(c.lat, c.lon, ordenados.get(j+1).lat, ordenados.get(j+1).lon);
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
                            String insertRuta = "INSERT INTO rutas_generadas (token, movil_numero, clientes_json, distancia_total, tiempo_estimado) VALUES (?, ?, ?, ?, ?) RETURNING id";
                            PreparedStatement pstmt = conn.prepareStatement(insertRuta);
                            pstmt.setString(1, token);
                            pstmt.setInt(2, i + 1);
                            pstmt.setString(3, gson.toJson(clientesJson));
                            pstmt.setDouble(4, distTotal);
                            pstmt.setInt(5, tiempoEstimado);
                            pstmt.executeQuery();
                            
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
                            movilesRespuesta.add(movil);
                        }
                    }

                    Map<String, Object> respuestaFinal = new HashMap<>();
                    respuestaFinal.put("moviles", movilesRespuesta);
                    sendResponse(exchange, 200, gson.toJson(respuestaFinal));
                    
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

    static class EstadisticasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            if ("GET".equals(exchange.getRequestMethod())) {
                try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                    String sql = "SELECT estado, COUNT(*) as cantidad FROM entregas e JOIN rutas_generadas r ON e.ruta_token = r.token WHERE r.fecha = CURRENT_DATE GROUP BY estado";
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql);
                    
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
                    
                    // Rendimiento
                    String sqlRend = "SELECT r.movil_numero, " +
                        "SUM(CASE WHEN e.estado = 'entregado' THEN 1 ELSE 0 END) as ent, " +
                        "COUNT(e.id) as tot " +
                        "FROM rutas_generadas r JOIN entregas e ON r.token = e.ruta_token " +
                        "WHERE r.fecha = CURRENT_DATE GROUP BY r.movil_numero";
                    ResultSet rsRend = stmt.executeQuery(sqlRend);
                    List<Map<String, Object>> rendimientos = new ArrayList<>();
                    while(rsRend.next()){
                        Map<String, Object> rm = new HashMap<>();
                        rm.put("movil", rsRend.getInt("movil_numero"));
                        rm.put("entregados", rsRend.getInt("ent"));
                        rm.put("total", rsRend.getInt("tot"));
                        rendimientos.add(rm);
                    }
                    res.put("rendimiento_por_movil", rendimientos);
                    
                    sendResponse(exchange, 200, gson.toJson(res));
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

    // -- Clases y utilidades de Ruteo --
    static class Cliente {
        int id; String nombre; double lat, lon;
        Cliente(int id, String nombre, double lat, double lon){
            this.id = id; this.nombre = nombre; this.lat = lat; this.lon = lon;
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

    private static List<List<Cliente>> kmeans(List<Cliente> clientes, int k) {
        if (clientes.size() <= k) {
            List<List<Cliente>> res = new ArrayList<>();
            for (Cliente c : clientes) res.add(new ArrayList<>(Arrays.asList(c)));
            return res;
        }
        
        // Init centroids
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
                int bestK = 0;
                double bestDist = Double.MAX_VALUE;
                for (int i=0; i<k; i++) {
                    double d = haversine(c.lat, c.lon, centroids.get(i)[0], centroids.get(i)[1]);
                    if (d < bestDist) {
                        bestDist = d;
                        bestK = i;
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

    private static List<Cliente> nearestNeighbor(List<Cliente> cluster) {
        if (cluster.isEmpty()) return cluster;
        List<Cliente> unvisited = new ArrayList<>(cluster);
        List<Cliente> result = new ArrayList<>();
        
        Cliente current = unvisited.remove(0); // Empezar con cualquiera
        result.add(current);
        
        while(!unvisited.isEmpty()) {
            double bestDist = Double.MAX_VALUE;
            Cliente bestNext = null;
            for(Cliente c : unvisited) {
                double d = haversine(current.lat, current.lon, c.lat, c.lon);
                if (d < bestDist) {
                    bestDist = d;
                    bestNext = c;
                }
            }
            result.add(bestNext);
            unvisited.remove(bestNext);
            current = bestNext;
        }
        return result;
    }
}