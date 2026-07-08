package com.asbestosstar.tlmodloader;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;

import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.accesswidener.AccessWidenerVisitor;

public class TLMixinBootstrap {

	private static final List<ClassRule> CLASS_RULES = new ArrayList<>();
	private static final List<MemberRule> METHOD_RULES = new ArrayList<>();
	private static final List<MemberRule> FIELD_RULES = new ArrayList<>();
	private static final Set<String> TARGET_CLASSES = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private static final List<ICoremodTransformer> COREMODS = new ArrayList<>();

	public interface ICoremodTransformer {
		byte[] transform(String className, byte[] classBytes);
	}

	// Public records to store the parsed rules cleanly
	public record ClassRule(String name, AccessWidenerReader.AccessType access) {
	}

	public record MemberRule(String owner, String name, String descriptor, AccessWidenerReader.AccessType access) {
	}

	public static void applyAccessWideners(List<String> awFiles) {
		ClassLoader classLoader = TLLaunchWrapper.class.getClassLoader();

		for (String awPath : awFiles) {
			try (InputStream is = classLoader.getResourceAsStream(awPath)) {
				if (is != null) {
					// Pass our custom visitor directly to the reader, skipping the hidden
					// AccessWidener class entirely
					AccessWidenerReader reader = new AccessWidenerReader(new TLAcessWidenerVisitor());
					reader.read(is.readAllBytes());
					System.out.println("[TLModLoader] Parsed Access Widener: " + awPath);
				} else {
					System.err.println("[TLModLoader] Could not find Access Widener: " + awPath);
				}
			} catch (Exception e) {
				System.err.println("[TLModLoader] Failed to read Access Widener: " + awPath);
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
				System.out.println("[TLModLoader] Registered ICoremodTransformer: " + className);
			}
		} catch (Exception e) {
			System.err.println("[TLModLoader] Failed to load coremod: " + className);
			e.printStackTrace();
		}
	}

	public static byte[] applyPreMixinTransforms(String name, byte[] classBytes) {
		if (classBytes == null) {
			return null;
		}

		// Step 1: Apply Access Wideners if this class is a target
		if (TARGET_CLASSES.contains(name)) {
			try {
				ClassReader reader = new ClassReader(classBytes);
				ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
				AccessWidenerClassVisitor visitor = new AccessWidenerClassVisitor(writer, name);
				reader.accept(visitor, 0);
				classBytes = writer.toByteArray();
			} catch (Exception e) {
				System.err.println("[TLModLoader] Failed to apply access widener to: " + name);
				e.printStackTrace();
			}
		}

		// Step 2: Run all registered coremods
		for (ICoremodTransformer transformer : COREMODS) {
			classBytes = transformer.transform(name, classBytes);
			if (classBytes == null) {
				throw new RuntimeException(
						"[TLModLoader] Coremod " + transformer.getClass().getName() + " returned null for " + name);
			}
		}

		return classBytes;
	}

	public static void init() {
		System.setProperty("mixin.bootstrapService", TLMixinServiceBootstrap.class.getName());
		System.setProperty("mixin.service", TLMixinService.class.getName());

		// ADD THIS LINE:
		System.setProperty("mixin.globalPropertyService", TLMixinGlobalPropertyService.class.getName());

		MixinBootstrap.init();
		MixinExtrasBootstrap.init();

		gotoMixinDefaultPhaseReflective();
	}

	private static void gotoMixinDefaultPhaseReflective() {
		try {
			Method m = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
			m.setAccessible(true);
			m.invoke(null, MixinEnvironment.Phase.DEFAULT);
		} catch (Exception e) {
			throw new RuntimeException("[TLModLoader] Failed to switch Mixin phase", e);
		}
	}

	/**
	 * Our custom visitor that intercepts the parsing and stores it in our own
	 * public types.
	 */
	private static class TLAcessWidenerVisitor implements AccessWidenerVisitor {
		@Override
		public void visitClass(String name, AccessWidenerReader.AccessType access, boolean transitive) {
			CLASS_RULES.add(new ClassRule(name, access));
			addTarget(name);
		}

		@Override
		public void visitMethod(String owner, String name, String descriptor, AccessWidenerReader.AccessType access,
				boolean transitive) {
			METHOD_RULES.add(new MemberRule(owner, name, descriptor, access));
			addTarget(owner);
		}

		@Override
		public void visitField(String owner, String name, String descriptor, AccessWidenerReader.AccessType access,
				boolean transitive) {
			FIELD_RULES.add(new MemberRule(owner, name, descriptor, access));
			addTarget(owner);
		}

		private void addTarget(String clazz) {
			TARGET_CLASSES.add(clazz);
			// Transform all parent classes of inner classes (Standard Fabric behavior)
			while (clazz.contains("$")) {
				clazz = clazz.substring(0, clazz.lastIndexOf("$"));
				TARGET_CLASSES.add(clazz);
			}
		}
	}

	/**
	 * ASM ClassVisitor that applies the intercepted rules.
	 */
	private static class AccessWidenerClassVisitor extends ClassVisitor {
		private final String internalClassName;

		public AccessWidenerClassVisitor(ClassVisitor cv, String internalClassName) {
			super(Opcodes.ASM9, cv);
			this.internalClassName = internalClassName;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName,
				String[] interfaces) {
			for (ClassRule rule : CLASS_RULES) {
				if (rule.name().equals(name)) {
					access = applyAccess(rule.access(), access);
				}
			}
			super.visit(version, access, name, signature, superName, interfaces);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			for (MemberRule rule : FIELD_RULES) {
				if (rule.owner().equals(internalClassName) && rule.name().equals(name)
						&& rule.descriptor().equals(descriptor)) {
					access = applyAccess(rule.access(), access);
				}
			}
			return super.visitField(access, name, descriptor, signature, value);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				String[] exceptions) {
			for (MemberRule rule : METHOD_RULES) {
				if (rule.owner().equals(internalClassName) && rule.name().equals(name)
						&& rule.descriptor().equals(descriptor)) {
					access = applyAccess(rule.access(), access);
				}
			}
			return super.visitMethod(access, name, descriptor, signature, exceptions);
		}

		private int applyAccess(AccessWidenerReader.AccessType type, int access) {
			switch (type) {
			case ACCESSIBLE:
				return makePublic(access);
			case EXTENDABLE:
				return makePublic(removeFinal(access));
			case MUTABLE:
				return removeFinal(access);
			default:
				return access;
			}
		}

		private static int makePublic(int i) {
			return (i & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
		}

		private static int removeFinal(int i) {
			return i & ~Opcodes.ACC_FINAL;
		}
	}
}