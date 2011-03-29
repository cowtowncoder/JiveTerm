/************************************************************

Project:
    JiveTerm.

    A VT52/VT100/VT102-compliant telnet/terminal program
    written in java.

    (C) 1998-2001 Tatu Saloranta, tatu.saloranta@iki.fi.

Module:
    Display.java

Last changed:
    29-Sep-2001

Description:
    This class implements the matrix display
    part of the terminal application. It
    implements all the features required
    by VT-emulation, including character
    (bold/reversed/blinking/colours) and
    lind (double-width/height) attributes.
    Communicates with Terminal class
    using a unidirectional pipe (Terminal
    writes, Display reads).

Changes:

  21-Jan-1999, TSa:
    - Fixed the autowrap to be really VT-compliant;
      now only wraps when a character is to be written
      _beyond_ the right border; not after writing a
     char to the rightmost column.
  24-Jan-1999, TSa:
    Tab-setting now works ok.
  03-Feb-1999, TSa:
    Conformance-level setting works a bit better now.
  06-Feb-1999, TSa:
    16-bit internal arguments.
  25-Feb-1999, TSa:
    Slow-down modes are now implemented in Terminal;
    no support code in Display. Simplifies drawing
    a bit.
  03-Mar-1999, TSa:
    Drawing of the lines that have blinking chars
    rewritten. Now works much better than before;
    VT-animations (such as snowing.vt used for testing)
    look much better now.
  26-Apr-1999, TSa:
    Added the 'direct draw'; now repaint() is not always
    necessary after drawing new characters on the screen
  05-May-1999, TSa:
    Direct drawing wasn't such a brill idea (causes blinking),
    however, it's possible to draw without using async repaint().
    Also, I'm now trying to use clipping for speeding up some
    redraws.
  08-May-1999, TSa: About to add support for selecting text
    and cutting it clipboard...
  29-Oct-2001: (no, selection not there yet). Refactoring the
    whole codebase.

************************************************************/

package jiveterm;

import java.util.*;
import java.io.*;
import java.net.*;
import java.awt.*;
import java.awt.image.*;
import java.awt.event.*;
import java.awt.*;
import java.applet.Applet;

import jiveterm.*;

/*******************************************************
Class that implements the "output window" of the client:
*******************************************************/

