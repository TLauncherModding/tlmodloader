package com.asbestosstar.tlmodloader;

import java.util.List;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.IExtensionRegistry;

/**
 * Wraps the real MixinTransformer to apply our pre-transform pipeline.
 */
public class TLMixinTransformerWrapper implements IMixinTransformer {

	private final IMixinTransformer delegate;

	public TLMixinTransformerWrapper(IMixinTransformer delegate) {
		this.delegate = delegate;
	}

	@Override
	public void audit(MixinEnvironment environment) {
		delegate.audit(environment);
	}

	@Override
	public List<String> reload(String mixinClass, ClassNode classNode) {
		return delegate.reload(mixinClass, classNode);
	}

	@Override
	public boolean computeFramesForClass(MixinEnvironment environment, String name, ClassNode classNode) {
		return delegate.computeFramesForClass(environment, name, classNode);
	}

	@Override
	public byte[] transformClassBytes(String name, String transformedName, byte[] basicClass) {
		// Apply our coremod/AW transforms first
		String className = transformedName != null ? transformedName : name;
		byte[] transformed = TLMixinBootstrap.applyPreMixinTransforms(className, basicClass);

		// Then let Mixin do its thing
		return delegate.transformClassBytes(name, transformedName, transformed);
	}

	@Override
	public byte[] transformClass(MixinEnvironment environment, String name, byte[] classBytes) {
		byte[] transformed = TLMixinBootstrap.applyPreMixinTransforms(name, classBytes);
		return delegate.transformClass(environment, name, transformed);
	}

	@Override
	public boolean transformClass(MixinEnvironment environment, String name, ClassNode classNode) {
		// ClassNode is already parsed - transforms should have been applied during
		// getClassNode
		return delegate.transformClass(environment, name, classNode);
	}

	@Override
	public byte[] generateClass(MixinEnvironment environment, String name) {
		return delegate.generateClass(environment, name);
	}

	@Override
	public boolean generateClass(MixinEnvironment environment, String name, ClassNode classNode) {
		return delegate.generateClass(environment, name, classNode);
	}

	@Override
	public IExtensionRegistry getExtensions() {
		return delegate.getExtensions();
	}
}