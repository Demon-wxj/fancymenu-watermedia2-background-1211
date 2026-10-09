package local.bccsaomenu;

import de.keksuccino.fancymenu.customization.background.MenuBackgroundRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Registers an additional FancyMenu provider; it never replaces a Minecraft screen. */
public final class MenuBackgroundClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TitleScreenWidgetIdentifiers.install();
        MenuBackgroundRegistry.register(new Watermedia2BackgroundBuilder());
        ClientTickEvents.END_CLIENT_TICK.register(client -> Watermedia2Background.tickPlayers());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Watermedia2Background.releaseAll());
    }

    public static Watermedia2Background getLastRenderedBackground() {
        return Watermedia2Background.getLastRendered();
    }

    /** Side-effect-free probe API. timeMs is VLC's actual media time. */
    public static java.util.Map<String, Object> debugState() {
        Watermedia2Background background = getLastRenderedBackground();
        return background == null ? java.util.Map.of("state", "not_rendered", "timeMs", -1L)
                : background.debugSnapshot();
    }
}
