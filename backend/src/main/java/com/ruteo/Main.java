package com.ruteo;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.Gson;
import java.net.InetSocketAddress;
import java.sql.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    private static final Gson gson = new Gson();
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/ruteo_db";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "tu_contraseña";
    
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        
        // Configurar CORS
        server.createContext("/api/clientes", new ClientesHandler());
        server.createContext("/api/generar-rutas", new RutasHandler());
        server.createContext("/api/importar-kml", new KMLHandler());
        
        server.setExecutor(null);
        server.start();
        System.out.println("Servidor iniciado en http://localhost:8080");
        System.out.println("Presiona Ctrl+C para detener");
    }
    
    static class ClientesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            
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
                        cliente.put("direccion", rs.getString("direccion"));
                        cliente.put("latitud", rs.getDouble("latitud"));
                        cliente.put("longitud", rs.getDouble("longitud"));
                        cliente.put("telefono", rs.getString("telefono"));
                        cliente.put("seleccionado", false);
                        clientes.add(cliente);
                    }
                    
                    String response = gson.toJson(clientes);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.getBytes().length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                } catch (SQLException e) {
                    e.printStackTrace();
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
            
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
                
                Map<String, Object> request = gson.fromJson(body, Map.class);
                List<Map<String, Object>> clientes = (List<Map<String, Object>>) request.get("clientes");
                double numMoviles = (Double) request.get("numMoviles");
                
                // Aquí implementas tu algoritmo de ruteo
                Map<String, Object> respuesta = new HashMap<>();
                List<Map<String, Object>> rutas = new ArrayList<>();
                
                // Simulación: dividir clientes en grupos
                int clientesPorMovil = (int) Math.ceil(clientes.size() / numMoviles);
                for (int i = 0; i < numMoviles; i++) {
                    int start = i * clientesPorMovil;
                    int end = Math.min(start + clientesPorMovil, clientes.size());
                    if (start < clientes.size()) {
                        Map<String, Object> ruta = new HashMap<>();
                        ruta.put("clientes", clientes.subList(start, end));
                        rutas.add(ruta);
                    }
                }
                
                respuesta.put("rutas", rutas);
                String response = gson.toJson(respuesta);
                
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }
    }
    
    static class KMLHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCORS(exchange);
            
            if ("POST".equals(exchange.getRequestMethod())) {
                // Implementar importación de KML
                sendResponse(exchange, 200, "{\"mensaje\":\"KML importado\"}");
            }
        }
    }
    
    private static void setCORS(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }
    
    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, response.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }
    
    private static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        String response = gson.toJson(error);
        sendResponse(exchange, statusCode, response);
    }
}