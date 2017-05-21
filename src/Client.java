import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.StringTokenizer;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Timer;

//Client. Usage: java Client [Server hostname] [Server RTSP listening port] [Video file requested]

public class Client {

	// GUI:
	JFrame f = new JFrame("Client");
	JButton describeButton = new JButton("Describe");
	JButton setupButton = new JButton("Setup");
	JButton playButton = new JButton("Play");
	JButton pauseButton = new JButton("Pause");
	JButton tearButton = new JButton("Teardown");
	JPanel mainPanel = new JPanel();
	JPanel buttonPanel = new JPanel();
	JLabel iconLabel = new JLabel();
	ImageIcon icon;

	// RTP variables:
	DatagramPacket rcvdp; // UDP packet received from the server
	DatagramSocket RTPsocket; // socket to be used to send and receive UDP packets

	final static String PROTOCOL_TYPE = "RTSP/1.0";
	final static String TRANSPORT_TYPE = "RTP/UDP";

	static int RTP_RCV_PORT = 25000; // port where the client will receive the RTP packets
	static int RTP_SOCKET_TIMEOUT = 5; // timeout for RTP Socket.

	Timer timer; // timer used to receive data from the UDP socket
	byte[] buf; // buffer used to store data received from the server

	// RTSP variables
	// RTSP states
	final static int INIT = 0;
	final static int READY = 1;
	final static int PLAYING = 2;
	static int state; // RTSP state == INIT or READY or PLAYING
	Socket RTSPsocket; // socket used to send/receive RTSP messages
	// Input and output stream filters
	static BufferedReader RTSPBufferedReader;
	static BufferedWriter RTSPBufferedWriter;
	static String VideoFileName; // video file to request to the server
	int RTSPSeqNb = 0; // sequence number of RTSP messages within the session
	int RTSPid = 0; // ID of the RTSP session (given by the RTSP Server)

	final static String CRLF = "\r\n";

	// Video constants:
	static int MJPEG_TYPE = 26; // RTP payload type for MJPEG video

