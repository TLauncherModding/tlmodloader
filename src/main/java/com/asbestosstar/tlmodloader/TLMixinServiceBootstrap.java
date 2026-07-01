package com.asbestosstar.tlmodloader;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

public class TLMixinServiceBootstrap implements IMixinServiceBootstrap {

	@Override
	public void bootstrap() {
		// Pre-initialization hook if needed
		System.out.println("[TLLoader] Mixin service bootstrapping...");
	}

	@Override
	public String getServiceClassName() {
		return TLMixinService.class.getName();
	}

	@Override
	public String getName() {
		// TODO Auto-generated method stub
		return "tlmodloader";
	}
}