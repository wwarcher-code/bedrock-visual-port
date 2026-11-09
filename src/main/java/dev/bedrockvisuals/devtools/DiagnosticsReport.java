package dev.bedrockvisuals.devtools;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import dev.bedrockvisuals.bedrock.BedrockInputs;
import dev.bedrockvisuals.bedrock.ColorGradingSettings;
import dev.bedrockvisuals.bedrock.TimeOfDay;
import dev.bedrockvisuals.render.AutoExposure;
import dev.bedrockvisuals.render.ColorGrading;
import dev.bedrockvisuals.render.BloomPass;
import dev.bedrockvisuals.render.LocalExposure;
import dev.bedrockvisuals.render.Weather;
import dev.bedrockvisuals.render.CloudPass;
import dev.bedrockvisuals.render.Underwater;
import dev.bedrockvisuals.render.CloudShadows;
import dev.bedrockvisuals.render.DeferredRenderer;
import dev.bedrockvisuals.render.SkyAtmosphere;
import dev.bedrockvisuals.render.GBufferPass;
import dev.bedrockvisuals.render.LightingState;
import dev.bedrockvisuals.render.MaterialAtlas;
import dev.bedrockvisuals.render.ShadowPass;
import dev.bedrockvisuals.render.WaterPass;
import dev.bedrockvisuals.render.WaterColumns;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.joml.Vector3f;

/**
 * The plain-text report that goes with a test capture: what was built, what the GPU and each pass are doing,
 * the lighting values at each captured time, and the mod's log lines. Replaces the log searches and
 * questions a test round used to need.
 */
final class DiagnosticsReport {
	private static final int LOG_LINES = 60;

	private final StringBuilder text = new StringBuilder();

	DiagnosticsReport(String title) {
		line(title);
		line("");
		section("Build");
		line("commit " + buildCommit());
		FabricLoader.getInstance().getModContainer("bedrockvisuals")
				.ifPresent(mod -> line("mod version " + mod.getMetadata().getVersion().getFriendlyString()));
		for (String id : List.of("minecraft", "sodium", "distanthorizons", "flashback")) {
			FabricLoader.getInstance().getModContainer(id)
					.ifPresent(mod -> line(id + " " + mod.getMetadata().getVersion().getFriendlyString()));
		}
		section("System");
		try {
			DeviceInfo device = RenderSystem.getDevice().getDeviceInfo();
			line("GPU " + device.name() + " (" + device.vendorName() + "), backend " + device.backendName()
					+ ", driver " + device.driverInfo());
		} catch (RuntimeException e) {
			line("GPU unknown: " + e);
		}
		Minecraft minecraft = Minecraft.getInstance();
		line("window " + minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight()
				+ ", render distance " + minecraft.options.getEffectiveRenderDistance() + " chunks, field of view "
				+ minecraft.options.fov().get());
		section("Renderer");
		line("deferred lighting " + (DeferredRenderer.isEnabled() ? "on" : "off")
				+ (DeferredRenderer.lightingFailed() ? " (FAILED, see log)" : "") + "; since the last report: "
				+ DeferredRenderer.frameSummary());
		line("G-buffer " + (GBufferPass.isDisabled() ? "FAILED, see log" : "ok"));
		line("shadows " + (ShadowPass.isDisabled() ? "FAILED, see log" : ShadowPass.isValid() ? "ok" : "no map this frame")
				+ "; " + ShadowPass.CASCADES + " cascades of " + ShadowPass.RESOLUTION + "x" + ShadowPass.RESOLUTION
				+ " out to " + (int) ShadowPass.RANGE + " blocks");
		line("materials " + MaterialAtlas.status());
		line("entities " + dev.bedrockvisuals.render.EntityPass.status() + "; shadows "
				+ dev.bedrockvisuals.render.EntityShadows.status());
		line("water " + WaterPass.status() + "; " + Underwater.status() + "; columns " + WaterColumns.status());
		line("bloom " + BloomPass.status());
		line("local exposure " + LocalExposure.status());
		line("weather " + Weather.status());
		dev.bedrockvisuals.lod.DistantTerrain.source().ifPresent(source -> line("distant terrain " + source.name()
				+ ", its water " + source.waterStatus() + ", far shadows "
				+ dev.bedrockvisuals.render.DistantHeights.status()));
		line("volumetric fog " + dev.bedrockvisuals.render.FroxelVolume.status());
		line("sun and moon " + dev.bedrockvisuals.render.SunMoon.status());
		line("sky probe " + dev.bedrockvisuals.render.SkyProbe.status());
		line("scene probe " + dev.bedrockvisuals.render.SceneProbe.status());
		line("water reflections " + dev.bedrockvisuals.render.ReflectionPass.status());
		line("light volume " + dev.bedrockvisuals.render.LightVolume.status());
		line("biome mix " + dev.bedrockvisuals.render.BiomeMix.status() + " (a new biome takes 30 s to take over)");
		line("clouds " + CloudPass.status() + "; cloud shadows " + CloudShadows.status() + "; Java's Clouds setting "
				+ minecraft.options.cloudStatus().get());
		line("far clouds " + CloudShadows.farStatus());
		line("Bedrock packs " + BedrockInputs.pack().map(pack -> pack.summary()).orElse("NOT LOADED"));
		if (minecraft.player != null && minecraft.level != null) {
			section("Player");
			line(String.format(Locale.ROOT, "at %.1f %.1f %.1f in %s, biome %s", minecraft.player.getX(),
					minecraft.player.getY(), minecraft.player.getZ(), minecraft.level.dimension().identifier(),
					minecraft.level.getBiome(minecraft.player.blockPosition()).unwrapKey()
							.map(key -> key.identifier().toString()).orElse("unknown")));
		}
	}

