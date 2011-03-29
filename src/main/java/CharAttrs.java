/************************************************************

Project:
    JiveTerm.

    A VT52/VT100/VT102-compliant telnet/terminal program
    written in java.

    (C) 1998-2001 Tatu Saloranta
    tatu.saloranta@iki.fi.

Module:
    CharAttrs.java

    Class that encapsulates character attributes, and also
    contains method for rendering text that has these
    attributes.

Last changed:
    23-Sep-2001, TSa:

************************************************************/

package jiveterm;

import java.util.*;
import java.awt.*;

/**
 * Class that encapsulates various character specific display
 * attributes. Attribute instances are to be shared so that each
 * display character is associated with a CharAttrs instance.
 *
 * CharAttrs instances are not canonicalized (because of
 * problems with purging unused instanced: on Java1.2 and above
 * could use weak/soft references, on Java1.1 not), but some
 * attempts are made to promote sharing. This sharing property
 * is used in optimizing screen rendering; characters with same
 * attributes (and on same line) can be (and are) drawn using
 * just one draw - method, except for certain special cases
 * (double-sized chars).
 */

public final class
CharAttrs
{
    /* Per-character attribute flags we'll use in buffer: */
    public final static short FX_BOLD = 0x0001; // (or bold)
    public final static short FX_ITALICS = 0x0002;
    public final static short FX_UNDERLINING = 0x0004;
    public final static short FX_BLINK = 0x0008;

    // These are for VT-emulation support:
    public final static short FX_PROTECTED = 0x0010; // can't be overridden?
    public final static short FX_SELECTED = 0x0020; // whatever this might mean

    // And these are for extended UI support (buttons, text areas etc):
    public final static short FX_BRIGHT_TOP = 0x0100;
    public final static short FX_BRIGHT_LEFT = 0x0200;
    public final static short FX_BRIGHT_RIGHT = 0x0400;
    public final static short FX_BRIGHT_BOTTOM = 0x0800;
    public final static short FX_DARK_TOP = 0x1000;
    public final static short FX_DARK_LEFT = 0x2000;
    public final static short FX_DARK_RIGHT = 0x4000;
    public final static short FX_DARK_BOTTOM = (short)0x8000;
    public final static short FX_BRIGHT_BORDER_MASK  = (short) 0x0F00;
    public final static short FX_DARK_BORDER_MASK  = (short) 0xF000;
    public final static short FX_BORDER_MASK  =
	FX_BRIGHT_BORDER_MASK | FX_DARK_BORDER_MASK;

    /* We need 4 different fonts for rendering 'normal' text; combination
     * of italics and/or bolding:
     */
    public final static int FONT_SET_SIZE = 4;
    public final static int FONT_INDEX_BOLD = 1;
    public final static int FONT_INDEX_ITALICS = 2;

    /* Similarly, 4 colors (plus another set for reversed screen if
     * necessary)
     */
    public final static int FG_INDEX = 0;
    public final static int BG_INDEX = 1;
    public final static int BRIGHT_BORDER_INDEX = 2;
    public final static int DARK_BORDER_INDEX = 3;

    /* Per-character properties. Note that font being used is not stored
     * here as it may dynamically change; set of fonts will be passed
     * for rendering methdos.
     */
    private final int mAttributes;
    private final Color[] mColours;
    private Color[] mReverseColours;
    private final int mFontIndex;

    /* Some 'optional' properties; only used when drawing borders around
     * characters:
     */
    private Color mBrightForeground, mDarkForeground;

    public CharAttrs(int attrs, Color fg, Color bg)
    {
	mAttributes = attrs;
	int fi = 0;
	if ((mAttributes & FX_BOLD) != 0) {
	    fi |= FONT_INDEX_BOLD;
	}
	if ((mAttributes & FX_ITALICS) != 0) {
	    fi |= FONT_INDEX_ITALICS;
	}
	if ((mAttributes & FX_BORDER_MASK) == 0) {
	    mColours = new Color[] { fg, bg };
	} else {
	    mColours = new Color[] {
		fg, bg,
		(mAttributes & FX_BRIGHT_BORDER_MASK) != 0 ?
		fg.brighter() : null,
		(mAttributes & FX_DARK_BORDER_MASK) != 0 ?
		  bg.darker() : null
	    };
	}
	mFontIndex = fi;
    }

    public int getAttributes() { return mAttributes; }
    public boolean isBold() { return (mAttributes & FX_BOLD) != 0; }
    public boolean isItalics() { return (mAttributes & FX_ITALICS) != 0; }
    public boolean hasUnderlining() { return (mAttributes & FX_UNDERLINING) != 0; }
    public boolean isBlinking() { return (mAttributes & FX_BLINK) != 0; }
    public boolean isProtected() { return (mAttributes & FX_PROTECTED) != 0; }
    public boolean isSelected() { return (mAttributes & FX_SELECTED) != 0; }
    
    /**
     * Need to override this method as we do want to compare contents,
     * not just pointers. This is done to maximize sharing of CharAttrs
     * instances (from Display)
     */
    public boolean equals(Object o)
    {
	if (!(o instanceof CharAttrs)) {
	    return false;
	}
	
	CharAttrs ca = (CharAttrs) o;
	if (ca.mAttributes != mAttributes) {
	    return false;
	}
	
	if (!ca.mColours[FG_INDEX].equals(mColours[FG_INDEX])) {
	    return false;
	}
	
	return ca.mColours[BG_INDEX].equals(mColours[BG_INDEX]);
    }

    public int getFontIndex() { return mFontIndex; }

    private final Color[] getReverseColours(Color[] colours)
    {
	Color c = colours[FG_INDEX];
	Color fg = new Color(
			     255 - c.getRed(),
			     255 - c.getGreen(),
			     255 - c.getBlue());
	c = colours[BG_INDEX];
	Color bg = new Color(
			     255 - c.getRed(),
			     255 - c.getGreen(),
			     255 - c.getBlue());
	
	if ((mAttributes & FX_BORDER_MASK) == 0) {
	    return new Color[] { fg, bg };
	}

	return new Color[] { fg, bg,
		(mAttributes & FX_BRIGHT_BORDER_MASK) != 0 ?
				 fg.brighter() : null,
		(mAttributes & FX_DARK_BORDER_MASK) != 0 ?
				 bg.darker() : null
				 };
    }

    /**
     * Method for painting the given text on given coordinates.
     * 
     * Note that caller is supposed to have been set correct
     * font to the graphics context prior to calling this method.
     *
     * @param g Graphics context to paint the text on
     * @param text Character buffer that contains text to be drawn
     *    (plus possibly some other text)
     * @param start Starting offset of the text to draw, in buffer
     * @param start Length of the text to draw (in characters)
     * @param coords Coordinates to draw text on.
     * @param baseline Offset of the text baseline, from the top
     *    of the drawing area (specified in coords)
     * @param blinkedOut If true will skip actual drawing of text.
     * @param reversed If true, screen is in reversed mode, so we'll need
     *    use different set of colors.
     */
    public void paintText(Graphics g, char[] text, int start, int len,
			  Rectangle coords, int baseline,
			  boolean blinkedOut, boolean reversed)
    {
	Color[] colours;
	
	if (reversed) {
	    if (mReverseColours == null) {
		mReverseColours = getReverseColours(mColours);
	    }
	    colours = mReverseColours;
	} else {
	    colours = mColours;
	}
	
	g.setColor(colours[BG_INDEX]);
	g.fillRect(coords.x, coords.y, coords.width, coords.height);

	// If blinking text, and we are in "off" - phase, let's split:
	if ((mAttributes & FX_BLINK) != 0 && blinkedOut) {
	    return;
	}
	
	g.setColor(colours[FG_INDEX]);
	g.drawChars(text, start, len, coords.x, coords.y + baseline);

	// Underlining?
	if ((mAttributes & FX_UNDERLINING) != 0) {
	    /* Hmmh. Some style guides say underline is not to be applied
	     * over descending chars, and perhaps not under empty spaces.
	     * That would be difficult to do. :->
	     */
	    int y = coords.y + baseline + 2; // 2 px below baseline?
	    g.drawLine(coords.x, y, coords.x + coords.width - 1, y);
	}
	
	// Any borders?
	if ((mAttributes & FX_DARK_BORDER_MASK) != 0) {
	    g.setColor(colours[DARK_BORDER_INDEX]);
	    if ((mAttributes & FX_DARK_TOP) != 0) {
		g.drawLine(coords.x, coords.y, coords.x + coords.width - 1,
			   coords.y);
	    }
	    if ((mAttributes & FX_DARK_LEFT) != 0) {
		g.drawLine(coords.x, coords.y, coords.x,
			   coords.y + coords.height - 1);
	    }
	    if ((mAttributes & FX_DARK_RIGHT) != 0) {
		int x = coords.x + coords.width - 1;
		g.drawLine(x, coords.y, x, coords.y + coords.height - 1);
	    }
	    if ((mAttributes & FX_DARK_BOTTOM) != 0) {
		int y = coords.y + coords.height - 1;
		g.drawLine(coords.x, y, coords.x + coords.width - 1, y);
	    }
	}
	if ((mAttributes & FX_BRIGHT_BORDER_MASK) != 0) {
	    g.setColor(colours[BRIGHT_BORDER_INDEX]);
	    if ((mAttributes & FX_BRIGHT_TOP) != 0) {
		g.drawLine(coords.x, coords.y, coords.x + coords.width - 1,
			   coords.y);
	    }
	    if ((mAttributes & FX_BRIGHT_LEFT) != 0) {
		g.drawLine(coords.x, coords.y, coords.x,
			   coords.y + coords.height - 1);
	    }
	    if ((mAttributes & FX_BRIGHT_RIGHT) != 0) {
		int x = coords.x + coords.width - 1;
		g.drawLine(x, coords.y, x, coords.y + coords.height - 1);
	    }
	    if ((mAttributes & FX_BRIGHT_BOTTOM) != 0) {
		int y = coords.y + coords.height - 1;
		g.drawLine(coords.x, y, coords.x + coords.width - 1, y);
	    }
	}
}

    /**
     * Method for clearing the specified area using background color
     * (depending on reverse mode) this object defines.
     *
     * @param g Graphics context to draw on
     * @param coords Area to clear
     * @param reversed If true, screen is in reversed mode, so we'll need
     *    to reversed background colour.
     */
    public void clearArea(Graphics g, Rectangle coords, boolean reversed)
    {
	Color[] colours;

	if (reversed) {
	    if (mReverseColours == null) {
		mReverseColours = getReverseColours(mColours);
	    }
	    colours = mReverseColours;
	} else {
	    colours = mColours;
	}

	g.setColor(colours[BG_INDEX]);
	g.fillRect(coords.x, coords.y, coords.width, coords.height);
    }
}

