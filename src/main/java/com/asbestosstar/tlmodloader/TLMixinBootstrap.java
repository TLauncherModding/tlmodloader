package com.asbestosstar.tlmodloader;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;

import net.fabricmc.loader.impl.lib.accesswidener.AccessWidener;
import net.fabricmc.loader.impl.lib.accesswidener.AccessWidenerClassVisitor;
import net.fabricmc.loader.impl.lib.accesswidener.AccessWidenerReader;

public class TLMixinBootstrap {

	private static final AccessWidener ACCESS_WIDENER = new AccessWidener();
	private static final List<ICoremodTransformer> COREMODS = new ArrayList<>();

	public interface ICoremodTransformer {
		byte[] transform(String className, byte[] classBytes);
	}

	public static void applyAccessWideners(List<String> awFiles) {
		ClassLoader classLoader = TLLaunchWrapper.class.getClassLoader();

		for (String awPath : awFiles) {
			try (InputStream is = classLoader.getResourceAsStream(awPath)) {
				if (is != null) {
					AccessWidenerReader reader = new AccessWidenerReader(ACCESS_WIDENER);
					reader.read(is.readAllBytes()); // fixed
					System.out.println("[TLLoader] Applied Access Widener: " + awPath);
				} else {
					System.err.println("[TLLoader] Could not find Access Widener: " + awPath);
				}
			} catch (Exception e) {
				System.err.println("[TLLoader] Failed to read Access Widener: " + awPath);
				e.printStackTrace();
			}
		}
	}

	public static void registerCoremod(String className) {
		try {
			Class<?> coremodClass = Class.forName(className, true, TLLaunchWrapper.class.getClassLoader());
			Object instance = coremodClass.getDeclaredConstructor().newInstance();

			if (instance instanceof ICoremodTransformer) {
				COREMODS.add((ICoremodTransformer) instance);
				System.out.println("[TLLoader] Registered ICoremodTransformer: " + className);
			}
		} catch (Exception e) {
			System.err.println("[TLLoader] Failed to load coremod: " + className);
			e.printStackTrace();
		}
	}

	/**
	 * Applies coremod and access widener transformations before Mixin processes the
	 * class.
	 */
	public static byte[] applyPreMixinTransforms(String name, byte[] classBytes) {
		if (classBytes == null) {
			return null;
		}

		// Step 1: Apply Fabric Access Wideners via ASM
		if (!ACCESS_WIDENER.getTargets().isEmpty() && ACCESS_WIDENER.getTargets().contains(name.replace('/', '.'))) {

			ClassReader reader = new ClassReader(classBytes);
			ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

			ClassVisitor visitor = AccessWidenerClassVisitor.createClassVisitor(Opcodes.ASM9, writer, ACCESS_WIDENER);

			reader.accept(visitor, 0);
			classBytes = writer.toByteArray();
		}

		// Step 2: Run all registered byte[] coremods
		for (ICoremodTransformer transformer : COREMODS) {
			classBytes = transformer.transform(name, classBytes);

			if (classBytes == null) {
				throw new RuntimeException(
						"[TLLoader] Coremod " + transformer.getClass().getName() + " returned null for " + name);
			}
		}

		return classBytes;
	}

	public static void init() {
		System.setProperty("mixin.bootstrapService", TLMixinServiceBootstrap.class.getName());
		System.setProperty("mixin.service", TLMixinService.class.getName());

		MixinBootstrap.init();
		gotoMixinDefaultPhaseReflective();
	}

	private static void gotoMixinDefaultPhaseReflective() {
		try {
			Method m = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
			m.setAccessible(true);
			m.invoke(null, MixinEnvironment.Phase.DEFAULT);
		} catch (Exception e) {
			throw new RuntimeException("[TLLoader] Failed to switch Mixin phase", e);
		}
	}

}