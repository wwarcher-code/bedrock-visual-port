package dev.bedrockvisuals.compat;

import dev.bedrockvisuals.bedrock.QualitySettings;
import dev.bedrockvisuals.config.Settings;
import dev.bedrockvisuals.raytrace.RayQuery;
import dev.bedrockvisuals.raytrace.RtEmissive;
import dev.bedrockvisuals.raytrace.RtShadows;
import dev.bedrockvisuals.render.OfflineRender;
import dev.bedrockvisuals.render.Quality;
import dev.bedrockvisuals.render.TemporalAA;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Bedrock Visuals' page in Sodium's video settings (Sodium's config API, the {@code sodium:config_api_user}
 * entrypoint): Bedrock's quality preset, anti-aliasing, the ray-traced lighting and the export settings. Every
 * change applies at once and is saved (Settings; the preset in Quality's own file).
 */
public final class SodiumOptions implements ConfigEntryPoint {
	private static final StorageEventHandler SAVE = Settings::save;

	private static final Identifier PRESET = id("quality_preset");
	private static final Identifier TAA = id("temporal_aa");
	private static final Identifier SHADOWS = id("rt_shadows");
	private static final Identifier SUN_SIZE = id("rt_sun_size");
	private static final Identifier LIGHT = id("rt_light");
	private static final Identifier LIGHT_GAIN = id("rt_light_gain");
	private static final Identifier LIGHT_FLOOR = id("rt_light_floor");
	private static final Identifier ENTITY_LIGHTS = id("rt_entity_lights");
	private static final Identifier BOUNCE = id("rt_bounce");
	private static final Identifier BOUNCE_GAIN = id("rt_bounce_gain");
	private static final Identifier SKY_BOUNCE = id("rt_sky_bounce");
	private static final Identifier SAMPLES = id("rt_samples");
	private static final Identifier EXPORT_SUBFRAMES = id("export_subframes");
	private static final Identifier EXPORT_SAMPLES = id("export_rt_samples");

	/** The shadow choices shown (the comparison view stays a /bv command). */
	public enum Shadows {
		MAPS, TRACED
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("bedrockvisuals", path);
	}

	private static Component text(String text) {
		return Component.literal(text);
	}

	private static boolean rayTracing(ConfigState state) {
		return RayQuery.available();
	}

	private static boolean lightOn(ConfigState state) {
		return RayQuery.available() && state.readEnumOption(LIGHT, RtEmissive.Mode.class) != RtEmissive.Mode.OFF;
	}

	private static String rayTracingNote() {
		return RayQuery.available() ? "" : "\n\nNeeds a GPU and driver with Vulkan ray queries; this one has none.";
	}

	/** Sodium's video settings open at one of this mod's pages (for test scripts), or at their first. */
	public static net.minecraft.client.gui.screens.Screen screen(int index) {
		var config = net.caffeinemc.mods.sodium.client.config.ConfigManager.CONFIG;
		if (config != null) {
			for (var mod : config.getModOptions()) {
				if (mod.configId().equals("bedrockvisuals") && index < mod.pages().size()
						&& mod.pages().get(index) instanceof net.caffeinemc.mods.sodium.client.config.structure.OptionPage page) {
					return net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen.createScreen(null, page);
				}
			}
		}
		return net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen.createScreen(null);
	}

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		builder.registerOwnModOptions().setName("Bedrock Visuals")
				.addPage(builder.createOptionPage().setName(text("Quality")).addOptionGroup(quality(builder)))
				.addPage(builder.createOptionPage().setName(text("Ray Tracing"))
						.addOptionGroup(rayTracedShadows(builder))
						.addOptionGroup(rayTracedLight(builder)))
				.addPage(builder.createOptionPage().setName(text("Exports")).addOptionGroup(exports(builder)));
	}

	private static OptionGroupBuilder quality(ConfigBuilder builder) {
		Quality.ensureLoaded(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
		List<QualitySettings.Preset> presets = Quality.presets();
		int last = Math.max(presets.size() - 1, 0);
		int fallback = Math.max(presets.indexOf(Quality.preset().orElse(null)), 0);
		return builder.createOptionGroup().setName(text("Quality"))
				.addOption(builder.createIntegerOption(PRESET)
						.setName(text("Bedrock preset"))
						.setTooltip(text("One of Bedrock's own graphics presets, read from the Bedrock install: it sets "
								+ "the shadow cascades, reflections, volumetric fog, clouds and whether temporal "
								+ "anti-aliasing runs. The reference captures used tier 3 Performance."))
						.setImpact(OptionImpact.HIGH)
						.setEnabled(!presets.isEmpty())
						.setRange(0, last, 1)
						.setDefaultValue(fallback)
						.setValueFormatter(index -> text(index < presets.size() ? presets.get(index).label() : "Not found"))
						.setBinding(index -> {
							if (index < presets.size()) {
								QualitySettings.Preset preset = presets.get(index);
								Quality.select(preset.tier(), preset.name(), true);
							}
						}, () -> Math.max(presets.indexOf(Quality.preset().orElse(null)), 0))
						.setStorageHandler(SAVE))
				.addOption(builder.createBooleanOption(TAA)
						.setName(text("Temporal anti-aliasing"))
						.setTooltip(text("Bedrock's temporal anti-aliasing: smooths edges and the ray-traced light's last "
								+ "flicker, softening the picture a little. The preset picks it; this overrides it. F6 "
								+ "switches it for the session."))
						.setImpact(OptionImpact.LOW)
						.setDefaultValue(false)
						.setBinding(TemporalAA::setEnabled, TemporalAA::isEnabled)
						.setStorageHandler(SAVE));
	}

	private static OptionGroupBuilder rayTracedShadows(ConfigBuilder builder) {
		return builder.createOptionGroup().setName(text("Ray-traced shadows"))
				.addOption(builder.createEnumOption(SHADOWS, Shadows.class)
						.setName(text("Sun and moon shadows"))
						.setTooltip(text("Shadow maps: Bedrock's shadows. Ray traced: every shadow traced against the "
								+ "world's blocks and entities, sharp up close and softening with distance, with no "
								+ "cascades or shadow acne." + rayTracingNote()))
						.setImpact(OptionImpact.MEDIUM)
						.setEnabledProvider(SodiumOptions::rayTracing)
						.setDefaultValue(Shadows.MAPS)
						.setElementNameProvider(value -> text(value == Shadows.TRACED ? "Ray traced" : "Shadow maps"))
						.setBinding(value -> RtShadows.setMode(value == Shadows.TRACED ? RtShadows.Mode.ON
										: RtShadows.Mode.OFF),
								() -> RtShadows.mode() == RtShadows.Mode.OFF ? Shadows.MAPS : Shadows.TRACED)
						.setStorageHandler(SAVE))
				.addOption(builder.createIntegerOption(SUN_SIZE)
						.setName(text("Sun size"))
						.setTooltip(text("The sun's and moon's angular radius: how fast their shadows soften with "
								+ "distance. The real sun's is 0.27°; 0 gives hard shadows."))
						.setEnabledProvider(state -> rayTracing(state)
								&& state.readEnumOption(SHADOWS, Shadows.class) == Shadows.TRACED, SHADOWS)
						.setRange(0, 100, 1)
						.setDefaultValue(27)
						.setValueFormatter(value -> text(String.format(Locale.ROOT, "%.2f°", value / 100f)))
						.setBinding(value -> RtShadows.setSunRadius(value / 100f),
								() -> Math.round(RtShadows.sunRadius() * 100))
						.setStorageHandler(SAVE));
	}

	private static OptionGroupBuilder rayTracedLight(ConfigBuilder builder) {
		return builder.createOptionGroup().setName(text("Ray-traced light"))
				.addOption(builder.createEnumOption(LIGHT, RtEmissive.Mode.class)
						.setName(text("Block light"))
						.setTooltip(text("Off: Bedrock's block light. Ray traced: every glowing texel is a light, with "
								+ "shadows that soften with distance, in place of Bedrock's block light. With "
								+ "minimum: the same, but never darker than a share of Bedrock's block light."
								+ rayTracingNote()))
						.setImpact(OptionImpact.HIGH)
						.setEnabledProvider(SodiumOptions::rayTracing)
						.setDefaultValue(RtEmissive.Mode.OFF)
						.setElementNameProvider(value -> text(switch (value) {
							case OFF -> "Off";
							case REPLACE -> "Ray traced";
							case FLOOR -> "Ray traced, with minimum";
						}))
						.setBinding(RtEmissive::setMode, RtEmissive::mode)
						.setStorageHandler(SAVE))
				.addOption(builder.createIntegerOption(LIGHT_GAIN)
						.setName(text("Light strength"))
						.setTooltip(text("How bright the glowing texels' light is. 10 puts a torch's light near "
								+ "Bedrock's block light beside it."))
						.setEnabledProvider(SodiumOptions::lightOn, LIGHT)
						.setRange(1, 40, 1)
						.setDefaultValue(10)
						.setValueFormatter(value -> text(Integer.toString(value)))
						.setBinding(value -> RtEmissive.setGain(value), () -> Math.round(RtEmissive.gain()))
						.setStorageHandler(SAVE))
				.addOption(builder.createIntegerOption(LIGHT_FLOOR)
						.setName(text("Minimum light"))
						.setTooltip(text("With minimum: the share of Bedrock's block light kept wherever the traced "
								+ "light is darker."))
						.setEnabledProvider(state -> lightOn(state)
								&& state.readEnumOption(LIGHT, RtEmissive.Mode.class) == RtEmissive.Mode.FLOOR, LIGHT)
						.setRange(0, 100, 5)
						.setDefaultValue(35)
						.setValueFormatter(value -> text(value + "%"))
						.setBinding(value -> RtEmissive.setFloor(value / 100f), () -> Math.round(RtEmissive.floor() * 100))
						.setStorageHandler(SAVE))
				.addOption(builder.createBooleanOption(ENTITY_LIGHTS)
						.setName(text("Light from entities"))
						.setTooltip(text("Torches and other glowing blocks held or dropped, burning mobs, blazes, "
								+ "magma cubes, glow squids, fireballs and the like light the world as they move, "
								+ "with ray-traced shadows."))
						.setImpact(OptionImpact.LOW)
						.setEnabledProvider(SodiumOptions::lightOn, LIGHT)
						.setDefaultValue(true)
						.setBinding(RtEmissive::setEntityLights, RtEmissive::entityLights)
						.setStorageHandler(SAVE))
				.addOption(builder.createBooleanOption(BOUNCE)
						.setName(text("Bounce light"))
						.setTooltip(text("Light from the sun, the moon and glowing blocks bounced once more off the "
								+ "surfaces it reaches, tinted by them." + rayTracingNote()))
						.setImpact(OptionImpact.HIGH)
						.setEnabledProvider(SodiumOptions::rayTracing)
						.setDefaultValue(false)
						.setBinding(RtEmissive::setBounce, RtEmissive::bounce)
						.setStorageHandler(SAVE))
				.addOption(builder.createIntegerOption(BOUNCE_GAIN)
						.setName(text("Bounce strength"))
						.setTooltip(text("How much bounced light there is: 100% is physically right."))
						.setEnabledProvider(state -> rayTracing(state) && state.readBooleanOption(BOUNCE), BOUNCE)
						.setRange(0, 300, 10)
						.setDefaultValue(100)
						.setValueFormatter(value -> text(value + "%"))
						.setBinding(value -> RtEmissive.setBounceGain(value / 100f),
								() -> Math.round(RtEmissive.bounceGain() * 100))
						.setStorageHandler(SAVE))
				.addOption(builder.createBooleanOption(SKY_BOUNCE)
						.setName(text("Sky light bounced"))
						.setTooltip(text("The sky's light comes in through openings and bounces off what it reaches, "
								+ "in place of Bedrock's even sky light: rooms with windows are lit from them, darker "
								+ "away from them."))
						.setImpact(OptionImpact.LOW)
						.setEnabledProvider(state -> rayTracing(state) && state.readBooleanOption(BOUNCE), BOUNCE)
						.setDefaultValue(false)
						.setBinding(RtEmissive::setSkyBounce, RtEmissive::skyBounce)
						.setStorageHandler(SAVE))
				.addOption(builder.createIntegerOption(SAMPLES)
						.setName(text("Rays per pixel"))
						.setTooltip(text("Light samples each pixel takes a frame. The light is averaged per texel over "
								+ "frames either way; more settles it faster after a change, at a cost."))
						.setImpact(OptionImpact.HIGH)
						.setEnabledProvider(SodiumOptions::rayTracing)
						.setRange(1, 8, 1)
						.setDefaultValue(1)
						.setValueFormatter(value -> text(Integer.toString(value)))
						.setBinding(RtEmissive::setSamples, RtEmissive::samples)
						.setStorageHandler(SAVE));
	}

	private static OptionGroupBuilder exports(ConfigBuilder builder) {
		return builder.createOptionGroup().setName(text("Exports (Flashback)"))
				.addOption(builder.createIntegerOption(EXPORT_SUBFRAMES)
						.setName(text("Sub-frames"))
						.setTooltip(text("While Flashback exports, each frame is drawn this many times and averaged: "
								+ "motion blur, smooth shadows and clean ray-traced light."))
						.setRange(1, OfflineRender.MAX_SAMPLES, 1)
						.setDefaultValue(OfflineRender.samples())
						.setValueFormatter(value -> text(Integer.toString(value)))
						.setBinding(OfflineRender::setSamples, OfflineRender::samples)
						.setStorageHandler(SAVE))
				.addOption(builder.createIntegerOption(EXPORT_SAMPLES)
						.setName(text("Rays per sub-frame"))
						.setTooltip(text("Light samples each pixel takes in each sub-frame of an export."))
						.setEnabledProvider(SodiumOptions::rayTracing)
						.setRange(1, 16, 1)
						.setDefaultValue(16)
						.setValueFormatter(value -> text(Integer.toString(value)))
						.setBinding(RtEmissive::setOfflineSamples, RtEmissive::offlineSamples)
						.setStorageHandler(SAVE));
	}
}
