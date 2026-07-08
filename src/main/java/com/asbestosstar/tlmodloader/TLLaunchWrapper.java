package com.asbestosstar.tlmodloader;

import com.asbestosstar.tlmodloader.discovery.ModDiscoverer;
import com.asbestosstar.tlmodloader.mod.ModContainer;
import org.spongepowered.asm.mixin.Mixins;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

public class TLLaunchWrapper {
	private static final String TL_MAIN_CLASS = "org.tlauncher.tlauncher.rmo.TLauncher";
	private static final File MODS_DIR = new File("mods");
	private static final File CACHE_DIR = new File(".tlmodcache");

	public static void main(String[] args) throws Exception {
		System.out.println("[TLModLoader] Initialising TLauncher Mod Loader...");

		CACHE_DIR.mkdirs();
		if (!MODS_DIR.exists()) {
			MODS_DIR.mkdirs();
		}

		// 1. Discover mods
		List<ModContainer> mods = ModDiscoverer.discoverMods(MODS_DIR);

		// 2. Add mod classpaths to the SYSTEM classloader directly
		// We CANNOT use a custom URLClassLoader here because it creates a new "unnamed
		// module",
		// which breaks JavaFX and other libraries that rely on
		// --add-opens=...=ALL-UNNAMED JVM args.
		ClassLoader sysClassLoader = ClassLoader.getSystemClassLoader();

		for (ModContainer mod : mods) {
			for (URL url : mod.getAllClasspathUrls()) {
				addUrlToSystemClassLoader(sysClassLoader, url);
			}
		}

		System.out.println("[TLModLoader] Mod classpaths injected into system classloader.");

		// 3. Prepare Access Wideners and Coremods
		for (ModContainer mod : mods) {
			TLMixinBootstrap.applyAccessWideners(mod.getAccessWideners());
			for (String coremod : mod.getCoremods()) {
				TLMixinBootstrap.registerCoremod(coremod);
			}
		}

		// 4. Initialize Mixin environment
		System.out.println("[TLModLoader] Bootstrapping Mixin environment...");
		TLMixinBootstrap.init();

		// 5. Register Mixin Configs
		for (ModContainer mod : mods) {
			for (String mixinConfig : mod.getMixins()) {
				System.out.println("[TLModLoader] Registering Mixin config: " + mixinConfig);
				Mixins.addConfiguration(mixinConfig);
			}
		}

		// 6. Trigger Mod Entrypoints
		for (ModContainer mod : mods) {
			for (String entrypoint : mod.getEntrypoints()) {
				System.out.println("[TLModLoader] Invoking mod entrypoint: " + entrypoint);
				Class<?> clazz = Class.forName(entrypoint, false, sysClassLoader);
				Method initMethod = clazz.getDeclaredMethod("init");
				initMethod.invoke(null);
			}
		}

		System.out.println("[TLModLoader] Handing control over to TLauncher...");

		// 7. Launch actual TLauncher using the system class loader
		Class<?> tlauncherClass = Class.forName(TL_MAIN_CLASS, true, sysClassLoader);
		Method mainMethod = tlauncherClass.getMethod("main", String[].class);
		mainMethod.invoke(null, (Object) args);
	}

	/**
	 * Uses reflection to invoke
	 * AppClassLoader.appendToClassPathForInstrumentation(URL) This is the standard
	 * Java 9+ workaround to add jars to the system classpath at runtime.
	 */
	private static void addUrlToSystemClassLoader(ClassLoader classLoader, URL url) {
		try {
			// Java 9+ AppClassLoader method
			Method addMethod = classLoader.getClass().getDeclaredMethod("appendToClassPathForInstrumentation",
					URL.class);
			addMethod.setAccessible(true);
			addMethod.invoke(classLoader, url);
		} catch (NoSuchMethodException e) {
			// Fallback for Java 8 or if running in an IDE
			try {
				if (classLoader instanceof URLClassLoader) {
					Method addMethod = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
					addMethod.setAccessible(true);
					addMethod.invoke(classLoader, url);
				} else {
					System.err.println("[TLModLoader] Cannot add URL to classpath (Unsupported ClassLoader): " + url);
				}
			} catch (Exception ex) {
				System.err.println("[TLModLoader] Failed to add URL to classpath: " + url);
				ex.printStackTrace();
			}
		} catch (Exception e) {
			System.err.println("[TLModLoader] Failed to add URL to classpath: " + url);
			e.printStackTrace();
		}
	}
}