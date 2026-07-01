package com.asbestosstar.tlmodloader.installer;

import com.google.gson.*;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;

public final class TLModLoaderInstaller {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static final String WRAPPER_MAIN_CLASS = "com.asbestosstar.tlmodloader.TLLaunchWrapper";

	private static final List<MavenDep> MAVEN_DEPS = List.of(
			new MavenDep("net.fabricmc", "sponge-mixin", "0.17.2+mixin.0.8.7", "https://maven.fabricmc.net/"),
			new MavenDep("com.github.llamalad7", "mixinextras", "0.4.1", "https://repo1.maven.org/maven2/"),
			new MavenDep("net.fabricmc", "access-widener", "2.1.1", "https://maven.fabricmc.net/"));

	public static void main(String[] args) {
		SwingUtilities.invokeLater(TLModLoaderInstaller::showGui);
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

		Runnable reload = () -> {
			model.clear();
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
			chooser.setDialogTitle("Select folder containing dependencies.json and appConfig.json");

			if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
				Path dir = chooser.getSelectedFile().toPath();
				Path deps = dir.resolve("dependencies.json");
				Path app = dir.resolve("appConfig.json");

				if (!Files.isRegularFile(deps) || !Files.isRegularFile(app)) {
					JOptionPane.showMessageDialog(frame,
							"That folder must contain dependencies.json and appConfig.json.", "Invalid folder",
							JOptionPane.ERROR_MESSAGE);
					return;
				}

				TLauncherInstall manual = new TLauncherInstall(dir, deps, app);
				model.addElement(manual);
				list.setSelectedValue(manual, true);
				log.append("Added manual install: " + dir + "\n");
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

		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
		top.add(refresh);
		top.add(browse);
		top.add(install);

		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(list), new JScrollPane(log));
		split.setResizeWeight(0.55);

		frame.add(top, BorderLayout.NORTH);
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
				Files.walk(base, 12).filter(path -> path.getFileName().toString().equals("dependencies.json"))
						.forEach(deps -> {
							Path dir = deps.getParent();
							Path app = dir.resolve("appConfig.json");

							if (Files.isRegularFile(app)) {
								result.add(new TLauncherInstall(dir, deps, app));
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
				return List.of(Paths.get(appData, "tlauncher"));
			}
			return List.of(Paths.get(home, "AppData", "Roaming", "tlauncher"));
		}

		if (os.contains("mac")) {
			return List.of(Paths.get(home, "Library", "Application Support", "tlauncher"));
		}

		return List.of(Paths.get(home, ".tlauncher"));
	}

	private static void patchInstall(TLauncherInstall install, JTextArea log) throws Exception {
		Path versionDir = install.versionDir;
		Path dependenciesDir = versionDir.resolve("dependencies");
		Files.createDirectories(dependenciesDir);

		backup(install.dependenciesJson);
		backup(install.appConfigJson);

		JsonObject dependenciesJson = readJsonObject(install.dependenciesJson);

		JsonArray repositories = dependenciesJson.getAsJsonArray("repositories");
		if (repositories == null) {
			repositories = new JsonArray();
			dependenciesJson.add("repositories", repositories);
		}

		addRepositoryIfMissing(repositories, "https://maven.fabricmc.net/");
		addRepositoryIfMissing(repositories, "https://repo1.maven.org/maven2/");

		JsonArray resources = dependenciesJson.getAsJsonArray("resources");
		if (resources == null) {
			resources = new JsonArray();
			dependenciesJson.add("resources", resources);
		}

		Path currentJar = getCurrentJar();
		Path wrapperJar = dependenciesDir.resolve("tlmodloader-launchwrapper.jar");
		Files.copy(currentJar, wrapperJar, StandardCopyOption.REPLACE_EXISTING);

		addOrReplaceResource(resources, wrapperJar, "dependencies/tlmodloader-launchwrapper.jar", "", "");

		log.append("Copied wrapper JAR: " + wrapperJar + "\n");

		for (MavenDep dep : MAVEN_DEPS) {
			Path out = dependenciesDir.resolve(dep.fileName());

			log.append("Downloading " + dep.url() + "\n");
			download(dep.url(), out);

			addOrReplaceResource(resources, out, "dependencies/" + dep.fileName(), dep.mavenPath(), dep.url());

			log.append("Added dependency: " + dep.fileName() + "\n");
		}

		writeJson(install.dependenciesJson, dependenciesJson);
		log.append("Patched dependencies.json\n");

		JsonObject appConfig = readJsonObject(install.appConfigJson);
		appConfig.addProperty("mainClass", WRAPPER_MAIN_CLASS);
		writeJson(install.appConfigJson, appConfig);

		log.append("Set mainClass to " + WRAPPER_MAIN_CLASS + "\n");
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
			if (!el.isJsonObject()) {
				continue;
			}

			JsonObject obj = el.getAsJsonObject();
			if (obj.has("path") && path.equals(obj.get("path").getAsString())) {
				resources.remove(i);
			}
		}
	}

	private static void addRepositoryIfMissing(JsonArray repositories, String repo) {
		for (JsonElement el : repositories) {
			if (el.isJsonPrimitive() && repo.equals(el.getAsString())) {
				return;
			}
		}
		repositories.add(repo);
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
			GSON.toJson(object, writer);
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

	private record TLauncherInstall(Path versionDir, Path dependenciesJson, Path appConfigJson) {
		@Override
		public String toString() {
			return versionDir.toString();
		}
	}
}