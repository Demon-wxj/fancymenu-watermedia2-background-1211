package local.bccsaomenu;

import de.keksuccino.fancymenu.customization.background.MenuBackgroundBuilder;
import de.keksuccino.fancymenu.util.properties.PropertyContainer;
import net.minecraft.text.Text;

public final class Watermedia2BackgroundBuilder extends MenuBackgroundBuilder<Watermedia2Background> {
    public static final String ID = "bcc_sao_watermedia2";

    public Watermedia2BackgroundBuilder() { super(ID); }
    @Override public Watermedia2Background buildDefaultInstance() { return new Watermedia2Background(this); }
    @Override public void deserializeBackground(PropertyContainer source, Watermedia2Background target) { }
    @Override public void serializeBackground(Watermedia2Background background, PropertyContainer target) { }
    @Override public Text getDisplayName() { return Text.literal("BCC / SAO 视频背景"); }
    @Override public Text getDescription() { return Text.literal("播放本地视频与声音。"); }
}
