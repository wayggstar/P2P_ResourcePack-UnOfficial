package dev.p2p.resourcepack;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackConsentTest {
    @Test void aChoiceBelongsToOnePlayerAndOnePackRevision() {
        var current = new PackConsent();
        var older = new PackConsent();
        var otherPlayer = new PackConsent();

        assertFalse(current.choose(older.id()));
        assertFalse(current.choose(otherPlayer.id()));
        assertTrue(current.pending());
        assertTrue(current.choose(current.id()));
        assertFalse(current.choose(current.id()));
    }

    @Test void onlyPrivateRoomsCanRequirePacksFromGuests() {
        assertFalse(PackConsent.required(false, true, false));
        assertFalse(PackConsent.required(true, false, false));
        assertFalse(PackConsent.required(true, true, true));
        assertTrue(PackConsent.required(true, true, false));
    }

    @Test void onlyValidPackChoicePayloadsAreRecognized() {
        var id = UUID.randomUUID();
        var code = Optional.<Tag>of(StringTag.valueOf(id.toString()));
        var accept = Identifier.parse("p2p_resourcepack:accept");
        var decline = Identifier.parse("p2p_resourcepack:decline");

        assertEquals(new ResourcePackChoiceDialog.Choice(id, true),
                ResourcePackChoiceDialog.readChoice(accept, code).orElseThrow());
        assertEquals(new ResourcePackChoiceDialog.Choice(id, false),
                ResourcePackChoiceDialog.readChoice(decline, code).orElseThrow());
        assertTrue(ResourcePackChoiceDialog.readChoice(accept, Optional.of(StringTag.valueOf("wrong"))).isEmpty());
        assertTrue(ResourcePackChoiceDialog.readChoice(accept, Optional.of(StringTag.valueOf("!".repeat(36)))).isEmpty());
        assertTrue(ResourcePackChoiceDialog.readChoice(Identifier.parse("other:accept"), code).isEmpty());
    }
}
