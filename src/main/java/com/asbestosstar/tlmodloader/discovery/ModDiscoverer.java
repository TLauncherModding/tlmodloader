package com.asbestosstar.tlmodloader.discovery;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.asbestosstar.tlmodloader.mod.ModContainer;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class ModDiscoverer {
	private static final Gson GSON = new Gson();

	public static List<ModContainer> discoverMods(File modsDir) {
		List<ModContainer> mods = new ArrayList<>();
		File[] files = modsDir.listFiles((dir, name) -> name.endsWith(".jar"));

		if (files == null)
			return mods;

		for (File modJar : files) {
			try {
				ModContainer container = parseModJar(modJar);
				if (container != null) {
					// Add the root mod jar to classpath
					container.addClasspathUrl(modJar.toURI().toURL());

					// Extract and handle nested JARs (JAR-in-JAR)
					extractNestedJars(modJar, container);

					// Resolve macro dependencies (e.g., ${HOME_DIR}/libs/lib.jar or
					// ${HOME_DIR}/libs/*)
					resolveMacroDependencies(container);

					mods.add(container);
				}
			} catch (Exception e) {
				System.err.println("[TLModLoader] Failed to load mod: " + modJar.getName());
				e.printStackTrace();
			}
		}
		return mods;
	}

	private static ModContainer parseModJar(File jarFile) throws Exception {
		try (JarFile jar = new JarFile(jarFile)) {
			JarEntry entry = jar.getJarEntry("tlmod.json");
			if (entry == null)
				return null; // Not a valid mod

			try (InputStream is = jar.getInputStream(entry)) {
				JsonObject json = GSON.fromJson(new String(is.readAllBytes()), JsonObject.class);
				ModContainer mod = new ModContainer(json.get("id").getAsString(), json.get("version").getAsString());

				if (json.has("entrypoints")) {
					json.getAsJsonArray("entrypoints").forEach(e -> mod.getEntrypoints().add(e.getAsString()));
				}
				if (json.has("mixins")) {
					json.getAsJsonArray("mixins")
							.forEach(e -> mod.getMixins().add(resolveJsonStringWildcard(e.getAsString())));
				}
				if (json.has("accessWideners")) {
					json.getAsJsonArray("accessWideners")
							.forEach(e -> mod.getAccessWideners().add(resolveJsonStringWildcard(e.getAsString())));
				}
				if (json.has("coremods")) {
					json.getAsJsonArray("coremods").forEach(e -> mod.getCoremods().add(e.getAsString()));
				}
				if (json.has("dependencies")) {
					mod.setRawDependencies(json.getAsJsonArray("dependencies"));
				}

				return mod;
			}
		}
	}

	private static void extractNestedJars(File modJar, ModContainer container) throws Exception {
		try (JarFile jar = new JarFile(modJar)) {
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				if (entry.getName().startsWith("META-INF/jars/") && entry.getName().endsWith(".jar")) {
					File cachedJar = new File(".tlmodcache", entry.getName().replace("META-INF/jars/", ""));
					cachedJar.getParentFile().mkdirs();

					if (!cachedJar.exists()) {
						try (InputStream is = jar.getInputStream(entry)) {
							java.nio.file.Files.copy(is, cachedJar.toPath(),
									java.nio.file.StandardCopyOption.REPLACE_EXISTING);
						}
					}
					container.addClasspathUrl(cachedJar.toURI().toURL());
				}
			}
		}
	}

	private static void resolveMacroDependencies(ModContainer container) {
		if (container.getRawDependencies() == null)
			return;

		String homeDir = System.getProperty("user.home").replace("\\", "/");
		String appDir = System.getProperty("user.dir").replace("\\", "/");

		for (JsonElement element : container.getRawDependencies()) {
			String rawPath = element.getAsString();

			// Resolve macros first
			String resolvedPath = rawPath.replace("${HOME_DIR}", homeDir).replace("${APP_DIR}", appDir);

			// Resolve wildcards
			resolvePathWithWildcard(resolvedPath, container);
		}
	}

	/**
	 * Resolves paths containing * or ** and adds all matching files to the
	 * classpath.
	 */
	private static void resolvePathWithWildcard(String path, ModContainer container) {
		File file = new File(path);
		File parentDir = file.getParentFile();

		// If there's no wildcard, just add the single file
		if (!path.contains("*")) {
			addDependencyFile(file, container);
			return;
		}

		// Handle wildcard logic
		if (parentDir != null && parentDir.exists()) {
			String targetPattern = file.getName();

			// Match everything in the directory (e.g., libs/*)
			if (targetPattern.equals("*")) {
				File[] matchedFiles = parentDir.listFiles();
				if (matchedFiles != null) {
					for (File matchedFile : matchedFiles) {
						addDependencyFile(matchedFile, container);
					}
				}
			}
			// Match specific extensions (e.g., libs/*.jar)
			else if (targetPattern.startsWith("*.")) {
				String extension = targetPattern.substring(1); // ".jar"
				File[] matchedFiles = parentDir.listFiles((dir, name) -> name.endsWith(extension));
				if (matchedFiles != null) {
					for (File matchedFile : matchedFiles) {
						addDependencyFile(matchedFile, container);
					}
				}
			}
			// Handle recursive wildcard ** (e.g., libs/**/*.jar)
			else if (path.contains("**")) {
				String requiredSuffix = targetPattern.replace("**", ""); // e.g. ".jar"
				listFilesRecursive(parentDir, requiredSuffix, container);
			}
		} else {
			System.err.println("[TLModLoader] Wildcard dependency directory does not exist: " + path);
		}
	}

	/**
	 * Helper to recursively find files matching a suffix.
	 */
	private static void listFilesRecursive(File dir, String suffix, ModContainer container) {
		File[] files = dir.listFiles();
		if (files == null)
			return;

		for (File file : files) {
			if (file.isDirectory()) {
				listFilesRecursive(file, suffix, container);
			} else if (file.getName().endsWith(suffix)) {
				addDependencyFile(file, container);
			}
		}
	}

	/**
	 * Safely adds a single dependency file to the classpath.
	 */
	private static void addDependencyFile(File file, ModContainer container) {
		if (file.exists()) {
			try {
				container.addClasspathUrl(file.toURI().toURL());
				System.out.println("[TLModLoader] Resolved dependency: " + file.getAbsolutePath());
			} catch (Exception e) {
				System.err.println("[TLModLoader] Failed to add dependency: " + file.getAbsolutePath());
			}
		} else {
			System.err.println("[TLModLoader] Dependency not found: " + file.getAbsolutePath());
		}
	}

	/**
	 * Small helper to resolve wildcards in Mixin/AW JSON paths just in case.
	 * Usually, you want explicit paths for these, but this allows e.g.
	 * "mixins/*.json"
	 */
	private static String resolveJsonStringWildcard(String path) {
		// If it doesn't contain a wildcard, return as-is
		if (!path.contains("*"))
			return path;

		// Note: Returning the raw string with '*' to Mixin will crash it.
		// If you actually use wildcards in tlmod.json for mixins, you must expand
		// them here into a list of explicit strings. For now, we just strip/flag it.
		System.err.println(
				"[TLModLoader] WARNING: Wildcards in 'mixins' or 'accessWideners' are not fully supported. Use explicit paths.");
		return path.replace("*", "");
	}
}