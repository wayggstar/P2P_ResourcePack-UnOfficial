package dev.p2p.resourcepack;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

enum PackGuestLanguage {
    ENGLISH("en_us"),
    KOREAN("ko_kr");

    private final Map<String, String> messages;

    PackGuestLanguage(String locale) {
        var loaded = new HashMap<String, String>();
        String prefix = "p2p_resourcepack.guest_";
        try (var input = PackGuestLanguage.class.getResourceAsStream("/assets/p2p_resourcepack/lang/" + locale + ".json")) {
            Language.loadFromJson(Objects.requireNonNull(input), (key, value) -> {
                if (key.startsWith(prefix)) loaded.put(key.substring(prefix.length()), value);
            });
        } catch (IOException e) {
            throw new IllegalStateException("Could not read guest messages for " + locale, e);
        }
        messages = Map.copyOf(loaded);
    }

    static PackGuestLanguage forClient(String language) {
        String locale = language.toLowerCase(Locale.ROOT).replace('-', '_');
        return locale.equals("ko") || locale.startsWith("ko_") ? KOREAN : ENGLISH;
    }

    Component text(String key) {
        return Component.literal(Objects.requireNonNull(messages.get(key), key));
    }
}
