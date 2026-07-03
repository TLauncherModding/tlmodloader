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
            new MavenDep("net.fabricmc", "sponge-mixin", "0.17.2+mixin.0.8.7", "https://maven.fabricmc.net/"),
            // Updated to exact JitPack URL and version 0.2.2
            new MavenDep("com.github.LlamaLad7", "MixinExtras", "0.2.2", "https://jitpack.io/"), 
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
        
        // Disable install button by default until an item is selected
        install.setEnabled(false);

        // Enable/Disable install button based on list selection
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
            install.setEnabled(false); // Disable while reloading
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

        // New Layout: Top buttons on left, Install on right
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
                // Increased depth from 12 to 20 to reach deep cache folders
                Files.walk(base, 20).filter(path -> path.getFileName().toString().equals("dependencies.json"))
                        .forEach(deps -> {
                            Path versionDir = deps.getParent(); // e.g., .../tlauncher/2.9369/
                            Path app = versionDir.getParent().resolve("appConfig.json"); // e.g., .../tlauncher/appConfig.json

                            if (Files.isRegularFile(app)) {
                                result.add(new TLauncherInstall(versionDir, deps, app));
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
        addRepositoryIfMissing(repositories, "https://jitpack.io/"); // Add Jitpack to repos just in case

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
            writer.write(object.toString()); // Uses our pretty-print toString()
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

    // ====================================================================================
    // Minimal Internal JSON Parser (Replaces Gson to avoid Loom dependency hell)
    // ====================================================================================

    private static abstract class JsonElement {
        public JsonObject getAsJsonObject() { throw new IllegalStateException("Not a JsonObject"); }
        public JsonArray getAsJsonArray() { throw new IllegalStateException("Not a JsonArray"); }
        public String getAsString() { throw new IllegalStateException("Not a String"); }
        public boolean isJsonObject() { return this instanceof JsonObject; }
        public boolean isJsonPrimitive() { return this instanceof JsonPrimitive; }
    }

    private static class JsonPrimitive extends JsonElement {
        private final Object value;
        public JsonPrimitive(String value) { this.value = value; }
        public JsonPrimitive(Number value) { this.value = value; }
        public JsonPrimitive(Boolean value) { this.value = value; }

        @Override
        public String getAsString() { return value == null ? "null" : value.toString(); }

        @Override
        public String toString() {
            if (value instanceof String) {
                String s = value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
                return "\"" + s + "\"";
            }
            return String.valueOf(value);
        }
    }

    private static class JsonObject extends JsonElement {
        private final java.util.Map<String, JsonElement> members = new java.util.LinkedHashMap<>();

        public void add(String key, JsonElement value) { members.put(key, value); }
        public void addProperty(String key, String value) { add(key, new JsonPrimitive(value)); }
        public void addProperty(String key, Number value) { add(key, new JsonPrimitive(value)); }
        public void addProperty(String key, Boolean value) { add(key, new JsonPrimitive(value)); }

        public JsonElement get(String key) { return members.get(key); }
        public boolean has(String key) { return members.containsKey(key); }

        @Override
        public JsonObject getAsJsonObject() { return this; }

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
                if (i < members.size() - 1) sb.append(",");
                sb.append("\n");
                i++;
            }
            sb.append("}");
            return sb.toString();
        }
    }

    private static class JsonArray extends JsonElement {
        private final List<JsonElement> elements = new ArrayList<>();

        public void add(JsonElement element) { elements.add(element); }
        public JsonElement get(int index) { return elements.get(index); }
        public void remove(int index) { elements.remove(index); }
        public int size() { return elements.size(); }

        @Override
        public JsonArray getAsJsonArray() { return this; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < elements.size(); i++) {
                sb.append("  ").append(elements.get(i).toString());
                if (i < elements.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]");
            return sb.toString();
        }
    }

    private static class JsonParser {
        private final Reader reader;
        private int c = -1;

        private JsonParser(Reader reader) { this.reader = reader; }

        private int read() throws IOException { c = reader.read(); return c; }
        private void skipWhitespace() throws IOException { while (Character.isWhitespace(c)) read(); }

        public static JsonElement parseReader(Reader reader) throws IOException {
            return new JsonParser(reader).parse();
        }

        private JsonElement parse() throws IOException {
            read();
            skipWhitespace();
            return parseValue();
        }

        private JsonElement parseValue() throws IOException {
            skipWhitespace(); // Safety check
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') { read(); read(); read(); read(); return new JsonPrimitive(""); } // null
            return parseNumber();
        }

        private JsonObject parseObject() throws IOException {
            read(); // skip '{'
            JsonObject obj = new JsonObject();
            skipWhitespace();
            if (c == '}') { read(); return obj; }
            while (true) {
                skipWhitespace();
                String key = parseString().getAsString();
                skipWhitespace();
                read(); // skip ':'
                JsonElement val = parseValue();
                obj.add(key, val);
                skipWhitespace();
                if (c == '}') { read(); return obj; }
                read(); // skip ','
            }
        }

        private JsonArray parseArray() throws IOException {
            read(); // skip '['
            JsonArray arr = new JsonArray();
            skipWhitespace();
            if (c == ']') { read(); return arr; }
            while (true) {
                arr.add(parseValue());
                skipWhitespace();
                if (c == ']') { read(); return arr; }
                read(); // skip ','
            }
        }

        private JsonPrimitive parseString() throws IOException {
            read(); // skip '"'
            StringBuilder sb = new StringBuilder();
            while (c != '"') {
                if (c == '\\') {
                    read();
                    if (c == 'n') sb.append('\n');
                    else if (c == 't') sb.append('\t');
                    else if (c == 'r') sb.append('\r');
                    else sb.append((char) c);
                } else {
                    sb.append((char) c);
                }
                read();
            }
            read(); // skip closing '"'
            return new JsonPrimitive(sb.toString());
        }

        private JsonPrimitive parseNumber() throws IOException {
            StringBuilder sb = new StringBuilder();
            while (c == '-' || Character.isDigit(c) || c == '.') {
                sb.append((char) c);
                read();
            }
            String num = sb.toString();
            if (num.contains(".")) return new JsonPrimitive(Double.parseDouble(num));
            return new JsonPrimitive(Long.parseLong(num));
        }

        private JsonPrimitive parseBoolean() throws IOException {
            if (c == 't') { read(); read(); read(); read(); return new JsonPrimitive(true); }
            read(); read(); read(); read(); read(); return new JsonPrimitive(false);
        }
    }
}