package dev.p2p.resourcepack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;

public final class ResourcePackChoiceDialog {
    private static final Identifier ACCEPT = Identifier.fromNamespaceAndPath("p2p_resourcepack", "accept");
    private static final Identifier DECLINE = Identifier.fromNamespaceAndPath("p2p_resourcepack", "decline");

    record Choice(UUID offer, boolean accept) {}

    public static ConfirmationDialog create(UUID offer, boolean required, List<PackSettings.Offer> packs, String language) {
        var text = PackGuestLanguage.forClient(language);
        var body = new ArrayList<DialogBody>();
        body.add(line(text.text(required ? "required_question" : "question")));
        if (packs.size() > 1) body.add(line(text.text("priority")));
        int rank = 1;
        // Delivery uses low-to-high order. The dialog shows the top-most pack first.
        for (var pack : packs.reversed()) {
            var name = pack.name().isBlank() ? text.text("unnamed") : Component.literal(pack.name());
            body.add(line(Component.literal(rank++ + ". ").append(name)));
        }

        var common = new CommonDialogData(text.text("title"), Optional.empty(), !required, false, DialogAction.CLOSE,
                List.copyOf(body), List.of());
        var accept = button(text.text("apply"), ACCEPT, offer);
        var decline = button(text.text(required ? "leave" : "skip"), DECLINE, offer);
        return new ConfirmationDialog(common, accept, decline);
    }

    private static PlainMessage line(Component text) {
        return new PlainMessage(text, PlainMessage.DEFAULT_WIDTH);
    }

    private static ActionButton button(Component label, Identifier id, UUID offer) {
        var button = new CommonButtonData(label, CommonButtonData.DEFAULT_WIDTH);
        var action = new StaticAction(new ClickEvent.Custom(id, Optional.of(StringTag.valueOf(offer.toString()))));
        return new ActionButton(button, Optional.of(action));
    }

    static Optional<Choice> readChoice(Identifier id, Optional<Tag> payload) {
        boolean accept = ACCEPT.equals(id);
        if ((!accept && !DECLINE.equals(id)) || !(payload.orElse(null) instanceof StringTag code)
                || code.value().length() != 36) return Optional.empty();
        try {
            return Optional.of(new Choice(UUID.fromString(code.value()), accept));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
