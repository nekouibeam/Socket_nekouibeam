// Name: 陳昱綸, ID: 112306069, Department: 資管三甲
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A simple TFTP Server implementation based on UDP.
 * Supports RRQ (Download) and WRQ (Upload) with block ID roll-over and timeout retransmission.
 * Listens on port 6699.
 */
public class TFTPServer {

	private static final int BUFFERSIZE = 512;

	private static final short RRQ = 1;
	private static final short WRQ = 2;
	private static final short DATA = 3;
	private static final short ACK = 4;
	private static final short ERRO = 5;

	public static String mode;

	/**
     * Main entry point of the TFTP Server.
     * @param args Command line arguments (not used).
     */
	public static void main(String[] args) {

		try {
			TFTPServer tftpServer = new TFTPServer();
			tftpServer.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
     * Starts the TFTP server, binds to port 6699, and listens for incoming requests.
     * Handles concurrency by spawning a new thread for each client connection.
     * * @throws SocketException If the socket could not be opened, or the socket could not bind to the specified local port.
     */
	private void start() throws SocketException {

		byte[] buffer = new byte[BUFFERSIZE];

		/* Create socket */
		DatagramSocket serverSocket = new DatagramSocket(null);

		/* Create local bind point, set at IP 127.0.0.1 and port 6699 */
		serverSocket.bind(new InetSocketAddress("127.0.0.1", 6699));

		System.out.printf("Listening at port %d for new requests\n", 6699);

		while (true) {
			/*
			*Create packet for receiving packets 
			*建構一個 DatagramPacket，用於接收長度為 length 的封包
			*Constructor： DatagramPacket(byte[] buf, int length) 
			*buf: the buffer to store incoming data 
			*length: the length of the buffer
			*/
			DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

			// receive packet from client
			try {
				serverSocket.receive(packet);
			} catch(IOException e) {
				e.printStackTrace();
				break;
			}

			final InetSocketAddress clientSocketAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());

			// parse the file header, retrieve opcode and file name.
			final ByteBuffer bufwrap = ByteBuffer.wrap(buffer);
			final short opcode = bufwrap.getShort(0);
			final String fileName = get(bufwrap, 2, (byte)0);
			final String mode = get(bufwrap, 2 + fileName.length() + 1, (byte) 0);
	
			System.out.printf("Receive connection from %s:%d\n", packet.getAddress(), packet.getPort());
			System.out.printf("opcode=%h, mode=%s, file_name=%s\n", opcode, mode, fileName);
			if (mode.compareTo("octet") == 0) {

				// create new thread to handle connection
				new Thread() {
						public void run() {
							try {
								DatagramSocket clientUDPSocket = new DatagramSocket(null);
								int randomPortNum = ThreadLocalRandom.current().nextInt(49152, 65535 + 1);
								clientUDPSocket.bind(new InetSocketAddress("127.0.0.1", randomPortNum));
								clientUDPSocket.connect(clientSocketAddress);
								switch(opcode) {
									case RRQ:
										download(clientUDPSocket, fileName.toString(), RRQ);
									break;
									case WRQ:
										upload(clientUDPSocket, fileName.toString(), WRQ);
									break;
								}
								clientUDPSocket.close();
							} catch (SocketException e) {
								e.printStackTrace();
							}
						}
				}.start();
			} else {
				continue;
			}
		}
		serverSocket.close();
	}

	/**
     * Extracts a string from the ByteBuffer starting at the specified index until the delimiter byte.
     * * @param buf The ByteBuffer containing the data.
     * @param current The starting index.
     * @param del The delimiter byte (usually 0).
     * @return The extracted String.
     */
	private String get(ByteBuffer buf, int current, byte del) {
		StringBuffer sb = new StringBuffer();
		while (buf.get(current) != del) {
			sb.append((char) buf.get(current));
			current++;
		}
		return sb.toString();
	}

	/**
     * Checks if the received packet is a valid ACK for the specified block number.
     * * @param buf The ByteBuffer containing the packet data.
     * @param blocknum The expected block number.
     * @return true if it is a valid ACK, false otherwise.
     */
	private boolean isAck(ByteBuffer buf, short blocknum) {
		short op = buf.getShort();
		short block = buf.getShort(2);
		return (op == (short) ACK && block == blocknum);
	}

	/**
     * Handles the Read Request (RRQ) - Sends a file to the client.
     * Implements Stop-and-Wait protocol with timeout retransmission and block ID roll-over.
     * * @param sendSocket The socket used for communication.
     * @param fileName The name of the file to download.
     * @param opcode The operation code (RRQ).
     */
	private void download(DatagramSocket sendSocket, String fileName, int opcode) {
		File file = new File(fileName);
		byte[] buffer = new byte[BUFFERSIZE];
		FileInputStream in = null;
		try {
			in = new FileInputStream(file);
		} catch (FileNotFoundException e) {
			System.err.println("File not found.");
			sendError(sendSocket, (short) 1, "File not found.");
			return;
		}

		int blockNum = 1; //short數值範圍為 -32768 到 32767，改用 int 型態以避免溢位方便計算
		int length;

		try {
       		sendSocket.setSoTimeout(2000); // 設定 timeout 2 秒
		} catch (SocketException e) {
			e.printStackTrace();
		}

		while (true) {

			try {
				// 從檔案讀取最多 512 bytes 到 buffer 中
        		// length 會回傳實際讀取到的 byte 數量
				length = in.read(buffer);
			} catch (IOException e) {
				e.printStackTrace();
				break;
			}

			if (length == -1) {
				length = 0; // 如果讀到檔尾 (EOF)，長度設為 0
			}

			// 1. 封裝並發送資料
			/*
			*Create packet for send data 
			*建構一個資料封包，用於將長度為 length 的封包發送到指定主機的指定連接埠號
			*在 socket connect 後可省略 address 與 port
			*Constructor： DatagramPacket(byte[] buf, int length, InetAddress address, int port)
			*buf: the packet data to be sent
			*length: the packet length
			*address: the destination IP address
			*port: the destination port number
			*/
			DatagramPacket packet = toData((short)blockNum, buffer, length);
			try {
				sendSocket.send(packet);
			} catch (Exception e) {
				e.printStackTrace();
			}
			int retries = 0;
       		boolean ackReceived = false;
			while (!ackReceived && retries < 5) {  // 最多重試 5 次
				try {
					// 2. 準備接收 ACK
					byte[] recvbuf = new byte[BUFFERSIZE];
					DatagramPacket recv = new DatagramPacket(recvbuf, BUFFERSIZE);
					sendSocket.receive(recv); // lockstep，直到收到客戶端的回應 //throws IOException
					// 3. 檢查 ACK 是否正確
					ByteBuffer recvbytebuf = ByteBuffer.wrap(recvbuf);
					if (isAck(recvbytebuf, (short)blockNum)) { 
						ackReceived = true; // 收到正確的 ACK															
					}else { // 收到非預期封包：忽略
						System.err.println("Unexpected packet, retrying...");
					}
				} catch (SocketTimeoutException e) {
					retries++;
					System.err.printf("Timeout waiting for ACK #%d, retry %d/5...\n", blockNum, retries);
					try {
						sendSocket.send(packet); // 超時重送資料封包
					} catch (IOException ioException) {
						ioException.printStackTrace();
					}
				} catch (IOException e) {
					e.printStackTrace();
					break;
				} 
			}
			
			if (!ackReceived) {
				System.err.println("Transfer failed after retries.");
				sendError(sendSocket, (short) 0, "Transfer failed.");
				break;
        	}

			blockNum += 1; // 確認無誤，準備發送下一個 Block (編號 +1)
			if(blockNum > 65535) { // Block 編號超過 65535 後，回到 0 開始
				blockNum = 0;
			}
			if (length < 512) { // 如資料長度小於 512，代表這是最後一塊
				try {
					in.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				break;
			}			
		}
	}

	/**
     * Handles the Write Request (WRQ) - Receives a file from the client.
     * Implements Stop-and-Wait protocol with timeout retransmission and block ID roll-over.
     * * @param sendSocket The socket used for communication.
     * @param fileName The name of the file to upload.
     * @param opcode The operation code (WRQ).
     */
	private void upload(DatagramSocket sendSocket, String fileName, int opcode) {
		File file = new File(fileName);
		if (file.exists()) {
			System.out.println("File already exists.");
			sendError(sendSocket, (short) 6, "File already exists.");
			return;
		} else {
			FileOutputStream output = null; 
			try {
				output = new FileOutputStream(file); // 建立檔案輸出串流，準備寫入硬碟
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				sendError(sendSocket, (short) 1, "File not found.");
				return;
			}

			int expectedBlockNum = 1; // 剛發送了 ACK 0，所以預期收到 Block 1
			try {
				sendSocket.setSoTimeout(2000); // 設定 timeout 2 秒
				sendSocket.send(toAck((short) 0)); // 傳送 ACK 0 給客戶端，準備接收資料
				
				while (true) {
					byte[] recvbuf = new byte[BUFFERSIZE + 4]; // 4 bytes for header(OpCode + BlockNum)
					DatagramPacket recv = new DatagramPacket(recvbuf, BUFFERSIZE + 4);
					
					int retries = 0;
            		boolean packetReceived = false;

					while (!packetReceived && retries < 5) { // 最多重試 5 次
						try {
							// 1. 等待接收客戶端的 DATA 封包
							sendSocket.receive(recv);
							packetReceived = true;
						} catch (SocketTimeoutException e) { // 超時重試
							retries++;
							System.err.printf("Timeout waiting for DATA #%d, retry %d/5...\n", expectedBlockNum, retries);
							 sendSocket.send(toAck((short) (expectedBlockNum - 1))); // 重送上一個 ACK
						} catch (IOException e) {
							e.printStackTrace();
							break;
						}
					}
					
					if (!packetReceived) { 
						System.err.println("Upload failed after retries.");
						sendError(sendSocket, (short) 0, "Upload failed.");
						break;
            		}

					ByteBuffer recvbytebuffer = ByteBuffer.wrap(recvbuf);
					// 2. 驗證是否為 DATA 封包 (OpCode = 3)
					if (recvbytebuffer.getShort() == (short) 3) {
						short receivedBlockShort = recvbytebuffer.getShort();
						//-32768 ~ 32767 的 short 轉為 0-65535 的 int
                        int receivedBlock = receivedBlockShort & 0xFFFF;
						
						// 檢查是否為預期的 Block 編號
						// 如果收到重複或錯誤的 Block，則忽略不處理
						if (receivedBlock == expectedBlockNum) {
							// 3. 寫入檔案
							// 參數說明：從 recvbuf 寫入，跳過前 4 bytes (標頭)，寫入剩下的長度
							output.write(recvbuf, 4, recv.getLength() - 4);
							sendSocket.send(toAck((short) receivedBlock)); // 發送 ACK (轉回 short)
							expectedBlockNum++;
                            if (expectedBlockNum > 65535) {
                                expectedBlockNum = 0; // Wrap to zero
                            }
							if (recv.getLength() - 4 < 512) {
								output.close();
								break; // 如果資料長度小於 512，代表這是最後一塊
							}
						}
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * sendError
	 * 
	 * Sends an error packet to sendSocket
	 * 
	 * @param sendSocket
	 * @param errorCode
	 * @param errMsg
	 */
	private void sendError(DatagramSocket sendSocket, short errorCode, String errMsg) {

		ByteBuffer wrap = ByteBuffer.allocate(BUFFERSIZE);
		wrap.putShort(ERRO);
		wrap.putShort(errorCode);
		wrap.put(errMsg.getBytes());
		wrap.put((byte) 0);

		DatagramPacket receivePacket = new DatagramPacket(wrap.array(), wrap.array().length);
		try {
			sendSocket.send(receivePacket);
		} catch (IOException e) {
			System.err.println("Problem sending error packet.");
			e.printStackTrace();
		}
	}

	/**
	 * ackPacket
	 * 
	 * Constructs an ACK packet for the given block number.
	 * 
	 * @param block the current block number
	 * @return ackPacket
	 */
	private DatagramPacket toAck(short block) {

		ByteBuffer buffer = ByteBuffer.allocate(4);
		buffer.putShort(ACK);
		buffer.putShort(block);

		return new DatagramPacket(buffer.array(), 4);
	}

	/**
	 * dataPacket
	 * 
	 * Constructs an DATA packet
	 * 
	 * @param block  current block number
	 * @param data   data to be sent
	 * @param length length of data
	 * @return DatagramPacket to be sent
	 */
	private DatagramPacket toData(short block, byte[] data, int length) {

		ByteBuffer buffer = ByteBuffer.allocate(BUFFERSIZE + 4);
		buffer.putShort(DATA);
		buffer.putShort(block);
		buffer.put(data, 0, length);

		return new DatagramPacket(buffer.array(), 4 + length);
	}
}
