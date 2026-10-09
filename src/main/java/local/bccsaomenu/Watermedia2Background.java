package local.bccsaomenu;

import com.mojang.blaze3d.systems.RenderSystem;
import de.keksuccino.fancymenu.customization.background.MenuBackground;
import de.keksuccino.fancymenu.customization.layout.editor.LayoutEditorScreen;
import de.keksuccino.fancymenu.util.properties.Property;
import de.keksuccino.fancymenu.util.rendering.ui.contextmenu.v2.ContextMenu;
import de.keksuccino.fancymenu.util.resource.ResourceSupplier;
import de.keksuccino.fancymenu.util.resource.resources.texture.ITexture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.sound.SoundCategory;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.player.videolan.VideoPlayer;
import org.watermedia.videolan4j.player.base.MediaPlayer;
import org.watermedia.videolan4j.player.embedded.videosurface.callback.BufferFormat;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/** One VLC player owns both video and sound. The movie itself defines the 52-second cue. */
public final class Watermedia2Background extends MenuBackground<Watermedia2Background> {
    private static final Logger LOGGER = LoggerFactory.getLogger("bcc-sao-menu-background");
    private static final Set<Watermedia2Background> LIVE = new HashSet<>();
    private static Watermedia2Background lastRendered;
    private static boolean audioInitChecked;
    private static final Executor RENDER_EXECUTOR = task -> {
        if (RenderSystem.isOnRenderThread()) task.run();
        else RenderSystem.recordRenderCall(task::run);
    };

    public final Property.StringProperty videoPath = (Property.StringProperty) putProperty(Property.stringProperty(
            "video_path", "config/fancymenu/assets/bcc_sao_between_worlds.mp4", false, false, "Video path"));
    public final Property.StringProperty fallbackPath = (Property.StringProperty) putProperty(Property.stringProperty(
            "fallback_path", "config/fancymenu/assets/bcc_sao_menu_poster.png", false, false, "Loading image"));
    public final Property.FloatProperty volume = (Property.FloatProperty) putProperty(Property.floatProperty(
            "volume", 1.0f, "Music volume"));

    private TrackingPlayer player;
    private String openedPath;
    private String lastFallbackPath;
    private ResourceSupplier<ITexture> fallbackSupplier;
    private long lastRenderedNanos;
    private long retryAfterNanos;
    private boolean wantsPlayback;
    private int lastVolume = -1;
    private String lastError = "";

    Watermedia2Background(Watermedia2BackgroundBuilder builder) { super(builder); }

    @Override protected void initConfigMenu(ContextMenu menu, LayoutEditorScreen editor) {
        videoPath.buildContextMenuEntryAndAddTo(menu, this);
        fallbackPath.buildContextMenuEntryAndAddTo(menu, this);
        volume.buildContextMenuEntryAndAddTo(menu, this);
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        lastRendered = this;
        lastRenderedNanos = System.nanoTime();
        MinecraftClient client = MinecraftClient.getInstance();
        // Previewing a layout never starts audible playback.
        wantsPlayback = client.currentScreen instanceof TitleScreen && opacity > 0.0f;
        if (!wantsPlayback) { pausePlayback(); drawFallback(context); return; }
        initializeMusicOnce();
        ensurePlayer();
        updatePlayback();
        if (player == null || !player.frameReady || player.width() < 2 || player.height() < 2) {
            drawFallback(context);
            return;
        }
        drawCover(context, player.texture(), player.width(), player.height());
    }

