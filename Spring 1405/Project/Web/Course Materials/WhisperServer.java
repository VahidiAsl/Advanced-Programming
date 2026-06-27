import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

public class WhisperServer {

    // پایگاه داده‌های در حافظه (In-Memory Databases)
    private static final Map<String, String> users = new HashMap<>(); // username -> password
    private static final Map<String, String> userProfiles = new HashMap<>(); // username -> base64 image
    private static final Map<String, List<Message>> chatRooms = new HashMap<>(); // roomName -> messages

    static {
        chatRooms.put("General", new ArrayList<>());
        chatRooms.put("University", new ArrayList<>());
        chatRooms.put("Gaming", new ArrayList<>());
    }

    // کلاس مدل پیام
    static class Message {
        String id;
        String sender;
        String type; // "text" or "image"
        String content;

        public Message(String sender, String type, String content) {
            this.id = UUID.randomUUID().toString(); // شناسه یکتا برای هر پیام
            this.sender = sender;
            this.type = type;
            this.content = content;
        }

        public String toJson() {
            // ساخت JSON دستی
            String safeContent = content.replace("\"", "\\\"").replace("\n", "\\n");
            return String.format("{\"id\":\"%s\", \"sender\":\"%s\", \"type\":\"%s\", \"content\":\"%s\"}",
                    id, sender, type, safeContent);
        }
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);

        // 1. Endpoint: /api/login (POST)
        server.createContext("/api/login", exchange -> {
            handleCORS(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) return;

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = getRequestBody(exchange);
                String user = extractJsonValue(body, "user");
                String pass = extractJsonValue(body, "pass");

                if (users.containsKey(user)) {
                    if (users.get(user).equals(pass)) {
                        sendResponse(exchange, 200, "{\"status\":\"success\"}"); // 200 OK
                    } else {
                        sendResponse(exchange, 401, "{\"error\":\"Wrong password\"}"); // 401 Unauthorized
                    }
                } else {
                    users.put(user, pass); // ثبت‌نام کاربر جدید
                    sendResponse(exchange, 201, "{\"status\":\"created\"}"); // 201 Created
                }
            } else {
                sendResponse(exchange, 405, "Method Not Allowed");
            }
        });

        // 2. Endpoint: /api/chat (GET, POST, PUT, DELETE)
        server.createContext("/api/chat", exchange -> {
            handleCORS(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) return;

            String query = exchange.getRequestURI().getQuery();
            String room = (query != null && query.contains("room=")) ? query.split("room=")[1] : "General";
            List<Message> roomMessages = chatRooms.computeIfAbsent(room, k -> new ArrayList<>());

            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                // دریافت لیست پیام‌ها
                StringBuilder jsonList = new StringBuilder("[");
                for (int i = 0; i < roomMessages.size(); i++) {
                    jsonList.append(roomMessages.get(i).toJson());
                    if (i < roomMessages.size() - 1) jsonList.append(",");
                }
                jsonList.append("]");
                sendResponse(exchange, 200, jsonList.toString()); // 200 OK

            } else if ("POST".equalsIgnoreCase(method)) {
                // ارسال پیام جدید
                String body = getRequestBody(exchange);
                String sender = extractJsonValue(body, "sender");
                String type = extractJsonValue(body, "type");
                String content = extractJsonValue(body, "content");

                Message newMessage = new Message(sender, type, content);
                roomMessages.add(newMessage);
                sendResponse(exchange, 201, "{\"status\":\"Message sent\"}"); // 201 Created

            } else if ("PUT".equalsIgnoreCase(method)) {
                // ویرایش پیام (Update)
                String body = getRequestBody(exchange);
                String id = extractJsonValue(body, "id");
                String newContent = extractJsonValue(body, "content");

                for (Message msg : roomMessages) {
                    if (msg.id.equals(id)) {
                        msg.content = newContent;
                        sendResponse(exchange, 200, "{\"status\":\"Message updated\"}"); // 200 OK
                        return;
                    }
                }
                sendResponse(exchange, 404, "{\"error\":\"Message not found\"}"); // 404 Not Found

            } else if ("DELETE".equalsIgnoreCase(method)) {
                // حذف پیام
                String body = getRequestBody(exchange);
                String id = extractJsonValue(body, "id");

                boolean removed = roomMessages.removeIf(msg -> msg.id.equals(id));
                if (removed) {
                    sendResponse(exchange, 204, ""); // 204 No Content (موفق بدون نیاز به برگرداندن دیتا)
                } else {
                    sendResponse(exchange, 404, "{\"error\":\"Message not found\"}");
                }
            }
        });

        // 3. Endpoint: /api/profile (GET, PUT, DELETE) - بخش جدید برای مدیریت پروفایل
        server.createContext("/api/profile", exchange -> {
            handleCORS(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) return;

            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                // دریافت عکس پروفایل کاربر
                String query = exchange.getRequestURI().getQuery();
                String user = query != null ? query.split("user=")[1] : "";
                String profileBase64 = userProfiles.getOrDefault(user, "");
                sendResponse(exchange, 200, "{\"image\":\"" + profileBase64 + "\"}");

            } else if ("PUT".equalsIgnoreCase(method)) {
                // قرار دادن/تغییر عکس پروفایل
                String body = getRequestBody(exchange);
                String user = extractJsonValue(body, "user");
                String image = extractJsonValue(body, "image");
                userProfiles.put(user, image);
                sendResponse(exchange, 200, "{\"status\":\"Profile updated\"}");

            } else if ("DELETE".equalsIgnoreCase(method)) {
                // حذف عکس پروفایل
                String body = getRequestBody(exchange);
                String user = extractJsonValue(body, "user");
                userProfiles.remove(user);
                sendResponse(exchange, 204, ""); // 204 No Content
            }
        });

        // 4. Endpoint: /api/user (GET) - جستجوی کاربر
        server.createContext("/api/user", exchange -> {
            handleCORS(exchange);
            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) return;

            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.contains("search=")) {
                    String username = query.split("search=")[1];
                    if (users.containsKey(username)) {
                        sendResponse(exchange, 200, "{\"status\":\"Found\"}");
                    } else {
                        sendResponse(exchange, 404, "{\"error\":\"Not Found\"}");
                    }
                }
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("Server is running on port 8081...");
    }

    // متدهای کمکی (Helper Methods)
    private static void handleCORS(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }
    }

    private static String getRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) { sb.append(line); }
        return sb.toString();
    }

    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return "";
        start += search.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes("UTF-8");
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, statusCode == 204 ? -1 : bytes.length);
        if (statusCode != 204) {
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }
}
