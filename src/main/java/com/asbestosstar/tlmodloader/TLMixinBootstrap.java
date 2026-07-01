package com.asbestosstar.tlmodloader;

import java.io.InputStream;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.impl.lib.accesswidener.AccessWidener;
import net.fabricmc.loader.impl.lib.accesswidener.AccessWidenerReader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;

public class TLMixinBootstrap {

	private static final AccessWidener ACCESS_WIDENER = new AccessWidener();
	private static final List<ICoremodTransformer> COREMODS = new ArrayList<>();

	public interface ICoremodTransformer {
		byte[] transform(String className, byte[] classBytes);
	}

	public static void applyAccessWideners(List<String> awFiles) {
		URLClassLoader classLoader = (URLClassLoader) TLLaunchWrapper.class.getClassLoader();
		for (String awPath : awFiles) {
			try (InputStream is = classLoader.getResourceAsStream(awPath)) {
				if (is != null) {
					AccessWidenerReader reader = new AccessWidenerReader(ACCESS_WIDENER);
					reader.read(is);
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
		if (!ACCESS_WIDENER.getTargets().isEmpty()) {
			ClassNode node = new ClassNode();
			ClassReader reader = new ClassReader(classBytes);
			reader.accept(node, 0);

			ACCESS_WIDENER.applyTo(node);

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
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
		// Must set these BEFORE MixinBootstrap.init()
		System.setProperty("mixin.bootstrapService", TLMixinServiceBootstrap.class.getName());
		System.setProperty("mixin.service", TLMixinService.class.getName());

		MixinBootstrap.init();
		MixinEnvironment.gotoPhase(MixinEnvironment.Phase.DEFAULT);
	}
}