	/** Records the lighting the renderer is using right now, labelled with the requested time. */
	void lightingAt(String label) {
		Minecraft minecraft = Minecraft.getInstance();
		long dayTime = minecraft.level == null ? -1 : minecraft.level.getDefaultClockTime();
		LightingState light = LightingState.current();
		section("Lighting at " + label);
		line("day time " + dayTime + " ticks (Bedrock time " + format(TimeOfDay.fromJavaDayTime(dayTime)) + ")");
		line("towards sun " + vector(light.sunDirection()) + ", sun " + vector(light.sun()) + ", moon " + vector(light.moon()));
		line("sky zenith " + vector(light.sky()) + ", horizon " + vector(light.skyHorizon()) + ", ambient "
				+ vector(light.ambient()));
		SkyAtmosphere sky = light.atmosphere();
		line(sky == null ? "sky: Java's (no Bedrock atmosphere here)"
				: "sky: Bedrock's, sun strength " + format(sky.sun().w) + ", moon " + format(sky.moon().w)
						+ ", distance fog " + (sky.fog().w > 0 ? "from " + format(sky.fog().x * sky.fog().z) + " to "
								+ format((sky.fog().x + 1 / sky.fog().y) * sky.fog().z) + " blocks"
								: "off (Distant Horizons' vanilla fog is off, or no air fog)")
						+ ", air extinction " + format(sky.volumeAir().w) + ", zenith " + vector(sky.zenith()));
		line("weather " + dev.bedrockvisuals.render.Weather.status() + "; " + Underwater.status());
		line("exposure " + format(AutoExposure.exposure()) + " (metered average luminance "
				+ format(AutoExposure.averageLuminance()) + " in Bedrock's units after the tone map's clamp, "
				+ format(AutoExposure.adaptedLuminance()) + " before it; meter " + AutoExposure.meterStatus() + ")");
		line("meter " + AutoExposure.meterBreakdown());
		line("shadow map " + (ShadowPass.isValid() ? "drawn from the " + (ShadowPass.fromMoon() ? "moon" : "sun")
				: "not drawn (light too low or failed)"));
		ColorGradingSettings grading = ColorGrading.current();
		if (grading != null) {
			line("grading " + grading.identifier() + ": tone map " + grading.toneMapping() + ", contrast "
					+ grading.midtones().contrast() + ", saturation " + grading.midtones().saturation()
					+ ", temperature " + (grading.temperature().enabled() ? grading.temperature().kelvin() + " K" : "off"));
		}
	}

	void note(String note) {
		section("Note");
		line(note);
	}

	/** The mod's lines and any errors from this session's log. */
	void logExcerpt(Path gameDirectory) {
		section("Log (mod lines and errors, newest last)");
		Path log = gameDirectory.resolve("logs").resolve("latest.log");
		try {
			List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8).stream()
					.filter(l -> l.contains("(bedrockvisuals)") || l.contains("compile pipeline")
							|| l.contains("bedrockvisuals:")
							|| (l.contains("Exception") || l.contains("/ERROR]")) && !isKnownNoise(l))
					.toList();
			lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size()).forEach(this::line);
		} catch (IOException e) {
			line("could not read " + log + ": " + e.getMessage());
		}
	}

	/** Errors every offline dev client logs: account and Realms sign-in. */
	private static boolean isKnownNoise(String line) {
		return line.contains("Realms") || line.contains("realms") || line.contains("user properties")
				|| line.contains("authlib") || line.contains("SignedJWT");
	}

	String text() {
		return text.toString();
	}

	private void section(String title) {
		line("");
		line("== " + title);
	}

	private void line(String line) {
		text.append(line).append('\n');
	}

	private static String vector(Vector3f v) {
		return "(" + format(v.x) + ", " + format(v.y) + ", " + format(v.z) + ")";
	}

	private static String format(float value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	private static String buildCommit() {
		try (InputStream in = DiagnosticsReport.class.getResourceAsStream("/bedrockvisuals.build.properties")) {
			Properties properties = new Properties();
			if (in != null) {
				properties.load(in);
			}
			return properties.getProperty("commit", "unknown");
		} catch (IOException e) {
			return "unknown";
		}
	}
}
