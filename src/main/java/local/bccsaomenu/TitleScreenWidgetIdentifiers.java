package local.bccsaomenu;

import de.keksuccino.fancymenu.customization.widget.identification.identificationcontext.contexts.WidgetIdentificationContexts;

/** Adds the Minecraft 1.21.1 localization keys to FancyMenu's existing title context. */
final class TitleScreenWidgetIdentifiers {
    private static boolean installed;

    private TitleScreenWidgetIdentifiers() {}

    @SuppressWarnings("deprecation")
    static synchronized void install() {
        if (installed) return;
        // Extend the same context object that FancyMenu registers, irrespective of init order.
        // The native widgets, callbacks and all other identifier providers remain unchanged.
        WidgetIdentificationContexts.TITLE_SCREEN_CONTEXT.addUniversalIdentifierProvider(meta -> {
            String key = meta.getWidgetLocalizationKey();
            if ("options.language".equals(key)) return "mc_titlescreen_language_button";
            if ("options.accessibility".equals(key)) return "mc_titlescreen_accessibility_button";
            return null;
        });
        installed = true;
    }
}
