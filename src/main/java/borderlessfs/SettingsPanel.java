package borderlessfs;

import basemod.BaseMod;
import basemod.ModLabel;
import basemod.ModLabeledButton;
import basemod.ModPanel;
import basemod.interfaces.PostInitializeSubscriber;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import org.lwjgl.opengl.Display;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Mod settings entry with one button that rewrites info.displayconfig (the game reads it from its
 * working directory, i.e. the running app bundle's Resources) for borderless windowed mode at the
 * desktop resolution. Takes effect on the next launch.
 *
 * File lines: width, height, fps limit, fullscreen, borderless windowed, vsync.
 */
public class SettingsPanel implements PostInitializeSubscriber {

    private static String status = "";

    private static final File CONFIG = new File("info.displayconfig");

    @Override
    public void receivePostInitialize() {
        Pixmap pixmap = new Pixmap(32, 32, Pixmap.Format.RGBA8888);
        pixmap.setColor(0.3f, 0.5f, 0.8f, 1f);
        pixmap.fill();
        Texture badge = new Texture(pixmap);
        pixmap.dispose();

        ModPanel panel = new ModPanel();
        // BaseMod elements multiply x and y by Settings.scale themselves, so pass 1920x1080 design units.
        // The button sizes itself to its constructor label, so results go in a separate label.
        panel.addUIElement(new ModLabeledButton("Set borderless windowed at desktop resolution",
                350f, 650f, panel, button -> status = apply()));
        panel.addUIElement(new ModLabel("", 350f, 580f, panel, label -> label.text = status));
        BaseMod.registerModBadge(badge, "Borderless Fullscreen (macOS)", "jeffzyliu",
                "Window level and cursor handling for borderless fullscreen on macOS.", panel);
    }

    private static String apply() {
        try {
            List<String> lines = Files.readAllLines(CONFIG.toPath(), StandardCharsets.UTF_8);
            if (lines.size() < 6) return "Unexpected info.displayconfig; not changed";
            int w = Display.getDesktopDisplayMode().getWidth();
            int h = Display.getDesktopDisplayMode().getHeight();
            lines.set(0, String.valueOf(w));
            lines.set(1, String.valueOf(h));
            lines.set(3, "false");
            lines.set(4, "true");
            Files.write(CONFIG.toPath(), lines, StandardCharsets.UTF_8);
            return "Saved " + w + "x" + h + " borderless windowed; restart the game";
        } catch (Exception e) {
            return "Failed: " + e;
        }
    }
}