final class Display
    extends Canvas // could use Component as well?
    implements MouseMotionListener, MouseListener, FocusListener
{
    // On/off properties (display modes):
    public final static int MODE_ORIGIN_RELATIVE = 0x0001;
    public final static int MODE_AUTO_WRAP = 0x0002;
    public final static int MODE_SMOOTH_SCROLL = 0x0004;
    public final static int MODE_INSERT_MODE = 0x0008;
    public final static int MODE_CURSOR_VISIBLE = 0x0010;
    public final static int MODE_SCREEN_REVERSED = 0x0020;

    // Default settings for those properties (list of those that are on)
    public final static int DEFAULT_DISPLAY_FLAGS =
	MODE_AUTO_WRAP | MODE_CURSOR_VISIBLE;

    public final static int DEFAULT_TAB_SIZE = 8;
    /* Are they all saveable? According to docs, only auto-wrap and
     * origin mode?
     */
    //public final static int SAVEABLE_DISPLAY_FLAGS = 0xFFFF;
    public final static int SAVEABLE_DISPLAY_FLAGS =
	MODE_ORIGIN_RELATIVE | MODE_AUTO_WRAP;

    /* *** Default look'n feel: *** */
    public final static Color DEFAULT_FG = Color.black;
    public final static Color DEFAULT_BG = Color.white;

    public final static int BORDER_X = 2;
    public final static int BORDER_Y = 2;
    public final static int UNDERLINE_OFFSET = 1;

    //public final static int BUFFER_LINES = 500;
    public final static int BUFFER_LINES = 100;
    public final static int INPUT_CHARS = 2048; /* Just a guess */
    /* Bigger buffer seems to improve the throughput (quite naturally)... */

    public final static long BLINK_INTERVAL = 500; // On/off every 500 msecs

    /* Some character constants we need to recognize: */
    public final static char CHAR_NULL = 0x0;
    public final static char CHAR_BELL = 7;
    public final static char CHAR_BS = 8;
    public final static char CHAR_DEL = 127;
    public final static char CHAR_SPACE = ' ';
    public final static char CHAR_TAB = '\t';

    /* Then some definitions & restrictions about screen/window sizes: */
    public final static int MIN_COLS = 20;
    public final static int MIN_ROWS = 10;

    // These two need not be identical; former is the default size of
    // the window in chars, latter is the default size when terminal
    // is put to 'default' vt-size.
    public final static int DEF_COLS = 80, DEF_ROWS = 24;
    public final static int DEF_VT_COLS = 80, DEF_VT_ROWS = 24;
    
    public final static int DEF_TAB_SIZE = 8;
    
    protected CharAttrs mCharAttrs;
    protected CharAttrs mDefaultCharAttrs;
    protected int mDisplayModes = DEFAULT_DISPLAY_FLAGS;

    public final static Dimension minCharSize = new Dimension(MIN_COLS, MIN_ROWS);
    protected int sizeInCharsW, sizeInCharsH;
    protected Dimension pixelSize, usablePixelSize, minPixelSize;
    
    // For debugging:
    //private final static boolean debugDisplay = true;
    private final static boolean debugDisplay = false;
    //private final static boolean dumpCommands = true;
    private final static boolean dumpCommands = false;
    //private final static boolean dumpText = true;
    private final static boolean dumpText = false;
    //private final static boolean doubleDraw = true;
    
    /* Now the buffer space: */
    private DisplayLine[] mLines;

    /* Information about (and related to) the cursor: */
    private int mCurrRow, mCurrCol; // Current position of cursor;
    // row number is absolute regarding the char/attr buffers
    private Integer cursorLock = new Integer(0); // For locking
    //private Color cursorXORColour = new Color(255, 255, 255);
    private Color cursorXORColour = PlatformSpecific.getCursorXORColour();
    // Sigh. cursorXORColour:
    // - Has to be something other than  Color.white, on linux-JDK
    // - Has to be _black_ (new instance or Color.black, no difference)
    //   on w95 JDK.
    // Talk about WORA...
    private boolean cursorDrawn = false;
    private boolean cursorFocused = false;
    private int lastCursorCol = -1, lastCursorRow = -1;
    private int scrollRegionTop = 0, scrollRegionBottom = 0;
    // Unlike mCurrRow, these are relative numbers, and are thus
    // added to mTopRow
    private boolean [] mTabStops; // Contains tab stops; one entry per clumn,
    // true -> tab stop at the column, false -> no tab stop
    
    /* These specify the position of the visible screen (ie. which internal
     * row is the top row, which bottom row)
     */
    public int mTopRow, mBottomRow;
    /* And this defines position of the current screen on the gfx context;
     * screenRow is the row in the buffer that contains first row of visible
     * screen.
     */
    private int screenRow;
    /* Finally, this defines position of the history buffer display: */
    public int topBufferRow;
    
    /* Our font: */
    private String fontName;
    private int fontSize = 12;
    private Font currFont = null, currBoldFont = null, activeFont = null;
    FontMetrics currFontMetrics = null;
    private int fontWidth = 8, fontHeight = 12, fontDescent = 4, fontBase = 8;
    
    /* Our graphics buffer and the scrollback buffer too: */
    private Image screenImage = null;
    private Graphics screenGraphics = null;
    private Image offScreenImage = null;
    private Graphics offScreenGraphics = null;
    
    protected Integer screenLock = new Integer(0); // Needs to be obtained when
    // drawing to the Graphics context of the window...
    protected int updateX1 = -1, updateX2 = 0;
    protected int updateY1 = -1, updateY2 = 0;
    
    /* Then the graphics buffers that contain doubled chars and
     * related data:
     */
    private FontLoader fontLoader;
    public final static int DW_FONT_NORMAL = 0;
    public final static int DW_FONT_BOLD = 1;
    public final static int DH_FONT_NORMAL = 2;
    public final static int DH_FONT_BOLD = 3;
    private Image [][] doubleFonts;
    // Actually, also some special chars...
    private Image [] gfxFonts; // Contains all the symbols in one array

    /* Current/default char/line attribute values: */

    /* Then link(s) to the other objects with which we need to
     * communicate:
     */
    protected JiveTerm master;
    private Thread blinkThread; // The thread that informs us about blinking...
    
    /* Some internal mode flags: */
    public boolean bufferMode = false; // Are we viewing the scrollback buffer?
    private boolean hasFocus = false; // Does the terminal have the focus?
    private boolean blinkedState = false; // If true, blinked chars are 'off' now
    private boolean blinkActive = false; // Whether blink-thread is active

    private boolean mScreenReversed = false; // For reverse-mode
    
    /* Various modes that may be changed... */
    private boolean scrollOnOutput = false; // Buffer-mode off when text is received?
    
    private Font[] mCurrFonts;
    /* Even though there are 4 fonts, font metrics should be identical
     * (as it's fixed width font, same size)
     */
    private FontMetrics mCurrFontMetrics;
    
    public Display(JiveTerm m, String fontName, int fontSize)
    {
	super();
	
	master = m;
	
	sizeInCharsW = DEF_COLS;
	sizeInCharsH = DEF_ROWS;
	
	mCurrFonts = getFonts(fontName, fontSize);
	mDefaultCharAttrs = new CharAttrs(0, DEFAULT_FG, DEFAULT_FG);
	
	mCurrRow = mCurrCol = mTopRow = screenRow = 0;
	mBottomRow = sizeInCharsH - 1;
	
	// We better initialize the buffers first:
	mLines = new DisplayLine[BUFFER_LINES];
	// Let's only create lines for the first screen here:
	for (int i = 0; i <= mBottomRow; ++i) {
	    mLines[i] = new DisplayLine(this, sizeInCharsW);
	}
	
	if (currFont == null || currBoldFont == null) {
	    doWarning("Warning: Couldn't get font-info when calculating layout!");
	}
	
	updateTabStops(sizeInCharsW);
	
	/* Hmmh. We need to initialize, so that we always begin with
	 * the default values:
	 */
	softResetDisplay(false);
	
	/* We really should get the damn fonts, otherwise we can't make
	 * the double width/height ones... 
	 */
	doubleFonts = new Image[4][];
	fontLoader = new FontLoader(this, currFont, currBoldFont);
	for (int i = 0; i < 4; i++) {
	    doubleFonts[i] = new Image[fontLoader.IMAGES_PER_CHARSET];
	}
	new Thread(fontLoader).start();
	blinkThread = new Blinker(this);
	blinkThread.start();
	
	/* These are just sort of first guesses: */
	//pixelSize = new Dimension(sizeInCharsW * fontWidth + 2 * BORDER_X,
	//		      sizeInCharsH * fontHeight + 2 * BORDER_Y);
	// Let's add 1 to the height to compensate for the menus (which
	// seem to eat space at least on linux)
	// Seems like width needs patching too, who knows why...
	pixelSize = new Dimension(sizeInCharsW * fontWidth + 2 * BORDER_X + 4,
				  (sizeInCharsH + 1) * fontHeight + 2 * BORDER_Y);
	minPixelSize = new Dimension(minCharSize.width * fontWidth + 2 * BORDER_X,
				     minCharSize.height * fontHeight + 2 * BORDER_Y);
	usablePixelSize = new Dimension(sizeInCharsW * fontWidth,
					sizeInCharsH * fontHeight);
	addMouseMotionListener(this);
	
	addFocusListener(this);
	setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
    }

    /* *** Event listeners: *** */

    // FocusListener:
    public void focusGained(FocusEvent e)
    {
	hasFocus = true;
	drawCursor(true, true, null);
    }

    public void focusLost(FocusEvent e)
    {
	hasFocus = false;
	drawCursor(false, true, null);
    }

    /* *** Mode changes: *** */

/*************************************

 Functions for changing various modes: 

*************************************/

    // Can query one or more modes; has to have all on to return true:
    public final boolean displayMode(int mode)
    {
	return (mDisplayModes & mode) == mode;
    }

    public final boolean getDisplayModes() { return mDisplayModes; }
    public final boolean getDisplayMode(int mode)
    {
	return (mDisplayModes & mode);
    }

    // Returns true if screen was redrawn
    public boolean setDisplayModes(int modes)
    {
	boolean redrawn = false;
	int oldModes = mDisplayModes;
	int changed = oldModes ^ modes;

	mDisplayModes = modes;
	if ((changed & MODE_CURSOR_VISIBLE) != 0) {
	    // Should it be redrawn?
	}
	if ((changed & MODE_SCREEN_REVERSED) != 0) {
	    redrawn = true;
	    redrawScreen();
	    if (mBufferMode) {
		paintBuffer(master.scrBar.getValue(), true, true);
	    }
	}

	return redrawn;
    }

    // Returns true if screen was redrawn:
    public boolean setDisplayMode(int mode, boolean state)
    {
	
	if (mode) {
	    return setDisplayModes(mDisplayModes | modes);
	} 
	return setDisplayModes(mDisplayModes & ~modes);
    }

    public void clearScrollingRegion()
    {
	/* Should this implicitly reset the scrolling mode (ie. turn
	 * it off)?
	 */
	if (displayMode(MODE_ORIGIN_RELATIVE)) {
	    if (dumpCommands) {
		doWarning("(implicit mode-origin-absolute!)");
	    }
	    setDisplayMode(MODE_ORIGIN_RELATIVE, false);
	}
	scrollRegionTop = 0;
	scrollRegionBottom = sizeInCharsH - 1;
	
	// Should we now actually move the damn cursor or not?
	// Petri Virekoski's docs say 'yes', vttest seems to imply 'no'...
	//setCursorAt(0, mTopRow);
    }

    public void setScrollingRegion(int i, int j)
    {
	if (i >= sizeInCharsH)
	    i = sizeInCharsH - 1;
	if (j >= sizeInCharsH)
	    j = sizeInCharsH - 1;
	
	/* If bottom row smaller than top, let's make bottom
	 * be the defined top. Another way would be to simply
	 * swap them. Not sure if the standards specifies
	 * the behaviour... (probably not?)
	 */
	if (j < i) {
	    if (debugDisplay) {
		doWarning("Warning: setScrollingRegion; top and bottom in wrong order?");
	    }
	    j = i;
	}

	scrollRegionTop = i;
	scrollRegionBottom = j;
	
	// Does this mean an implicit 'origin relative' or not???
	if (!displayMode(MODE_ORIGIN_RELATIVE)) {
	    if (dumpCommands) {
		doWarning("(implicit mode-origin-relative!)");
	    }
	    setDisplayMode(MODE_ORIGIN_RELATIVE, true);
	}
	
	// ... if it does, this check is unnecessary:
	//if (displayMode(MODE_ORIGIN_RELATIVE)) {
	    if (mCurrRow < (mTopRow + scrollRegionTop))
		mCurrRow = mTopRow + scrollRegionTop;
	    else if (mCurrRow > (mTopRow + scrollRegionBottom))
		mCurrRow = mTopRow + scrollRegionBottom;
	//}
	
	/* Should we now actually move the damn cursor or not?
	 * Petri Virekoski's docs say 'yes', vttest seems to imply 'no'...
	 */
	//setCursorAt(0, mTopRow);
    }

    public void resetCharAttrs()
    {
	mCharAttrs = sDefCharAttrs;
    }
    
    /* *** Then the effects that apply to the whole line (row): * ***/

    private final void updateDoubleRow(boolean repaint)
    {
	if (repaint) {
	    redrawRow(mCurrRow, 0);
	}
	if (mCurrCol >= (sizeInCharsW + 1)/ 2) {
		mCurrCol = (sizeInCharsW + 1) / 2 - 1;
	}
    }

    public void setLineEffectDW(boolean repaint)
    {
	mLines[mCurrRow].setDoubleWidth();
	updateDoubleRow(repaint);
    }

    public void setLineEffectDHTop(boolean repaint)
    {
	mLines[mCurrRow].setDoubleTop();
	updateDoubleRow(repaint);
    }

    public void setLineEffectDHBottom(boolean repaint)
    {
	mLines[mCurrRow].setDoubleBottom();
	updateDoubleRow(repaint);
    }

    public void setLineEffectNone(boolean repaint)
    {
	mLines[mCurrRow].setDoubledNone();
	if (repaint) {
	    redrawRow(mCurrRow, 0);
	}
    }

    public void setLineEffectTest()
    {
	for (int r = mTopRow; r <= mBottomRow; r++) {
	    mLines[r].fillLineWith('E', mDefaultCharAttrs, sizeInCharsW);
	    redrawRow(r, 0);
	}
    }

    private void redrawScreen()
    {
      screenRow = 0;
      paintBuffer(mTopRow, false, true);
      if (bufferMode) {
	paintBuffer(master.scrBar.getValue(), true, true);
      }
    }

/********** Simple set/get-functions: ***********/

    /* If necessary, this can be uncommented... Affects the way AWT
     * handles focus (in 1.1 at least)
     */
  //public boolean isFocusTraversable() { return true; }

    protected int getPixelX()
    {
	if (mLines[mCurrRow].isDoubleWidth()) {
	    return (mCurrCol * fontWidth) << 1;
	}
	return (mCurrCol * fontWidth);
    }

    protected int getPixelY(int row)
    {
	return ((screenRow + (row - mTopRow)) % sizeInCharsH) * fontHeight;
    }


    public Dimension getSizeInChars() { return new Dimension(sizeInCharsW, sizeInCharsH); }
    public final boolean doDebugDisplay() { return debugDisplay; }
    public final void toggleScrollOnOutput() { scrollOnOutput = !scrollOnOutput; }
    public final boolean isBufferMode() { return bufferMode; }

    public final void setBufferMode(boolean x, boolean move_scrbar)
    {
	boolean old = bufferMode;
	
	bufferMode = x;
	
	// When exiting buffer mode, we better move the scrollbar too:
	if (old == true && x == false && move_scrbar) {
	    master.moveScrollBarToBottom();
	}
  }

    // Cursor position (relative to the top of screen)
    public int getCursorX() { return mCurrCol; }
    public int getCursorY() { return mCurrRow - mTopRow; }

    /**
     * Method for setting cursor position. X & Y-coordinates are 0 based,
     * relative to the top-left of the screen.
     *
     * @param x Cursor column to set, 0 means leftmost column
     * @param y Cursor row to set, 0 means topmost row
     */
    public final void setCursorPosition(int x, int y) {
	setCursorPosition(p.x, p.y);
    }

    public void setCursorPosition(int x, int y)
    {
	if (dumpCommands) {
	    doWarning("<CRSR ABS @"+x+", "+y+">");
	}
	setCursorAt(x, mTopRow + y);
    }

    public Point getCursorPosition()
    {
	return new Point(mCursorX, mCursorY - mTopRow);
    }

    public void setCursorX(int x)
    {
	if (dumpCommands) {
	    doWarning("<CRSR ABS @"+x+", <current>>");
	}
	setCursorAt(x, mCurrRow);
    }

  public void moveCursor(int x, int y)
  {
      if (dumpCommands) {
	  doWarning("<CRSR REL @"+x+", "+y+">");
      }
      setCursorAt(mCurrCol + x, mCurrRow + y);
  }

    /* Sets the cursor at absolute position col, row, except if some
     * physical restrictions prevent that (can't move cursor out
     * of screen etc)
     */
    public void setCursorAt(int c, int r)
    {
	/* Let's not allow it to be set outside visible window: */
	
	// If we are on the relative origin mode:
	if (displayMode(MODE_ORIGIN_RELATIVE)) {
	    if (r < (mTopRow + scrollRegionTop)) {
		r = mTopRow + scrollRegionTop;
	    }
	    else  if (r > (mTopRow + scrollRegionBottom)) {
		r = mTopRow + scrollRegionBottom;
	    }
	} else {
	    if (r < mTopRow) {
		r = mTopRow;
	    }
	    else if (r >= (mTopRow + sizeInCharsH)) {
	        r = mTopRow + sizeInCharsH - 1;
	    }
	}

	DisplayLine currLine = mLines[r];
	boolean dw = currLine.isDoubleWidth();

	/* We can't access line attribute data before making sure
	 * the row is valid...
	 */
	
	if (c < 0) {
	    c = 0;
	} else if (dw) {
	    if (c >= (sizeInCharsW + 1) / 2) {
		c = (sizeInCharsW + 1) / 2 - 1;
	    }
	} else if (c >= sizeInCharsW) {
	    c = sizeInCharsW - 1;
	}

	int old_c = mCurrCol;
	int old_r = mCurrRow;
	
	mCurrCol = c;
	mCurrRow = r;

	/* Hmmh. This should never happen, actually; linefeed
	 * should be the only way to do it?
	 */
	if (mCurrRow > mBottomRow) {
	    reshapeScrollbar();
	    if (mCurrRow >= mLines.length) {
		adjustCharBuffer();
	    }
	    mBottomRow = mCurrRow;
	}

	/* Did we move past previous end of the line? Need to 'fill in blanks',
	 * so to speak...
	 */
	mCurrLine.checkLength(mCurrRow);

	/* We also need to mark the areas covered by cursor (before
	 * and after move). It might be possible to optimize this
	 * out, but at least this way works ok:
	 */
	if (dw) {
	    if (old_c < mCurrCol) {
		markRangeDirty(old_c * 2, old_r - mTopRow,
			       mCurrCol * 2 + 1, mCurrRow - mTopRow);
	    } else {
		markRangeDirty(mCurrCol * 2, mCurrRow - mTopRow,
			       old_c * 2 + 1, old_r - mTopRow);
	    }
	} else {
	    markRangeDirty(old_c, old_r - mTopRow, mCurrCol, mCurrRow - mTopRow);
	}
    }

/* ********* Functions related to font/symbol handling: ********** */

    public Dimension getCurrFontSize()
    {
	return new Dimension(fontWidth, fontHeight);
    }

    private Font[] getFonts(String fontName, int fontSize)
    {
	mCurrFonts = new Font[] {
	    new Font(Font.PLAIN, fontName, fontSize),
	    new Font(Font.ITALICS, fontName, fontSize),
	    new Font(Font.BOLD, fontName, fontSize),
	    new Font(Font.BOLD | Font.ITALICS, fontName, fontSize)
	};
	try {
	    mCurrFontMetrics = getFontMetrics(currFonts[0]);
	} catch (Exception e) {
	    doError("DEBUG: Error when trying to get font metrics.\n");
	}
	if (mCurrFontMetrics != null) {
	    fontWidth = PlatformSpecific.getActualFontWidth(currFontMetrics.getMaxAdvance());
	    fontHeight = currFontMetrics.getHeight();
	    fontDescent = currFontMetrics.getDescent();
	    fontBase = fontHeight - fontDescent;
	}
    }

    public void setDoubleFont(int which, Image [] dw)
    {
	doubleFonts[which] = dw;
    }
    
    public Image [] getDoubleFont(int which)
    {
	Image img;
	
	return doubleFonts[which];
    }
    
    public void setGfxFont(Image [] dw)
    {
	gfxFonts = dw;
    }
    
    public final Image waitForDoubleFont(Image [] set, int entry)
    {
	while (set[entry] == null) {
	    if (debugDisplay) {
		doWarningLF("DpDebug: Waiting for loading of double font of set #"+set+", entry #"+entry);
	    }
	    try {
		Thread.sleep(100);
	    } catch (InterruptedException ie) {
	    }
	}
	return set[entry];
    }

    public final Image waitForDoubleFont(int set, int entry)
    {
	return waitForDoubleFont(doubleFonts[set], entry); 
    }
    
    public final Image waitForGfxFont(int entry)
    {
	while (gfxFonts[entry] == null) {
	    if (debugDisplay) {
		doWarningLF("DpDebug: Waiting for loading of gfx font #"+entry);
	    }
	    try {
		Thread.sleep(100);
	    } catch (InterruptedException ie) {
	    }
	}
	return gfxFonts[entry];
    }

    /* *** Redraw: *** */
    
    public void update(Graphics g)
    {
	paint(g);
    }

    public Dimension getPreferredSize()
    {
	/*System.err.println("DEBUG: pref size = "+pixelSize.width+" x "
	  +pixelSize.height + " char size = "+sizeInCharsW+" x "
	  +sizeInCharsH +" font size = "+fontWidth + " x " + fontHeight);*/
	
	return pixelSize;
    }

  // Arguments are in characters...
    public Dimension getPreferredSize(int x, int y)
    {
	Dimension d = new Dimension(2 * BORDER_X + sizeInCharsW * fontWidth,
				    2 * BORDER_Y + sizeInCharsH * fontHeight);
	return d;
    }

    public Dimension getMinimumSize()
    {
	return minPixelSize;
    }

    /**
     * This is the method to actually get output printed on screen;
     * given character data in input buffer (consisting of characters
     * and some ascii control chars -- linefeed, carriage return, tabs,
     * backspace, possibly delete -- display will print out text using
     * current attributes.
     *
     * @param chars Array of character data
     * @param start Index of the first character to handle
     * @param len Number of valid characters array contains
     */
    public void output(char[] chars, int start, int len)
    {
	Rectangle coords;

	int i = start;
        len += start;
	for (; i < len; ) {
	    char c = chars[i];

	    // A control char?
	    if ((c & 0x7F) < 32 || c == CHAR_DEL) {
		printChar(c);
		++i;
		continue;
	    }

	    // Nope, just normal stuff:
	    int j = i;
	    for (; j < len; ++j) {
		char x = chars[j];

		if ((c & 0x7F) < 32 || c == CHAR_DEL) {
		    break;
		}
	    }
	    
	  // Text may span multiple lines, thus looping:
	    while (i < j) {
		if (coords == null) {
		    coords = new Rectangle(); // Should be cached?
		}
		i = j - printChars(chars, i, j - i, coords);
	    }
	}

	/* If the user wants to exit the buffer mode when text (including
	 * control codes) is received, we better check that now:
	 */
	if (bufferMode && scrollOnOutput) {
	    setBufferMode(false, true);
	    doPaint();
	}
    }

    /* *** Methods for saving, restoring and resetting display state: *** */
    public void softResetDisplay(boolean repaint)
    {
	boolean repainted = setDisplayModes(DEFAULT_DISPLAY_FLAGS);
	resetCharAttrs();
	if (repaint && !repainted) {
	    redrawScreen();
	}
    }

    public Object getDisplayState()
    {
	DisplayState result = new DisplayState();
	result.mCursorPosition = getCursorPosition();
	result.mDisplayModes = (mDisplayModes & SAVEABLE_DISPLAY_FLAGS);
	result.mCharAttrs = mCharAttrs;
	return result;
    }

    public void setDisplayState(Object o)
    {
	if (o != null && o instanceof DisplayState) {
	    DisplayState state = (DisplayState) o;
	    mDisplayModess &= ~SAVEABLE_DISPLAY_FLAGS; 
	    mDisplayModes |= state.mDisplayModes;
	    mCharAttrs = state.mCharAttrs;
	    setCursorPosition(mCursorPosition());
	}
    }

    public void setColumns132(boolean to132)
    {
	int i = sizeInCharsW;
	int j = sizeInCharsH;
	int newWidth = to132 ? 132 : 80;
	
	// If no need to resize, then no need to resize:
	if (i != newWidth || j != 24) {
	    // Let's "pre-adjust"; redraw might take some time:
	    adjustToCharSize(newWidth, 24);
	    master.resizeToChars(i, j, newWidth, 24);
	}
    }

/* *** Methods for erasing lines and chars: *** */

    /**
     * Method for clearing the consequtive horizontal character positions.
     *
     * @param row Row where to clear character positions
     * @param left Leftmost character position to clear
     * @param right Rightmost - "" -
     */
    private void clearHorizontal(int row, int left, int right)
    {
	int y = getPixelY(row);
	screenGraphics.setColor(mDefCharAttrs.getBackground());
	if (mLines[row].isDoubleWidth()) {
	    screenGraphics.fillRect(left * fontWidth * 2, y,
				    (left - right + 1) * fontWidth * 2,
				    fontHeight);
	} else {
	    screenGraphics.fillRect(left * fontWidth, y,
				    (left - right + 1) * fontWidth,
				    fontHeight);
	}
    }

    /**
     * Method for clearing the lines between specified start and end
     * lines (inclusive)
     *
     * @param topRow Topmost row to clear (absolute row number)
     * @param bottomRow Bottommost character position to clear (abs. row)
     */
    private void clearLines(int topRow, int bottomRow)
    {
	screenGraphics.setColor(defBgroundColor);
	int prevY = -1; 
	/* Because gfx buffer is ring buffer, can't just simply clear
	 * between y-coords of top & bottom. However, it's not rocket
	 * science to figure whether we need to clear one or 2 areas:
	 */
	int topY = getPixelY(topRow);
	int bottomY = getPixelY(bottomRow);
	// Consequtive?
	if (bottomY > topY) {
	    screenGraphics.fillRect(0, topY, usablePixelSize.width,
				bottomY - topY + fontHeight);
	} else {
	    /* In 2 pieces; first piece from top to bottomRow, second
	     * piece from topRow to bottom:
	     */
	    screenGraphics.fillRect(0, 0, usablePixelSize.width,
				bottomY + fontHeight);
	    screenGraphics.fillRect(0, topY, usablePixelSize.width,
				usablePixelSize.height - topY);
	}
    }

    public void eraseLine()
    {	
	DisplayLine currLine = mLines[mCurrRow];
	int oldLen = currLine.getLineLength();

	currLine.eraseLine(true, mCurrCol);
	clearHorizontal(mCurrRow, 0, oldLen);
	markRowDirty(mCurrRow - mTopRow);
    }

    public void eraseNonSelectedLine()
    {
	mLines[mCurrRow].eraseLine(false, mCurrCol);
	redrawRow(mCurrRow, 0);
    }
    
    public void eraseDown()
    {
	/* Down means "from cursor to the end of the screen", ie.
	 * not the whole current row... Weird.
	 */
	for (int i = mCurrRow + 1; i <= mBottomRow; i++) {
	    DisplayLine currLine = mLines[i];
	    int oldLen = currLine.getLineLength();
	    
	    currLine.eraseLine(true, -1);
	    clearHorizontal(i, 0, oldLen);
	    /* Should line attrs be cleared?
	     * According to VT220 Ref. Manual, _yes_ for all complete
	     * lines (not the partially removed line):
	     */
	    currLine.resetLineAttrs();
	}
	markRowsDirty(mCurrRow - mTopRow, sizeInCharsH - 1);
	// Also need to do ERASE_EOL
	eraseEOL();
    }

    public void eraseNonSelectedDown()
    {
	for (int i = mCurrRow + 1; i <= mBottomRow; i++) {
	    mLines[i].eraseLine(false, -1);
	    redrawRow(i, 0);
	}
	eraseNonSelectedEOL();
    }
    
    public void eraseEOL()
    {	      
	DisplayLine currLine = mLines[mCurrRow];
	int oldLength = currLine.getLineLength();
	
	currLine.eraseEOL(true, mCurrCol);
	clearHorizontal(mCurrRow, mCurrCol, oldLength);
	markRowDirty(mCurrRow - mTopRow);
    }

    public void eraseNonSelectedEOL()
    {
	mLines[mCurrRow].eraseEOL(true, mCurrCol);
	redrawRow(mCurrRow, mCurrCol);
    }

    public void eraseSOL()
    {
	mLines[mCurrRow].eraseSOL(true, mCurrCol);
	clearHorizontal(mCurrRow, 0, mCurrCol);
	markRowDirty(mTopRow - mCurrRow);
    }

    public void eraseNonSelectedSOL()
    {
	mLines[mCurrRow].eraseSOL(false, mCurrCol);
	redrawRow(mCurrRow, 0);
    }

    /* Interesting. According to the specs, "UP" here means
     * actually "erase from the start of the screen to cursor,
     * inclusive", ie. not the whole current row! (erase from cursor
     * to top-left or such)
     */
    public void eraseUp()
    {
	for (i = mTopRow; i < mCurrRow; i++) {
	    DisplayLine currLine = mLines[i];
	    int oldLen = currLine.getLineLength();
	    
	    currLine.eraseLine(true, -1);
	    clearHorizontal(i, 0, oldLen);
	    /* Should line attrs be cleared?
	     * According to VT220 Ref. Manual, _yes_ for all complete
	     * lines (not the partially removed line):
	     */
	    currLine.resetLineAttrs();
	}
	markRowsDirty(0, mTopRow - mCurrRow);
	eraseSOL();
    }

    public void eraseNonSelectedUp()
    {
	for (i = mTopRow; i < mCurrRow; i++) {
	    mLines[i].eraseLine(false, -1);
	    redrawRow(i, 0);
	}
	eraseNonSelectedSOL();
    }

    public void eraseScreen()
    {
	for (i = mTopRow; i <= mBottomRow; i++) {
	    mLines[i].eraseLine(true, -1);
	    currLine.resetLineAttrs();
	}
	clearLines(mTopRow, mBottomRow);
	//mCurrRow = mTopRow; // Cursor does NOT move
	markRowsDirty(0, sizeInCharsH - 1);
    }

    public void eraseNonSelectedScreen()
    {
	for (i = mTopRow; i <= mBottomRow; i++) {
	    mLines[i].eraseLine(false, -1);
	    currLine.resetLineAttrs();
	    redrawRow(i, 0);
	}
    }

    /* Erase chars erases N chars to the _right_, from the
     * current position, including the curr position.
     * 
     * Note: doesn't check protection status of characters
     * (and why is that? No vt-command for such thing?)
     */
    public void eraseChars(int count)
    {
	int oldLen = mLines[i].getLineLength();

	display.eraseChars(mCurrCol, count);
	clearHorizontal(i, mCurrCol, oldLen);
	markRowDirty(mTopRow - mCurrRow);
    }

    /* *** Methods for deleting things, opposite of inserting: *** */

    public void deleteChars(int count)
    {
	if (deleteCharsRight(mCurrCol, mCurrRow, count)) {
	    redrawRow(mCurrRow, mCurrCol);
	}
    }

    public void deleteLines(int count)
    {
	int max;

	if (displayMode(MODE_ORIGIN_RELATIVE)) {
	    max = (mTopRow + scrollRegionBottom) - mCurrRow + 1;
	} else {
	    max = mBottomRow - mCurrRow + 1;
	}
	if (count > max) {
	    count = max;
	} else if (j < max) {
	    System.arraycopy(mLines, mCurrRow + count, mLines,
			     mCurrRow, max - count);
	}
	
	/* We need to redraw the lines that were not
	 * deleted but that are moved due to the deletion:
	 */
	int i = 0;
	for (; i < (max - count); ++i) {
	    redrawRow(mCurrRow + i, 0);
	}
	
	clearLines(i, max - 1);
	markRowsDirty(mCurrRow - mTopRow, sizeInCharsH - 1);
    }

    public void insertLines(int count)
    {
	int max;

	if (displayMode(MODE_ORIGIN_RELATIVE)) {
	    max = (mTopRow + scrollRegionBottom) - mCurrRow + 1; 
	} else {
	    max = mBottomRow - mCurrRow + 1; 
	}
	if (count > max) {
	    /* Inserting enough lines to move all existing lines below
	     * outside page bottom?
	     */
	    count = max;
	} else if (count < max) {
	    System.arraycopy(mLines, mCurrRow, mLines,
			     mCurrRow+count, max - count);
	}

	clearLines(mCurrRow, mCurrRow + count - 1);

	for (int i = count; i < max; i++) {
	    redrawRow(mCurrRow + i, 0);
	}
	markRowsDirty(mCurrRow - mTopRow, sizeInCharsH - 1);
    }

    /* *** Indexing: *** */

   /*** 'Index down' is just a fancy name for 'move cursor down by
	one without affecting the column; scroll if need be':
   ****/

    public void indexDown(int count)
    {
	if (displayMode(MODE_ORIGIN_RELATIVE)) {
	    if ((mCurrRow + count) <= (mTopRow + scrollRegionBottom)) {
		setCursorAt(mCurrCol, mCurrRow + count);
		return;
	    } else {
		count -= (mTopRow + scrollRegionBottom - mCurrRow);
		scrollUpLines(mTopRow + scrollRegionTop,
			      mTopRow + scrollRegionBottom, count);
		setCursorAt(mCurrCol, mTopRow + scrollRegionBottom);
	    }
	} else {
	    if ((mCurrRow + count) <= mBottomRow) {
		setCursorAt(mCurrCol, mCurrRow + count);
		return;
	    } else {
		count -= (mBottomRow - mCurrRow);
		scrollUpLines(mTopRow, mBottomRow, count);
		setCursorAt(mCurrCol, mBottomRow);
	    }
	}
	if (debugDisplay) {
	    doWarningLF("DEBUG: CODE_INDEX_DOWN; indexing past the bottom row, thus scrolling "+count+" lines.");
	}
    }

    public void indexUp(int count)
    {
	if (displayMode(MODE_ORIGIN_RELATIVE)) {
	    if ((mCurrRow - count) >= (mTopRow + scrollRegionTop)) {
		setCursorAt(mCurrCol, mCurrRow - count);
		return;
	    } else {
		count -= (mCurrRow - (mTopRow + scrollRegionTop));
		scrollDownLines(mTopRow + scrollRegionTop,
				mTopRow + scrollRegionBottom, count);
		setCursorAt(mCurrCol, mTopRow + scrollRegionTop);
	    }
	} else {
	    if ((mCurrRow - count) >= mTopRow) {
		setCursorAt(mCurrCol, mCurrRow - count);
		return;
	    } else {
		count -= (mCurrRow - mTopRow);
		scrollDownLines(mTopRow, mBottomRow, count);
		setCursorAt(mCurrCol, mTopRow);
	    }
	}
	if (debugDisplay) {
	    doWarningLF("DEBUG: CODE_INDEX_UP; indexing past the top row, thus scrolling "+count+" lines.");
	}
    }

    public void indexLeft(int count)
    {
	if ((mCurrCol - count) >= 0) {
	    setCursorAt(mCurrCol - count, mCurrRow);
	    return;
	}
	count -= mCurrRow;
	scrollScreenRight(count);
	setCursorAt(0, mCurrRow);
	if (debugDisplay) {
	    doWarningLF("DEBUG: CODE_INDEX_UP; indexing past the top row, thus scrolling "+count+" lines.");
	}
    }

    public void indexRight(int count)
    {
	if ((mCurrCol + count) < sizeInCharsW) {
	    setCursorAt(mCurrCol + count, mCurrRow);
	    return;
	}
	count -= (sizeInCharsW - mCurrCol);
	scrollScreenLeft(count);
	setCursorAt(0, mCurrRow);
    }

    // Wonder how should bell be indicated... Beep, flash?
    public void printBell()
    {
	if (dumpText) {
	    doWarningLF("DEBUG: <Bell>"); 
	} 
	if (allowBell) {
	    Toolkit.getDefaultToolkit().beep();
	}
    }

  synchronized public void printCarriageReturn()
  {
      // Nothing much to do; we'll just 'rewind back' to first column:
      mCurrCol = 0;
      if (dumpText) {
	  doWarning("<CR>");
      }
  }

  synchronized public void printLinefeed(boolean auto)
  {
    int i;
    int old_row = mCurrRow;

    mCurrCol = 0;
    mCurrRow++;

    // If the origin mode is relative, we need to use the scrolling
    // region:
    if (displayMode(MODE_ORIGIN_RELATIVE)) {
      if (mCurrRow > (mTopRow + scrollRegionBottom)) {
	mCurrRow = mTopRow + scrollRegionBottom;
	if (dumpText) {
	  if (auto)
	    doWarning("<LF/A, scr-reg+(scroll)>");
	  else
	    doWarning("<LF, scr-reg+(scroll)>");
	}
	/* For some reason it seems we are NOT to scroll. Perhaps scrolling
	 * region really prevents that then:
	 */
	scrollUpLines(mTopRow + scrollRegionTop, mTopRow + scrollRegionBottom, 1);
      } else {
	if (dumpText) {
	  if (auto)
	    doWarning("<LF/A, scr-reg>");
	  else
	    doWarning("<LF, scr-reg>");
	}
      }

      markRowsDirty(old_row - mTopRow, mCurrRow - mTopRow);
      return;
    }

    /* We'll only scroll screen up if we move from the last row to a
     * new one
     */
    if (mCurrRow > mBottomRow) {
      mBottomRow = mCurrRow = mBottomRow + 1;

      if ((screenRow += 1) >= sizeInCharsH) {
	screenRow %= sizeInCharsH;
      }
      mTopRow++;
      screenGraphics.setColor(defBgroundColor);
      screenGraphics.fillRect(0, getPixelY(mCurrRow),
			      usablePixelSize.width, fontHeight);
      markWholeAreaDirty();
    }

    /* This call probably also updates currTopRow! */
    reshapeScrollbar();

    /* We may have to scroll the buffer up, though... */
    if (mCurrRow >= charBuffer.length) {
      adjustCharBuffer();
    } else {
    }

    if (dumpText) {
      if (auto)
	doWarning("<LF/A->" +mCurrCol+ ","+(mCurrRow-mTopRow)+">");
      else
	doWarning("<LF->" +mCurrCol+ ","+(mCurrRow-mTopRow)+">");
    }

    if (displayMode(MODE_SMOOTH_SCROLL)) {
      markWholeAreaClean();
      repaint();
      try { Thread.sleep(10); } catch (InterruptedException ie) { }
    } else {
      markRowsDirty(old_row - mTopRow, mCurrRow - mTopRow);
    }
  }

  /* *** Tab-handling: *** */

  public void printTab()
  {
      /* Actually, TAB just moves the cursor to the next tab stop
       * (if no next tab stop, no move?)
       */
      int old = mCurrCol;
      for (int i = mCurrCol + 1; i < sizeInCharsW; i++) {
	  if (mTabStops[i]) {
	      /* We need to call setCursorAt() to make sure that the linelength
	       * is appropriately updated, and possible old trash deleted
	       */
	      setCursorAt(i, mCurrRow);
	      break;
	  }
      }

      /* Old cursor place (and the next position) are marked dirty
       * already but in addition we need to:
       */
      markRangeDirty(old, mCurrRow - mTopRow, mCurrCol, mCurrRow - mTopRow);
  }
  
  public void addTab()
  {
      mTabStops[mCurrCol] = true;
  }

  public void removeTab()
  {
      mTabStops[mCurrCol] = false;
  }

  public void removeAllTabs()
  {
      for (int i = mTabStops.length; --i >= 0; ) {
	  mTabStops[i] = false;
      }
  }

  /* This function either initializes tab stop settings (if called for
   * first time), or updates tab stop array size.
   */
  public void
  updateTabStops(int newSize)
  {
      boolean[] oldTabs = mTabStops;
      mTabStops = new boolean[newSize];
      if (oldTabs == null) {
	  for (int i = 1; i < newSize; ++i) {
	      mTabStops[i] = ((i % DEFAULT_TAB_SIZE) == 0);
	  }
      } else {
	  int i = oldTabs.length;

	  if (i > newSize) {
	      i = newSize;
	  }

	  System.arraycopy(oldTabs, 0, mTabStops, 0, i);
      }
  }

  /* This function is called when the line buffer is full
   * and we need to scroll it upwards:
   */
  private void adjustCharBuffer()
  {
    int i = mCurrRow - charBuffer.length + 1;
    
    mCurrRow -= i;
    mTopRow -= i;
    mBottomRow -= i;
    i = charBuffer.length - 1;

    /* It might be more effective to move more than one line at a time,
     * but on the other hand, that would mean that the scroll bar
     * would have to be adjusted constantly.
     */
    DisplayLine l = mLines[0];
    System.arraycopy(mLines, 1, mLines, 0, i);
    mLines[i] = l; // This is just 'recycling the buffer'
    l.clearLine(mDefaultCharAttrs);

    /* Although the buffer position should not change, the actual line
     * number (in buffer) does change.
     */
    if (bufferMode) {
      topBufferRow -= 1;

      /* However, if we were at the beginning of the buffer, we
       * actually may need to scroll..
       */
      if (topBufferRow < 0) {
	paintBuffer(0, true, false);
	repaint();
      }
    }
  }

  /* This function is called to make sure the lines of the
   * char & attr arrays are of the proper length:
   */
  public synchronized void adjustBuffers(int newSize)
  {
      int last = mLines.length;
      for (int i = 0; i < last; ++i) {
	  if (mLines[i] != null) {
	      mLines[i].resize(newSize);
	  }
      }
  }

  /* We don't have to worry about the 'weird' chars here I hope: */
  /* Except, of course, delete/backspace and tab ?
   */
  synchronized public void printChar(byte c)
  {
    if (dumpText) {
      doWarning("'#"+((int) c & 0xFF)+"'[" +mCurrCol
+ ","+(mCurrRow-mTopRow)+"]");
    }

    // This isn't optimal, as not all characters are drawn, and OTOH,
    // some chars make larger areas dirty. Nevertheless:
    markRangeDirty(mCurrCol, mCurrRow - mTopRow, mCurrCol + 1, mCurrRow - mTopRow);

    switch (c) {

      // DEL removes the character under the cursor...
      // Actually, DEL should _never_ be sent on a vt100-connection,
      // according to the specs...
    case CHAR_DEL:

      if (debugDisplay) {
	doWarningLF("Warning(D): DEL gotten, discarded!");
      }
      return;

      // Whereas Backspace moves the cursor one to the left...
    case CHAR_BS:

      //doWarningLF("DEBUG: BS, curr col was "+mCurrCol);

      // Backspace doesn't delete anything:
      if (mCurrCol > 0) {
	--mCurrCol;
	// The position itself was already marked dirty, now we need to
	// mark the previous position dirty...
	markPositionDirty(mCurrCol, mCurrRow - mTopRow);
      }
      return;

    case CHAR_TAB:

	printTab();
	break;

      // Jingle bells, schmingle bells. Discard 'em I say:
    case CHAR_BELL:

	printBell();
	return;

    default:

      // What to do with the control characters (Ascii < 32)?
      // At the moment, let's just skip them:
      if (c >= CHAR_NULL && c < CHAR_SPACE) {

	if (debugDisplay) {
	  doWarning("DEBUG: ctrl-char "+c+" gotten, skipping.");
	}
	return;
      }
    }
  }

  char [] oneCharArray = new char[1];

  /* Note that we won't have any "funny" characters to print. */
  protected int
  printChars(char [] c, int first, int len, Rectangle coords)
  {
      // We have to handle a pending linefeed?
      if (displayMode(MODE_AUTO_WRAP) && mCurrCol >= sizeInCharsW) {
	  doLinefeed(true);
      }
      
      DisplayLine currLine = mLines[mCurrRow];
      
      if (dumpText) {
	  doWarning("\""+new String(c, first, len)+"\"[" +mCurrCol
			   + ","+(mCurrRow-mTopRow)+" #"+ len+"]");
      }
      
      if (displayMode(MODE_INSERT_MODE)) {
	  insertChars(mCurrCol, len, sizeInCharsW);
      }
      
      // As the first thing, let's put the stuff in the arrays...
      int left = mCurrLine.addChars(c, first, len, mCharAttrs,
				mCurrCol, sizeInCharsW,
				    displayMode(MODE_AUTO_WRAP));
      len -= left;
      if (mCharAttrs.isBlinking()) {
	  if (!blinkActive) {
	      blinkThread.interrupt();
	  }
      }
      
      /* We have to make sure we have an image to update: */
      if (screenImage == null) {
	  getImages();
      }

      int origCol = mCurrCol;
      mCurrCol += len;
      
      if (mCurrCol >= sizeInCharsW) {
	  if (displayMode(MODE_AUTO_WRAP)) {
	      /* Let's not yet linewrap; we need to move cursor 'outside'
	       * the right border though, to mark a pending LF (which 
	       * will be done next time printChars() is called)
	       */
	      mCurrCol = sizeInCharsW;
	  } else {
	      /* If no autowrap cursor stays there so all chars will just
	       * be printed "on top of each other" at the end of the
	       * line:
	       */
	      mCurrCol = sizeInCharsW - 1;
	  }
      }

      /* Ok; internal data updated, now need to redraw things:
       */

      /* If insert mode was used, we need to do more redrawing; except
       * if it's a double-size line in which case it'll be redrawn
       * completely no matter what, later on:
       */
      if (displayMode(MODE_INSERT_MODE) && !currLine.isDoubleWidth()) {
	  /* When using doubled fonts, redrawing is costy... and it will
	   * be done at a later point if necessary anyway.
	   */
	  redrawRow(mCurrRow, origCol);
	  return len;
      }

      if (mCurrRow < mTopRow || mCurrRow > mBottomRow) {
	  // Out-of-screen print... Shouldn't happen:
	  doWarningLF("DEBUG: out-of-screen print: "+
			     "TopRow = "+mTopRow+", BottomRow = "+mBottomRow+
			     ", mCurrRow = "+mCurrRow+", screenRow = "+screenRow+".");
	  return len;
      }

      /* Doubled lines have their own drawing: */
      if (currLine.isDoubleWidth()) {
	  int last;
	  
	  if (displayMode(MODE_INSERT_MODE)) {
	      last = mCurrLine.getLineLength();
	  } else {
	      last = mCurrCol;
	  }

	  // Double-width -> can only print 50% of the max chars
	  if (last > (sizeInCharsW / 2)) {
	      last = sizeInCharsW / 2;
	  }
	  
	  Graphics double_g = screenGraphics.create(0, queryPixelY(currRow),
			       usablePixelSize.width, fontHeight);
	  
	  redrawDoubleRow(double_g, currLine, 0, last, false);
	  double_g.dispose();
	  return len;
      }

      coords.x = getPixelX();
      coords y = getPixelY(mCurrRow);
      coords.width = len * fontWidth;
      coords.height = fontHeight;
      mCurrAttrs.paintText(c, first, len, coords, fontBase,
			   !blinkedState, mScreenReversed);
      markRangeDirty(orig_col, mCurrRow - mTopRow, mCurrCol, mCurrRow - mTopRow);
      return len;
  }
  
  private void adjustToCharSize(int cx, int cy)
  {
    int dx = cx - sizeInCharsW;
    int dy = cy - sizeInCharsH;

    // Certain things only need to be updated if the char size did change:
    if (dx != 0 || dy != 0) {
	sizeInCharsW = cx;
	sizeInCharsH = cy;
	if (sizeInCharsW > mTabStops.length) {
	    updateTabStops(sizeInCharsW);
	}
	// First one should seldom happen, latter may happen:
	if (scrollRegionTop >= sizeInCharsH) {
	    scrollRegionTop = sizeInCharsH - 1;
	}
	if (scrollRegionBottom >= sizeInCharsH) {
	    scrollRegionBottom = sizeInCharsH - 1;
	}
	usablePixelSize = new Dimension(sizeInCharsW * fontWidth,
					sizeInCharsH * fontHeight);
	reshapeScrollbar();
	
	/* Too bad, we have to create a new offscreen image as well... */
	getImages();
	
	/* Then we probably better update the window... */
	if (dy > 0) {
	    if ((mBottomRow - mTopRow) < sizeInCharsH) {
		mTopRow -= dy;
		if (mTopRow < 0)
		    mTopRow = 0;
	    }
	} else if (dy < 0) {
	    mTopRow -= dy;
	    if (mTopRow > mBottomRow) {
		mTopRow = mBottomRow;
	    }
	}
	
      // Cursor better still be inside the visible screen:
	if (mCurrRow < mTopRow) {
	    mCurrRow = mTopRow;
	} else if (mCurrRow > mBottomRow) {
	    mCurrRow = mBottomRow;
	}
	if (mCurrCol >= sizeInCharsW) {
	    mCurrCol = sizeInCharsW - 1;
	}
	// And char/attr buffers may need to be adjusted as well:
	adjustBuffers(cx);
	
	/* We may as well set the top row to be the top row of buffer
	 * before redrawing everything:
	 */
	screenRow = 0;
	paintBuffer(mTopRow, false, true);
	if (bufferMode) {
	    paintBuffer(master.scrBar.getValue(), true, true);
	}
	
	/* And last but not least, perhaps we better inform the server too.
	 * (if we have a telnet-connection, at least)
	 */
	master.setWindowSizeInChars(sizeInCharsW, sizeInCharsH, false);
    }
    
    /* In any case, we have to return the focus... */
    if (!master.isConnected()) {
	master.updateFocus();
    }
  }

  /* We should allow for enlargening/smallening of the window... */
  public void setBounds(int loc_x, int loc_y, int x, int y)
  {
      int old_x, old_y, dx, dy;
      int i;

/* These are just to make sure we never go below certain minimum sizes... */
      if (x <= 0 || y <= 0) {
	return;
      }
      
      old_x = sizeInCharsW;
      old_y = sizeInCharsH;
      
      /* This is an important thing to do: */
      super.setBounds(loc_x, loc_y, x, y);

      pixelSize = new Dimension(x, y);
      /* No need to force some minimum size... doubt it'd help. */
      minPixelSize = new Dimension(minCharSize.width * fontWidth,
				   minCharSize.height * fontHeight);

      /* We have to patch the size to make space for borders! */
      adjustToCharSize((pixelSize.width - 2 * BORDER_X) / fontWidth,
		       (pixelSize.height - 2 * BORDER_Y)/ fontHeight);
  }
  
  void reshapeScrollbar()
  {
      int value, vis, min, max;
      
      vis = sizeInCharsH;
      min = 0;
      max = mBottomRow + 1;
      if (max < min) {
	  max = min;
      }
    
      if (bufferMode == true) {
	  value = (1000 * master.scrBar.getValue()) / master.scrBar.getMaximum()
	      * max / 1000;
      } else {
	  value = max;
      }
      
      if (value < min) {
	  value = min;
      } else if (value > max) {
	  value = max;
      }
    
      master.scrBar.setValues(value, vis, min, max);
  }

  /* This version is called from paint(), and specifically:
   * - It never needs to clear the old cursor position
   * - It needs to restore the drawing mode
   */
  private final void
  drawCursorFromPaint(Graphics g)
  {
      synchronized (cursorLock) {
	  cursorFocused = hasFocus;
	  lastCursorCol = mCurrCol;
	  lastCursorRow = mCurrRow;
	  cursorDrawn = modeCursorOn;
	  // No need to clear the old cursor, thanks to complete redraw:
	  if (modeCursorOn) {
	      g.setXORMode(cursorXORColour);
	      if (hasFocus) {
		  g.fillRect(getPixelX(), (mCurrRow - mTopRow) * fontHeight,
			     fontWidth, fontHeight);
	      } else {
		  g.drawRect(getPixelX(), (mCurrRow - mTopRow) * fontHeight,
			     fontWidth - 1, fontHeight - 1);
	      }
	  }
      }
      g.setPaintMode();
  }

  private final void
  drawBlinkingLine(Graphics g, int rel_row, Image img, int offset, int width)
  {
    short [] attrs;
    int j, end, curr_attrs;
    int fw = fontWidth;
    int max = lineLengths[mTopRow + rel_row];
    int img_offset = offset - (rel_row * fontHeight);

    if ((lineAttrs[mTopRow + rel_row] & (LINE_DOUBLE_W | LINE_DOUBLE_H_TOP | 
					LINE_DOUBLE_H_BOTTOM)) != 0) {
	fw *= 2;
	// No need to check out the whole line for doubled lines
	// (well; there probably won't be more in any case but...)
	if (max >= (sizeInCharsW / 2))
	  max = sizeInCharsW;
    }

    attrs = attrBuffer[mTopRow + rel_row];

    // First we need to draw the whole line:
    //g.setColor(mCurrColors[rel_row % 8]);
    //g.fillRect(0, rel_row * fontHeight, usablePixelSize.width,fontHeight);
    Graphics g2 = g.create(0, rel_row * fontHeight, width, fontHeight);
    Graphics g3 = null;
    //g2.drawImage(img, 0, img_offset, this);

    j = 0;
    while (j < max) {
      // First we'll draw the non-blinking parts:
      if ((attrs[j] & FX_BLINK) == 0) {
	for (end = j + 1; end < max && (attrs[end] & FX_BLINK) == 0; end++)
	  ;
	if (end >= max)
	  g3 = g2.create(j * fw, 0, width - (j * fw), fontHeight);
	else
	  g3 = g2.create(j * fw, 0, (end - j) * fw, fontHeight);
	g3.drawImage(img, - (j * fw), img_offset, this);
	if (end >= max)
	  break;
	j = end;
      }
      
      // Then the blinking parts:
      curr_attrs = attrs[j];
      for (end = j + 1;end<max && attrs[end] == curr_attrs; end++)
	;
      // Now we know the area to clear:
      if ((curr_attrs & FX_REVERSED) != 0)
	g2.setColor(mCurrColors[curr_attrs & 0x0007]);
      else
	g2.setColor(mCurrColors[(curr_attrs & 0x0070) >> 4]);
      
      g2.fillRect(j * fw, 0, (end - j) * fw, fontHeight);
      j = end;
    }

    g2.dispose();
    if (g3 != null)
      g3.dispose();
  }

  public void
  drawCursor(boolean now_focus, boolean draw, Graphics g)
  {
    // We need exclusive access to the gfx context...
    synchronized (screenLock) {

      if (g == null)
	g = getGraphics();

    // Cursor is only drawn in the non-buffer mode, and only if it
    // hasn't been hidden
      if (bufferMode)
	return;

      if (g == null)
	return;

      synchronized (cursorLock) {

	int w = fontWidth;
	int h = fontHeight;
	int x, y;

	if ((lineAttrs[mCurrRow] & (LINE_DOUBLE_W | LINE_DOUBLE_H_TOP
				   | LINE_DOUBLE_H_BOTTOM)) != 0) {
	  w *= 2;
	}

	g.setXORMode(cursorXORColour);
	if (cursorDrawn) {
	  // If it was already drawn correctly, there's no need to redraw:
	  // (not sure if this should ever happen?)
	  if (cursorFocused == hasFocus && lastCursorCol == mCurrCol
	      && lastCursorRow == mCurrRow)
	    return;

	  x = BORDER_X + (lastCursorCol * fontWidth);
	  y = BORDER_Y + (lastCursorRow - mTopRow) * fontHeight;
	  // If it was the other shape, let's remove the old image first:
	  if (cursorFocused) {
	    g.fillRect(x, y, w, h);
	  } else {
	    g.drawRect(x, y, w-1, h-1);
	  }

	}

	cursorFocused = now_focus;

	// In case cursor should be invisible:
	if (!modeCursorOn) {
	  cursorDrawn = false;
	  return;
	}

	if (draw) {
	  lastCursorCol = mCurrCol;
	  lastCursorRow = mCurrRow;
	  x = BORDER_X + (mCurrCol * fontWidth);
	  y = BORDER_Y + (mCurrRow - mTopRow) * fontHeight;

	  if (now_focus) {
	    g.fillRect(x, y, w, h);
	  } else {
	    g.drawRect(x, y, w-1, h-1);
	  }
	}

	cursorDrawn = draw;
      }
    }
  }

/***************************************

 Functions for inserting/deleting chars:

***************************************/

    public void insertChars(int count)
    {
	if (mLines[mCurrRow].insertChars(mCurrCol, count)) {
	    redrawRow(mCurrRow, mCurrCol, sizeInCharsW);
	}
	//setCursorAt(mCurrCol + j, mCurrRow);
    }


  /* Returns true to indicate that the row
   * should be redrawn.
   * Deletes nr characters _left_ from 'col', including 'col' itself!
   */
  public boolean
  deleteCharsLeft(int col, int row, int nr)
  {
  int i, j;

    // Can't delete chars past the left border... 
    if (nr > (col + 1))
      nr = col + 1;

    byte [] chars = charBuffer[row];
    short [] attrs = attrBuffer[row];

    // We can delete max 'i' characters:
    i = lineLengths[row] - col ;
 
    System.arraycopy(chars, col + 1, chars, col + 1 - nr,
		     sizeInCharsW - (col + 1));
    System.arraycopy(attrs, col + 1, attrs, col + 1 - nr,
		     sizeInCharsW - (col + 1));
    
    j = sizeInCharsW - (col + 1);
    for (i = sizeInCharsW; --i >= j; ) {
      chars[i] = defChar;
      attrs[i] = defAttrs;
    }
    return true;
  }

  /* Similar to the above except removes characters to the right from
   * the specified column, including the column:
   */
  public boolean
  deleteCharsRight(int col, int row, int nr)
  {
  int i, max;
              
    // We can delete max 'i' characters:
    i = lineLengths[row] - col;

    // A trivial case: no chars to delete (cursor is at the end
    // of line, not over any character):
    if (i == 0) {
      return false;

      // If we are to delete the rest of the line, it's easy
      // as well
    } else if (nr >= i) {
      lineLengths[row] = col;
      byte [] chars = charBuffer[row];
      short [] attrs = attrBuffer[row];
      // To play it safe, we could delete the stuff:
      /*for (nr = 0; nr < i; nr++) {
        chars[nr] = defChar;
        attrs[nr] = defAttrs;
      }*/
      // In the default case we need to move stuff a bit:
    } else {
      
      byte [] chars = charBuffer[row];
      short [] attrs = attrBuffer[row];         
      
      // max -> max nr of chars we need to copy:
      max = sizeInCharsW - col - nr;
      System.arraycopy(chars, sizeInCharsW - max,
                       chars, col, max);
      System.arraycopy(attrs, sizeInCharsW - max,
                       attrs, col, max);
      // We could nullify the stuff, if we wanted to be cautious:
      /*for (i = col + max; i < sizeInCharsW; i++) {
        chars[i] = defChar;
        attrs[i] = defAttrs;
      }*/
      lineLengths[row] -= nr;
    }

    return true;
 }

  /* *** Methods for scrolling (whole or partial depending on scroll area)
   *     up, down, left or right
   *** */

  public void
  scrollUpLines(int top, int bottom, int times)
  {
  int i;

      if (times < 1) // Sanity check...
	return;

      if (times <= (bottom - top)) {
	System.arraycopy(mLines, top + times, mLines, top,
			 (bottom - top + 1) - times);
      }
      int last = bottom - times + 1;
      for (i = top; i < last; i++) {
	redrawRow(i, 0);
      }

      clearLines(i, bottom);

      if (displayMode(MODE_SMOOTH_SCROLL)) {
	markWholeAreaClean();
	repaint();
	try { Thread.sleep(10); } catch (InterruptedException ie) { }
      } else {
	markRowsDirty(top - mTopRow, bottom - mTopRow);
      }
  }

  public void
  scrollDownLines(int top, int bottom, int times)
  {
      int i;
      
      if (times < 1) { // Sanity check...
	  return;
      }

      if (times <= (bottom - top)) {
	System.arraycopy(mLines, top, mLines, top + times,
			 (bottom - top + 1) - times);
      } else {
	  times = bottom - top + 1;
      }

      clearLines(top, top + times - 1);
      screenGraphics.setColor(defBgroundColor);
      for (int i = top+times; i <= bottom; ++i) {
	  redrawRow(i, 0);
      }

      if (hasSmoothScroll()) {
	markWholeAreaClean();
	repaint();
	try { Thread.sleep(10); } catch (InterruptedException ie) { }
      } else {
	markRowsDirty(top - mTopRow, bottom - mTopRow);
      }
  }

    public void scrollScreenLeft(int count)
    {
      for (int i = topRow; i <= bottomRow; ++i) {
	  mLines[i].scrollLeft(count);
	  redrawRow(i, 0);
      }
    }

    public void scrollScreenRight(int count)
    {
      for (int i = topRow; i <= bottomRow; ++i) {
	  mLines[i].scrollRight(count, sizeInCharsW);
	  redrawRow(i, 0);
      }
    }

/******************************

 The drawing-related functions:

*******************************/

  int skip_repaints = 0; // A kludge, thanks to weirdness with FontLoader
  int skipped_repaints = 0; // (at least on Linux + X)

  public void addSkipRepaint(int x) {
    skip_repaints += x;
    skipped_repaints = 0;
  }

  public void clearSkipRepaint() {
    skip_repaints = 0;
    // To make sure we won't miss any 'real' repaint, let's do one
    // repaint() just in case:
    if (skipped_repaints > 0)
      repaint();
  }

  // These functions mark parts of the window as 'dirty'; dirty areas
  // will be redrawn later on, by calling doPaint()
  private final void
  markPositionDirty(int x, int y)
  {
    if (updateX1 < 0) {
      updateX1 = x;
      updateY1 = y;
      updateX2 = x;
      updateY2 = y;
    } else {
      if (x < updateX1)
	updateX1 = x;
      if (y < updateY1)
	updateY1 = y;
      if (x > updateX2)
	updateX2 = x;
      if (y > updateY2)
	updateY2 = y;
    }
  }

  private final void
  markRangeDirty(int x1, int y1, int x2, int y2)
  {
  int i;

    if (x1 > x2) {
      i = x1;
      x1 = x2;
      x2 = i;
    }
    if (y1 > y2) {
      i = y1;
      y1 = y2;
      y2 = i;
    }

    if (updateX1 < 0) {
      updateX1 = x1;
      updateY1 = y1;
      updateX2 = x2;
      updateY2 = y2;
    } else {
      if (x1 < updateX1)
	updateX1 = x1;
      if (y1 < updateY1)
	updateY1 = y1;
      if (x2 > updateX2)
	updateX2 = x2;
      if (y2 > updateY2)
	updateY2 = y2;
    }
  }

  private final void markRowsDirty(int row1, int row2)
  {
    markRangeDirty(0, row1, sizeInCharsW - 1, row2);
  }

  private final void markRowDirty(int row1)
  {
    markRangeDirty(0, row1, sizeInCharsW - 1, row1);
  }


  private final void markWholeAreaDirty()
  {
    updateX1 = updateY1 = 0;
    updateX2 = sizeInCharsW - 1;
    updateY2 = sizeInCharsH - 1;
  }

  private final void
  markWholeAreaClean()
  {
    updateX1 = updateY1 = -1;
  }

  /* This won't be called asynchronously, but always from the
   * event handling thread:
   */
  private final void
  doPaint()
  {
    if (updateX1 < 0) {
      System.err.println("NO DRAW!");
      return;
    }
    /* New, 08-May-1999, TSa: It's possible that 2 threads may try to
     * simultaneously draw to screen (via paint() and doPaint()), so
     * the access has to be synchronized:
     */
    Graphics g;

    synchronized (screenLock) {
      g = getGraphics();
      g.setClip(updateX1 * fontWidth + BORDER_X,
	      updateY1 * fontHeight + BORDER_Y,
	      (updateX2 - updateX1 + 1) * fontWidth,
	      (updateY2 - updateY1 + 1) * fontHeight);
      paint(g);
    }
    g.dispose();
/*
Display.ssh_draw = System.currentTimeMillis();
Display.sshDebug();
*/
    markWholeAreaClean();
  }

  public void
  paint(Graphics g)
  {
    int i, j, rx, ry, x, y;

    //long now = System.currentTimeMillis();

    if (screenImage == null)
      return;

    /* A kludge; FontLoader causes blinking due to excessive (and worse,
     * unnecessary) repaint-requests. This is to prevent that from
     * happening...
     */
    if (skip_repaints > 0) {
      skip_repaints -= 1;
      skipped_repaints += 1;
      return;
    }

    /* New, 08-May-1999, TSa: It's possible that 2 threads may try to
     * simultaneously draw to screen (via paint() and doPaint()), so
     * the access has to be synchronized:
     */
    Graphics g2;

    synchronized (screenLock) {

   /* First the surrounding rectangle: (borders) */

      x = pixelSize.width;
      y = pixelSize.height;
      
      g.setColor(defFgroundColor);
      g.drawRect(0, 0, x - 1, y - 1);
      g.setColor(defBgroundColor);
      g.drawRect(1, 1, x - 3, y - 3);
      
      x -= 2 * BORDER_X;
      y -= 2 * BORDER_Y;
      
      rx = usablePixelSize.width;
      ry = usablePixelSize.height;

      /* Garbage on the bottom and right can be removed now, as long as we
     * do use clipping later on.
     */
      if (ry < y)
	g.fillRect(BORDER_X, BORDER_Y + ry, x, y - ry);
      if (rx < x)
	g.fillRect(BORDER_X + rx, BORDER_Y, x - rx, y);

   /* Then we can as well set new bounds so as to prevent borders
    * from getting removed:
    */


    /* Buffer mode is quite simple: */
      if (bufferMode) { // Buffer mode:
	g.drawImage(offScreenImage, BORDER_X, BORDER_Y, this);
	return;
      }

      g2 = g.create(BORDER_X, BORDER_Y, rx, ry);
      Graphics g3 = null;

      y = getPixelY(mTopRow);
      int top_size = sizeInCharsH - (y / fontHeight);

      /* All in all, 'blinked' case (ie. some chars are blinking and
       * are now to be cleared, ie. not drawn) has to be handled
       * separately:
       */
    
      if (blinkedState) {
     
	  /* We need to handle the top & bottom part separately (the
	   * special case, only one part, can be handled in the same
	   * way; just that its bottom part size is 0
	   */
	i = 0;

	// Top part:
	while (i < top_size) {
	  j = i;
	  /* Let's see how many lines have no blinking; these can be
	   * drawn in just one blit:
	   */
	  while (j < top_size && !mLines[mTopRow+j].isBlinking()) {
	      j++;
	  }
	  if (j > i) { // if j == i) chunk has 0 lines...
	    if (i == 0 && j == top_size) { // Whole top part in one draw:
		g2.drawImage(screenImage, 0, - y, this);		
	    } else {
		g3 = g2.create(0, i * fontHeight, rx, (j - i) * fontHeight);
		g3.drawImage(screenImage, 0, - (y + i * fontHeight), this);
		g3.dispose();
	    }
	  }
	  // Let's make sure the cursor is drawn when it should:
	  if (mCurrRow >= (mTopRow + i) && mCurrRow < (mTopRow + j))
	    drawCursorFromPaint(g2.create());
	  // Then we'll handle the "blinking line(s)":
	  i = j;
	  while (i < top_size && mLines[mTopRow + i].isBlinking()) {
	    drawBlinkingLine(g2, i, screenImage, -y, rx);
	    // ... And the cursor if need be:
	    if (mCurrRow >= (mTopRow + i) && mCurrRow < (mTopRow + j))
	      drawCursorFromPaint(g2.create());
	    i += 1;
	  }
	}

	// Bottom part:
	while (i < sizeInCharsH) {
	  j = i;
	  /* Let's see how many lines have no blinking; these can be
	   * drawn in just one blit:
	   */
	  while (j < sizeInCharsH && !mLines[mTopRow+j].isBlinking()) {
	      j++;
	  }
	  if (j > i) { // if (j == i) chunk has 0 lines...
	    if (i == top_size && j == sizeInCharsH) { // Whole bottom part in one draw:
	      g2.drawImage(screenImage, 0, ry - y, this);		
	    } else {
	      g3 = g2.create(0, i * fontHeight, rx, (j - i) * fontHeight);
	      g3.drawImage(screenImage, 0, ry - (y + i * fontHeight), this);
	      g3.dispose();
	    }
	  }
	  // Let's make sure the cursor is drawn when it should:
	  if (mCurrRow >= (mTopRow + i) && mCurrRow < (mTopRow + j))
	      drawCursorFromPaint(g2.create());
	  // Then we'll handle the "blinking line(s)":
	  i = j;
	  while (i < sizeInCharsH && mLines[mTopRow + i].isBlinking()) {
	      drawBlinkingLine(g2, i, screenImage, ry - y, rx);
	      // ... And the cursor if need be:
	      if (mCurrRow >= (mTopRow + i) && mCurrRow < (mTopRow + j))
		  drawCursorFromPaint(g2.create());
	      i += 1;
	  }
	}
      
      } else { // ... if (blinkedState) ...
      
	if (y == 0) {
	    g2.drawImage(screenImage, 0, 0, this);
	    drawCursorFromPaint(g2);
	} else {
	    /* We can draw cursor between the two draws; we just need
	     * to know which one to draw first, then:
	     */
	  if ((mCurrRow - mTopRow) < top_size) { // Cursor on upper part; upper first
	      g2.drawImage(screenImage, 0, -y, this);		
	      drawCursorFromPaint(g2);
	      g2.drawImage(screenImage, 0, ry - y,this);
	  } else { // Cursor on lower part; lower first
	      g2.drawImage(screenImage, 0, ry - y,this);
	      drawCursorFromPaint(g2);
	      g2.drawImage(screenImage, 0, - y, this);
	  }
	}
      }

    } // synchronized(screenLock) ...

    g2.dispose();

  }

  /* This function is used to paint the scrollback buffer: */  
  // Should it be synchronized?
  synchronized public void
  paintBuffer(int y, boolean off_scr, boolean force_new)
  {
    int from_row, to_row, lines, line, i, j, x, last, w, h;
    int curr_char;
    short curr_attr;
    byte [] chars;
    short [] attrs;
    short line_attrs;
    Color bg, fg;
    boolean bold = false;
    Graphics g;
    
    if (off_scr) {
      g = offScreenGraphics;
    } else {
	g = screenGraphics;
    }   
 
    if (g == null) {
      return;
    }

    if (!force_new && topBufferRow == y) {
      return;
    }

      /* Let's check if we can use some part of the old buffer: */
      /* If not, we'll do complete update... */
    g.setColor(defBgroundColor);

    if (force_new || topBufferRow < 0 || y <= (topBufferRow - sizeInCharsH)
	|| y >= (topBufferRow + sizeInCharsH)) {
      
	/* We'll first clear the whole screen... */
	g.fillRect(0, 0, usablePixelSize.width, usablePixelSize.height);
	from_row = y;
	to_row = 0;
	lines = sizeInCharsH;
    } else {
	/* Now, if we can use some part of the buffer, let's do so: */
	if (y < topBufferRow) {
	    /* Scrollbar went up -> we'll scroll down: */
	    lines = topBufferRow - y;
	    j = lines * fontHeight;
	    g.copyArea(0, 0, usablePixelSize.width, usablePixelSize.height - j,
		       0, j);
	    g.fillRect(0, 0, usablePixelSize.width, j);
	    from_row = y;
	    to_row = 0;
	} else {
	    /* Scrollbar went down -> scroll up: */
	    lines = y - topBufferRow;
	    j = lines * fontHeight;
	    g.copyArea(0, j, usablePixelSize.width, usablePixelSize.height - j,
		       0, -j);
	    g.fillRect(0, usablePixelSize.height - j, usablePixelSize.width, j);
	    to_row = sizeInCharsH - lines;
	    from_row = y + to_row;
	}
    }

    // This can occur when enlargening the window?
    if ((from_row + lines) > charBuffer.length) {
	doWarning("Repaint overflow!");
	lines = charBuffer.length - from_row;
    }
    for (line = 0; line < lines; line++) {
	DisplayLine currLine = mLines[from_row + line];
	last = currLine.getLength();
	// Can skip empty lines...
	if (last == 0) {
	    continue;
	}
      
	// h -> y-coordinate of the line top
	h = (to_row + line) * fontHeight;

	/* Double-width/height drawing differs significantly;
	 * they can't be well optimized and are thus always drawn
	 * char by char.
	 */
	if (currLine.isDoubleWidth()) {
	    // Double-width -> can only print 50% of the max chars
	    if (last > (sizeInCharsW / 2))
		last = sizeInCharsW / 2;
	    Graphics double_g = g.create(0, h,usablePixelSize.width,
					 fontHeight);
	    redrawDoubleRow(double_g, 0, last, chars, attrs, line_attrs,
			    false);
	    double_g.dispose();
	    continue;
	}
	
	for (i = x = 0; i < last; ) {
	    curr_char = (int) chars[i] & 0xFF;
	    if ((curr_char & 0x7F) < 32 || (curr_char == 0xFF)) {
		x += fontWidth;
		i++;
		continue;
	    }
	    
	    curr_attr = attrs[i];
	    
	    // Normal chars should be printed ok.
	    for (j = i + 1; j < last; j++) {
		curr_char = (int) chars[j] & 0xFF;
		if ((curr_char & 0x7F) < 32 || (curr_char == 0xFF))
		    break;
		if (curr_attr != attrs[j])
		    break;
	    }
	    w = fontWidth * (j - i);
	    
	    bg = mCurrColors[(curr_attr & 0x0070) >> 4];
	    if ((curr_attr & FX_BOLD) != 0) {
		fg = currBrightColors[(curr_attr & 0x0007)];
		if (!bold) {
	      g.setFont(currBoldFont);
	      bold = true;
		}
	    } else {
		if (bold) {
		    g.setFont(currFont);
		    bold = false;
		}
		if ((curr_attr & FX_DIM) != 0) {
		    fg = currDimColors[(curr_attr & 0x0007)];
		} else {
		    fg = mCurrColors[(curr_attr & 0x0007)];
		}
	    }
	    
	    /* Setting background color: */
	    if ((curr_attr & FX_REVERSED) == 0) g.setColor(bg);
	    else g.setColor(fg);
	    
	    /* First we need to clear the space... */
	    g.fillRect(x, h, w, fontHeight);
	    
	    /* Setting drawing color: */
	    if ((curr_attr & FX_REVERSED) == 0) g.setColor(fg);
	  else g.setColor(bg);
	    
	  /* Then we might need to do the underscore... */
	    if ((curr_attr & FX_UNDERSCORE) != 0) {
		g.drawLine(x, h + fontBase + UNDERLINE_OFFSET,
			   x + w - 1, h + fontBase + UNDERLINE_OFFSET);
	  }

	  //g.drawBytes(chars, i, j - i, x, h + fontBase);
	    g.drawString(new String(chars, i, j - i), x, h + fontBase);
	    x += w;
	  }
	  i = j;
    }
    topBufferRow = y;
  }

  /* This is used to update a certain row in the internal draw
   * buffer. It does not automatically result in paint() getting
   * called, for efficiency reasons.
   * New! Argument 'col' is the column from which the redrawing begins;
   * the last column is the rightmost column
   */
  synchronized public void
  redrawRow(int row, int col)
  {
      // Sanity checks first:
      if (row < mTopRow || row > mBottomRow) {
	  return;
      }

      DisplayLine currLine = mLines[row];

      int i, x, y, curr_char;
      short curr_attr;
      Color bg, fg;
      
      byte [] chars = charBuffer[row];
      short [] attrs = attrBuffer[row];
      boolean bold = false;
      Graphics gfx_g = null;
      
      markRowDirty(mCurrRow - mTopRow);

    // Not sure if this is possible but:
      if (screenImage == null) {
	  getImages();
      }

    y = getPixelY(row); // The location in the (circular) buffer

    int last = lineLengths[row];
    i = col;
    x = i * fontWidth;

    /* New: Double-width/height drawing differs significantly;
     * they can't be well optimized and are thus always drawn
     * char by char.
     */
    if (currLine.isDoubleWidth()) {

	// Double-width -> can only print 50% of the max chars
	if (last > (sizeInCharsW / 2)) {
	    last = sizeInCharsW / 2;
	}

	Graphics double_g = screenGraphics.create(0, y,usablePixelSize.width,
						  fontHeight);
	redrawDoubleRow(double_g, col, last, chars, attrs, line_attrs,
			true);
	double_g.dispose();
	return;
    }

    // First we'll erase stuff that's already on the row:
    screenGraphics.setColor(defBgroundColor);
//screenGraphics.setColor(Color.yellow);
    screenGraphics.fillRect(x, y, pixelSize.width - x, fontHeight);
  
    // Similar to the drawing part in paintBuffer():
    for (; i < last; ) {
      int w, j;

      curr_char = (int) chars[i] & 0xFF;
      if ((curr_char & 0x7F) < 32 || (curr_char == 0xFF)) {
	// Shouldn't be necessary, but just in case let's skip
	// weird chars:
	x += fontWidth;
	i++;

      } else {

	curr_attr = attrs[i];
	
	// Normal chars should be printed ok, but usually we can
	// print more than one consequtive char at a time, provided
	// their attributes are the same:
	for (j = i + 1; j < last; j++) {
	  curr_char = (int) chars[j] & 0xFF;
	  if ((curr_char & 0x7F) < 32 || curr_char == 0xFF)
	    break;
	  if (curr_attr != attrs[j])
	    break;
	}

	w = fontWidth * (j - i);
	bg = mCurrColors[(curr_attr & 0x0070) >> 4];
	if ((curr_attr & FX_BOLD) != 0) {
	  fg = currBrightColors[(curr_attr & 0x0007)];
	  if (!bold) {
	    bold = true;
	    screenGraphics.setFont(currBoldFont);
	  }
	} else {
	  if (bold) {
	    bold = false;
	    screenGraphics.setFont(currFont);
	  }
	  if ((curr_attr & FX_DIM) != 0) {
	    fg = currDimColors[(curr_attr & 0x0007)];
	  } else {
	    fg = mCurrColors[(curr_attr & 0x0007)];
	  }
	}

	if ((curr_attr & FX_REVERSED) == 0) screenGraphics.setColor(bg);
	else screenGraphics.setColor(fg);
	
	/* First we need to clear the space... */
	screenGraphics.fillRect(x, y, w, fontHeight);
	
	if ((curr_attr & FX_REVERSED) == 0) screenGraphics.setColor(fg);
	else screenGraphics.setColor(bg);
	
	// Doubled chars need doubled underscores?
	if ((curr_attr & FX_UNDERSCORE) != 0) {
	  screenGraphics.drawLine(x, y + fontBase + UNDERLINE_OFFSET,
		     x + w - 1, y + fontBase + UNDERLINE_OFFSET);
	}

	// And gfx symbols are different as well:
	if ((curr_attr & FX_GFX) != 0) {
	  Image img;

	  if (gfx_g == null) {
	    gfx_g=screenGraphics.create(0,y,usablePixelSize.width,fontHeight);
	  }
	  gfx_g.setXORMode(new Color(bg.getRed() ^ fg.getRed() ^ 255,
				 bg.getGreen() ^ fg.getGreen() ^ 255,
				 bg.getBlue() ^ fg.getBlue() ^ 255));
	  for (int k = i; k < j; k++, x += fontWidth) {
	    curr_char = chars[k];
	    // If it's not 'really' a symbol (or we don't know what it
	    // might be), let's use the normal font letter:
	    if (curr_char < 0137 || curr_char > 0176) {
	      oneCharArray[0] = (char) curr_char;
	      screenGraphics.drawChars(oneCharArray, 0, 1, x, y + fontBase);
	      continue;
	    }
	    if (curr_char == 0137) // We can skip spaces
	      continue;

	    //if ((curr_attr & FX_BOLD) != 0)
	    img = gfxFonts[curr_char - 0140];
	    if (img == null)
	      img = waitForGfxFont(curr_char - 0140);
	    gfx_g.drawImage(img, x, 0, this);
	  }
	} else { // Normal chars:
	  //screenGraphics.drawBytes(chars, i, j - i, x, h + fontBase);
	  screenGraphics.drawString(new String(chars, i, j-i),x,y + fontBase);
	  x += w;
	}
	i = j;
      }
    }

    if (bold)
      screenGraphics.setFont(currFont);
  }

  // Note that this function does not clear the row before drawing
  // (except if the background color is not the default colour); it is
  // up to the caller to take care the clearing is done if necessary:

  // last -> last char + 1
  // g -> 'new' graphics context restricted and translated in y-direction
  //   to the line

  synchronized public void
  redrawDoubleRow(Graphics g, DisplayLine currLine, int first, int last,
		  //byte [] chars, short [] attrs, int line_attrs,
		  boolean clear_end)
  {
    int w, j, curr_char;
    short curr_attr;
    int fw = 2 * fontWidth;
    int x = first * fw;
    Color bg, fg;
    boolean dw =((line_attrs & LINE_DOUBLE_W) != 0) ? true : false;
    boolean dh = ((line_attrs & LINE_DOUBLE_H_TOP) != 0) ? true : false;
    boolean dh_bottom = ((line_attrs & LINE_DOUBLE_H_BOTTOM) != 0) ? true : false;

    markRowDirty(mCurrRow - mTopRow);

    if (clear_end) {
       w = last * fw;
       if (w < usablePixelSize.width) {
	 g.setColor(defBgroundColor);
	 g.fillRect(w, 0, usablePixelSize.width - w, fontHeight);
       }
    }
 
    // Should we redraw the whole damn row or not?
    //g.setPaintMode();
    //g.setColor(defBgroundColor);
    //g.fillRect(0, 0, usablePixelSize.width, fontHeight);

    for (int i = first; i < last; ) {

      curr_char = (int) chars[i] & 0xFF;

      // Shouldn't really happen:
      if ((curr_char & 0x7F) < 32 || (curr_char == 0xFF)) {
	x += fw;
	i++;
	continue;
      }

      curr_attr = attrs[i];
	
	// Normal chars should be printed ok, but usually we can
	// print more than one consequtive char at a time, provided
	// their attributes are the same:
      for (j = i + 1; j < last; j++) {
	curr_char = (int) chars[j] & 0xFF;
	if ((curr_char & 0x7F) < 32 || curr_char == 0xFF)
	  break;
	if (curr_attr != attrs[j])
	  break;
      }

      w = fw * (j - i);

      bg = mCurrColors[(curr_attr & 0x0070) >> 4];
      if ((curr_attr & FX_BOLD) != 0) {
	fg = currBrightColors[(curr_attr & 0x0007)];
      } else {
	if ((curr_attr & FX_DIM) != 0) {
	  fg = currDimColors[(curr_attr & 0x0007)];
	} else {
	  fg = mCurrColors[(curr_attr & 0x0007)];
	}
      }

      if ((curr_attr & FX_REVERSED) != 0) {
	Color foo = fg;
	fg = bg;
	bg = foo;
      }
	
      // Needs to be cleared in any case, first:
      g.setPaintMode();
      /* This seems idiotic doesn't it? But try it without creating a
       * new colour instance and see what happens... Welcome to the
       * Twilight Zone!
       */
      g.setColor(new  Color(bg.getRed(), bg.getGreen(), bg.getBlue()));
      g.fillRect(x, 0, w, fontHeight);
		
      // Doubled characters need to be drawn char at a time, though:
      Image [] f;
      Image f2;

	// We need to set XOR-mode to get the desired font color:
      
      g.setXORMode(new Color(bg.getRed() ^ fg.getRed() ^ 255,
			     bg.getGreen() ^ fg.getGreen() ^ 255,
			     bg.getBlue() ^ fg.getBlue() ^ 255));

      // Special GFX are drawn separately. Could use a font set too?
      if ((curr_attr & FX_GFX) != 0) { // Gfx chars:

	int y, offset;

	if (dh_bottom)
	  y = -fontHeight;
	else y = 0;

	if ((curr_attr & FX_BOLD) != 0) {
	  offset = 3 * fontLoader.NR_OF_SYMBOLS;
	} else {
	  offset = 2 * fontLoader.NR_OF_SYMBOLS;
	}

	offset = 0;

	if (dh || dh_bottom)
	  offset += (2 * fontLoader.NR_OF_SYMBOLS);
	
	for (int k = i; k < j; k++, x += fw) {
	  curr_char = ((int) chars[k] & 0xFF);
	  // Doh. We need to draw the doubled char after all. :-p
	  if (curr_char < 0137 || curr_char > 0176) {
	    oneCharArray[0] = (char) curr_char;
	    continue;
	  } else {
	    if (curr_char == 0137) // We can skip spaces
	      continue;
	  }

	  curr_char = curr_char - 0140 + offset;
	  f2 = gfxFonts[curr_char];
	  if (f2 == null)
	    f2 = waitForGfxFont(curr_char);
	  g.drawImage(f2, x, 0, this);
	}

      } else { // 'normal' doubled, not gfx

	if (dw) {
	  if ((curr_attr & FX_BOLD) != 0)
	    f = getDoubleFont(DW_FONT_BOLD);
	  else
	    f = getDoubleFont(DW_FONT_NORMAL);
	} else {
	  if ((curr_attr & FX_BOLD) != 0)
	    f = getDoubleFont(DH_FONT_BOLD);
	  else
	    f = getDoubleFont(DH_FONT_NORMAL);
	}

      
	for (int k = i; k < j; k++, x += fw) {
	  curr_char = ((int) chars[k] & 0xFF) - 32;
	  // No need to 'draw' spaces:
	  if (curr_char == 0)
	    continue;
	  if (curr_char > 95)
	    curr_char -= 32;
	  
	  int set = curr_char / fontLoader.CHARS_PER_IMAGE;
	  if ((f2 = f[set]) == null)
	    f2 = waitForDoubleFont(f, set);
	  curr_char %= fontLoader.CHARS_PER_IMAGE;
	  curr_char *= fontHeight;
	  if (dh_bottom)
	    curr_char = curr_char * 2 + fontHeight;
	  else if (dh)
	    curr_char *= 2;
	  g.drawImage(f2, x, -curr_char, this);
	  
	}
      }

      // Doubled chars need doubled underscores?
      if ((curr_attr & FX_UNDERSCORE) != 0) {
	g.setPaintMode();
	g.setColor(fg);
	g.drawLine(x, fontBase + UNDERLINE_OFFSET,
		   x + w - 1, fontBase + UNDERLINE_OFFSET);
	g.drawLine(x, fontBase + UNDERLINE_OFFSET + 1,
		   x + w - 1, fontBase + UNDERLINE_OFFSET + 1);
      }
      
      i = j;
    }

    g.setPaintMode();
  }
  
  public void
  getImages()
  {
    int x = usablePixelSize.width;
    int y = usablePixelSize.height;
    
    screenImage = createImage(x, y);
    offScreenImage = createImage(x, y);
    if (screenImage == null || offScreenImage == null) {
      doError("Can't get off-screen image(s).");
    }
    screenGraphics = screenImage.getGraphics();
    offScreenGraphics = offScreenImage.getGraphics();
    if (screenGraphics == null || offScreenGraphics == null) {
      doError("Can't get off-screen graphics context(s).");
    }

    //System.err.println("def bg="+defBgroundColor+", def fg="+defFgroundColor);
    
    screenGraphics.setColor(defBgroundColor);
    screenGraphics.fillRect(0, 0, x, y);
    screenGraphics.setFont(currFont);
    offScreenGraphics.setColor(defBgroundColor);
    offScreenGraphics.fillRect(0, 0, x, y);
    offScreenGraphics.setFont(currFont);
    
    topBufferRow = -1; // Let's mark buffer image as invalid...
  }

/************* Functions triggered by menus ***********/

  // This function is called when 'redraw' is selected from the menu:
  public void
  redraw()
  {
    long now = System.currentTimeMillis();

    screenRow = 0;
    paintBuffer(mTopRow, false, true);
    if (bufferMode) {
      paintBuffer(master.scrBar.getValue(), true, true);
    }
    Toolkit.getDefaultToolkit().sync();

    System.err.println("DEBUG: Redraw took "+
		       (System.currentTimeMillis()-now)+" msecs.");

    repaint();
  }  

  public void
  toggleBell()
  {
    allowBell = !allowBell;
  }

  /* This function is called by the Blinker-thread, to inform
   * that the blinking state should be changed. We should now check
   * whether there is any blinking to do; if there is, we'll do the
   * stuff and return true, otherwise simply return false.
   */
  public boolean toggleBlink()
  {
    short [] attrs;
    int i, x;

    synchronized (this) {
      for (i = mTopRow; i <= mBottomRow; i++) {
	  DisplayLine currLine = mLines[i];
	  /* The per-line flag is for optimizing; if it's not set
	   * we can be sure it contains no blinking chars.
	   */
	  if (!currLine.containsBlinking()) {
	      continue;
	  }

	  /* However, the flag doesn't have to mean there is blinking
	   * for certain, so we need to check whether there is:
	   */
	attrs = attrBuffer[i];
	for (x = lineLengths[i]; --x >= 0; ) {
	  if ((attrs[x] & FX_BLINK) != 0)
	    break;
	}
	// If not, we'll clear the flag and continue checking
	if (x < 0) {
	  lineAttrs[i] &= ~LINE_HAS_BLINK;
	  continue;
	}
	// Otherwise we can just skip the other lines:
	break;
      }

    // If no blinking, let's return:
      if (i > mBottomRow) {
	blinkedState = false;
	blinkActive = false;
	return false;
      }
      
      blinkedState = !blinkedState;
    }

    blinkActive = true;

    // Repaint() need not be synced?
    // We can't call 'doPaint()' because this is _not_
    // called by Display-thread!
    repaint();
    return true;
  }

/********* Functions required by MouseMotionListener: ************/

  // Whether to put these instance variables after other instvars
  // or not? For now, let 'em be here:
  private boolean dragging = false;
  private Rectangle chosenArea = new Rectangle(); // Should be locked...
  private boolean chosenAreaUpsideDown = false; // ie. start below end

  // We need to catch drag-events to make selections possible:
  public void
  mouseDragged(MouseEvent e)
  {
    if (!dragging)
      dragging = true;

    int x = e.getX();
    int y = e.getY();

    int col = (x - BORDER_X) / fontWidth;
    int row = (y - BORDER_Y) / fontHeight;

    System.err.println("Drag at "+col+", "+row+" ("+x+", "+y+"): "+e);
  }

  // No need to do anything; we're only interested in the drag-event...
  public void
  mouseMoved(MouseEvent e)
  {
  }

/********* Functions required by MouseListener: ************/

	public void mouseEntered(MouseEvent e) {
	}
	public void mouseExited(MouseEvent e) {
	}
	public void mouseClicked(MouseEvent e) {
	}
	public void mousePressed(MouseEvent e) {
	}

	public void mouseReleased(MouseEvent e) {
	  if (dragging) {
	    dragging = false;
	  }
	  master.updateFocus();
	}

/********* And other oddball-functions: ************/

  public void
  dumpChars()
  {
    for (int i = mTopRow; i <= mBottomRow; i++) {
      System.err.println("\"" + new String(charBuffer[i], 0,
					   lineLengths[i])+"\"");
    }
  }

  public void
  dumpAttrs()
  {
    for (int i = mTopRow; i <= mBottomRow; i++) {
      short [] attrs = attrBuffer[i];
      int len = lineLengths[i];
      if (len == 0) {
	System.err.println("<empty>");
	continue;
      }
      int j = 0, k;
      while (j < len) {
	short curr_attr = attrs[j];
	for (k = j + 1; k < len && attrs[k] == curr_attr; k++)
	  ;
	if (j != 0)
	  System.err.print(" ");
	System.err.print("" + (k - j) + "x0x"+Integer.toHexString(curr_attr));
	j = k;
      }
      System.err.println();
    }
  }

    public void doWarning(String msg) { System.err.println(msg); }
    public void doError(String msg) { System.err.println(msg); }

  public void
  debugFromMenu2()
  {
    byte [] row;
    for (int i = mTopRow; i <= mBottomRow; i++) {
      row = charBuffer[i];
      System.err.print("\""+new String(row, 0,lineLengths[i])+"\"");
      if (lineLengths[i] < row.length)
	System.err.println("+'"+new String(row, lineLengths[i], row.length)
			   +"'");
      else System.err.println("+''");
    }
  }

  public void
  debugFromMenu3()
  {
    for (int i = mTopRow; i <= mBottomRow; i++) {
      System.err.println("->" + charBuffer[i]);
    }
  }

  /**** Debugging utility: *****/
  public static long ssh_key1 = 0;
  public static long ssh_sent = 0;
  public static long ssh_sent2 = 0;
  public static long ssh_rec = 0;
  public static long ssh_draw = 0;
  public static long ssh_draw2 = 0;

  public static void sshDebug() {///*
    ssh_sent -= ssh_key1;
    ssh_sent2 -= ssh_key1;
    ssh_rec -= ssh_key1;
    ssh_draw -= ssh_key1;
    ssh_draw2 -= ssh_key1;
    System.err.println("<"+(ssh_key1%1000)
		       +"/"+ssh_sent
		       +"/"+ssh_sent2
		       +"/"+ssh_rec
		       +"/"+ssh_draw
		       +">");
		       //*/
  }

  /**
   * A small internal State - container class, used for implementing
   * VT100 'save cursor' and 'restore cursor' operations:
   */
    private static class DisplayState
    {
	public int mDisplayModes;
	public CharAttrs mCharAttrs;
	public Dimension mCursorPosition;

	public DisplayState() { }
    }

    /*** A small helper class necessary for implementing blinking: ***/
    final class Blinker
	extends Thread
    {
	public final static long BLINK_THREAD_IDLE_SLEEP = 20000;
	
	Display beeped;
	
	public Blinker(Display b)
	{
	    beeped = b;
	}
	
	public void run()
	{
	    long end, now, delay;
	    long interval = Display.BLINK_INTERVAL;
	    
	    // A never-ending loop...
	    while (true) {
		
		now = System.currentTimeMillis();
		end = now + interval;
		
		while ((delay = end - now) > 5) {
		    try {
			Thread.sleep(delay);
		    } catch (InterruptedException ie) {
			;
		    }
		    now = System.currentTimeMillis();
		}
		/* However, if no blinking is necessary, let's sleep
		 * for longer time...
		 */
		if (!beeped.toggleBlink()) {
		    try {
			// We could sleep indefinitely but perhaps we better not
			Thread.sleep(BLINK_THREAD_IDLE_SLEEP);
		    } catch (InterruptedException ie2) {
			// This _should_ be interrupted occasionally
		    }
		} else {
		}
	    }
	}
    }
  
  /* **********************************************************************

   Another internal utility class, for storing the display lines.

  ********************************************************************** */
    final static class DisplayLine
    {
	 /* Per-line attributes: */
	 public final static short LINE_DOUBLE_W = 0x0001;
	 public final static short LINE_DOUBLE_H_TOP = 0x0002;
	 public final static short LINE_DOUBLE_H_BOTTOM = 0x0004;
	 public final static short LINE_DOUBLE_MASK = 0x0007;
	 public final static short LINE_HAS_BLINK = 0x0008;
	 /* ^^^ This is only a hint about possible presence about a blinking
	  * character in the line... updated if/when necessary
	  */
	 
	 public final static short sDefLineAttrs = (short) 0;
	 public static char sDefChar = ' ';
	 
	 private final Display mDisplay;
	 private char[] mChars;
	 private CharAttrs[] mCharAttrs;
	 
	 private int mLength;
	 private int mLineAttrs;
	 
	 public DisplayLine(Display d, int width)
	 {
	     mDisplay = d;
	     mChars = new char[width];
	     mCharAttrs = new CharAttrs[width];
	     mLength = 0;
	     mLineAttrs = sDefLineAttrs;
	 }
	 
	 /* *** Simple getters/setters: *** */
	 public boolean isDoubleWidth() {
	     return (mLineAttrs &
		     (LINE_DOUBLE_W | LINE_DOUBLE_H_TOP | LINE_DOUBLE_H_BOTTOM))
		 != 0;
	 }
	 
	 public boolean isDoubleHeight() {
	     return (mLineAttrs & (LINE_DOUBLE_H_TOP | LINE_DOUBLE_H_BOTTOM))!= 0;
	 }
	 
	 public int getLineLength() { return mLength; }

	 public void resetLineAttrs() { mLineAttrs = sDefLineAttrs; }

	 /* Method for setting doubled width, doubled width+height or
	  * normal size:
	  */
	 public void setDoubleWidth()
	 {
	     mLineAttrs &= ~LINE_DOUBLE_MASK;
	     mLineAttrs |= ~LINE_DOUBLE_W;
	 }

	 public void setDoubleTop()
	 {
	     mLineAttrs &= ~LINE_DOUBLE_MASK;
	     mLineAttrs |= ~LINE_DOUBLE_H_TOP;
	 }

	 public void setDoubleBottom()
	 {
	     mLineAttrs &= ~LINE_DOUBLE_MASK;
	     mLineAttrs |= ~LINE_DOUBLE_H_BOTTOM;
	 }

	 public void setDoubleNone()
	 {
	     mLineAttrs &= ~LINE_DOUBLE_MASK;
	     mLineAttrs |= ~LINE_DOUBLE_H_BOTTOM;
	 }

	 public void isBlinking() { return (mLineAttrs & LINE_HAS_BLINK) != 0;}
	 public void setIsBlinking(boolean state)
	 {
	     if (state) {
		 mLineAttrs |= LINE_HAS_BLINK;
	     } else {
		 mLineAttrs &= ~LINE_HAS_BLINK;
	     }
	 }

	public boolean containsBlinking()
	{
	    if ((mLineAttrs & LINE_HAS_BLINK) == 0) {
		return false;
	    }
	    for (int i = 0; i < mLength; ++i) {
		if (mCharAttrs[i].isBlinking()) {
		    return true;
		}
	    }
	    /* Hmmh. Ok, so we don't have anything, after all. Better
	     * update the line attr flag then:
	     */
	    mLineAttrs &= ~LINE_HAS_BLINK;
	}

	 /* *** Methods for adding/deleting chars/space: *** */

	 public void clearLine()
	 {
	     mLength = 0;
	     /* Let's actually clean attributes, so that GC may kick
	      * them out (if they aren't used any more):
	      */
	     for (int i = mCharAttrs.length; --i >= 0; ) {
		 mCharAttrs[i] = null;
	     }
	 }

	 /** This function actually only makes room for characters;
	  * the actual writing is usually done by using the default
	  * replace-mode. Returns 'true' to indicate that the row
	  * edited needs to be redrawn.
	  */
	 public boolean insertChars(int col, int nr, int charWidth)
	 {
	     CharAttrs defAttrs = mDisplay.mDefaultCharAttrs;
	     int last = col + nr;

	     /* The trivial case is the one in which we are just appending
	      * stuff to the end of the line:
	      */
	     if (col == mLength) {
		 if (last > charWidth) {
		     last = charWidth;
		 }
	     } else {
		 int max = charWidth - col - nr;
		 
		 if (max <= 0) {
		     /* In another simple case we are inserting so many new
		      * characters that we don't actually need any copying:
		      */
		     if ((col + nr) > charWidth) {
			 nr = charWidth - col;
		     }
		     last = charWidth;
		 } else {
		     /* In the common case we need to shift stuff to the
		      * right first:
		      */
		     System.arraycopy(mChars, col,
				      mChars, col + nr, max);
		     System.arraycopy(mCharAttrs, col,
				      mCharAttrs, col + nr, max);
		     last = col + nr;
		 }
	     }

	     for (int i = col; i < last; ++i) {
		 mChars[i] = sDefChar;
		 mCharAttrs[i] = defAttr;
	     }
	     mLength = last;
    
	     return true;
	 }

	 /**
	  * Method for adding as many of the specified characters on this
	  * line (starting from specified column) as possible, and returns
	  * number of characters that didn't fit.
	  * 
	  * Note that in case characters are added on top of existing
	  * characters, it's done by replacing old contents.
	  *
	  * @param chars Character array that contains characters to add
	  * @param start Index of the first character to add
	  * @param len Number of characters to add
	  * @param currAttrs Currently used character attributes
	  * @param column Column starting from which to add stuff
	  * @param lineLength Maximum width of the line
	  * @param wrap Whether lines are to be wrapped; if false will
	  *    never return anything but 0 (plus last character of the
	  *    input chars is always printed; intervening characters for
	  *    which there's no room are just discarded); if true
	  *    normally returns characters.
	  *
	  * @return Returns number of characters left that don't
	  *   fit in this row.
	  */
	 public int addChars(char[] chars, int start, int len,
			     CharAttrs currAttrs, int column, int lineLength,
			     boolean wrap)
	 {
	     int ret;

	     // Printing blinking character(s)?
	     if (currAttrs.isBlinking()) {
		  setIsBlinking(true);
	     }

	      /* We need to make sure we don't accidentally try to add
	       * 'too much stuff' to the array. Might happen because
	       * of a resize:
	       */
	      if ((mLength + len) > lineLength) {
		  ret = (mLength + len) - lineLength;
		  len -= ret;
	      } else {
		  ret = 0;
	      }
	      System.arraycopy(c, first, mChars, mLength, len);
	      for (i = 0; i < len; i++) {
		  mCharAttrs[mLength + i] = currAttrs;
	      }
	      mLength += len;

	      // No wrapping and didn't fit? Let's squeeze a bit..
	      if (!wrap && ret > 0) {
		  mChars[mLength - 1] = chars[start + len - 1];
		  mCharAttrs[mLength - 1] = currAttrs;
		  // Ie. some chars were just discarded
		  ret = 0;
	      }

	      return ret;
	 }
	 
	 /** Method for filling the line with specified char using specified
	  * attributes.
	  *
	  * @param c Character to fill line with
	  * @param attrs Character attributes to apply to the characters
	  * @param length Length of the line to fill
	  */
	 public void fillLineWith(char c, CharAttrs attrs, int length)
	 {
	     mLength = length;
	     for (int col = 0; col < length; ++col) {
		mCharAttrs[col] = defAttrs;
		mChars[col] = c;
	    }		  
	 }

	 /**
	  * Method for moving contents of this line left 'count' times.
	  * First 'count' characters are discarded.
	  *
	  * @param count Number of char. positions to move characters left.
	  */
	 public void scrollLeft(int count)
	 {
	     int copyCount = mLength - count;

	     if (copyCount < 0) {
		 mLength = 0;
	     } else {
		 System.arraycopy(mChars, count, mChars, 0, copyCount);
		 System.arraycopy(mCharAttrs, count, mCharAttrs, 0, copyCount);
		 mLength -= count;
	     }
	 }

	 /**
	  * Method for moving contents of this line left 'count' times.
	  * Last 'count' characters in the line are discarded (except if
	  * there's enough empty space at the end of the line)
	  *
	  * @param count Number of char. positions to move characters right.
	  * @param width Width of this line
	  */
	 public void scrollRight(int count, int width)
	 {
	     if (count > width) {
		 count = width;
	     }

	     // All chars still fit in?
	     int copyCount = mLength;

	     if ((mLength + count) > width) {
		 copyCount = width - mLength;
	     }
	     System.arraycopy(mChars, 0, mChars, count, mLength);
	     System.arraycopy(mCharAttrs, 0, mCharAttrs, count, copyCount);
	     // Need to clear the beginning of the line:
	     CharAttrs defAttrs = mDisplay.mDefaultCharAttrs;

	     for (int i = 0; i < count; ++i) {
		 mChars[i] = sDefChar;
		 mCharAttrs[i] = defAttrs;
	     }
	     mLength += count;
	     if (mLength > width) {
		 mLength = width;
	     }
	 }

	 /**
	  * This method is called when the width of the terminal window
	  * changes. Depending on how width changes we may need to reallocate
	  * the char/attr arrays (grows) or truncate lines (shrinks).
	  */
	 public void resize(int newWidth)
	 {
	     if (newWidth < mLength) {
		 mLength = newWidth;
	     } else {
		 if (newWidth > mChars.length) {
		     char[] oldChars = mChars;
		     CharAttrs oldAttrs = mCharAttrs;
		     
		     mChars = new char[newWidth];
		     mCharAttrs = new CharAttrs[newWidth];
		     
		     System.arraycopy(oldChars, 0, mChars, 0, oldChars.length);
		     System.arraycopy(oldAttrs, 0, mCharAttrs, 0, oldAttrs.length);
		 }
	     }
	 }

	 /**
	  * This method is called to make sure that cursor can be moved to
	  * the specified column. It 'fills in blanks' if necessary, so that
	  * cursor is always inside valid region:
	  *
	  * @param column location that has to be included in this line
	  */
	 public void checkLength(int column)
	 {
	     if (column > mLength) {
		 CharAttrs defAttrs = mDisplay.mDefaultCharAttrs;
		 for (int i = mLength; i < column; i++) {
		     mChars[i] = sDefChar;
		     mCharAttrs[i] = defAttrs;
		 }
		 mLength = column;
	     }
	 }
	 
	 /**
	  * Method for erasing (ie. clearing) a line (possibly saving
	  * protected chars). Different from deleting (at higher level)
	  * in that deletion moves lines below up, erasing does not.
	  *
	  * @param eraseAll If true erases all characters, including
	  *    protected ones, if false, leaves protected characters.
	  * @param cursorColumn If the cursor is on this line, the column
	  *    cursor is over, otherwise -1. Lines can't be truncated to
	  *    be too short to contain the cursor.
	  */
	 public void eraseLine(boolean eraseAll, int cursorColumn)
	 {
	     CharAttrs defAttrs = mDisplay.mDefaultCharAttrs;
	     if (eraseAll) {
		 mLength = cursorColumn;
		 for (int i = mLength; --i >= 0; ) {
		     mChars[i] = mDefChar;
		     mCharAttrs[i] = defAttrs;
		 }
	     } else {
		 int i = mLength;
		 /* All non-protected chars from the end can really be removed,
		  * and the line length updated:
		  */
		 while (--i >= 0 && !mCharAttrs[i].isProtected()) {
		     mChars[i] = defChar;
		     mCharAttrs[i] = defAttrs;
		 }
		 mLength = i + 1;
		 /* And the rest of non-protected may be removed too, but
		  * this doesn't update line length:
		  */
		 while (--i >= 0) {
		     if (mCharAttrs[i].isProtected()) {
			 mChars[i] = defChar;
			 mCharAttrs[i] = defAttrs;
		     }
		 }
	     }
	 }

	 /**
	  * Method for erasing (ie. clearing) end of line, from cursor
	  * position (inclusive). Protected characters may or may not
	  * be erased.
	  *
	  * @param eraseAll If true erases all characters, including
	  *    protected ones, if false, leaves protected characters.
	  * @param cursorColumn The column (char position) from which
	  *    characters are to be erased.
	  */
	 public void eraseEOL(boolean eraseAll, int cursorColumn)
	 {
	     // Perhaps we are already at the end of the line?
	     if (cursorColumn >= mLength) {
		 return;
	     }

	     if (eraseAll) {
		 // easy:
		 mLength = cursorColumn - 1;
	     } else {
		 CharAttrs defAttrs = mDisplay.mDefaultCharAttrs;
		 /* All non-protected chars from the end can really be removed,
		  * and line length updated:
		  */
		 int i = mLength;
		 while (--i >= mCurrCol && !mCharAttrs[i].isProtected) {
		     mChars[i] = sDefChar;
		     mCharAttrs[i] = defAttrs;
		 }
		 mLength = i + 1;
		 /* And the reset of non-procted may be removed too, but
		  * this doesn't update line length:
		  */
		 while (--i >= mCurrCol) {
		     if (!mCharAttrs[i].isProtected()) {
			 mChars[i] = sDefChar;
			 mCharAttrs[i] = defAttrs;
		     }
		 }
	     }
	 }

	 /**
	  * Method for erasing (ie. clearing) start of line, up to cursor
	  * position (inclusive). Protected characters may or may not
	  * be erased.
	  *
	  * @param eraseAll If true erases all characters, including
	  *    protected ones, if false, leaves protected characters.
	  * @param cursorColumn The column (char position) up to which
	  *    characters are to be erased.
	  */
	 public void eraseSOL(boolean eraseAll, int cursorColumn)
	 {
	     CharAttrs defAttrs = mDisplay.mDefaultCharAttrs;

	     for (int i = 0; i <= cursorColumn; i++) {
		 if (eraseAll || !mCharAttrs[i].isProtected()) {
		     mChars[i] = sDefChar;
		     mCharAttrs[i] = defAttrs;
		 }
	     }
	 }

	 /**
	  * Method for erasing (ie. clearing) specified number of characters
	  * starting from current cursor column (inclusive) to right.
	  * Does not move other characters (that would be 'deleteChars')
	  *
	  * @param cursorColumn The column (char position) starting from which
	  *    characters are to be erased.
	  * @param count Number of characters to erase.
	  */
	 public void eraseChars(int cursorColumn, int count)
	 {
	     CharAttrs defAttrs = mDisplay.mDefaultCharAttrs;
	     int last = cursorColumn + count;

	     // Erasing end of line? Can change line length then as well:
	     if (last >= mLength) {
		 last = mLength;
		 mLength = cursorColumn+1;
	     }
	     for (int i = cursorColumn; i < last; ++i) {
		 mChars[i] = sDefChar;
		 mCharAttrs[i] = defAttrs;
	     }
	 }

	 /**
	  * Method for painting this line on the provided graphics
	  * context.
	  *
	  * @param g
	  * @param coords
	  * @param fonts
	  * @param fontWidth
	  * @param fontBase
	  * @param blinkedOut
	  * @param reversed
	  */
	 public void paintLine(Graphics g, Rectangle coords,
			       Font[] fonts, int fontWidth, int fontBase,
			       boolean blinkedOut, boolean reversed)
	 {
	     int last = mLength;
	     int baseX = coords.x;
	     Font currFont = 0;

	     for (int i = 0; i < last; ) {
		 /* Are we still supposed to have ctrl chars here?
		  * If so let's just skip them (tabs?)
		  */
		 if (mChars[i] < 32) {
		     x += fontWidth;
		     ++i;
		     continue;
		 }
	    
		 CharAttrs currAttr = mCharAttrs[i];
	    
		 // Normal chars should be printed ok.
		 for (int j = i + 1; j < last; j++) {
		     if (mChars[j] < 32 || mCharAttrs[i] != currAttr) {
			 break;
		     }
		 }

		 // Ok, let's draw the text run:
		 coords.x = baseX + (i * fontWidth);
		 Font nextFont = fonts[currAttrs.getFontIndex()];
		 if (nextFont != currFont) {
		     g.setFont(currFont);
		 }
		 CharAttrs.paintText(mChars, i, j-i, coords, fontBase,
				     blinkedOut, reversed);
	     /* CharAttrs: 
    public void paintText(char[] text, int start, int len,
			  Rectangle coords, int baseline,
			  boolean blinkOn, boolean reversed)
	     */
	     }
	 }
    } // class DisplayLine
} // class Display