	// Constructor
	public Client() {

		// Build GUI
		// Frame
		f.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				System.exit(0);
			}
		});

		// Buttons
		buttonPanel.setLayout(new GridLayout(1, 0));
		buttonPanel.add(describeButton);
		buttonPanel.add(setupButton);
		buttonPanel.add(playButton);
		buttonPanel.add(pauseButton);
		buttonPanel.add(tearButton);
		describeButton.addActionListener(new describeButtonListener());
		setupButton.addActionListener(new setupButtonListener());
		playButton.addActionListener(new playButtonListener());
		pauseButton.addActionListener(new pauseButtonListener());
		tearButton.addActionListener(new tearButtonListener());

		// Image display label
		iconLabel.setIcon(null);

		// Frame layout
		mainPanel.setLayout(null);
		mainPanel.add(iconLabel);
		mainPanel.add(buttonPanel);
		iconLabel.setBounds(0, 0, 380, 280);
		buttonPanel.setBounds(0, 280, 380, 50);

		f.getContentPane().add(mainPanel, BorderLayout.CENTER);
		//f.setSize(new Dimension(1024, 768));
		f.setSize(new Dimension(390, 370));
		f.setVisible(true);

		// Init timer
		timer = new Timer(20, new timerListener());
		timer.setInitialDelay(0);
		timer.setCoalesce(true);

		// Allocate enough memory for the buffer used to receive data from the server
		buf = new byte[15000];
	}

	// Main method
	public static void main(String argv[]) throws Exception {
		// Create a Client object
		Client theClient = new Client();

		if (argv.length == 0) {
			JOptionPane.showMessageDialog(null, "Please re-run application with parameters.", "Error",
					JOptionPane.ERROR_MESSAGE, null);
		}
		// Get server RTSP port and IP address from the command line
		int RTSP_server_port = Integer.parseInt(argv[1]);
		String ServerHost = argv[0];
		InetAddress ServerIPAddr = InetAddress.getByName(ServerHost);

		// Get video filename to request:
		VideoFileName = argv[2];

		// Establish a TCP connection with the server to exchange RTSP messages
		theClient.RTSPsocket = new Socket(ServerIPAddr, RTSP_server_port);

		// Set input and output stream filters:
		RTSPBufferedReader = new BufferedReader(new InputStreamReader(theClient.RTSPsocket.getInputStream()));
		RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theClient.RTSPsocket.getOutputStream()));

		// Init RTSP state:
		state = INIT;

	}

	// Handler for Describe button
	class describeButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			System.out.println("Describe Button pressed !");

			// Init RTSP sequence number
			RTSPSeqNb++;

			send_RTSP_request("DESCRIBE");

			if (parse_describe_response() != 200) {
				System.out.println("Invalid Server Response");
			}

		}

	}

	// Handler for Setup button
	class setupButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			System.out.println("Setup Button pressed !");

			if (state == INIT) {
				// Init non-blocking RTPsocket that will be used to receive data
				try {

					// Construct a new DatagramSocket to receive RTP packets from the server, on port RTP_RCV_PORT
					RTPsocket = new DatagramSocket(RTP_RCV_PORT);

					// Set TimeOut value of the socket to 5msec.
					RTPsocket.setSoTimeout(RTP_SOCKET_TIMEOUT);

				} catch (Exception se) {
					System.out.println("Socket exception: " + se);
					System.exit(0);
				}

				// Init RTSP sequence number
				RTSPSeqNb++;

				// Send SETUP message to the server
				send_RTSP_request("SETUP");

				// Wait for the response
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else {
					state = READY;
					System.out.println("New RTSP state: READY");
				}
			} // else if state != INIT then do nothing
		}
	}

	// Handler for Play button
	class playButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			System.out.println("Play Button pressed !");

			if (state == READY) {
				// Increase RTSP sequence number
				RTSPSeqNb++;

				// Send PLAY message to the server
				send_RTSP_request("PLAY");

				// Wait for the response
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else {
					// Change RTSP state and print out new state
					state = PLAYING;
					System.out.println("New RTSP state: PLAYING");

					// Start the timer
					timer.start();
				}
			} // else if state != READY then do nothing
		}
	}

	// Handler for Pause button
	class pauseButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			System.out.println("Pause Button pressed !");

			if (state == PLAYING) {
				// Increase RTSP sequence number
				RTSPSeqNb++;

				// Send PAUSE message to the server
				send_RTSP_request("PAUSE");

				// Wait for the response
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else {
					// Change RTSP state and print out new state
					state = READY;
					System.out.println("New RTSP state: READY");

					// Stop the timer
					timer.stop();
				}
			}
			// else if state != PLAYING then do nothing
		}
	}

	// Handler for Teardown button
	class tearButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			System.out.println("Teardown Button pressed !");

			// Increase RTSP sequence number
			RTSPSeqNb++;

			// Send TEARDOWN message to the server
			send_RTSP_request("TEARDOWN");

			// Wait for the response
			if (parse_server_response() != 200)
				System.out.println("Invalid Server Response");
			else {
				// Change RTSP state and print out new state
				state = INIT;
				System.out.println("New RTSP state: INIT");

				// Stop the timer
				timer.stop();

				// Exit
				System.exit(0);
			}
		}
	}

	// Handler for timer
	class timerListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			// Construct a DatagramPacket to receive data from the UDP socket
			rcvdp = new DatagramPacket(buf, buf.length);

			try {
				// Receive the DP from the socket:
				RTPsocket.receive(rcvdp);

				// Create an RTPpacket object from the DP
				RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());

				// Print important header fields of the RTP packet received:
				System.out.println("Got RTP packet with SeqNum # " + rtp_packet.getsequencenumber() + " TimeStamp "
						+ rtp_packet.gettimestamp() + " ms, of type " + rtp_packet.getpayloadtype());

				// Print header bitstream:
				rtp_packet.printheader();

				// Get the payload bitstream from the RTPpacket object
				int payload_length = rtp_packet.getpayload_length();
				byte[] payload = new byte[payload_length];
				rtp_packet.getpayload(payload);

				// Get an Image object from the payload bitstream
				Toolkit toolkit = Toolkit.getDefaultToolkit();
				Image image = toolkit.createImage(payload, 0, payload_length);

				// Display the image as an ImageIcon object
				icon = new ImageIcon(image);
				iconLabel.setIcon(icon);
			} catch (InterruptedIOException iioe) {
				System.out.println("Nothing to read");
			} catch (IOException ioe) {
				System.out.println("Exception caught: " + ioe.getMessage());
			}
		}
	}

	// Parse Server Response
	private int parse_server_response() {
		int reply_code = 0;

		try {
			// Parse status line and extract the reply_code:
			String StatusLine = RTSPBufferedReader.readLine();
			System.out.println("RTSP Client - Received from Server:");
			System.out.println(StatusLine);

			StringTokenizer tokens = new StringTokenizer(StatusLine);
			tokens.nextToken(); // Skip over the RTSP version
			reply_code = Integer.parseInt(tokens.nextToken());

			// If reply code is OK get and print the 2 other lines
			if (reply_code == 200) {
				String SeqNumLine = RTSPBufferedReader.readLine();
				System.out.println(SeqNumLine);

				String SessionLine = RTSPBufferedReader.readLine();
				System.out.println(SessionLine);

				// If state == INIT gets the Session Id from the SessionLine
				if (state == INIT) {
					tokens = new StringTokenizer(SessionLine);
					tokens.nextToken(); // Skip over the Session:
					RTSPid = Integer.parseInt(tokens.nextToken());
				}
			}
		} catch (Exception ex) {
			System.out.println("Exception caught: " + ex.getMessage());
			System.exit(0);
		}

		return (reply_code);
	}

	private int parse_describe_response() {
		int reply_code = 0;

		try {
			// Parse status line and extract the reply_code:
			String StatusLine = RTSPBufferedReader.readLine();
			System.out.println("RTSP Client - Received from Server:");
			System.out.println(StatusLine);

			StringTokenizer tokens = new StringTokenizer(StatusLine);
			tokens.nextToken(); // Skip over the RTSP version
			reply_code = Integer.parseInt(tokens.nextToken());

			// If reply code is OK get and print the 2 other lines
			if (reply_code == 200) {
				String SeqNumLine = RTSPBufferedReader.readLine();
				System.out.println(SeqNumLine);

				String SessionLine = RTSPBufferedReader.readLine();
				System.out.println(SessionLine);

				String StreamsLine = RTSPBufferedReader.readLine();
				System.out.println(StreamsLine);
				String EncodingsLine = RTSPBufferedReader.readLine();
				System.out.println(EncodingsLine);

				JOptionPane.showMessageDialog(null, StreamsLine + EncodingsLine, "Information About Server",
						JOptionPane.INFORMATION_MESSAGE);
			}
		} catch (Exception ex) {
			System.out.println("Exception caught: " + ex.getMessage());
			System.exit(0);
		}
		return reply_code;
	}

	private void send_RTSP_request(String request_type) {
		try {
			// Use the RTSPBufferedWriter to write to the RTSP socket

			// write the request line:
			RTSPBufferedWriter.write(request_type + " " + VideoFileName + " " + PROTOCOL_TYPE + CRLF);

			// write the CSeq line:
			RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);

			// check if request_type is equal to "SETUP" and in this case write the Transport: line advertising to the server the port used to receive the RTP packets RTP_RCV_PORT

			// if ....
			// otherwise, write the Session line from the RTSPid field
			// else ....

			if (request_type.equals("SETUP")) {
				RTSPBufferedWriter.write("Transport: " + TRANSPORT_TYPE + "; client_port= " + RTP_RCV_PORT + CRLF);
			} else {
				RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);
			}

			RTSPBufferedWriter.flush();
		} catch (Exception ex) {
			System.out.println("Exception caught: " + ex);
			System.exit(0);
		}
	}

}
