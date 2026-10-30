package dev.bedrockvisuals.bedrock;

/** The keyed settings file kinds a client biome points at, with their folder and vanilla default. */
enum SettingsKind {
	LIGHTING("lighting", LightingSettings.KEY, "minecraft:default_lighting"),
	ATMOSPHERE("atmospherics", AtmosphereSettings.KEY, "minecraft:default_atmospherics"),
	COLOR_GRADING("color_grading", ColorGradingSettings.KEY, "minecraft:default_color_grading"),
	WATER("water", WaterSettings.KEY, "minecraft:default_water"),
	FOG("fogs", FogSettings.KEY, "minecraft:fog_default");

	final String folder;
	final String rootKey;
	final String defaultId;

	SettingsKind(String folder, String rootKey, String defaultId) {
		this.folder = folder;
		this.rootKey = rootKey;
		this.defaultId = defaultId;
	}
}
