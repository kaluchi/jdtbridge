package io.github.kaluchi.jdtbridge.ui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.kaluchi.jdtbridge.Activator;

/**
 * Reads bridge connection info from {@code ~/.jdtbridge/instances/}.
 * Used by the launch delegate to inject JDT_BRIDGE_* env vars
 * so the agent connects to the correct bridge without discovery.
 */
public class BridgeConnection {

	public final int port;
	public final String token;
	public final String workspace;

	private BridgeConnection(int port, String token, String workspace) {
		this.port = port;
		this.token = token;
		this.workspace = workspace;
	}

	/**
	 * Find the current bridge instance. Reads the first valid
	 * instance file from {@code ~/.jdtbridge/instances/}.
	 *
	 * @return connection info, or {@code null} if no bridge is running
	 */
	public static BridgeConnection find() {
		Path home = Activator.getHome();
		Path instancesDir = home.resolve("instances");
		if (!Files.isDirectory(instancesDir)) {
			return null;
		}

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(
				instancesDir, "*.json")) {
			for (Path file : stream) {
				try {
					String content = Files.readString(file);
					JsonObject obj = JsonParser.parseString(content)
							.getAsJsonObject();
					int port = obj.get("port").getAsInt();
					String token = obj.get("token").getAsString();
					String workspace = obj.has("workspace")
							? obj.get("workspace").getAsString()
							: "";
					if (port > 0) {
						return new BridgeConnection(port, token, workspace);
					}
				} catch (Exception e) {
					// corrupt file — skip
				}
			}
		} catch (IOException e) {
			// directory not readable
		}
		return null;
	}

	public static Path resolveHome() {
		return Activator.getHome();
	}
}
