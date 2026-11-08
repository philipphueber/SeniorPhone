package app.phueber.seniorphone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.phueber.seniorphone.Palette.Role;
import app.phueber.seniorphone.Palette.Theme;

import org.junit.Test;

/**
 * Every theme must stay readable: these are the combinations the screens
 * actually draw. Text levels follow WCAG: 7:1 (AAA) for body text on its
 * background, 4.5:1 (AA) for bold button text and secondary text.
 */
public class PaletteTest {
    private static final double AAA = 7.0;
    private static final double AA = 4.5;

    private static double c(Theme t, Role fg, Role bg) {
        return Palette.contrast(Palette.color(t, fg), Palette.color(t, bg));
    }

    private static void atLeast(double min, double actual, String what) {
        assertTrue(what + " contrast " + actual + " < " + min, actual >= min);
    }

    @Test public void textIsReadableOnPageAndCardInEveryTheme() {
        for (Theme t : Theme.values()) {
            atLeast(AAA, c(t, Role.TEXT, Role.PAGE), t + " text/page");
            atLeast(AAA, c(t, Role.TEXT, Role.CARD), t + " text/card");
            atLeast(AA, c(t, Role.TEXT_MUTED, Role.CARD), t + " muted/card");
            atLeast(AA, c(t, Role.TEXT_MUTED, Role.PAGE), t + " muted/page");
        }
    }

    @Test public void whiteButtonTextIsReadableOnEveryActionColour() {
        for (Theme t : Theme.values()) {
            for (Role r : new Role[] {Role.GREEN, Role.BLUE, Role.RED}) {
                atLeast(AA, Palette.contrast(Palette.ON_COLOR, Palette.color(t, r)), t + " white/" + r);
            }
        }
    }

    @Test public void highContrastButtonsReachAaa() {
        for (Role r : new Role[] {Role.GREEN, Role.BLUE, Role.RED}) {
            atLeast(AAA, Palette.contrast(Palette.ON_COLOR, Palette.color(Theme.HIGH_CONTRAST, r)),
                    "high contrast white/" + r);
        }
    }

    @Test public void batteryTextIsReadableOnTheGauge() {
        for (Theme t : Theme.values()) {
            atLeast(AA, c(t, Role.TEXT, Role.BATTERY_FILL), t + " text/battery fill");
        }
    }

    @Test public void disabledButtonsAreStillLegible() {
        for (Theme t : Theme.values()) {
            atLeast(3.0, c(t, Role.DISABLED_TEXT, Role.DISABLED_BG), t + " disabled");
        }
    }

    @Test public void tilesStandOutFromThePage() {
        // A tile is found by its outline or its fill.
        for (Theme t : Theme.values()) {
            double border = c(t, Role.CARD_BORDER, Role.PAGE);
            double fill = c(t, Role.CARD, Role.PAGE);
            assertTrue(t + " tiles invisible", Math.max(border, fill) >= 1.15);
        }
        atLeast(AAA, c(Theme.HIGH_CONTRAST, Role.CARD_BORDER, Role.PAGE), "high contrast outline");
    }

    @Test public void everyRoleIsDefinedAndOpaqueExceptRipples() {
        for (Theme t : Theme.values()) {
            for (Role r : Role.values()) {
                int alpha = Palette.color(t, r) >>> 24;
                if (r == Role.PRESS_ON_CARD || r == Role.PRESS_ON_COLOR) continue;
                assertEquals(t + " " + r + " must be opaque", 0xFF, alpha);
            }
        }
    }

    @Test public void everyTileColourCarriesWhiteText() {
        for (int i = 0; i < Palette.tileColorCount(); i++) {
            atLeast(AA, Palette.contrast(Palette.ON_COLOR, Palette.tileColor(i)), "tile " + i);
        }
    }

    @Test public void neighbouringTilesNeverShareAColour() {
        // The launcher grid has two columns: right is index + 1, below index + 2.
        for (int i = 0; i < 4 * Palette.tileColorCount(); i++) {
            assertTrue("right of " + i, Palette.tileColor(i) != Palette.tileColor(i + 1));
            assertTrue("below " + i, Palette.tileColor(i) != Palette.tileColor(i + 2));
        }
    }

    @Test public void tileColoursAreDistinctAndStandOutFromThePage() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int page = Palette.color(Theme.COLOURFUL, Role.PAGE);
        for (int i = 0; i < Palette.tileColorCount(); i++) {
            assertTrue("duplicate tile colour " + i, seen.add(Palette.tileColor(i)));
            atLeast(1.15, Palette.contrast(Palette.tileColor(i), page), "tile " + i + " vs page");
        }
    }

    @Test public void onlyColourfulColoursTheTiles() {
        for (Theme t : Theme.values()) {
            assertEquals(t == Theme.COLOURFUL, Palette.colouredTiles(t));
        }
        assertEquals(Palette.tileColor(0), Palette.tileColor(Palette.tileColorCount()));
    }

    @Test public void parseFallsBackToLight() {
        assertEquals(Theme.LIGHT, Palette.parse(null));
        assertEquals(Theme.LIGHT, Palette.parse("PURPLE"));
        assertEquals(Theme.DARK, Palette.parse("DARK"));
        assertEquals(Theme.HIGH_CONTRAST, Palette.parse("HIGH_CONTRAST"));
    }

    @Test public void contrastOfBlackOnWhiteIs21() {
        assertEquals(21.0, Palette.contrast(0xFF000000, 0xFFFFFFFF), 0.01);
        assertEquals(1.0, Palette.contrast(0xFF777777, 0xFF777777), 0.001);
    }

    @Test public void onlyLightUsesDarkBarIcons() {
        assertTrue(Palette.isLight(Theme.LIGHT));
        assertTrue(!Palette.isLight(Theme.DARK));
        assertTrue(!Palette.isLight(Theme.HIGH_CONTRAST));
        assertTrue(!Palette.isLight(Theme.COLOURFUL));
    }
}
