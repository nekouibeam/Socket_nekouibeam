import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class TFTPTestClient {
    private static final int TFTP_PORT = 6699;
    private static final String SERVER_IP = "127.0.0.1";
    private static final String FILENAME = "verybigfile.txt"; // 請確保此檔案存在且夠大
    private static final int BUFFER_SIZE = 516; 

    // 設定要測試重傳的 Block 編號 (隨意選一個小的數字以便快速觸發)
    private static final int TEST_RETRY_BLOCK = 10; 

    public static void main(String[] args) {
        try {
            System.out.println("=== 開始測試 Block ID Roll-over 與 重傳機制 (Timeout) ===");
            System.out.println("測試檔案: " + FILENAME);
            
            // 1. 測試下載 (Roll-over + Server重傳)
            testDownload();
            
            // 2. 測試上傳 (Roll-over + Server重傳)
            testUpload();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void testDownload() throws IOException, InterruptedException {
        System.out.println("\n[測試 1] 下載測試 (RRQ)");
        System.out.println("   - 目標 1: 驗證 Block ID Roll-over (65535 -> 0)");
        System.out.println("   - 目標 2: 驗證 Server 超時重傳 (故意丟棄 Block " + TEST_RETRY_BLOCK + " 的 ACK)");

        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(5000); // Client 也要設 timeout，以免測試失敗卡死
        InetAddress address = InetAddress.getByName(SERVER_IP);

        // 發送 RRQ
        byte[] rrq = createRequest(1, FILENAME, "octet");
        socket.send(new DatagramPacket(rrq, rrq.length, address, TFTP_PORT));

        int expectedBlock = 1;
        boolean rollOverDetected = false;
        boolean retransmissionTested = false;

        while (true) {
            byte[] buf = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                System.err.println("Client 等待 Server 資料超時 (測試失敗)");
                break;
            }

            int serverPort = packet.getPort();
            ByteBuffer wrap = ByteBuffer.wrap(packet.getData());
            short opcode = wrap.getShort();

            if (opcode == 3) { // DATA
                short blockShort = wrap.getShort();
                int block = blockShort & 0xFFFF;

                // === 測試 Server 重傳機制 ===
                if (block == TEST_RETRY_BLOCK && !retransmissionTested) {
                    System.out.println(">>> [模擬封包遺失] 收到 Block " + block + "，故意不回傳 ACK...");
                    System.out.println(">>> 等待 Server (2秒 timeout) 重傳...");
                    
                    retransmissionTested = true; 
                    // 這裡直接 continue，不發送 ACK，並回到 receive 等待 Server 重傳的封包
                    // Server 應該在 2 秒後重傳同樣的 block
                    continue; 
                }

                if (retransmissionTested && block == TEST_RETRY_BLOCK) {
                     System.out.println(">>> [成功] 收到 Server 重傳的 Block " + block + "！");
                }
                // ============================

                // 檢查 Roll-over
                if (expectedBlock == 0 && block == 0) {
                    System.out.println(">>> [成功] 偵測到 Roll-over! Server 發送了 Block 0");
                    rollOverDetected = true;
                }

                // 發送 ACK
                byte[] ack = new byte[4];
                ByteBuffer ackBuf = ByteBuffer.allocate(4);
                ackBuf.putShort((short) 4);
                ackBuf.putShort(blockShort);
                socket.send(new DatagramPacket(ackBuf.array(), 4, address, serverPort));

                if (block == expectedBlock) {
                     expectedBlock++;
                     if (expectedBlock > 65535) expectedBlock = 0;
                }

                if (packet.getLength() < 516) break; // 傳輸結束
            } else if (opcode == 5) {
                System.err.println("收到錯誤封包: " + new String(packet.getData(), 4, packet.getLength()-4));
                break;
            }
        }
        
        System.out.println("[測試 1] 結果: " + (rollOverDetected && retransmissionTested ? "通過 (PASS)" : "失敗 (FAIL)"));
    }

    private static void testUpload() throws IOException, InterruptedException {
        System.out.println("\n[測試 2] 上傳測試 (WRQ)");
        System.out.println("   - 目標 1: 驗證 Block ID Roll-over (ACK 65535 -> 0)");
        System.out.println("   - 目標 2: 驗證 Server 超時重傳 (在傳送 Block " + TEST_RETRY_BLOCK + " 前暫停 3 秒)");

        String uploadName = "upload_test_retry.txt";
        File file = new File(FILENAME);
        if (!file.exists()) {
            System.err.println("錯誤：找不到 " + FILENAME);
            return;
        }

        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(5000);
        InetAddress address = InetAddress.getByName(SERVER_IP);
        FileInputStream fis = new FileInputStream(file);

        // 發送 WRQ
        byte[] wrq = createRequest(2, uploadName, "octet");
        socket.send(new DatagramPacket(wrq, wrq.length, address, TFTP_PORT));

        int blockToSend = 1; 
        boolean rollOverAckDetected = false;
        boolean retransmissionTested = false;
        int serverPort = 0;

        // 等待第一個 ACK 0
        byte[] buf = new byte[4];
        DatagramPacket ackPacket = new DatagramPacket(buf, buf.length);
        socket.receive(ackPacket);
        serverPort = ackPacket.getPort();

        byte[] fileBuf = new byte[512];
        int readLen;

        while ((readLen = fis.read(fileBuf)) != -1) {
            
            // === 測試 Server 重傳機制 ===
            if (blockToSend == TEST_RETRY_BLOCK && !retransmissionTested) {
                System.out.println(">>> [模擬延遲] 準備傳送 Block " + blockToSend + "，但先睡覺 3 秒...");
                // Server timeout 是 2 秒。我們睡 3 秒，Server 應該會在這期間重傳 ACK (block-1)
                Thread.sleep(3000);
                
                // 檢查是否收到 Server 重傳的 ACK
                // 注意：Server 重傳的是「上一個」ACK (也就是請求我們現在要傳的這個 block)
                try {
                    byte[] retryBuf = new byte[4];
                    DatagramPacket retryAck = new DatagramPacket(retryBuf, retryBuf.length);
                    socket.receive(retryAck); // 這裡應該收到 Server 因為等不到 DATA 而重傳的 ACK
                    
                    ByteBuffer tempWrap = ByteBuffer.wrap(retryAck.getData());
                    if (tempWrap.getShort() == 4) {
                        int retryAckNum = tempWrap.getShort() & 0xFFFF;
                        System.out.println(">>> [成功] 醒來後收到 Server 重傳的 ACK #" + retryAckNum);
                        retransmissionTested = true;
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println(">>> [失敗] Server 沒有重傳 ACK");
                }
            }
            // ============================

            // 建立 DATA 封包
            ByteBuffer dataPkt = ByteBuffer.allocate(4 + readLen);
            dataPkt.putShort((short) 3);
            dataPkt.putShort((short) blockToSend);
            dataPkt.put(fileBuf, 0, readLen);
            
            socket.send(new DatagramPacket(dataPkt.array(), dataPkt.capacity(), address, serverPort));

            // 接收 ACK
            socket.receive(ackPacket);
            ByteBuffer ackWrap = ByteBuffer.wrap(ackPacket.getData());
            if (ackWrap.getShort() == 4) { 
                int ackBlock = ackWrap.getShort() & 0xFFFF;
                
                if (blockToSend == 0 && ackBlock == 0) {
                     System.out.println(">>> [成功] 偵測到 Roll-over! Server 回傳了 ACK 0");
                     rollOverAckDetected = true;
                }
            }

            blockToSend++;
            if (blockToSend > 65535) blockToSend = 0;
        }
        fis.close();
        
        System.out.println("[測試 2] 結果: " + (rollOverAckDetected && retransmissionTested ? "通過 (PASS)" : "失敗 (FAIL)"));
    }

    private static byte[] createRequest(int op, String name, String mode) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeShort(op);
            dos.writeBytes(name);
            dos.writeByte(0);
            dos.writeBytes(mode);
            dos.writeByte(0);
        } catch (IOException e) { e.printStackTrace(); }
        return baos.toByteArray();
    }
}