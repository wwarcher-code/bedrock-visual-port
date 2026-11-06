package dev.bedrockvisuals.devtools;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * /bv: the testing helpers: test captures, the test scene and saved camera spots (TestSpots), the status report,
 * and /bv alone lists them.
 */
public final class DevCommands {
	private static final String HELP = """
			Bedrock Visuals testing:
			  F7  cycle debug views (albedo, normal, material, light, shadows)
			  F8  deferred lighting on/off (vanilla comparison)
			  F9  test capture: the test scene (if saved) and every saved spot; contact sheet, report on the clipboard (F9 again cancels)
			  /bv scene           go to this world's test scene (builds it here the first time)
			  /bv scene build     build the test scene here (again), and remember it
			  /bv spot <name>     remember where you stand and look, e.g. /bv spot ocean; F9 shoots it too
			  /bv spot <name> remove   forget it
			  /bv spots           list this world's spots
			  /bv go <name>       go to a spot
			  /bv capture [name]  same as F9, or just one spot ("scene" for the test scene)
			  /bv status          copy the diagnostics report to the clipboard""";

	private DevCommands() {
	}

	/** Builds the test scene where the player stands and remembers its camera as this world's scene spot. */
	private static void buildScene(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		TestScene.build();
		// The scene's last command stands the player in the middle of their block, facing it.
		TestSpots.save(TestSpots.SCENE, new TestSpots.Spot(player.level().dimension().identifier().toString(),
				Math.floor(player.getX()) + 0.5, Math.floor(player.getY()), Math.floor(player.getZ()) + 0.5, 180f,
				TestScene.PITCH));
		feedback(source, "Test scene built in front of you and remembered: /bv scene brings you back, F9 shoots it.");
	}

	private static void command(String command) {
		var player = Minecraft.getInstance().player;
		if (player != null) {
			player.connection.sendCommand(command);
		}
	}

	private static void feedback(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
			String message) {
		source.sendFeedback(Component.literal("[Bedrock Visuals] " + message));
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
				ClientCommands.literal("bv")
						.executes(c -> {
							c.getSource().sendFeedback(Component.literal(HELP));
							return 1;
						})
						.then(ClientCommands.literal("capture").executes(c -> {
							TestCapture.start();
							return 1;
						}).then(ClientCommands.argument("name", StringArgumentType.word()).executes(c -> {
							String name = StringArgumentType.getString(c, "name");
							if (!name.equals(TestSpots.SCENE) && TestSpots.get(name).isEmpty()) {
								feedback(c.getSource(), "No spot called " + name + " in this world (/bv spots lists them).");
								return 0;
							}
							TestCapture.start(name);
							return 1;
						})))
						.then(ClientCommands.literal("scene").executes(c -> {
							var scene = TestSpots.get(TestSpots.SCENE);
							if (scene.isPresent()) {
								command(scene.get().teleport());
								feedback(c.getSource(), "At the test scene (/bv scene build rebuilds it where you stand).");
							} else {
								buildScene(c.getSource());
							}
							return 1;
						}).then(ClientCommands.literal("build").executes(c -> {
							buildScene(c.getSource());
							return 1;
						})))
						.then(ClientCommands.literal("spot").then(ClientCommands.argument("name", StringArgumentType.word())
								.executes(c -> {
									String name = StringArgumentType.getString(c, "name");
									if (name.equals(TestSpots.SCENE)) {
										feedback(c.getSource(), "\"scene\" is the test scene's spot; use /bv scene build.");
										return 0;
									}
									boolean saved = TestSpots.here().map(spot -> TestSpots.save(name, spot)).orElse(false);
									feedback(c.getSource(), saved ? "Saved this view as " + name + "; F9 will shoot it."
											: "Couldn't save the spot (not in a world?).");
									return saved ? 1 : 0;
								})
								.then(ClientCommands.literal("remove").executes(c -> {
									String name = StringArgumentType.getString(c, "name");
									boolean removed = TestSpots.remove(name);
									feedback(c.getSource(), removed ? "Forgot " + name + "." : "No spot called " + name + ".");
									return removed ? 1 : 0;
								}))))
						.then(ClientCommands.literal("spots").executes(c -> {
							var spots = TestSpots.all();
							feedback(c.getSource(), spots.isEmpty() ? "No spots saved in this world."
									: "Spots in this world: " + String.join(", ", spots.keySet()));
							return 1;
						}))
						.then(ClientCommands.literal("go").then(ClientCommands.argument("name", StringArgumentType.word())
								.executes(c -> {
									String name = StringArgumentType.getString(c, "name");
									var spot = TestSpots.get(name);
									if (spot.isEmpty()) {
										feedback(c.getSource(), "No spot called " + name + " in this world.");
										return 0;
									}
									command(spot.get().teleport());
									return 1;
								})))
						.then(ClientCommands.literal("status").executes(c -> {
							DiagnosticsReport report = new DiagnosticsReport("Bedrock Visuals status");
							report.lightingAt("now");
							report.logExcerpt(Minecraft.getInstance().gameDirectory.toPath());
							Minecraft.getInstance().keyboardHandler.setClipboard(report.text());
							c.getSource().sendFeedback(Component.literal("[Bedrock Visuals] Status report copied to the clipboard."));
							return 1;
						}))));
	}
}