    /** The installer opt-in is consumed once; later user mute choices are preserved. */
    private static void initializeMusicOnce() {
        if (audioInitChecked) return;
        audioInitChecked = true;
        MinecraftClient client = MinecraftClient.getInstance();
        Path pending = client.runDirectory.toPath().resolve("config/fancymenu/assets/bcc_sao_audio_init.pending");
        if (!Files.isRegularFile(pending)) return;
        try {
            double requested = Double.parseDouble(Files.readString(pending).trim());
            if (!Double.isFinite(requested) || requested < 0 || requested > 1) {
                throw new IllegalArgumentException("Music initialization value must be between 0 and 1");
            }
            if (client.options.getSoundVolume(SoundCategory.MUSIC) == 0.0f) {
                client.options.getSoundVolumeOption(SoundCategory.MUSIC).setValue(requested);
                client.options.write();
                LOGGER.info("Applied one-time requested menu music volume: {}", requested);
            } else {
                LOGGER.info("Kept existing music volume: {}", client.options.getSoundVolume(SoundCategory.MUSIC));
            }
            Path done = pending.resolveSibling("bcc_sao_audio_init.done");
            try { Files.move(pending, done, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(pending, done, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception error) {
            LOGGER.error("Could not apply one-time menu audio initialization; pending marker preserved", error);
        }
    }

    private void ensurePlayer() {
        String configured = videoPath.getString();
        if (configured == null || configured.isBlank()) return;
        if (player != null && !configured.equals(openedPath)) releasePlayer();
        if (player != null || System.nanoTime() < retryAfterNanos) return;
        // VLC may still be preparing during the first title-screen frames.
        if (!PlayerAPI.isReady()) return;
        Path source = resolveLocal(configured);
        if (!Files.isRegularFile(source)) { fail("Missing menu movie: " + source, null); return; }
        try {
            player = new TrackingPlayer(RENDER_EXECUTOR);
            if (player.raw() == null) throw new IllegalStateException("WaterMedia's VLC player is unavailable");
            openedPath = configured;
            LIVE.add(this);
            lastVolume = -1;
            updateVolume();
            player.setRepeatMode(true);
            // VLC is the sole audio/video timing authority, including loop and pause.
            player.start(source.toUri());
            lastError = "";
            LOGGER.info("Started FancyMenu WaterMedia 2 background: {}", source);
        } catch (Throwable error) { fail("Cannot start menu movie: " + source, error); }
    }

    private static Path resolveLocal(String configured) {
        String source = configured.replace("[source:local]", "");
        Path path = Path.of(source);
        return path.isAbsolute() ? path.normalize() : MinecraftClient.getInstance().runDirectory.toPath().resolve(path).normalize();
    }

    private void updatePlayback() {
        if (player == null) return;
        try {
            if (!wantsPlayback) { pausePlayback(); return; }
            if (player.isBroken()) { fail("VLC reported an error while playing the menu movie", null); return; }
            updateVolume();
            if (player.isPaused()) player.setPauseMode(false);
        } catch (Throwable error) { fail("Menu movie playback failed", error); }
    }

    private void updateVolume() {
        if (player == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        float configured = volume.getFloat();
        if (!Float.isFinite(configured)) configured = 1.0f;
        float level = Math.max(0.0f, Math.min(1.0f, configured));
        level *= client.options.getSoundVolume(SoundCategory.MASTER) * client.options.getSoundVolume(SoundCategory.MUSIC);
        int next = wantsPlayback ? Math.round(100.0f * level) : 0;
        if (next != lastVolume) { player.setVolume(next); lastVolume = next; }
    }

    private void pausePlayback() {
        wantsPlayback = false;
        if (player == null) return;
        try {
            updateVolume();
            if (player.isPlaying()) player.setPauseMode(true);
        } catch (Throwable error) { fail("Cannot pause menu movie", error); }
    }

    @Override public void onCloseScreen(Screen closedScreen, Screen newScreen) { pausePlayback(); }
    @Override public void onDisableOrRemove() { releasePlayer(); }

    private void drawFallback(DrawContext context) {
        String source = fallbackPath.getString();
        if (source == null || source.isBlank()) return;
        if (!source.equals(lastFallbackPath)) {
            lastFallbackPath = source;
            fallbackSupplier = ResourceSupplier.image("[source:local]" + resolveLocal(source).toString().replace('\\', '/'));
        }
        ITexture texture = fallbackSupplier == null ? null : fallbackSupplier.get();
        if (texture == null || texture.getResourceLocation() == null) return;
        int screenWidth = MinecraftClient.getInstance().getWindow().getScaledWidth();
        int screenHeight = MinecraftClient.getInstance().getWindow().getScaledHeight();
        float scale = Math.max((float) screenWidth / texture.getWidth(), (float) screenHeight / texture.getHeight());
        int width = Math.round(texture.getWidth() * scale), height = Math.round(texture.getHeight() * scale);
        context.drawTexture(texture.getResourceLocation(), (screenWidth - width) / 2, (screenHeight - height) / 2,
                0, 0, width, height, width, height);
    }

    private void drawCover(DrawContext context, int texture, int videoWidth, int videoHeight) {
        int width = MinecraftClient.getInstance().getWindow().getScaledWidth();
        int height = MinecraftClient.getInstance().getWindow().getScaledHeight();
        float scale = Math.max((float) width / videoWidth, (float) height / videoHeight);
        float visibleU = width / (videoWidth * scale), visibleV = height / (videoHeight * scale);
        float u0 = (1 - visibleU) * 0.5f, v0 = (1 - visibleV) * 0.5f;
        context.draw();
        int oldTexture = RenderSystem.getShaderTexture(0);
        var oldShader = RenderSystem.getShader();
        float[] oldColor = RenderSystem.getShaderColor().clone();
        boolean oldBlend = GL11.glIsEnabled(GL11.GL_BLEND), oldDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean oldCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        try {
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionTexProgram);
            RenderSystem.setShaderTexture(0, texture);
            RenderSystem.setShaderColor(1, 1, 1, Math.max(0, Math.min(1, opacity)));
            Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
            BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
            buffer.vertex(matrix, 0, 0, 0).texture(u0, v0);
            buffer.vertex(matrix, 0, height, 0).texture(u0, v0 + visibleV);
            buffer.vertex(matrix, width, height, 0).texture(u0 + visibleU, v0 + visibleV);
            buffer.vertex(matrix, width, 0, 0).texture(u0 + visibleU, v0);
            BufferRenderer.drawWithGlobalProgram(buffer.end());
        } finally {
            RenderSystem.setShaderTexture(0, oldTexture);
            if (oldShader != null) RenderSystem.setShader(() -> oldShader);
            RenderSystem.setShaderColor(oldColor[0], oldColor[1], oldColor[2], oldColor[3]);
            RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (oldBlend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (oldDepth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (oldCull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        }
    }

    private void fail(String message, Throwable error) {
        if (!message.equals(lastError)) {
            lastError = message;
            LOGGER.error(message, error);
        }
        releasePlayer();
        retryAfterNanos = System.nanoTime() + 5_000_000_000L;
    }

    private void releasePlayer() {
        TrackingPlayer old = player;
        player = null;
        openedPath = null;
        lastVolume = -1;
        LIVE.remove(this);
        if (old != null) {
            try { old.setVolume(0); old.setPauseMode(true); old.release(); }
            catch (Throwable error) { LOGGER.warn("Could not fully release menu movie", error); }
        }
    }

    public static void tickPlayers() {
        boolean title = MinecraftClient.getInstance().currentScreen instanceof TitleScreen;
        long now = System.nanoTime();
        for (Watermedia2Background background : Set.copyOf(LIVE)) {
            if (!title || now - background.lastRenderedNanos > 500_000_000L) background.pausePlayback();
            else background.updatePlayback();
        }
    }

    public static void releaseAll() { for (Watermedia2Background background : Set.copyOf(LIVE)) background.releasePlayer(); }
    public static Watermedia2Background getLastRendered() { return lastRendered; }
    public long debugTimeMs() { return player == null ? -1 : player.getTime(); }
    public long debugDurationMs() { return player == null ? -1 : player.getDuration(); }
    public String debugState() { return player == null ? "uninitialized" : player.getStateName(); }
    public boolean debugFrameReady() { return player != null && player.frameReady; }
    public boolean debugPlaying() { return player != null && player.isPlaying(); }
    public int debugVolume() { return lastVolume; }
    public String debugError() { return lastError; }
    public String debugSource() { return openedPath; }
    public int debugTexture() { return player == null ? -1 : player.texture(); }

    public Map<String, Object> debugSnapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        TrackingPlayer current = player;
        result.put("timeMs", current == null ? -1L : current.getTime());
        result.put("durationMs", current == null ? -1L : current.getDuration());
        result.put("state", current == null ? "uninitialized" : current.getStateName());
        result.put("volume", current == null ? -1 : current.getVolume());
        result.put("commandedVolume", lastVolume);
        result.put("playerIdentity", current == null ? 0 : System.identityHashCode(current));
        result.put("mediaPath", openedPath);
        result.put("paused", current != null && current.isPaused());
        result.put("playing", current != null && current.isPlaying());
        result.put("frameReady", current != null && current.frameReady);
        result.put("texture", current == null ? -1 : current.texture());
        result.put("error", lastError);
        return result;
    }

    private static final class TrackingPlayer extends VideoPlayer {
        private final GenerationGateExecutor executor;
        private volatile boolean frameReady;
        private boolean released;

        TrackingPlayer(Executor executor) { this(new GenerationGateExecutor(executor)); }

        private TrackingPlayer(GenerationGateExecutor executor) {
            super(executor);
            this.executor = executor;
        }

        @Override public void display(MediaPlayer raw, ByteBuffer[] buffers, BufferFormat format) {
            synchronized (executor) {
                if (released) return;
                // WaterMedia queues its upload; release must invalidate both queued tasks.
                super.display(raw, buffers, format);
                executor.execute(() -> frameReady = true);
            }
        }

        @Override public void release() {
            synchronized (executor) {
                if (released) return;
                released = true;
                frameReady = false;
                executor.invalidatePending();
            }
            // The new generation accepts WaterMedia's texture deletion. Never hold the
            // callback lock while releasing native VLC resources.
            super.release();
        }
    }

    /** Cancels old queued uploads while allowing the subsequent texture deletion. */
    private static final class GenerationGateExecutor implements Executor {
        private final Executor delegate;
        private volatile long generation;

        GenerationGateExecutor(Executor delegate) { this.delegate = delegate; }

        void invalidatePending() { generation++; }

        @Override public void execute(Runnable command) {
            long submittedGeneration = generation;
            delegate.execute(() -> {
                // Uploads and deletions still run on the same render thread. Do not
                // hold the callback lock during WaterMedia's native-buffer semaphore wait.
                if (submittedGeneration == generation) command.run();
            });
        }
    }
}
