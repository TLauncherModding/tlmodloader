package com.asbestosstar.tlmodloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Collections;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleURI;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

public class TLMixinService extends MixinServiceAbstract
		implements IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker {

	private static IMixinTransformer transformer;
	private final ClassLoader targetClassLoader;

	public TLMixinService() {
		this.targetClassLoader = TLLaunchWrapper.class.getClassLoader();
	}

	@Override
	public void offer(IMixinInternal internal) {
		super.offer(internal); // Let base class register it in internals map
		if (internal instanceof IMixinTransformerFactory) {
			IMixinTransformerFactory factory = (IMixinTransformerFactory) internal;
			IMixinTransformer rawTransformer = factory.createTransformer();
			// Wrap the transformer to inject our pre-transforms
			transformer = new TLMixinTransformerWrapper(rawTransformer);
		}
	}

	public static IMixinTransformer getTransformer() {
		return transformer;
	}

	// =============================================
	// IClassProvider implementation
	// =============================================

	@Override
	@Deprecated
	public URL[] getClassPath() {
		if (targetClassLoader instanceof URLClassLoader) {
			return ((URLClassLoader) targetClassLoader).getURLs();
		}
		return new URL[0];
	}

	@Override
	public Class<?> findClass(String name) throws ClassNotFoundException {
		return targetClassLoader.loadClass(name);
	}

	@Override
	public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
		return Class.forName(name, initialize, targetClassLoader);
	}

	@Override
	public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
		return Class.forName(name, initialize, TLMixinService.class.getClassLoader());
	}

	// =============================================
	// IClassBytecodeProvider implementation
	// =============================================

	@Override
	public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
		return getClassNode(name, true);
	}

	@Override
	public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
		return getClassNode(name, runTransformers, 0);
	}

	@Override
	public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
			throws ClassNotFoundException, IOException {
		byte[] bytes = getRawClassBytes(name);
		if (bytes == null) {
			throw new ClassNotFoundException("Could not find class: " + name);
		}

		// Apply our pre-mixin transforms
		String transformedName = name.replace('/', '.');
		bytes = TLMixinBootstrap.applyPreMixinTransforms(transformedName, bytes);

		ClassReader reader = new ClassReader(bytes);
		ClassNode node = new ClassNode();
		reader.accept(node, readerFlags);
		return node;
	}

	private byte[] getRawClassBytes(String name) throws IOException {
		String resourcePath = name.replace('.', '/') + ".class";
		InputStream is = targetClassLoader.getResourceAsStream(resourcePath);
		if (is == null) {
			return null;
		}
		try (is) {
			return is.readAllBytes();
		}
	}

	// =============================================
	// ITransformerProvider implementation
	// =============================================

	@Override
	public Collection<ITransformer> getTransformers() {
		return Collections.emptyList();
	}

	@Override
	public Collection<ITransformer> getDelegatedTransformers() {
		return Collections.emptyList();
	}

	@Override
	public void addTransformerExclusion(String name) {
		// No-op - we don't have a separate transformer exclusion list
	}

	// =============================================
	// IClassTracker implementation
	// =============================================

	@Override
	public void registerInvalidClass(String className) {
		// No-op
	}

	@Override
	public boolean isClassLoaded(String className) {
		try {
			Class.forName(className, false, targetClassLoader);
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	@Override
	public String getClassRestrictions(String className) {
		return "";
	}

	// =============================================
	// IMixinService overrides (from MixinServiceAbstract)
	// =============================================

	@Override
	public String getName() {
		return "TLLoader Service";
	}

	@Override
	public boolean isValid() {
		return true;
	}

	@Override
	public IClassProvider getClassProvider() {
		return this;
	}

	@Override
	public IClassBytecodeProvider getBytecodeProvider() {
		return this;
	}

	@Override
	public ITransformerProvider getTransformerProvider() {
		return this;
	}

	@Override
	public IClassTracker getClassTracker() {
		return this;
	}

	@Override
	public IMixinAuditTrail getAuditTrail() {
		return null;
	}

	@Override
	public Collection<String> getPlatformAgents() {
		return Collections.singletonList("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault");
	}

	@Override
	public IContainerHandle getPrimaryContainer() {
		URL codeSource = TLMixinService.class.getProtectionDomain().getCodeSource().getLocation();
		try {
			return new ContainerHandleURI(codeSource.toURI());
		} catch (Exception e) {
			throw new RuntimeException("Failed to create primary container handle", e);
		}
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		return targetClassLoader.getResourceAsStream(name);
	}

//    @Override
//    public String getSideName() {
//        String side = System.getProperty("tl.side", "CLIENT");
//        return side.toUpperCase();
//    }

	@Override
	public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
		return MixinEnvironment.CompatibilityLevel.JAVA_8;
	}

	@Override
	public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
		return MixinEnvironment.CompatibilityLevel.JAVA_22;
	}
}