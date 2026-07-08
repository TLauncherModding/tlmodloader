package com.asbestosstar.tlmodloader;

import org.spongepowered.asm.service.IPropertyKey;

public class MixinStringPropertyKey implements IPropertyKey {
	final String key;

	public MixinStringPropertyKey(String key) {
		this.key = key;
	}
}