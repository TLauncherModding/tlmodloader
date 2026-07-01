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
		System.out.println("[TLModLoader] Initializing TLauncher Mod Loader...");

		CACHE_DIR.mkdirs();
		if (!MODS_DIR.exists()) {
			MODS_DIR.mkdirs();
		}

		// 1. Discover mods
		List<ModContainer> mods = ModDiscoverer.discoverMods(MODS_DIR);

		// 2. Add all classpaths
		URLClassLoader classLoader = (URLClassLoader) TLLaunchWrapper.class.getClassLoader();
		for (ModContainer mod : mods) {
			for (URL url : mod.getAllClasspathUrls()) {
				addUrl(classLoader, url);
			}
		}

		// 3. Prepare Access Wideners and Coremods
		for (ModContainer mod : mods) {
			TLMixinBootstrap.applyAccessWideners(mod.getAccessWideners());
			for (String coremod : mod.getCoremods()) {
				TLMixinBootstrap.registerCoremod(coremod);
			}
		}

		// 4. Initialize Mixin & Coremod Pipeline (No Reflection!)
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
				Class<?> clazz = Class.forName(entrypoint, false, classLoader);
				Method initMethod = clazz.getDeclaredMethod("init");
				initMethod.invoke(null);
			}
		}

		System.out.println("[TLModLoader] Handing control over to TLauncher...");

		// 7. Launch actual TLauncher
		Class<?> tlauncherClass = Class.forName(TL_MAIN_CLASS, true, classLoader);
		Method mainMethod = tlauncherClass.getDeclaredMethod("main", String[].class);
		mainMethod.invoke(null, (Object) args);
	}

	private static void addUrl(URLClassLoader classLoader, URL url) {
		try {
			Method addMethod = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
			addMethod.setAccessible(true);
			addMethod.invoke(classLoader, url);
		} catch (Exception e) {
			System.err.println("[TLModLoader] Failed to add URL to classpath: " + url);
		}
	}
}