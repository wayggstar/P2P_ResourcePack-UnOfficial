package dev.p2p.resourcepack;

import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.system.MemoryUtil;

/** Uses Minecraft 26.3's existing SDL runtime; no extra native library is bundled. */
public final class ZipFilePicker {
    private record Selection(Minecraft minecraft, Consumer<Path> selected, Runnable failed) {}
    private static volatile Selection pending;
    // One process-lifetime callback avoids freeing native callback memory while SDL is returning through it.
    private static final SDL_DialogFileCallback CALLBACK = SDL_DialogFileCallback.create((userdata, files, filter) -> {
        Selection selection = pending;
        if (selection == null) return;
        pending = null;
        String path = files == 0 || MemoryUtil.memGetAddress(files) == 0
                ? null : MemoryUtil.memUTF8(MemoryUtil.memGetAddress(files));
        selection.minecraft().execute(() -> {
            if (files == 0) selection.failed().run();
            else if (path != null) selection.selected().accept(Path.of(path));
        });
    });

    public static boolean open(Minecraft minecraft, Consumer<Path> selected, Runnable failed) {
        if (pending != null) return false;
        pending = new Selection(minecraft, selected, failed);
        try {
            SDLDialog.SDL_ShowOpenFileDialog(CALLBACK, 0L, minecraft.getWindow().handle(), null, (String) null, false);
            return true;
        } catch (Throwable e) {
            pending = null;
            failed.run();
            return false;
        }
    }
}
