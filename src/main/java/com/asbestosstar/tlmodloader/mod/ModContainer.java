package com.asbestosstar.tlmodloader.mod;

import com.google.gson.JsonArray;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class ModContainer {
	private final String id;
	private final String version;
	private final List<String> entrypoints = new ArrayList<>();
	private final List<String> mixins = new ArrayList<>();
	private final List<String> accessWideners = new ArrayList<>();
	private final List<String> coremods = new ArrayList<>();
	private final List<URL> classpathUrls = new ArrayList<>();

	// Added missing field
	private JsonArray rawDependencies;

	public ModContainer(String id, String version) {
		this.id = id;
		this.version = version;
	}

	// Getters and Adders
	public String getId() {
		return id;
	}

	public String getVersion() {
		return version;
	}

	public List<String> getEntrypoints() {
		return entrypoints;
	}

	public List<String> getMixins() {
		return mixins;
	}

	public List<String> getAccessWideners() {
		return accessWideners;
	}

	public List<String> getCoremods() {
		return coremods;
	}

	public void addClasspathUrl(URL url) {
		classpathUrls.add(url);
	}

	public List<URL> getAllClasspathUrls() {
		return classpathUrls;
	}

	// Added missing getter and setter
	public JsonArray getRawDependencies() {
		return rawDependencies;
	}

	public void setRawDependencies(JsonArray rawDependencies) {
		this.rawDependencies = rawDependencies;
	}
}