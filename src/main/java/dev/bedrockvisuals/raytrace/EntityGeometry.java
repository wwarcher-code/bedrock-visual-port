package dev.bedrockvisuals.raytrace;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * The frame's entities (and block entities, and items in the world) as triangles, captured from Minecraft's own
 * draws while the level renders, so each model is posed once, by Minecraft (RTX Remix's lesson).
 *
 * <p>Minecraft stages each render type's vertices in a StagedVertexBuffer draw. The draws made for the entity
 * shader in Minecraft's {@code ENTITY} format, as quads, between the start of the level's render and the hand,
 * are tagged when they are made (RenderTypeGroupMixin), and their vertices copied as they arrive
 * (StagedDrawMixin). Positions in them are relative to the camera.
 *
 * <p>Kept per frame: the vertices as Minecraft wrote them ({@code ENTITY}: position 3 floats, colour 4 bytes,
 * texture 2 floats, overlay 2 shorts, light 2 shorts, normal 3 bytes and one padding, 36 bytes), and their
 * positions as two triangles a quad, (0, 1, 2) and (2, 3, 0), as for the terrain.
 */
public final class EntityGeometry {
	static final int VERTEX_STRIDE = 36;
	private static final Identifier ENTITY_SHADER = Identifier.withDefaultNamespace("core/entity");

	private static final Set<Object> TAGGED = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Per tagged draw, its texture's mean colour (linear), for the bounce (null: unknown, a middling grey). */
	private static final java.util.Map<Object, float[]> DRAW_COLORS = new IdentityHashMap<>();
	/** Per render type, its texture (reflection: RenderType.state, RenderSetup.textures); empty if none. */
	private static final java.util.Map<RenderType, java.util.Optional<Identifier>> TEXTURES = new IdentityHashMap<>();
	private static final java.util.Map<Identifier, float[]> MEANS = new java.util.HashMap<>();
	private static int meansGeneration = -1;
	private static final float[] UNKNOWN = {0.4f, 0.4f, 0.4f};
	private static boolean capturing;
	private static ByteBuffer raw = MemoryUtil.memAlloc(1 << 20);
	private static ByteBuffer positions = MemoryUtil.memAlloc(1 << 20);
	private static int quads;

	private EntityGeometry() {
	}

	/** The level starts rendering: a new frame of entities. */
	public static void beginLevel() {
		TAGGED.clear();
		DRAW_COLORS.clear();
		raw.clear();
		positions.clear();
		quads = 0;
		capturing = RtScene.enabled();
	}

	/** The level is drawn; the hand comes next and isn't part of the scene. */
	public static void endLevel() {
		capturing = false;
		TAGGED.clear();
		DRAW_COLORS.clear();
	}

	/** Minecraft made a staged draw for this render type (RenderTypeGroupMixin). */
	public static void onDrawAdded(Object draw, RenderType type) {
		if (capturing && type.format() == DefaultVertexFormat.ENTITY
				&& DefaultVertexFormat.ENTITY.getVertexSize() == VERTEX_STRIDE
				&& type.primitiveTopology() == PrimitiveTopology.QUADS
				&& ENTITY_SHADER.equals(type.pipeline().getShaders().get(ShaderType.FRAGMENT))) {
			TAGGED.add(draw);
			DRAW_COLORS.put(draw, meanColor(type));
		}
	}

	/**
	 * The mean linear colour of a render type's texture's drawn pixels (weighed by alpha), for the bounce off
	 * entities (RtEmissive's bounceRadiance: their vertex colour times it); a middling grey when it can't be read
	 * (a downloaded skin, say).
	 */
	private static float[] meanColor(RenderType type) {
		if (meansGeneration != dev.bedrockvisuals.render.MaterialAtlas.generation()) {
			meansGeneration = dev.bedrockvisuals.render.MaterialAtlas.generation();
			MEANS.clear();
		}
		java.util.Optional<Identifier> texture = TEXTURES.computeIfAbsent(type, EntityGeometry::textureOf);
		if (texture.isEmpty()) {
			return UNKNOWN;
		}
		return MEANS.computeIfAbsent(texture.get(), id -> {
			try (var stream = net.minecraft.client.Minecraft.getInstance().getResourceManager().open(id);
					var image = com.mojang.blaze3d.platform.NativeImage.read(stream)) {
				double r = 0;
				double g = 0;
				double b = 0;
				double w = 0;
				for (int y = 0; y < image.getHeight(); y++) {
					for (int x = 0; x < image.getWidth(); x++) {
						int argb = image.getPixel(x, y);
						double alpha = (argb >>> 24) / 255.0;
						if (alpha > 0) {
							r += Math.pow((argb >> 16 & 0xFF) / 255.0, 2.2) * alpha;
							g += Math.pow((argb >> 8 & 0xFF) / 255.0, 2.2) * alpha;
							b += Math.pow((argb & 0xFF) / 255.0, 2.2) * alpha;
							w += alpha;
						}
					}
				}
				return w > 0 ? new float[] {(float) (r / w), (float) (g / w), (float) (b / w)} : UNKNOWN;
			} catch (java.io.IOException | RuntimeException e) {
				return UNKNOWN;
			}
		});
	}

	private static java.util.Optional<Identifier> textureOf(RenderType type) {
		try {
			java.lang.reflect.Field state = RenderType.class.getDeclaredField("state");
			state.setAccessible(true);
			Object setup = state.get(type);
			java.lang.reflect.Field textures = setup.getClass().getDeclaredField("textures");
			textures.setAccessible(true);
			for (Object binding : ((java.util.Map<?, ?>) textures.get(setup)).values()) {
				java.lang.reflect.Method location = binding.getClass().getDeclaredMethod("location");
				location.setAccessible(true);
				return java.util.Optional.ofNullable((Identifier) location.invoke(binding));
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// no texture known
		}
		return java.util.Optional.empty();
	}

	/** Vertices arrive in a staged draw (StagedDrawMixin). */
	public static void onVertices(Object draw, MeshData mesh) {
		if (!capturing || !TAGGED.contains(draw)) {
			return;
		}
		ByteBuffer vertices = mesh.vertexBuffer().duplicate().order(java.nio.ByteOrder.nativeOrder());
		int count = mesh.drawState().vertexCount() / 4 * 4;
		if (count == 0) {
			return;
		}
		int base = vertices.position();
		raw = ensure(raw, count * VERTEX_STRIDE);
		MemoryUtil.memCopy(MemoryUtil.memAddress(vertices), MemoryUtil.memAddress(raw),
				(long) count * VERTEX_STRIDE);
		// The copy's vertex colours times the texture's mean colour: the bounce's albedo off entities.
		float[] mean = DRAW_COLORS.getOrDefault(draw, UNKNOWN);
		int start = raw.position();
		for (int v = 0; v < count; v++) {
			int at = start + v * VERTEX_STRIDE + 12;
			for (int c = 0; c < 3; c++) {
				int value = raw.get(at + c) & 0xFF;
				raw.put(at + c, (byte) Math.min(255, Math.round(value * mean[c])));
			}
		}
		raw.position(raw.position() + count * VERTEX_STRIDE);
		positions = ensure(positions, count / 4 * 6 * 12);
		for (int quad = 0; quad < count / 4; quad++) {
			for (int v : QUAD_TRIANGLES) {
				int offset = base + (quad * 4 + v) * VERTEX_STRIDE;
				positions.putFloat(vertices.getFloat(offset)).putFloat(vertices.getFloat(offset + 4))
						.putFloat(vertices.getFloat(offset + 8));
			}
		}
		quads += count / 4;
	}

	private static final int[] QUAD_TRIANGLES = {0, 1, 2, 2, 3, 0};

	private static ByteBuffer ensure(ByteBuffer buffer, int more) {
		if (buffer.remaining() >= more) {
			return buffer;
		}
		int position = buffer.position();
		int capacity = Math.max(buffer.capacity() * 2, position + more);
		ByteBuffer grown = MemoryUtil.memRealloc(buffer, capacity);
		grown.order(java.nio.ByteOrder.nativeOrder()).limit(capacity).position(position);
		return grown;
	}

	static int quads() {
		return quads;
	}

	/** The frame's entity vertices, as Minecraft wrote them (position 0 to the end). */
	static ByteBuffer rawVertices() {
		return MemoryUtil.memByteBuffer(MemoryUtil.memAddress0(raw), raw.position());
	}

	/** The frame's entity triangles' positions, relative to the camera. */
	static ByteBuffer trianglePositions() {
		return MemoryUtil.memByteBuffer(MemoryUtil.memAddress0(positions), positions.position());
	}
}
