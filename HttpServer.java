//陳昱綸, 112306069, 資管三甲
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * HTTP Server 類別
 * 負責啟動伺服器並監聽指定 Port，為每個連線建立多執行緒處理。
 *
 * @author 陳昱綸, 112306069, 資管三甲
 */
public class HttpServer {

    /**
     * 程式進入點
     * 啟動 ServerSocket 並進入無窮迴圈等待客戶端連線。
     *
     * @param args 命令列參數 (本程式未使用)
     */
    public static void main(String[] args) {
        // 1. 修改 Port 為 8868
        String myHostName = "127.0.0.1";
        int myPortNumber = 8868;

        try {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(myHostName, myPortNumber));
            System.out.println("HTTP Server started on " + myHostName + ":" + myPortNumber);

            // 2. 實作 Multi-thread (多執行緒)
            // 使用無窮迴圈持續等待新的連線
            while (true) {
                Socket clientSocket = serverSocket.accept();
                // 當有新的連線時，建立一個新的 Thread 去處理它
                ClientHandler handler = new ClientHandler(clientSocket);
                Thread thread = new Thread(handler);
                thread.start();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

/**
 * ClientHandler 類別
 * 專門用來處理單一客戶端連線的執行緒邏輯。
 * 實作了 Persistent HTTP (持續連線) 與基本的 Request Routing。
 */
class ClientHandler implements Runnable {

    private Socket clientSocket;
    // 定義學號變數
    private String studentID = "112306069";

    /**
     * ClientHandler 建構子
     *
     * @param socket 與客戶端建立的連線 Socket
     */
    public ClientHandler(Socket socket) {
        this.clientSocket = socket;
    }

    /**
     * 執行緒主要邏輯
     * 負責讀取 HTTP Request、解析 URL 並分派給對應的處理方法。
     * 包含 Persistent HTTP 的實作 (限制處理 2 個請求)。
     */
    @Override
    public void run() {
        BufferedReader in = null;
        PrintWriter out = null;
        BufferedOutputStream dataOut = null;

        try {
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            dataOut = new BufferedOutputStream(clientSocket.getOutputStream());

            // 3. 實作 Persistent HTTP (傳輸 1 或 2 個物件)
            // 設定一個計數器，允許在同一個連線中處理最多 2 個請求
            int requestLimit = 2;
            int requestCount = 0;

            while (requestCount < requestLimit) {

                // 讀取 Request 的第一行 (例如: GET /good.html HTTP/1.1)
                String requestLine = in.readLine();

                // 如果讀不到東西 (客戶端關閉連線)，就跳出迴圈
                if (requestLine == null || requestLine.isEmpty()) {
                    break;
                }

                System.out.println("Request received: " + requestLine);

                // 簡單解析字串，取得 URL 部分 (GET /good.html HTTP/1.1 -> 分割後 index 1 是 /good.html)
                String[] tokens = requestLine.split(" ");
                String method = tokens[0];
                String url = tokens.length > 1 ? tokens[1] : "";

                // 略過剩下的 Header 內容
                while (in.ready()) {
                    String headerLine = in.readLine();
                    if (headerLine.isEmpty()) break;
                }

                // 4. 根據 URL 決定回應內容 (Routing)
                if (method.equals("GET")) {
                    if (url.equals("/good.html")) {
                        sendGoodHtml(out, dataOut);
                    } else if (url.equals("/style.css")) {
                        sendStyleCss(out, dataOut);
                    } else if (url.equals("/redirect.html")) {
                        sendRedirect(out);
                    } else if (url.equals("/notfound.html")) {
                        send404(out);
                    } else {
                        // 其他網址也視為 404
                        send404(out);
                    }
                }

                requestCount++;
                // 瀏覽器如果不需讀取 style.css，可能會保持連線但不送資料，此時 loop 會在上面的 readLine 等待
            }

            System.out.println("Closing connection after " + requestCount + " requests.");
            clientSocket.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 處理 /good.html 請求
     * 回應 200 OK 及 HTML 內容。
     *
     * @param out 用於發送 Header 的 PrintWriter
     * @param dataOut 用於發送 Body 的 BufferedOutputStream
     * @throws IOException 當輸出發生錯誤時拋出
     */
    private void sendGoodHtml(PrintWriter out, BufferedOutputStream dataOut) throws IOException {
        String content = "<html><head><link href=\"style.css\" rel=\"stylesheet\" type=\"text/css\"></head>" +
                         "<body>good: My student ID is " + studentID + "</body></html>";

        byte[] contentBytes = content.getBytes();

        out.println("HTTP/1.1 200 OK");
        out.println("Content-Type: text/html");
        out.println("Content-Length: " + contentBytes.length); // Persistent connection 需要知道這個長度
        out.println(); // Header 結束的空行
        out.flush(); //確保Header 在 Body 之前送達

        dataOut.write(contentBytes);
        dataOut.flush(); //確保瀏覽器收到完整的網頁
    }

    /**
     * 處理 /style.css 請求
     * 回應 200 OK 及 CSS 內容。
     *
     * @param out 用於發送 Header 的 PrintWriter
     * @param dataOut 用於發送 Body 的 BufferedOutputStream
     * @throws IOException 當輸出發生錯誤時拋出
     */
    private void sendStyleCss(PrintWriter out, BufferedOutputStream dataOut) throws IOException {
        String content = "Body {color: orange;}";
        byte[] contentBytes = content.getBytes();

        out.println("HTTP/1.1 200 OK");
        out.println("Content-Type: text/css");
        out.println("Content-Length: " + contentBytes.length);
        out.println();
        out.flush(); //確保Header 在 Body 之前送達

        dataOut.write(contentBytes);
        dataOut.flush(); //確保瀏覽器收到完整的網頁
    }

    /** 
     * 處理 /redirect.html 請求
     * 回應 301 Moved Permanently 並導向至 /good.html。
     *
     * @param out 用於發送 Header 的 PrintWriter
     */
    private void sendRedirect(PrintWriter out) {
        out.println("HTTP/1.1 301 Moved Permanently");
        out.println("Location: /good.html"); // 告訴瀏覽器去哪裡
        out.println("Content-Length: 0");
        out.println();
        out.flush();
    }

    /**
     * 處理 /notfound.html 或其他未定義請求
     * 回應 404 Not Found。
     *
     * @param out 用於發送 Header 的 PrintWriter
     */
    private void send404(PrintWriter out) {
        out.println("HTTP/1.1 404 Not Found");
        out.println("Content-Length: 0");
        out.println();
        out.flush();
    }
}