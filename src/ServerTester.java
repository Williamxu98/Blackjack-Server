import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;

import utilities.Validator;

public class ServerTester {
	private Socket socket;
	private BufferedReader in;
	private static PrintWriter out;

	public static void main(String[] args) {
		Scanner keyboard = new Scanner(System.in);

		// System.out.print("Please enter an IP address: ");
		// String ipAddress = keyboard.nextLine();
		//
		// String portStr;
		// int port = -1;
		// if (args.length > 0) {
		// if (Validator.isValidPort(args[0])) {
		// port = Integer.parseInt(args[0]);
		// }
		// } else {
		// System.out.print("Please enter a port: ");
		// portStr = keyboard.nextLine();
		// if (Validator.isValidPort(portStr)) {
		// port = Integer.parseInt(portStr);
		// }
		// }
		//
		// while (port == -1) {
		// System.out.print("Please enter a valid port: ");
		// portStr = keyboard.nextLine();
		// if (Validator.isValidPort(portStr)) {
		// port = Integer.parseInt(portStr);
		// }
		// }

		try {
			// Socket socket = new Socket(ipAddress, port);
			Socket socket = new Socket("127.0.0.1", 5000);
			BufferedReader in = new BufferedReader(new InputStreamReader(
					socket.getInputStream()));
			PrintWriter out = new PrintWriter(socket.getOutputStream());
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		while (true) {
			try {
				String message = keyboard.nextLine();
				out.println(message);
				out.flush();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
}