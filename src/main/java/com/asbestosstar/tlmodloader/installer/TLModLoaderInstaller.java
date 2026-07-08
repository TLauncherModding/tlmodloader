package com.asbestosstar.tlmodloader.installer;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

public final class TLModLoaderInstaller {

	private static final String WRAPPER_MAIN_CLASS = "com.asbestosstar.tlmodloader.TLLaunchWrapper";

	private static final List<MavenDep> MAVEN_DEPS = List.of(
			new MavenDep("org.ow2.asm", "asm", "9.10.1", "https://repo1.maven.org/maven2/"),
			new MavenDep("org.ow2.asm", "asm-tree", "9.10.1", "https://repo1.maven.org/maven2/"),
			new MavenDep("org.ow2.asm", "asm-analysis", "9.10.1", "https://repo1.maven.org/maven2/"),
			new MavenDep("org.ow2.asm", "asm-commons", "9.10.1", "https://repo1.maven.org/maven2/"),
			new MavenDep("org.ow2.asm", "asm-util", "9.10.1", "https://repo1.maven.org/maven2/"),
			new MavenDep("net.fabricmc", "sponge-mixin", "0.17.2+mixin.0.8.7", "https://maven.fabricmc.net/"),
			new MavenDep("io.github.llamalad7", "mixinextras-common", "0.5.4", "https://repo1.maven.org/maven2/"),
			new MavenDep("net.fabricmc", "access-widener", "2.1.0", "https://maven.fabricmc.net/"));

	public static void main(String[] args) {
		System.out.println("Starting Swing Installer");
		SwingUtilities.invokeLater(TLModLoaderInstaller::showGui);
		System.out.println("Opened");
	}

