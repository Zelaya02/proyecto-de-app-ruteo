import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class TestORS {
    public static void main(String[] args) {
        // Tu API Key para la prueba
        String apiKey = "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6ImE2Y2NjNjBiOTNiYjRlMTZiNmY2MDQxZGI3NWYyZTljIiwiaCI6Im11cm11cjY0In0=";
        
        // Coordenadas de prueba (Asunción)
        double startLat = -25.2867, startLon = -57.6470;
        double endLat = -25.2967, endLon = -57.6670;

        String url = String.format(java.util.Locale.US, "https://api.openrouteservice.org/v2/directions/driving-car?api_key=%s&start=%f,%f&end=%f,%f",
                apiKey, startLon, startLat, endLon, endLat);

        System.out.println("🚀 Probando conexión con OpenRouteService...");
        
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject summary = json.getAsJsonArray("features").get(0).getAsJsonObject()
                        .getAsJsonObject("properties").getAsJsonObject("summary");
                
                double distance = summary.get("distance").getAsDouble() / 1000.0;
                double duration = summary.get("duration").getAsDouble() / 60.0;

                System.out.println("✅ CONEXIÓN EXITOSA!");
                System.out.println("📍 Distancia calculada: " + String.format("%.2f", distance) + " km");
                System.out.println("⏱ Tiempo estimado: " + String.format("%.2f", duration) + " minutos");
            } else {
                System.out.println("❌ ERROR: El servidor respondió con código " + response.statusCode());
                System.out.println("Detalle: " + response.body());
            }
        } catch (Exception e) {
            System.out.println("❌ ERROR CRÍTICO: " + e.getMessage());
        }
    }
}
