import java.io.*;
import java.net.*;
import java.util.*;

public class SimpleServer {
    public static void main(String[] args) throws IOException {
        ServerSocket server = new ServerSocket(8080);
        System.out.println("서버가 포트 8080에서 실행 중입니다...");
        
        while (true) {
            Socket client = server.accept();
            handleRequest(client);
        }
    }
    
    private static void handleRequest(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
        PrintWriter out = new PrintWriter(client.getOutputStream());
        
        String line = in.readLine();
        System.out.println("요청: " + line);
        
        // HTTP 헤더 읽기
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            // 헤더 무시
        }
        
        // HTML 응답
        String html = readIndexFile();
        
        out.println("HTTP/1.1 200 OK");
        out.println("Content-Type: text/html; charset=UTF-8");
        out.println("Content-Length: " + html.getBytes("UTF-8").length);
        out.println();
        out.println(html);
        out.flush();
        
        client.close();
    }
    
    private static String readIndexFile() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("src/main/resources/static/index.html"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return "<h1>적토마 레이스</h1><p>파일을 읽을 수 없습니다.</p>";
        }
    }
}
