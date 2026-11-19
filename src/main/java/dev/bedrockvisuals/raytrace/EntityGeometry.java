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
	private static boolean capturing;
	private static ByteBuffer raw = MemoryUtil.memAlloc(1 << 20);
	private static ByteBuffer positions = MemoryUtil.memAlloc(1 << 20);
	private static int quads;

	private EntityGeometry() {
	}

	/** The level starts rendering: a new frame of entities. */
	public static void beginLevel() {
		TAGGED.clear();
		raw.clear();
		positions.clear();
		quads = 0;
		capturing = RtScene.enabled();
	}

	/** The level is drawn; the hand comes next and isn't part of the scene. */
	public static void endLevel() {
		capturing = false;
		TAGGED.clear();
	}

	/** Minecraft made a staged draw for this render type (RenderTypeGroupMixin). */
	public static void onDrawAdded(Object draw, RenderType type) {
		if (capturing && type.format() == DefaultVertexFormat.ENTITY
				&& DefaultVertexFormat.ENTITY.getVertexSize() == VERTEX_STRIDE
				&& type.primitiveTopology() == PrimitiveTopology.QUADS
				&& ENTITY_SHADER.equals(type.pipeline().getShaders().get(ShaderType.FRAGMENT))) {
			TAGGED.add(draw);
		}
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
