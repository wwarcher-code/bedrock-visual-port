package dev.bedrockvisuals.devtools;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * /bv: the testing helpers. /bv capture runs a test capture (as F9 does), /bv scene builds the test scene,
 * /bv status copies the diagnostics report alone to the clipboard, and /bv lists the keys and commands.
 */
public final class DevCommands {
	private static final String HELP = """
			Bedrock Visuals testing:
			  F7  cycle debug views (albedo, normal, material, light, shadows)
			  F8  deferred lighting on/off (vanilla comparison)
			  F9  test capture: screenshots at four times of day, contact sheet, report on the clipboard (F9 again cancels)
			  /bv scene   build the standard test scene in front of you
			  /bv capture same as F9
			  /bv status  copy the diagnostics report to the clipboard""";

	private DevCommands() {
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
						}))
						.then(ClientCommands.literal("scene").executes(c -> {
							TestScene.build();
							c.getSource().sendFeedback(Component.literal("[Bedrock Visuals] Test scene built in front of you."));
							return 1;
						}))
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