	private static void showGui() {
		JFrame frame = new JFrame("TLModLoader Installer");
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		frame.setSize(850, 450);
		frame.setLocationRelativeTo(null);

		DefaultListModel<TLauncherInstall> model = new DefaultListModel<>();
		JList<TLauncherInstall> list = new JList<>(model);
		JTextArea log = new JTextArea();
		log.setEditable(false);

		JButton refresh = new JButton("Refresh");
		JButton browse = new JButton("Select Folder Manually");
		JButton install = new JButton("Install / Patch Selected");

		install.setEnabled(false);

		list.addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(ListSelectionEvent e) {
				if (!e.getValueIsAdjusting()) {
					install.setEnabled(list.getSelectedIndex() != -1);
				}
			}
		});

		Runnable reload = () -> {
			model.clear();
			install.setEnabled(false);
			for (TLauncherInstall found : findInstalls()) {
				model.addElement(found);
			}
			if (!model.isEmpty()) {
				list.setSelectedIndex(0);
			}
			log.append("Found " + model.size() + " install(s).\n");
		};

		refresh.addActionListener(e -> reload.run());

		browse.addActionListener(e -> {
			JFileChooser chooser = new JFileChooser();
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			chooser.setDialogTitle(
					"Select the folder containing 2.9369 (which has dependencies.json and appConfig.json above it)");

			if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
				Path dir = chooser.getSelectedFile().toPath();
				Path versionDir = dir.resolve("2.9369");
				if (!Files.isDirectory(versionDir)) {
					versionDir = dir;
				}
				Path deps = versionDir.resolve("dependencies.json");
				Path rootAppConfig = versionDir.getParent().resolve("appConfig.json");
				Path versionAppConfig = versionDir.resolve("appConfig.json");

				if (!Files.isRegularFile(deps) || !Files.isRegularFile(rootAppConfig)) {
					JOptionPane.showMessageDialog(frame,
							"Couldn't find dependencies.json and appConfig.json in that structure.", "Invalid folder",
							JOptionPane.ERROR_MESSAGE);
					return;
				}

				TLauncherInstall manual = new TLauncherInstall(versionDir, deps, rootAppConfig, versionAppConfig);
				model.addElement(manual);
				list.setSelectedValue(manual, true);
				log.append("Added manual install: " + versionDir + "\n");
			}
		});

		install.addActionListener(e -> {
			TLauncherInstall selected = list.getSelectedValue();
			if (selected == null) {
				JOptionPane.showMessageDialog(frame, "Select a TLauncher install first.");
				return;
			}

			try {
				patchInstall(selected, log);
				JOptionPane.showMessageDialog(frame, "Installed successfully.");
			} catch (Exception ex) {
				ex.printStackTrace();
				log.append("ERROR: " + ex + "\n");
				JOptionPane.showMessageDialog(frame, ex.toString(), "Install failed", JOptionPane.ERROR_MESSAGE);
			}
		});

		JPanel topPanel = new JPanel(new BorderLayout());
		JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
		leftButtons.add(refresh);
		leftButtons.add(browse);

		JPanel rightButton = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		rightButton.add(install);

		topPanel.add(leftButtons, BorderLayout.WEST);
		topPanel.add(rightButton, BorderLayout.EAST);

		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(list), new JScrollPane(log));
		split.setResizeWeight(0.55);

		frame.add(topPanel, BorderLayout.NORTH);
		frame.add(split, BorderLayout.CENTER);
		frame.setVisible(true);

		reload.run();
	}

	private static List<TLauncherInstall> findInstalls() {
		List<TLauncherInstall> result = new ArrayList<>();

		for (Path base : getBaseDirs()) {
			if (!Files.isDirectory(base)) {
				continue;
			}

			try {
				Files.walk(base, 20).filter(path -> path.getFileName().toString().equals("dependencies.json"))
						.forEach(deps -> {
							Path versionDir = deps.getParent();
							Path rootAppConfig = versionDir.getParent().resolve("appConfig.json");
							Path versionAppConfig = versionDir.resolve("appConfig.json");

							if (Files.isRegularFile(rootAppConfig)) {
								result.add(new TLauncherInstall(versionDir, deps, rootAppConfig, versionAppConfig));
							}
						});
			} catch (IOException ignored) {
			}
		}

		result.sort(Comparator.comparing(i -> i.versionDir.toString()));
		return result;
	}

	private static List<Path> getBaseDirs() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home");

		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			if (appData != null && !appData.isBlank()) {
				return List.of(Paths.get(appData, ".tlauncher"));
			}
			return List.of(Paths.get(home, "AppData", "Roaming", ".tlauncher"));
		}

		if (os.contains("mac")) {
			return List.of(Paths.get(home, "Library", "Application Support", "tlauncher"));
		}

		return List.of(Paths.get(home, ".tlauncher"));
	}

	private static Path getGlobalDependenciesDir() {
		String home = System.getProperty("user.home");
		return Paths.get(home, ".tlauncher", "starter", "dependencies");
	}

	private static void patchInstall(TLauncherInstall install, JTextArea log) throws Exception {
		Path versionDir = install.versionDir;
		Path cacheDependenciesDir = versionDir.resolve("dependencies");
		Files.createDirectories(cacheDependenciesDir);

		Path globalDependenciesDir = getGlobalDependenciesDir();
		Files.createDirectories(globalDependenciesDir);

		backup(install.dependenciesJson);
		backup(install.rootAppConfigJson);

		// ==========================================
		// 1. Patch dependencies.json
		// ==========================================
		JsonObject dependenciesJson = readJsonObject(install.dependenciesJson);

		JsonArray repositories = dependenciesJson.getAsJsonArray("repositories");
		if (repositories == null) {
			repositories = new JsonArray();
			dependenciesJson.add("repositories", repositories);
		}
		addRepositoryIfMissing(repositories, "https://maven.fabricmc.net/");
		addRepositoryIfMissing(repositories, "https://jitpack.io/");
		addRepositoryIfMissing(repositories, "https://repo1.maven.org/maven2/");

		JsonArray resources = dependenciesJson.getAsJsonArray("resources");
		if (resources == null) {
			resources = new JsonArray();
			dependenciesJson.add("resources", resources);
		}

		Path currentJar = getCurrentJar();
		String wrapperFileName = "tlmodloader-launchwrapper.jar";

		Path cacheWrapper = cacheDependenciesDir.resolve(wrapperFileName);
		Path globalWrapper = globalDependenciesDir.resolve(wrapperFileName);

		Files.copy(currentJar, cacheWrapper, StandardCopyOption.REPLACE_EXISTING);
		Files.copy(currentJar, globalWrapper, StandardCopyOption.REPLACE_EXISTING);

		addOrReplaceResource(resources, cacheWrapper, "dependencies/" + wrapperFileName, "", "");
		createMetadataForFile(cacheWrapper, log);
		createMetadataForFile(globalWrapper, log);

		log.append("Copied wrapper JAR to cache and global folders\n");

		for (MavenDep dep : MAVEN_DEPS) {
			String fileName = dep.fileName();
			Path cacheOut = cacheDependenciesDir.resolve(fileName);
			Path globalOut = globalDependenciesDir.resolve(fileName);

			log.append("Downloading " + dep.url() + "\n");
			download(dep.url(), cacheOut);
			Files.copy(cacheOut, globalOut, StandardCopyOption.REPLACE_EXISTING);

			addOrReplaceResource(resources, cacheOut, "dependencies/" + fileName, dep.mavenPath(), dep.url());
			createMetadataForFile(cacheOut, log);
			createMetadataForFile(globalOut, log);

			log.append("Added dependency: " + fileName + "\n");
		}

		writeJson(install.dependenciesJson, dependenciesJson);
		log.append("Patched dependencies.json\n");

		// Reverted to safe, working metadata update
		updateMetadataSha(install.dependenciesJson, log);

		// ==========================================
		// 2. Patch Root appConfig.json
		// ==========================================
		patchAppConfig(install.rootAppConfigJson, log);

		// ==========================================
		// 3. Patch Version-Specific appConfig.json
		// ==========================================
		if (install.versionAppConfigJson != null && Files.isRegularFile(install.versionAppConfigJson)) {
			backup(install.versionAppConfigJson);
			patchAppConfig(install.versionAppConfigJson, log);
		}
	}

	private static void patchAppConfig(Path appConfigPath, JTextArea log) throws Exception {
		JsonObject appConfig = readJsonObject(appConfigPath);

		appConfig.addProperty("mainClass", WRAPPER_MAIN_CLASS);
		log.append("Set mainClass in " + appConfigPath.getFileName() + "\n");

		if (appConfig.has("appDependencies")) {
			JsonObject appDeps = appConfig.getAsJsonObject("appDependencies");
			if (appDeps != null) {
				JsonArray depRepos = appDeps.getAsJsonArray("repositories");
				if (depRepos != null) {
					addRepositoryIfMissing(depRepos, "https://maven.fabricmc.net/");
					addRepositoryIfMissing(depRepos, "https://jitpack.io/");
					addRepositoryIfMissing(depRepos, "https://repo1.maven.org/maven2/");
				}

				JsonArray depResources = appDeps.getAsJsonArray("resources");
				if (depResources != null) {
					for (MavenDep dep : MAVEN_DEPS) {
						Path out = getGlobalDependenciesDir().resolve(dep.fileName());
						addOrReplaceResource(depResources, out, dep.fileName(), dep.mavenPath(), dep.url());
					}
					log.append("Added deps to " + appConfigPath.getFileName() + " appDependencies\n");
				}
			}
		}

		writeJson(appConfigPath, appConfig);
		log.append("Patched " + appConfigPath.getFileName() + "\n");

		// Reverted to safe, working metadata update
		updateMetadataSha(appConfigPath, log);
	}

	// Safe metadata updater (exactly like the working versions prior to the bypass
	// attempt)
	private static void updateMetadataSha(Path jsonPath, JTextArea log) {
		Path metaPath = jsonPath.resolveSibling(jsonPath.getFileName() + ".metadata");
		if (Files.isRegularFile(metaPath)) {
			try {
				backup(metaPath);
				JsonObject metadata = readJsonObject(metaPath);
				metadata.addProperty("sha1", sha1(jsonPath));
				metadata.addProperty("size", Files.size(jsonPath));
				metadata.addProperty("lastAccessAt", System.currentTimeMillis());
				writeJson(metaPath, metadata);
				log.append("Patched " + metaPath.getFileName() + "\n");
			} catch (Exception ex) {
				log.append("WARNING: Could not patch " + metaPath.getFileName() + ": " + ex.getMessage() + "\n");
			}
		}
	}

	private static void createMetadataForFile(Path file, JTextArea log) throws Exception {
		Path metaPath = file.resolveSibling(file.getFileName() + ".metadata");
		JsonObject meta = new JsonObject();
		meta.addProperty("sha1", sha1(file));
		meta.addProperty("size", Files.size(file));
		meta.addProperty("lastAccessAt", System.currentTimeMillis());
		writeJson(metaPath, meta);
	}

	private static void addOrReplaceResource(JsonArray resources, Path file, String path, String relativeUrl,
			String link) throws Exception {
		removeResourceByPath(resources, path);

		JsonObject obj = new JsonObject();
		obj.addProperty("sha1", sha1(file));
		obj.addProperty("size", Files.size(file));
		obj.addProperty("path", path);
		obj.addProperty("relativeUrl", relativeUrl);
		obj.addProperty("executable", false);
		obj.addProperty("link", link == null ? "" : link);

		resources.add(obj);
	}

	private static void removeResourceByPath(JsonArray resources, String path) {
		for (int i = resources.size() - 1; i >= 0; i--) {
			JsonElement el = resources.get(i);
			if (!el.isJsonObject())
				continue;

			JsonObject obj = el.getAsJsonObject();
			if (obj.has("path") && path.equals(obj.get("path").getAsString())) {
				resources.remove(i);
			}
		}
	}

	private static void addRepositoryIfMissing(JsonArray repositories, String repo) {
		for (int i = 0; i < repositories.size(); i++) {
			JsonElement el = repositories.get(i);
			if (el.isJsonPrimitive() && repo.equals(el.getAsString())) {
				return;
			}
		}
		repositories.add(new JsonPrimitive(repo));
	}

	private static void download(String url, Path out) throws IOException {
		try (InputStream in = new URL(url).openStream()) {
			Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static JsonObject readJsonObject(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		}
	}

	private static void writeJson(Path path, JsonObject object) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path)) {
			writer.write(object.toString());
		}
	}

	private static void backup(Path path) throws IOException {
		Path backup = path.resolveSibling(path.getFileName() + ".bak");
		if (!Files.exists(backup)) {
			Files.copy(path, backup);
		}
	}

	private static Path getCurrentJar() throws URISyntaxException {
		return Paths.get(TLModLoaderInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
	}

	private static String sha1(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-1");
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
		StringBuilder sb = new StringBuilder();
		for (byte b : digest.digest()) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	private record TLauncherInstall(Path versionDir, Path dependenciesJson, Path rootAppConfigJson,
			Path versionAppConfigJson) {
		@Override
		public String toString() {
			return versionDir.toString();
		}
	}

	private record MavenDep(String group, String artifact, String version, String repo) {
		String fileName() {
			return artifact + "-" + version + ".jar";
		}

		String mavenPath() {
			return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName();
		}

		String url() {
			return repo + mavenPath();
		}
	}

	// ====================================================================================
	// Minimal Internal JSON Parser
	// ====================================================================================

	private static abstract class JsonElement {
		public JsonObject getAsJsonObject() {
			throw new IllegalStateException("Not a JsonObject");
		}

		public JsonArray getAsJsonArray() {
			throw new IllegalStateException("Not a JsonArray");
		}

		public String getAsString() {
			throw new IllegalStateException("Not a String");
		}

		public boolean isJsonObject() {
			return this instanceof JsonObject;
		}

		public boolean isJsonPrimitive() {
			return this instanceof JsonPrimitive;
		}
	}

	private static class JsonPrimitive extends JsonElement {
		private final Object value;

		public JsonPrimitive(String value) {
			this.value = value;
		}

		public JsonPrimitive(Number value) {
			this.value = value;
		}

		public JsonPrimitive(Boolean value) {
			this.value = value;
		}

		@Override
		public String getAsString() {
			return value == null ? "null" : value.toString();
		}

		@Override
		public String toString() {
			if (value instanceof String) {
				String s = value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
						.replace("\r", "\\r").replace("\t", "\\t");
				return "\"" + s + "\"";
			}
			return String.valueOf(value);
		}
	}

	private static class JsonObject extends JsonElement {
		private final java.util.Map<String, JsonElement> members = new java.util.LinkedHashMap<>();

		public void add(String key, JsonElement value) {
			members.put(key, value);
		}

		public void addProperty(String key, String value) {
			add(key, new JsonPrimitive(value));
		}

		public void addProperty(String key, Number value) {
			add(key, new JsonPrimitive(value));
		}

		public void addProperty(String key, Boolean value) {
			add(key, new JsonPrimitive(value));
		}

		public JsonElement get(String key) {
			return members.get(key);
		}

		public boolean has(String key) {
			return members.containsKey(key);
		}

		@Override
		public JsonObject getAsJsonObject() {
			return this;
		}

		// ADD THIS METHOD:
		public JsonObject getAsJsonObject(String key) {
			JsonElement e = members.get(key);
			return e != null ? e.getAsJsonObject() : null;
		}

		public JsonArray getAsJsonArray(String key) {
			JsonElement e = members.get(key);
			return e != null ? e.getAsJsonArray() : null;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder("{\n");
			int i = 0;
			for (var entry : members.entrySet()) {
				sb.append("  \"").append(entry.getKey()).append("\": ").append(entry.getValue().toString());
				if (i < members.size() - 1)
					sb.append(",");
				sb.append("\n");
				i++;
			}
			sb.append("}");
			return sb.toString();
		}
	}

	private static class JsonArray extends JsonElement {
		private final List<JsonElement> elements = new ArrayList<>();

		public void add(JsonElement element) {
			elements.add(element);
		}

		public JsonElement get(int index) {
			return elements.get(index);
		}

		public void remove(int index) {
			elements.remove(index);
		}

		public int size() {
			return elements.size();
		}

		@Override
		public JsonArray getAsJsonArray() {
			return this;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder("[\n");
			for (int i = 0; i < elements.size(); i++) {
				sb.append("  ").append(elements.get(i).toString());
				if (i < elements.size() - 1)
					sb.append(",");
				sb.append("\n");
			}
			sb.append("]");
			return sb.toString();
		}
	}

	private static class JsonParser {
		private final Reader reader;
		private int c = -1;

		private JsonParser(Reader reader) {
			this.reader = reader;
		}

		private int read() throws IOException {
			c = reader.read();
			return c;
		}

		private void skipWhitespace() throws IOException {
			while (Character.isWhitespace(c))
				read();
		}

		public static JsonElement parseReader(Reader reader) throws IOException {
			return new JsonParser(reader).parse();
		}

		private JsonElement parse() throws IOException {
			read();
			skipWhitespace();
			return parseValue();
		}

		private JsonElement parseValue() throws IOException {
			skipWhitespace();
			if (c == '{')
				return parseObject();
			if (c == '[')
				return parseArray();
			if (c == '"')
				return parseString();
			if (c == 't' || c == 'f')
				return parseBoolean();
			if (c == 'n') {
				read();
				read();
				read();
				read();
				return new JsonPrimitive("");
			}
			return parseNumber();
		}

		private JsonObject parseObject() throws IOException {
			read();
			JsonObject obj = new JsonObject();
			skipWhitespace();
			if (c == '}') {
				read();
				return obj;
			}
			while (true) {
				skipWhitespace();
				String key = parseString().getAsString();
				skipWhitespace();
				read();
				JsonElement val = parseValue();
				obj.add(key, val);
				skipWhitespace();
				if (c == '}') {
					read();
					return obj;
				}
				read();
			}
		}

		private JsonArray parseArray() throws IOException {
			read();
			JsonArray arr = new JsonArray();
			skipWhitespace();
			if (c == ']') {
				read();
				return arr;
			}
			while (true) {
				arr.add(parseValue());
				skipWhitespace();
				if (c == ']') {
					read();
					return arr;
				}
				read();
			}
		}

		private JsonPrimitive parseString() throws IOException {
			read();
			StringBuilder sb = new StringBuilder();
			while (c != '"') {
				if (c == '\\') {
					read();
					if (c == 'n')
						sb.append('\n');
					else if (c == 't')
						sb.append('\t');
					else if (c == 'r')
						sb.append('\r');
					else if (c == 'u') {
						char[] hex = new char[4];
						hex[0] = (char) read();
						hex[1] = (char) read();
						hex[2] = (char) read();
						hex[3] = (char) read();
						sb.append((char) Integer.parseInt(new String(hex), 16));
					} else
						sb.append((char) c);
				} else {
					sb.append((char) c);
				}
				read();
			}
			read();
			return new JsonPrimitive(sb.toString());
		}

		private JsonPrimitive parseNumber() throws IOException {
			StringBuilder sb = new StringBuilder();
			while (c == '-' || Character.isDigit(c) || c == '.') {
				sb.append((char) c);
				read();
			}
			String num = sb.toString();
			if (num.contains("."))
				return new JsonPrimitive(Double.parseDouble(num));
			return new JsonPrimitive(Long.parseLong(num));
		}

		private JsonPrimitive parseBoolean() throws IOException {
			if (c == 't') {
				read();
				read();
				read();
				read();
				return new JsonPrimitive(true);
			}
			read();
			read();
			read();
			read();
			read();
			return new JsonPrimitive(false);
		}
	}
}