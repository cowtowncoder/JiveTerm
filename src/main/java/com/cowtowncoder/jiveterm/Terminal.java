/**************************************

Project:
    JiveTerm.

    A VT52/VT100/VT102-compliant
    telnet/terminal program
    written in pure java.

    (C) 1998-2001 Tatu Saloranta, tatu.saloranta@iki.fi.

Module:
    Terminal.java

Last changed:
    27-Sep-2001

Description:
    This class handles the communication
    with the server side using the normal
    telnet-protocol, and also decodes the
    VT-control sequences, and writes the
    resulting internal control codes to
    Display class.

Changed:

  24-Jan-99, TSa:
    Added support for tab-handling.
  03-Feb-99, TSa:
    Trying to add support for conformance-level
    handling.
  06-Feb-99, TSa:
    16-bit internal arguments.
  25-Feb-99, TSa:
    Moving slow-down - functionality from Display
    to Terminal; drawing part need not worry about
    9600 bps etc restrictions.
  23-Apr-99, TSa:
    To support SSH, now handling of telnet's in-band
    signalling is optional.

**************************************/

package com.cowtowncoder.jiveterm;

import java.util.*;
import java.io.*;
import java.net.*;
import java.awt.*;
import java.awt.image.*;
import java.awt.event.*;
import java.awt.*;
import java.applet.Applet;

import jiveterm.*;

/****************************************************************

This is the class that actually implements the connection between
the server; translates the input from the server, and sends the
user input to the server.

****************************************************************/

final class
Terminal
extends Thread
{
  private final static byte [] sAnswerbackMsg = "JIVETERM 1.0".getBytes();

  // VT has 2 "char set sets" called G0 and G1:
  public final int NR_OF_CHARSETS = 2;

  /* *** Default states for terminal settings: *** */

  /* *** Character sets we may or may not support: *** */
  public final static int CHAR_SET_G0 = 0;
  public final static int CHAR_SET_G1 = 1;

  public final static int CHAR_SET_ASCII = 2;
  public final static int CHAR_SET_GFX = 3;
  public final static int CHAR_SET_UK = 4;
  public final static int CHAR_SET_ALT_ASCII = 5;
  public final static int CHAR_SET_ALT_GFX = 6;

  /* *** Ctrl code bytes we need to handle: * ***/
  public final static byte BYTE_NULL = (byte) 0x00;
  public final static byte BYTE_ENQ = 0x05;
  public final static byte BYTE_BELL = 0x07;
  public final static byte BYTE_BS = 0x08;
  public final static byte BYTE_TAB = 0x09;
  public final static byte BYTE_LF = 0x0A;
  public final static byte BYTE_VT = 0x0B; // Vertical tab
  public final static byte BYTE_FF = 0x0C; // Form feed
  public final static byte BYTE_CR = 0x0D;
  public final static byte BYTE_INVOKE_G1 = 0x0E;
  public final static byte BYTE_INVOKE_G0 = 0x0F;

  public final static byte BYTE_ESC = (byte) 0x1B;
  public final static byte BYTE_ESC_8BIT = (byte) 0x9B;
  public final static byte BYTE_CAN = (byte) 030;
  public final static byte BYTE_SUB = (byte) 032;

  public final static char CHAR_TAB = '\t';
  public final static char CHAR_CR = '\r';
  public final static char CHAR_LF = '\n';
  public final static byte BYTE_QUESTION_MARK = (byte) '?';
  public final static char CHAR_QUESTION_MARK = '?';
  public final static byte BYTE_BRACKET = (byte) '[';
  public final static char CHAR_BRACKET = '[';
  public final static byte BYTE_DEL = (byte) 127;

  /* Terminal state:
   */
    /* Then some terminal-mode flags (and other flag-like stuff): */
    // Note that these are actually set on run(), to the default
    // values:
    
    /* Various operating modes; some are set by Display, some not: */
    private boolean modeArrowApplication = false;
    private boolean modeCursorKeyApplication;
    private boolean modeKeypadApplication = false;
    private boolean modeNumberPadApplication = false;
    private boolean modeVT52 = false; // Set by certain VT-compatibility codes.

    private boolean modeNewline;
    private boolean modeVT52;
    private boolean modeAutorepeat;
    private boolean modeInterlace;
    private boolean modeGfxCoProc; // Not really necessary,
    //just for the sake of completeness & orthogonality...
    private boolean modePrintFF;
    private boolean modePrintScrollRegion;
    private boolean modeKeyboardLocked;
    private boolean modeEchoOn;
    private boolean modeMaySend8BitCodes;
    private boolean modeDoSend8BitCodes;
    
    private boolean allowBell = false;

    /* Char sets; VT-terms have 2 separate char sets, and commands
     * for both setting these sets and switching between them. Would
     * be cleaner just to have 1 set that can be changed.
     */
    private int[] mCharsets = { CHAR_SET_ASCII, CHAR_SET_GFX };
    private int mUsingCharset = 0;
    private int mCurrCharset = mCharsets[0];
    
    // Normal color set:
    public static Color [] sNormalColors = {
	Color.black, Color.red, Color.green, Color.yellow,
	Color.blue, Color.magenta, Color.cyan, Color.white };
    // Bright colors, normal:
    public static Color sBrightColors[] = {
	Color.black.brighter(), Color.red.brighter(),
	Color.green.brighter(), Color.yellow.brighter(),
	Color.blue.brighter(), Color.magenta.brighter(),
	Color.cyan.brighter(), Color.white.brighter() };
    // Dim colors, normal:
    public static Color sDimColors[] = {
	Color.black.darker(), Color.red.darker(),
	Color.green.darker(), Color.yellow.darker(),
	Color.blue.darker(), Color.magenta.darker(),
	Color.cyan.darker(), Color.white.darker()
    };

    public final static int DEFAULT_FG_INDEX = 0;
    public final static int DEFAULT_BG_INDEX = 7;
    
    public final int defBgroundColorNr = 7, defFgroundColorNr = 0;
    public int currBgroundColorNr, currFgroundColorNr;
    
    public Color defBgroundColor, defFgroundColor;
    public Color currBgroundColor, currFgroundColor;
    
    // VT-xxx modes we support (non-dynamic right now):
    public final static int VT52 = 1;
    public final static int VT100 = 2;
    public final static int VT102 = 3;
    public final static int VT220 = 4;
    public final static int VT320 = 5;
    public final static int VT420 = 6;
    public final static int VT520 = 7;
    
    private int VTMode = VT100;

    /* Main-level entities we communicate with: */
    protected Display mDisplay; // Display we control
    protected JiveConnection mConnection; // Connection (telnet- etc)

    protected byte[] mInputBuffer = new byte[4096];
    protected int mInputPtr = 0;
    protected int mInputSize = 0;

    private Hashtable VT100ctrlCodes = new Hashtable();
    {
	// Not a complete list yet:
	
	VT100ctrlCodes.put(new Integer(CODE_CURSOR_ABS), "\033[*f;");
	
	VT100ctrlCodes.put(new Integer(CODE_ERASE_EOL), "\033[K");
	VT100ctrlCodes.put(new Integer(CODE_ERASE_SOL), "\033[1K");
	VT100ctrlCodes.put(new Integer(CODE_ERASE_LINE), "\033[2K");
	VT100ctrlCodes.put(new Integer(CODE_ERASE_DOWN), "\033[J");
	VT100ctrlCodes.put(new Integer(CODE_ERASE_UP), "\033[1J");
	VT100ctrlCodes.put(new Integer(CODE_ERASE_SCREEN), "\033[2J");
	
	// Send-only codes:
	VT100ctrlCodes.put(new Integer(CODE_CURSOR_UP), "\033[*A");
	VT100ctrlCodes.put(new Integer(CODE_CURSOR_DOWN), "\033[*B");
	VT100ctrlCodes.put(new Integer(CODE_CURSOR_RIGHT), "\033[*C");
	VT100ctrlCodes.put(new Integer(CODE_CURSOR_LEFT), "\033[*D");
    }
    
    public final static byte [] DISPLAY_ERASE = {
	BYTE_BS, (byte) ' ', BYTE_BS
    };
    private byte[] displayCode = new byte[10];
    
    /* Various switches for outputting extra debug-information: */
    private final static boolean mDebugVT = true;
    //private final static boolean mDebugVT = false;
    //private final static boolean dumpVTCodes = true;
    private final static boolean dumpVTCodes = false;
    //private final static boolean preventVTErrors = true;
    private final static boolean preventVTErrors = false;

  /*** Telnet vs. SSH switch(es): ***/

  Terminal(Display d)
  {
      setDisplay(d);
      resetCharsets();
  }
 
  private void setConnection(JiveConnection c)
  {
      mConnection = c;
  }

  private void setDisplay(Display d)
  {
      mDisplay = d;
  }

  /* Not sure what specifically should hard reset do what soft
   * does not...
   */

  // Hard reset -> clears screen, soft not...
  public void hardResetTerminal(boolean repaint)
  {
      softResetTerminal(false);
      display.eraseScreen();
      if (repaint) {
	  display.redrawScreen();
      }
  }

  public void softResetTerminal(boolean repaint)
  {
    boolean old_inv = modeScreenReversed;

    display.resetCharAttrs();

    // Modes should be reset to default values:
    master.setModeNewline(modeNewline = false);
    modeCursorKeyApplication = false;
    modeVT52 = false;
    mode132Cols = false;
    modeAutorepeat = false;
    modeInterlace = false;
    modeGfxCoProc = true;
    modeKeypadApplication = false;
    modePrintFF = false;
    modePrintScrollRegion = false;
    modeKeyboardLocked = false;
    modeEchoOn = false;
    master.setEcho(0, false);

    // Not sure if this should reset 8-bitness off?
    modeMaySend8BitCodes = false;
    modeDoSend8BitCodes = false;

    // And scrolling region should be made window size:
    scrollRegionTop = 0;
    scrollRegionBottom = sizeInCharsH - 1;

    // Char sets better be initialized:
    resetCharsets();

    // And also colour 'palettes', due to reverting back to
    // non-inverted mode:
    currColors = normalColors;
    currBrightColors = brightColors;
    currDimColors = dimColors;

    currBgroundColorNr = defBgroundColorNr;
    currBgroundColor = defBgroundColor = currColors[currBgroundColorNr];
    currFgroundColorNr = defFgroundColorNr;
    currFgroundColor = defFgroundColor = currColors[currFgroundColorNr];

    display.softResetDisplay(repaint);
  }

  public void resetCharsets()
  {
    mCharset[0] = CHAR_SET_ASCII;
    mCharset[1] = CHAR_SET_GFX;
    mUsingCharset = 0;
    mCurrCharset = mCharset[mUsingCharset];
  }

  /* This function handles the control characters except for ESC: */
  public void handleCtrlChar(byte b)
  {
      /* Note that JiveConnection should already have filtered some
       * input, such as NVT-ascii weirdnesses in telnet connections;
       * NULLs won't (shouldn't) be encountered, linefeeds are all
       * just \n's etc.
       */
    switch (b) {
	// Shouldn't be seen:
    case BYTE_NULL:

      if (debugCtrlCodes) {
	master.doWarningLF("Warning: byte 0 encountered in terminal.");
      }
      return;

      // Form feed -> linefeed
    case BYTE_FF:
      // Vertical tab -> linefeed as well
    case BYTE_VT:
    case BYTE_LF:
	display.printLinefeed();
	break;

      // DEL should be discarded... (shouldn't even be sent, but just
      // in case let's check it):
      /* Unfortunately, DEL probably can't be handled here, it's not
       * a control code (ascii 127).
       */
    case BYTE_DEL:

      if (debugCtrlCodes) {
	master.doWarningLF("Warning(VT): DEL-char gotten, discarding.");
      }
      return;

      // These codes should be handled by the display...
    case BYTE_TAB:
	display.printTab();
	break;
    case BYTE_BELL:
	// How do we display visual (or aural) bell?
	display.printBell();
	break;

    case BYTE_BS:
	display.printBackspace();
	break;

    case BYTE_INVOKE_G0:
	useCharset(0);
	break;

    case BYTE_INVOKE_G1:
	useCharset(1);
	break;

    case BYTE_CR:
	display.printCarriageReturn();
	break;

	// ENQ -> send answerback message?
    case BYTE_ENQ:
	sendBytes(sAnswerbackMsg, false);
	break;

      // Other codes are quietly filtered:
    default:

      if (debugCtrlCodes) {
	master.doWarningLF("DEBUG: ctrl-char "+((int) b &0xFF)+
			 " gotten, skipping.");
      }
      return;
    }   
  }

  /* Reads in the short, vt52-compatible, vt-codes (not only
   * in vt52 mode, though)
   */
  public void handleShortCodes(byte b)
  throws VTCommandCancelled, VTCommandInterrupted,
    VTCommandInterrupted8Bit
  {
    switch (b) {
      /* Cursor up: */
    case (byte) 'A':
	display.moveCursor(0, -1);
	break;

      /* Cursor down: */
    case (byte) 'B':
	display.moveCursor(0, 1);
	break;

      /* Cursor right: */
    case (byte) 'C':
	display.moveCursor(1, 0);
	break;

      /* Reset device (non-VT52?): */
    case (byte) 'c':

	softResetTerminal(true);
	if (mDebugVT) {
	    master.doWarningLF("Warning: Reset device requested.");
	}

      break;

      /* Cursor left (VT52) or 'Index down' (VT100/ANSI ???): */
      /* Apparently, 'Index' simply means "move cursor down by one
       * but keeping the same column; do linefeed if necessary
       */

    case (byte) 'D':

      if (VTMode == VT52 || modeVT52) {
	  display.moveCursor(0, 1);

	  /* Indexing can _not_ be done by simple "cursor down" command
	   * because it needs to scroll stuff up if already at the bottommost
	   * row...
	   */
      } else {
	  display.indexDown(1);
      }
      break;

      // Next Line (ANSI-compatible mode, simple enter/newline?);
      // no matching VT52-mode command.
    case (byte) 'E':

	display.printLinefeed();
	break;

      /* Select special graphics character set ('enter gfx mode'): */	
    case (byte) 'F':

	useCharset(1);
	break;

      /* Select ASCII character set ('exit gfx mode': */
    case (byte) 'G':
	useCharset(0);
	break;
      
      /* Set tab at current column (VT100, ansi), or home (VT52): */
    case (byte) 'H':
	  
      if (VTMode == VT52 || modeVT52) {
	  display.setCursorPosition(0, 0);
      } else {
	  display.addTab();
      }
      break;

      /* Local printing (non-VT52): */
    case (byte) 'i':

      if (!preventVTErrors) {
	master.doWarningLF("Warning: 'Local Printing' received, not handled currently.");
      }
      break;

      /* "Reverse linefeed" (VT52). Up by one, no linefeed ("not "reverse newline") */
    case (byte) 'I':

	display.indexUp(1);
	break;

      /* Erase to end of Screen (VT52) */
    case (byte) 'J':
	
	display.eraseDown();
	break;

      /* Erase to end of Line (VT52) */
    case (byte) 'K':

	display.eraseEOL();
	break;

      /* Index (down?) (VT100): */
    case (byte) 'L':

	display.indexDown(1);
	break;

      /* Reverse index (up) (VT100): */
      // (In one list, was referred to as 'reverse linefeed'... an error?)
      // Ie. simply moves cursor up by one line without changing columns.
    case (byte) 'M':

	display.indexUp(1);
	break;

      /* 'Map G2 to GL for next char only, single shift' (Kermit-docs) */
      // Ie. 'switch to G1'
	// ... but only for one char? Really?
    case (byte) 'N':
	useCharset(1);
	break;

      /* 'Map G3 to GL for next char only, single shift' (Kermit-docs) */
      // Ie. 'switch to G0'
    case (byte) 'O':
	useCharset(0);
	break;

      /* 'Device Control String Introducer' (Kermit-docs). Wild. */
    case (byte) 'P':

      if (!preventVTErrors) {
	master.doWarningLF("Warning: 'Device Control String Introducer', not handled currently.");
      }
      return;

      /* 'Start Protected Area (erasure protection)' (Kermit-docs) */
    case (byte) 'V':
	setCharAttr(CharAttrs.FX_PROTECTION, true);
	break;

      /* 'End Protected Area (erasure protection)' (Kermit-docs) */
    case (byte) 'W':
	setCharAttr(CharAttrs.FX_PROTECTION, false);
	break;

      /* Direct cursor address (VT52) */
    case (byte) 'Y':

      int y = checkChar(getNextByte()) - 32;
      int x = checkChar(getNextByte()) - 32;

      /* 2 characters follow; the first is for line, second column;
       * characters are 32 + number (that is, home, (0, 0), translates
       * to (32, 32); although in general indexes in vt-xxx begin from
       * 1, not 0)
       */
      display.setCursorPosition(x, y);
      break;

      /* Report the device code ("Identify"): */
    case (byte) 'Z':	

	reportDeviceCode();
	break;

      // "Graphic proc option" on:
      /* Silly code, no effect, but let's process it nevertheless:
       * Heh. In addition, according to Kermit-docs, this may also
       * read as "Enter Tektronix sub-mode" (as an alias to
       * ESC + ^L... weird)
       */
   case (byte) '1':
       setModeGfxCoProc(true);
       break;

      // "Graphic proc option" off:
      // ... as with the previous code...
    case (byte) '2':
       setModeGfxCoProc(false);
       break;

      /* DECBI, back index (VT4xx); cursor left by one, scrolling if
       * at the left end:
       */
    case (byte) '6': 
	display.indexLeft(1);
	return;

      /* Save cursor + attrs: */
    case (byte) '7':
	saveCursor();
	break;

      /* Restore cursor + attrs: */
    case (byte) '8':
	restoreCursor();
	break;

      /* DECFI, forward index (VT4xx); cursor right by one, scrolling if
       * at the right end:
       */
    case (byte) '9': 

	display.indexRight(1);
	return;

    default:
    
      if (!preventVTErrors) {
	master.doWarningLF("Warning: Unknown short VT-code ESC + "
			   +(char) b + " received.");
      }
      break;

    }
  }

  private final int getFirstVTArg(int def_value)
  {
    return (VTAttrCount < 1) ? def_value : VTAttr[0];
  }

  private final int getNthVTArg(int index, int def_value)
  {
    return (VTAttrCount <= index) ? def_value : VTAttr[index];
  }

  /* These codes are mostly VT-100 compliant, and begin with the
   * open bracket (already read in, b is the 'first' character of
   * the code itself
   */

  /* Returns TRUE if all went ok (or current command was cancelled!),
   * and FALSE if another ESC-initiated interrupted this command.
   */
  public boolean handleBracketCodes(char c)
    throws VTCommandCancelled, VTCommandInterrupted,
    VTCommandInterrupted8Bit
  {
    int i, j, code;
    byte b;

    switch (c) {

      /* Kermit-docs say that it's "ANSI Cursor Forward N Columns"...*/
    case 'a':
	display.moveCursor(getFirstVTArg(1), 0);
	break;
	    
      /* Cursor Up */
    case 'A':
	display.moveCursor(-getFirstVTArg(1), 0);
	break;
	    
      /* Cursor Down */
    case 'B':
	display.moveCursor(0, getFirstVTArg(1));
	break;

      /* Query/report the device-code: */
      /* Probably depends on the VT-emulation in use... ? */
    case 'c':
	reportDeviceCode();
	break;

      /* Cursor Forward (right) */
    case 'C':
	display.moveCursor(0, getFirstVTArg(1));
	break;
	    
      /* According to Kermit-docs, "ANSI Cursor to row N, absolute" */
    case 'd':
      if (VTAttrCount < 1) {
	master.doWarningLF("Warning: ESC + [ + d (ANSI Set Cursor Row Abs) without argument received, ignored.");
      } else {
	  // Columns start from 1 on VT-stuff:
	  display.setCursorX(getFirstVTArg(1) - 1);
      }
      break;
    
      /* Cursor Back (left) */
    case 'D':
	display.moveCursor(-getFirstArg(1), 0);
	break;

      /* Kermit-docs say that it's "ANSI Cursor Down N Rows"...*/
    case 'e':
	display.moveCursor(0, getFirstArg(1));
	break;

      /* Next-line; like CR+LF but can be repeated arg times: */

    case 'E':
	i = getFirstVTArg(1);
	while (--i >= 0) {
	    display.printLinefeed();
	}
	break;
    
      /* Reverse-index, arg -> number of lines. */

    case 'F':
	display.indexUp(getFirstVTArg(1));
	break;

      /* Cursor position Force == Cursor home */
      /* We'll essentially limit the max. coords to 255. bah. */
      // First arg -> line, second -> col ("wrong order")
    case 'f':
      /* Cursor Home, ~= Set Cursor Abs. Position */
    case 'H':

	/* Note that top-left is (1, 1) in vtXXX, thus we need to subtract
	 * 1 from both indices:
	 */
	y = getFirstVTArg(1) - 1;
	x = getNthVTArg(1, 1) - 1;
	display.setCursorPosition(x, y);
	break;
	    
      /* Control Tabs */
    case 'g':

      /* 0 -> Clear tab at present position,
       * 3 -> Clear all tabs
       * ... vttest seems to send 1s and 2s too for testing they
       * really are NOPs... *grin*
       */
      switch (getFirstVTArg(0)) {
      case 0:
	  display.removeTab();
	  break;
      case 3:
	  display.removeAllTabs();
	  break;
      default:
	if (!preventVTErrors) {
	    master.doWarningLF("Warning: ESC + [ + g (Clear tab(s)) received with unknown argument '"+i+"'; treating as NOP.");
	}
      }
      break;

      /* Cursor to absolute column. */
    case 'G':

      if (VTAttrCount < 1) {
	if (!preventVTErrors) {
	  master.doWarningLF("Warning: ESC + [ + G (ANSI Set Cursor Col Abs) without arguments; ignoring.");
	  return true;
	}
      }
      display.setCursorX(getFirstVTArg(1) - 1);
      break;

      /* 'h' (high) is for setting an attribute on: */
    case 'h':

      if (VTAttrCount < 1) {
	master.doWarningLF("Warning: 'Esc + [ + h' with no arguments received.");
      } else {
	setVTMode(false);
      }
      break;

      /* 'i' -> ("Media Copy" tells Kermit-doc) Printing, with various args... */

    case 'i':

      if (!preventVTErrors) {
	master.doWarningLF("Warning: 'ESC + [ + i' (print xxx) received; printing not implemented.");
      }
      break;

      /* 'I' -> Horizontal index (forward by <arg> tabs): */
    case 'I':

	i = getFirstVTArg(-1);
	if (i == -1) {
	    master.doWarning("Warning: 'Esc + [ + I' (Horizontal Index) with no arguments received; defaulting to one tab.");
	    i = 1;
	} else if (i < 1) {
	    // Actually, has to be zero as we can't get negative numbers...
	    master.doWarning("Warning: 'Esc + [ + I' (Horizontal Index) with count "+i+", skipping.");
	}

	for (; --i >= 0; ) {
	    display.printTab();
	}
	break;

      /* 'l' (low) is for setting an attribute off: */
    case 'l':

      if (VTAttrCount < 1) {
	master.doWarning("Warning: 'Esc + [ + l' with no arguments received.");
	return true;
      }
      resetVTMode(false);
      break;

      /* Query/report the device-status: */
      /* Better report we are functioning ok, right? */
    case 'n':

      if (VTAttrCount < 1) {
	master.doWarning("Warning: VT-100 code 'ESC [ n' with no arguments received.");
	return true;
      }

      switch (VTAttr[0]) {
      case 5:
	  // This means server is asking our status; we have to answer.
	reportStatus();
	return true;

      case 6:

	  /* And this means they want to know cursor location...
	   * fair enough
	   */
	  reportCursorPosition();
	  break;

      default:
	if (!preventVTErrors) {
	  master.doWarningLF("Warning: 'ESC + [ + n' with unknown argument, "
            + VTAttr[0]+", received, ignored.");
	}
	return true;
      }
      break;

      /* Erase (clear) lines below/above/both: */
    case 'J':

      if (VTAttrCount == 0) {
	  i = 0;
      } else {
	  i = VTAttr[0];
      }
      switch (i) {
      case 0:
	  display.eraseDown();
	  break;
      case 1:
	  display.eraseUp();
	  break;
      case 2:
	  display.eraseScreen();
	  break;

      default:
	  if (!preventVTErrors) {
	      master.doWarningLF("Warning: ESC + [ + J with invalid argument ("+VTAttr[0]+" received, ignored.");
	  }
      }
      break;
	    
      /* Erase parts of the current line: */
    case 'K':

      if (VTAttrCount == 0) {
	  i = 0;
      } else {
	  i = VTAttr[0];
      }
      switch (i) {
      case 0:
	  display.eraseEOL();
	  break;
      case 1:
	  display.eraseSOL();
	  break;
	/* Erase Line: */
      case 2:
	  display.eraseLine();
	  break;
      default:
	  if (!preventVTErrors) {
	      master.doWarningLF("Warning: ESC + [ + K with invalid argument ("+VTAttr[0]+" received, ignored.");
	  }
      }
      break;

      /* Insert a line (VT102): */
    case 'L':
	display.insertLines(getFirstVTArg(1));
	break;

      /* Set attribute(s): */
    case 'm':

	// No arguments -> reset
      if (VTAttrCount < 1) {
	VTAttr[0] = (byte) 0;
	VTAttrCount = 1;
      }

      for (i = 0; i < VTAttrCount; i++) {
	  code = VTAttr[i];
	  
	      // Foreground:
	  if (code >= 30 && code < 38) {
	      setForegroundIndex(code - 30);

	      // Background:
	  } else if (code >= 40 && code < 48) {
	      setBackgroundIndex(code - 40);

	      // Effect:
	  } else {
	      setVTAttribute(code);
	  }
      }
      break;

      /* Delete a line (VT102): */
    case 'M':

	display.deleteLines(getFirstVTArg(1));
	break;

      /* Define key (macro?) */
    case 'p':

      if (!preventVTErrors) {
	master.doWarningLF("Debug: ESC + [ + p, 'define macro' received, not implemented.");
      }
      break;

      /* Delete character(s), from the cursor _left_ (VT102): */
    case 'P':

	display.deleteChars(getFirstVTArg(1));
	break;

      // Load LEDs (DEC private)
    case 'q':

	if (!preventVTErrors) {
	    master.doWarningLF("Debug: DECLL (Load leds) received, not implemented");
	}
	break;
	
      /* (Set) scrolling region (VT102?): */
    case 'r':

      // Takes 2 arguments, or 0 if removing the scrolling region
      if (VTAttrCount == 0) {
	  display.clearScrollingRegion();
      } else {
	  display.setScrollingRegion(getFirstVTArg(0, 0) - 1,
				     getNthVTArg(1, Int.MAX_VALUE) - 1);
      }
      break;

      /* Save cursor: */
    case 's':
	saveCursor();
	break;

      /* VT-330, Set Lines Per Page... Pages not implemented */
    case 't':

	if (!preventVTErrors) {
	    master.doWarningLF("Warning: 'Set Lines Per Page' received; not implemented, ignoring.");
	}
	break;
	
      /* Restore (unsave) cursor: */
    case 'u':
	restoreCursor();
	break;

      /* VT-330, move to next page; pages not implemented. */
    case 'U':

      if (!preventVTErrors) {
	master.doWarningLF("Warning: 'Move To Next Page' received; pages not implemented, skipping the command.");
      }
      break;

      /* VT-330, move to previous page; pages not implemented. */

case 'V':

      if (!preventVTErrors) {
	master.doWarningLF("Warning: 'Move To Previous Page' received; pages not implemented, skipping the command.");
      }
      break;

      /* VT-220, Erase Characters (at and right of cursor claims kermit): */
    case 'X':
	display.eraseChars(getFirstVTArg(1));
	break;

      /* Reqest/report terminal parameters. */
    case 'x':
	// Should the argument be obligatory? Defaulting to 1 for now:
	i = getFirstVTArg(1);

	switch (i) {
	case 0: /* Terminal is allowed to send unsolicited reports. In
		 * addition, a report is expected...
		 */
	case 1: // Terminal is to report the terminal parameters now:
	    reportTerminalParameters(i);
	    break;

	default:

	    if (!preventVTErrors) {
		master.doWarningLF("Warning: 'ESC [ <params> x' (report terminal status) received with unknown second argument, "+i+".");
	    }
	}
	break;

      /* Invoke Confidence Test: */
      /* Accepts 2 args; 2 + <tests> would invoke following
       * tests (tests is a sum of the parts):
       * 1 -> POST (ROM checksum, RAM NVR, keyboard and AV0)
       * 2 -> Data Loop Back (Loopback connector required)
       * 4 -> EIA Modem Control test (Loopback connector required)
       * 8 -> Repeat testing until failure
       * If <tests> is 0, no test is performed, but VT100 is reset.
       *
       * Interestingly, nothing is reported back?
       */
 
    case 'y':

	i = getFirstVTAttr(0);
	// Should other arguments cause the reset too?
	if (i == 0) {
	    hardResetTerminal(true);
	} else {
	    if (!preventVTErrors) {
		master.doWarningLF("Warning: 'Invoke Confidence Test' received; currently not implemented (except with argument 0, to mean RESET).");
	    }
	}
	break;

      /* ANSI (not VT-102 says vttest...) 'Insert Character' function: */
    case '@':

	display.insertChars(getFirstVTAttr(1));
	if (mDebugVT) {
	    master.doWarningLF("Debug: ESC + [ + @ is a 'ANSI-only' function, INSERT_CHAR, not really supported by VT-102.");
	}
	break;

      /* Probably DEC-specific as well: */
    case '"':

      b = getNextByte();
      c = checkChar(b);

      switch (c) {

	/* Actually, this should also do a soft reset? */
      case 'p': // Set conformance level:

	if (VTAttrCount < 1) {
	  master.doWarningLF("Warning: ESC + { + \" + <args> + p (Set Conformance level gotten without arguments; ignoring.");
	  return true;
	}

	setConformanceLevel(getFirstVTArg(0),
			    getNthVTArg(0));
	break;

      default:
	if (!preventVTErrors) {
	  master.doWarningLF("Debug: ESC + [ + \" + "+c+"; an unknown special code.");
	}
	return true;
      }
      break;

default:
    
      if (!preventVTErrors) {
	master.doWarningLF("DEBUG: Unknown extended command ("
			   +"ESC + [ + "+c + ", ascii "+((int) c)
			   +"), with "+VTAttrCount+" args, received.");
      }
      return true;
    }

    return true;
  }

  /* *** (Local) character attribute changes:  *** */
  private boolean mCharAttrsChanged = false;
  private boolean mCharReversed = false;
  private boolean mCharBright = false;
  private boolean mCharDim = false;
  private boolean mCharInvisible = false;
  private int mCharAttrs = 0;
  private int mCharFgIndex, mCharBgIndex;

  public void resetCharAttrs()
  {
      mCharAttrsChanged = true;
      mCharAttrs = 0;
      mCharFgIndex = DEFAULT_FG_INDEX;
      mCharBgIndex = DEFAULT_BG_INDEX;
      mCharReversed = false;
      mCharBright = false;
      mCharDim = false;
      mCharInvisible = false;
  }

  public void setCharReversed(boolean on) { mCharReversed = on; }
  public void setCharBright(boolean on)
  {
      mCharBright = true;
      if (on) {
	  mCharDim = false;
      }
  }
  
  public void setCharDim(boolean on)
  {
      mCharDim = on;
      if (on) {
	  mCharBright = false;
      }
  }
  
  public void setForegroundIndex(int color)
  {
    mCharAttrsChanged = true;
    mFgIndex = color;
  }

  public void setBackgroundIndex(int color)
  {
      mCharAttrsChanged = true;
      mBgIndex = color;
  }

  public void setCharAttr(int attr, boolean state)
  {
      mCharAttrsChanged = true;
      if (state) {
	  mCharAttrs |= attr;
      } else {
	  mCharAttrs &= ~attr;
      }
  }

  public void setVTAttribute(int attr)
  {
      boolean set = true;

      switch (attr) {
      case 0: // reset
	  resetCharAttrs();
	  break;

      case 21:
	  set = false;
      case 1: // Bright (or bold; implementationn-dependant):
	  setCharBright(set);
	  break;

      case 22:
	  set = false;
      case 2: // Dim:
	  setCharDim(set);
	  break;

      case 23:
      case 3: // ????
	  break;
	  
      case 24:
	  set = false;
      case 4: // Underlining:
	  setCharAttr(CharAttrs.FX_UNDERLINING, set);
	  break;

      case 25:
	  set = false;
      case 5: // Blinking:
	  setCharAttr(CharAttrs.FX_BLINKING, set);
	  break;

      case 26:
      case 6: // ????
	  break;

      case 27:
	  set = false;
      case 7: // Reversed:
	  setCharReversed(set);
	  break;

      case 28:
	  set = false;
      case 8: // (Hidden) invisible. How to implement? Same fg/bg?
	  setCharInvisible(set);
	  break;

      default:
	if (!preventVTErrors) {
	  master.doWarningLF("Debug: unrecognized VT-effect, "+attr);
	}
      }
  }

  public void applyCharAttrs()
  {
      Color fg, bg;

      if (mCharBright) {
	  fg = sBrightColors[mCharFgIndex];
	  bg = sBrightColors[mCharBgIndex];
      } else if (mCharDim) {
	  fg = sDimColors[mCharFgIndex];
	  bg = sDimColors[mCharBgIndex];
      } else {
	  fg = sNColors[mCharFgIndex];
	  bg = sNormalColors[mCharBgIndex];
      }

      if (mCharReversed) {
	  Color tmp = fg;
	  fg = bg;
	  bg = tmp;
      }

      // Invisible... let's just set foreground to background. :-)
      if (mCharInvisible) {
	  fg = bg;
      }

      display.setNewCharAttrs(mCharAttrs, fg, bg);
      mCharAttrsChanged = false;
  }

  /* *** And then actual terminal code emulation: *** */

  public void handleQuestionCodes(char c)
  {
    int i;

    // Hmmh. Seems DEC implemented many of the 'normal' commands
    // slightly modified as special commands...
    switch (c) {

    case 'c': // Return the mode?
	      
      if (!preventVTErrors) {
	master.doWarningLF("Warning: 'Esc + ? + .. + c' received, don't"
			   +"know how to handle.");
      }
      return;

    case 'h': // 'High', ie. set:
      
      setVTMode(true);
      return;

    case 'J': // Much  like 'normal' CSI + J, ie. erase-in-display.
      // The difference is that this won't erase protected characters.
      // So, we could easily interpret it, but for now let's not:

      i = getFirstVTAttr(0);
      switch (VTAttr[0]) {
      case 0:
	  display.eraseDownNonSelected();
	  break;
      case 1:
	  display.eraseUpNonSelected();
	  break;
      case 2:
	  display.eraseScreenNonSelected();
	  break;
      default:
	  if (!preventVTErrors) {
	      master.doWarningLF("Warning: ESC + [ + ? + J (Selective Erase in Screen) with invalid argument ("+VTAttr[0]+" received, ignored.");
	  }
      }
      break;

    case 'K': // Much  like 'normal' CSI + K, ie. erase-in-line. The only
      // difference is that this won't erase protected characters.

	switch (VTAttr[0]) {
	case 0:
	    display.eraseEOLNonSelected();
	    break;
	case 1:
	    display.eraseSOLNonSelected();
	    break;
      case 2:
	    display.eraseLineNonSelected();
	    break;
      default:

	if (!preventVTErrors) {
	  master.doWarningLF("Warning: ESC + + [ + ? + K (Selective Erase in Line) with invalid argument ("+VTAttr[0]+") received, ignored.");
	}
      }
      break;

      // 'l' (low?) -> turn an attribute off
    case 'l': // 'Low', ie. reset:

      resetVTMode(true);
      return;
      
    default:
      
      if (!preventVTErrors) {
	master.doWarningLF("Warning: A mode set/reset code (ESC + [ + ? + "
			   + " <arg(s)> <command> encountered; "
			   +"<command> was '"+c
			   +"', not 'h' (high, set), 'l' (low, reset) or 'c'.");
      }
    }
  }

  public void
  handleGtCodes(char c)
  {
      /* Gt comes from '>'-char, Greater Than. These commands are manufacturer
       * specific, usually by DEC I suppose
       */

    switch (c) {
    case 'c':
	report2ndaryDevAttrs();
	break;

    default:
      master.doWarning("<GT-CODE ignored>");
      return;
    }
  }

  public void
  handleQuoteCodes(char c)
  {
    int i;
    // These commands are manufacturer-specific, usually DEC...

    switch (c) {
    case 'q': // Set Character Protection Attributes...

	switch (getFirstVTAttr(0)) {
	case 0: // 0 and 2 -> ok to erase
	case 2:
	    setCharAttr(CharAttrs.FX_PROTECTION, false);
	    break;

	case 1: // 1 -> Not ok to erase (with selective erase)
	    setCharAttr(CharAttrs.FX_PROTECTION, true);
	    break;

	default:
	    if (!preventVTErrors) {
		master.doWarningLF("Warning: unknown attribute "+i+" for DECSCA (Set Char Protection Attrs); allowed values are 0, 1 and 2. Ignoring the command.");
	    }
	    return;
	}
	break;
	
    default:
	
	master.doWarning("Warning: Quote-CODE (ESC + \"" + ((char) c)
			 +" ignored>");
	return;
    }
  }

  public void handleExclamationCodes(char c)
  {
    // And once again, rather non-standard commands...
    switch (c) {
    case 'p': // Soft terminal reset
	softResetTerminal(true);
	break;

    default:
      master.doWarningLF("Unknown exclamation-code (ESC + [ + ! + "+c+") ignored!");
      return;
    }
  }

  /**
   * Method for handling Yet More vendor-specific codes.
   *
   * At the moment nothing much is done here, but let's still try to parse
   * commands so we'll know if someone is actually trying to use the codes
   */
  public void handleDollarCodes(char c)
  {
    // And once again, rather non-standard commands...
    switch (c) {

    case 'r': // Set Rectangular Area to... ?

      // Syntax is Pt; Pl; Pb; Pr; Ps1..PsN $ r
      // where Pt->top, Pl->left, Pb->bottom, Pr->right (edges),
      // and Ps1 to PsN are normal screen attributes to set.
      master.doWarningLF("Debug: ESC + [ + $ + p -> Set Rectangular Area to; not implemented, ignoring.");
      return;

    case '}': // Select Active Status Display; 0->terminal, 1->status

      master.doWarningLF("Debug: Select Active Status Display, with "
         +VTAttrCount+" args, arg#0->"+VTAttr[0]+", ignoring.");
      return;

    case '-': // Set Status Display Type; 0->Blank, 1->Indicator,
      // 2 -> Hist-writable

      master.doWarningLF("Debug: Set Status Display Type, with "+VTAttrCount
			 +" args, arg#0->"+VTAttr[0]+", ignoring.");
      return;

    case '|': // Set page N to 80/132 cols; arg of 0, 80 (or missing)->
      // 80 columsn, 132 -> 132 cols...

      master.doWarningLF("Debug: Set Page #n to 80/132 columns; ignoring.");
      return;

    default:

      master.doWarningLF("Unknown quote-code (ESC + [ + ! + "+c+") ignored!");
    }
  }

  /**
   * An internal method for optimizing redraw of double width rows.
   * The idea is that if we know that the next VT-command will be
   * changing the current row's width status, there's no need to
   * redraw the row yet. This may seem like a tiny optimization, but
   * since in many cases this saves 2 out of 3 redraws when dealing
   * with double-sized rows, it is actually significant (if anyone
   * ever uses doubled stuff).
   *
   * Note that if this returns false, it doesn't guarantee there won't
   * be double-width command coming, but if it returns true it certainly
   * is.
   */
  public boolean isNextDoubleWidthCommand()
  {
      /* Double-width escape codes start with ESC + #...
       */
      if (mInputPtr < (mInputSize + 1)) {
	  byte b1 =  mInputBuffer[mInputPtr];
	  byte b2 =  mInputBuffer[mInputPtr+1];

	  if (b1 == BYTE_ESC && b2 == (byte) '#') {
	      return true;
	  }
      }
      return false;
  }

  /* VT-xxx codes found here are _mostly_ DEC-specific, but not
   * always.
   * Returns TRUE if things go ok (or the command is cancelled!),
   * and FALSE to indicate that another ESC-initiated command interrupted
   * the current command.
   */
  public boolean handleSpecialCodes(char c)
      throws VTCommandCancelled, VTCommandInterrupted,
      VTCommandInterrupted8Bit
  {
    byte b;

    /* Probably all these codes do require the next character
     * to be read also... So we perhaps could check for ESC,
     * CAN or SUB here?
     */

    switch (c) {

    case ' ':

      b = getNextByte(false);
      c = checkChar(b);

      if (c == 'F') {
	set8Bitness(false);
      } else if (c == 'G') {
	set8Bitness(true);
      } else {
	if (mDebugVT) {
	master.doWarningLF("Warning: ESC + <space> + "+(char) b+" is an unknown escape code, ignored.");
	}
	return true;
      }
      break;

    case '#':

      b = getNextByte(false);
      c = checkChar(b);

      /* #3 -> Change this line to double-height top half
       * #4 -> Change this line to double-height bottom half
       * #5 -> Change this line to single-width single-height
       * #6 -> Change this line to double-width single-height
       * #8 -> Fill screen with "E"s (test)
       */
     
     switch (c) {

     case '3':
	 display.setLineEffectDHTop(!isNextDoubleWidthCommand());
	 break;
     case '4':
	 display.setLineEffectDHBottom(!isNextDoubleWidthCommand());
	 break;
     case '5':
	 display.setLineEffectNone(!isNextDoubleWidthCommand());
	 break;
     case '6':
	 display.setLineEffectDW(!isNextDoubleWidthCommand());
	 break;
     case '8':
	 display.setLineEffectTest();
	 break;

     default:
	 if (!preventVTErrors) {
	     master.doWarningLF("Warning: Unknown ESC + # code, ascii "
				+ ((int) c) + ".");
	 }
     }
     break;
     
     
      // Open parenthesis begins G0-designator codes (char set selection):
    case '(':

      b = getNextByte(false);
      c = checkChar(b);

      switch (c) {
      case 'A':
	  setCharset(0, CHAR_SET_UK);
	  break;
      case 'B':
	  setCharset(0, CHAR_SET_ASCII);
	  break;
      case '0':
	  setCharset(0, CHAR_SET_GFX);
	  break;
      case '1':
	  setCharset(0, CHAR_SET_ALT_ASCII);
	  break;
      case '2':
	  setCharset(0, CHAR_SET_ALT_GFX);
	  break;
      default:
	if (!preventVTErrors) {
	  master.doWarningLF("Warning: ESC + ( + Unknown char set '"+
			     ((char) c)+"'; ignoring.");
	}
      }
      break;

      // Closed parenthesis begins G1-designator codes (char set selection):
    case ')':
      // And minus sign sets G1 as well, except it uses an alternate
      // char set (94 byte char set with parenthesis, 96 with minus)
    case '-':

      b = getNextByte(false);
      c = checkChar(b);

      switch (c) {
      case 'A': // UK-ascii / ISO-latin
	  setCharset(1, CHAR_SET_UK);
	  break;
      case 'B': // Ascii
	  setCharset(1, CHAR_SET_ASCII);
	  break;
      case '0': // Dec special gfx
      case '<': // User Preferred Supplemental set
      case '>': // DEC technical set
	  // No idea how to do those, so let's default to basic gfx:
	  setCharset(1, CHAR_SET_GFX);
	  break;
      case '1': // ALT-rom
	  setCharset(1, CHAR_SET_ALT_ASCII);
	  break;
      case '2':
	  setCharset(1, CHAR_SET_ALT_GFX);
	  break;
      case 'H': // Hebrew-ISO (ISO 8859-8)
      case '"': // If followed by '4', Hebrew-7
	  // Yeah nice... Unicode has 'em, but don't know code mapping.
	  // Thus, let's just use alt gfx set; not good but has to do
	  setCharset(1, CHAR_SET_ALT_GFX);
	  if (!preventVTErrors) {
	      master.doWarningLF("Warning: Trying to select Hebrew char-set; not implemented.");
	  }
	  break;

      case '%': // If followed by '5', DEC supplemental gfx:
	  b = getNextByte(false);
	  c = checkChar(b);
	  if (c != '5') {
	      if (!preventVTErrors) {
		  master.doWarningLF("Warning: Unknown char set '%"+c+"; only %5 known.");
	      }
	      send_size = 0;
	  } else {
	      setCharset(1, CHAR_SET_ALT_GFX);
	  }
	break;
      default:
	if (!preventVTErrors) {
	  master.doWarningLF("Warning: ESC + ) + Unknown char set '"+
			     ((char) c)+"'; ignoring.");
	}
      }
      break;

      // Asterisk begins G2-designator codes... wonder how G2 is chosen?
    case '*':
      if (!preventVTErrors) {
	master.doWarningLF("Warning: ESC + * + <char set>; G2 not implemented.");
      }
      break;
      
      // Plus begins G3-designator codes... wonder how G3 is chosen, then?
    case '+':
      if (!preventVTErrors) {
	master.doWarningLF("Warning: ESC + * + <char set>; G3 not implemented.");
      }
      break;
      
    case '<': // Enter ANSI mode
	setVT52Mode(false);
	break;

      // "Numeric keypad mode'
    case '>':
	setModeKeypadApplication(false);
	break;

      // "Application keypad mode'
    case '=':
	setModeKeypadApplication(true);
	break;

      // Probably various codes, but currently just one understood;
      // ESC + [ + ! + p is "soft reset":

    case '!':

      b = getNextByte(false);

      if (b == BYTE_ESC || b == BYTE_ESC_8BIT)
	  return false;
      if (b == BYTE_CAN || b == BYTE_SUB)
	  return true;
      
      if (b == (byte) 'p') {
	  if (mDebugVT) {
	      master.doWarning("Debug: soft reset (ESC + [ + ! + p) issued, ignoring.");
	  }
	  softResetTerminal(true);
      } else {
	  if (mDebugVT) {
	      master.doWarning("Debug: Unknown code, ESC + [ + ! + "+(char)b
			       +" issued, ignoring.");
	  }       
      }
      break;

      /* ESC + \ -> String terminator... whatever that may mean (can't
       * be used standalone, right?)
       */

    case '\\':

	if (mDebugVT) {
	    master.doWarning("Debug: String terminator, ESC + \\ received...");
	}       
	return true;

	/* ESC + ] -> 'Operating System Command' (says Kermit-docs):
	 * (followed by the string ending with ESC + \)
	 */
    case ']':

	if (!preventVTErrors) {
	    master.doWarning("Debug: 'Operating System Command', ESC + ], received, ignored.");
	}       
	return true;

	/* ESC + ^ -> 'Privacy Message' (says Kermit-docs):
	 * (followed by the string ending with ESC + \)
	 */

    case '^':

     if (!preventVTErrors) {
       master.doWarning("Debug: 'Privacy Message', ESC + ^, received, ignored.");
     }       
     return true;

     // ESC + _ -> 'Application Program Command' (says Kermit-docs):
     // (followed by the string ending with ESC + \)

    case '_':

     if (!preventVTErrors) {
       master.doWarning("Debug: 'Application Program Command', ESC + _, received, ignored.");
     }       
     return true;

    default:
      
      if (!preventVTErrors) {
	master.doWarningLF("Warning: Unknown ESC - code (ESC + "+c
			   +"=ascii "+ ((int) c & 0xFF) + ").");
      }
      break;
    }

    return true;
  }


  /* *** Methods for saving/storing terminal state: *** */

  public void saveCursor() { mSavedDisplayState = display.getDisplayState(); }
  public void restoreCursor() { mDisplay.setDisplayState(mSavedDisplayState); }

  /* *** Reporting: *** */

  /**
   * Send an appropriate cursor position notification over the connection
   * (usually responding to a request).
   * 
   * Note that VT-modes expect top-left position to be (1, 1); internally
   * we'll use (0, 0). Also, VT uses (row, col) indexing, we use (col, row),
   * ie. (x, y)
   */
  public void reportCursorPosition()
  {
      Dimension d = display.getCursorPosition();
      String reply = "\u001B[" + (d.y+1) + ";" + (d.x+1) + "R";
      sendBytes(reply.getBytes(), false);
  }

  // VT-320 response is directly from Kermit-docs:
  // 63 -> VT320 (Operating level 3)
  // 1-> 132 columns available
  // 2 -> printer port
  // 4 -> Sixel gfx
  // 6 -> Selective erase
  // 8 -> User-defined keys
  // 9 -> National replacement char sets
  // 15 -> Technical char set
  private byte [] VT320DeviceCodeReply = new byte [] {
    BYTE_ESC, (byte) '[', BYTE_QUESTION_MARK, (byte) '6', (byte) '3',
      (byte) ';', (byte) '1',
      (byte) ';', (byte) '2',
      (byte) ';', (byte) '4',
      (byte) ';', (byte) '6',
      (byte) ';', (byte) '8',
      (byte) ';', (byte) '9',
      (byte) ';', (byte) '1', (byte) '5',
      (byte) 'c'
  };
  // As is VT220 response. Note that these are almost identical; only
  // the type code (62 for VT220) is different; capabilities
  // are identical.
  private byte [] VT220DeviceCodeReply = new byte [] {
    BYTE_ESC, (byte) '[', BYTE_QUESTION_MARK, (byte) '6', (byte) '2',
      (byte) ';', (byte) '1',
      (byte) ';', (byte) '2',
      (byte) ';', (byte) '4',
      (byte) ';', (byte) '6',
      (byte) ';', (byte) '8',
      (byte) ';', (byte) '9',
      (byte) ';', (byte) '1', (byte) '5',
      (byte) 'c'
  };
  // Actually, VT100 returns '1' instead of '6' but...
  private byte [] VT100DeviceCodeReply = new byte [] {
    BYTE_ESC, (byte) '[', (byte) '?', (byte) '1', (byte) ';',
      (byte) '6', (byte) 'c'
  };
  private byte [] VT52DeviceCodeReply = new byte [] {
    BYTE_ESC, (byte) '/', (byte) 'Z'
  };

  void
  reportDeviceCode()
  {
    // The second number indicates options, combined from
    // 1 -> Processor option
    // 2 -> Advanced video option
    // 4 -> Graphics processor option

    // On the other hand, on VT52-mode, we'll return other way:

    if (VTMode == VT52 || modeVT52) { 
      if (mDebugVT) {
	master.doWarningLF("DEBUG: Sending the device code reply of VT52.");
      }
      sendBytes(VT52DeviceCodeReply, false);
    } else if (VTMode == VT320) {
     if (mDebugVT) {
	master.doWarningLF("DEBUG: Sending the device code reply of VT320.");
      }
      sendBytes(VT320DeviceCodeReply, false);
    } else {
     if (mDebugVT) {
	master.doWarningLF("DEBUG: Sending the device code reply of VT100/VT102.");
      }
      connection.sendBytes(sVT100DeviceCodeReply, false);
    }
    
  }

  // The default parameter query reply...
  private byte[] VT100TermParamReply1 = "\u001B[2;1;1;120;120;1;0x".getBytes();
  private byte[] VT100TermParamReply2 = "\u001B[3;1;1;120;120;1;0x".getBytes();
// 2 -> a report on request (1 -> unsolicited one)
  // 1 -> No parity (4 -> odd parity, 5 -> even parity)
  // 1 -> 8 bits per char (2 -> 7 bits)
  // 120 -> Xmit speed, 19.2 kbps (0->50bps etc)
  // 120 -> Recv speed, 19.2 kbps (0->50bps etc)
  // 1 -> Bit rate multiplier, 16 (?)
  // 0 -> "flags", 0 - 15, a 4-bit value

  // Called by Display...
  public void
  reportTerminalParameters(int requested)
  {
    sendBytes((requested == 0) ? VT100TermParamReply1 :
	      VT100TermParamReply2, false);
  }

  private byte [] VT220SecondaryDevAttrReply = "\u001B[>1;10;0c".getBytes();
  void
  report2ndaryDevAttrs()
  {
    sendBytes(VT220SecondaryDevAttrReply, false);
  }

  // Actually, this is "All ok" - reply, not just for vt100...
  private byte[] VT100StatusReply = { BYTE_ESC, (byte) '[',
				      (byte) '0', (byte) 'n' };

  public void
  reportStatus()
  {
    sendBytes(VT100StatusReply, false);
  }

  /* *** Methods for changing terminal state: * ***/

  public void setVT52Mode(boolean x) { modeVT52 = x; }
  public void setKeyboardLocked(boolean state) {
      modeKeyboardLocked = state;
  }
  public void setModeCursorKeyApplication(boolean x) { 
      modeCursorKeyApplication = x; }
  public void setModeKeypadApplication(boolean x) {
    modeKeypadApplication = x;
  }
  public void setModeNewline(boolean on) {
      modeNewline = on;
  }
  public void setModePrintFF(boolean on) { modePrintFF = on; }
  public void setModePrintFullScreen(boolean on) { modePrintFullScreen = on; }
  public void setModeInterlace(boolean on) { modeInterlace = on; }
  public void setModeAutoRepeat(boolean on) { modeAutoRepeat = on; }
  public void setModeGfxCoProc(boolean on) { modeGfxCoProc = on; }
  public void setModeEcho(boolean on) { modeEcho = on; }

  public void setColumns132(boolean is132)
  {
      if (!master.VTResizeOk()) {
	  if (debugVT) {
	      master.doWarningLF("Warning: VT-originated resize not allowed.");
	  }
      } else {
	  mode132Cols = is132;
	  display.setColumns132(is132);
      }
  }

  public void useCharset(int set)
  {
      if (set >= mCharsets.length) {
	  if (debugVT) {
	      master.doWarningLF("Warning: trying to invoke unknown character set "+set+" (only 0 - "+(mCharsets.length-1)+" allowed).");
	  }
      } else {
	  mUsingCharset = set;
	  mCurrCharset = mCharsets[set];
      }
  }

  public void setCharset(int set, int charset)
  {
      if (set >= mCharsets.length) {
	  if (debugVT) {
	      master.doWarningLF("Warning: trying to invoke unknown character set "+set+" (only 0 - "+(mCharsets.length-1)+" allowed).");
	  }
      } else {
	  mCharsets[set] = charset;
	  if (mUsingCharset == set) {
	      mCurrCharset = charset;
	  }
      }
  }

  public void set8Bitness(boolean x)
  {
      if (mDebugVT) {
	  master.doWarningLF("Debug: "+(x ? "enable" : "disable")
			     +" output of 8-bit control codes.");
      }
      if (x) {
	  modeMaySend8BitCodes = true;
	  if (master.send8BitCodesOk()) {
	      modeDoSend8BitCodes = true;
	  }
      } else {
	  modeMaySend8BitCodes = modeDoSend8BitCodes = false;
      }
  }
  /* Is used by the server to change the conformance level...
   * a, b: (from Kermit-docs)
   * 61, 0      -> vt102, 7 bits (61 is otherwise vt100 level)
   * 62, 0 or 2 -> vt320, 8-bits (62 is otherwise vt200 level)
   * 62, 1      -> vt320, 7 bits
   * 63, 0 or 2 -> vt320, 8 bits (63 is otherwise vt300 level)
   * 63, 1      -> vt320, 7 bits
   */

  // 60 means vt52?
  // 64->420, 65->520 etc?

  // Probably means that we can freely use the 'correct' levels...

  public void
  setConformanceLevel(int a, int b)
  {
    switch (a) {
    case 60: // Just a guess that this is to be VT52
      master.terminal.setEmulationLevel(Terminal.VT52);
      modeMaySend8BitCodes = false;
      break;
    case 61:
      master.terminal.setEmulationLevel(Terminal.VT102);
      modeMaySend8BitCodes = false;
      break;
    case 62:
      master.terminal.setEmulationLevel(Terminal.VT220);
      modeMaySend8BitCodes = (b == 1) ? false : true;
      break;
    case 63:
      master.terminal.setEmulationLevel(Terminal.VT320);
      modeMaySend8BitCodes = (b == 1) ? false : true;
      break;
    case 64:
      master.terminal.setEmulationLevel(Terminal.VT420);
      modeMaySend8BitCodes = (b == 1) ? false : true;
      break;
    case 65:
      master.terminal.setEmulationLevel(Terminal.VT520);
      modeMaySend8BitCodes = (b == 1) ? false : true;
      break;
    default:
      master.doWarningLF("Warning: unknown conformance level (termtype) "+a
			 +"; using 7-bit VT102 instead.");
      master.terminal.setEmulationLevel(Terminal.VT102);
      modeMaySend8BitCodes = false;
    }

    if (!modeMaySend8BitCodes)
      modeDoSend8BitCodes = false;
    else {
      // Should we turn on sending 8-bit codes now?
      modeDoSend8BitCodes = true;
    }

    // Also should do soft terminal reset:
    softResetTerminal(false);
  }

  /* Note that in many cases we do not distinguish between
   * ESC + [ + ? + mode + h/l and
   * ESC + [ + mode + h/l... but sometimes we do. :-/
   */
  // NEW: Can set more than one mode...
  public void
  setVTMode(boolean question_mark)
  {
    for (int i = 0; i < VTAttrCount; ++i) {
      int mode = VTAttr[i];
      i++;

      switch (mode) {

      case 1: // Cursor key mode / application
	  setModeCursorKeyApplication(true);
	  break;

	  /* With ? - prefix (DEC), ANSI/VT-52 mode,
	   * without (ansi), lock/unlock keyboard
	   */
      case 2:

	if (question_mark) {
	    setVT52Mode(false);
	} else {
	    setKeyBoardLocked(true);
	}
	break;

      case 3: /* 132/80 char mode (DEC), or ctrl code interpret,
	       * act/print (ansi) (not implemented; will always act upon
	       * ctrl codes!)
	       */
	  
	  setColumns132(true);
	  break;

      case 4:
	  /* With ? - prefix (DEC), Smooth scroll,
	   * without (ansi), insert/replace mode
	   */

	if (question_mark) {
	    display.setDisplayMode(Display.MODE_SMOOTH_SCROLL, true);
	} else {
	    display.setDisplayMode(Display.MODE_INSERT_MODE, true);
	}
	break;

      case 5: // Inverse video
	  display.setScreenReversed(true);
	  break;

      case 6: // Origin mode relative/absolute
	  display.setDisplayMode(Display.MODE_ORIGIN_RELATIVE, true);
	  break;

      case 7: // Wrap-around on
	  display.setDisplayMode(Display.MODE_AUTO_WRAP, true);
	  break;
		
      case 8: // Autorepeat
	  setModeAutoRepeat(true);
	  break;

      case 9: // Interlace on/off (???)
	  setModeInterlace(true);
	  break;

      case 12: // Echo on/off:
	  setModeEcho(true);
	  break;

      case 18: // Print formfeed / don't print ff
	  setModePrintFF(true);
	  break;

      case 19: // Print Full screen / scrolling region
	  setModePrintFullScreen(true);
	  break;

      case 20: // (only without the question mark? ie. ANSI),
	// Enter -> CR+LF / CR
	  setModeNewline(true);
	  break;

      case 21: // Rumoured to mean "Set cursor to block" (on Irix)
	// not implemented...
	if (!preventVTErrors) {
	  master.doWarningLF("Warning: ESC + [ + (?) + 21 + h gotten; possibly means 'cursor to block'; skipping.");
	}
	return;

      case 25: // Cursor on / off
	  display.setDisplayMode(Display.MODE_CURSOR_VISIBLE, true);
	  break;

      case 33: // Rumoured to mean "Wyse Steady Cursor Mode", not handled

	if (!preventVTErrors) {
	  master.doWarningLF("Warning: ESC + [ + (?) + 33 + h gotten; possibly means 'Wyse Steady Cursor Mode'; skipping.");
	}
	return;

      case 34: // Rumoured to mean "Wyse Underline Cursor Mode", not handled
	// Or alternatively; with the question mark can also mean DEC's
	// right-to-left / left-to-right mode:

	if (!preventVTErrors) {
	  master.doWarningLF("Warning: ESC + [ + (?) + 34 + h gotten; possibly means 'Wyse Underline Cursor Mode'; skipping, or 'left-to-right' mode set (DEC)");
	}
	return;

      case 35: // DEC, 'Invoke Macro'
      case 36: // DEC, Hebrew encoding
      case 38: // DEC, Graphics (Tek) / text
      case 40: // DEC, Enable 80/132 switch
      case 42: /* (the answer to the Question of Life, Universe and
	        * everything), but.... also....
		* Nat Repl Char enable / disable, by DEC (whatever
		* that may mean)
		*/
      case 66: // Encore??? Numeric keypad, application/numeric
      case 68: // Typewriter, data process/typewriter (DEC).
	       // What on earth does that mean?
	if (!preventVTErrors) {
	  master.doWarningLF("Warning: recognized but unimplemented mode ("
			     +mode+") to set " +"with ESC + [ + ?");
	}
	break;

      default:

	if (!preventVTErrors) {
	  master.doWarningLF("Warning: Unknown mode ("+mode+") to set "
			     +"with ESC + [ + ?");
	}
	return;

      }
    }
  }

  public void resetVTMode(boolean question_mark)
  {
    int i = 0;

    while (i < VTAttrCount) {
      
      int mode = VTAttr[i];
      i++;

      switch (mode) {

      case 1: // Cursor key mode / application
	  setModeCursorKeyApplication(false);
	  break;

	// With ? - prefix, ANSI/VT-52 mode,
	// without (VT102), lock/unlock keyboard
      case 2:

	if (question_mark) {
	    setVT52Mode(true);
	} else {
	    setKeyBoardLocked(false);
	}
	break;

      case 3: // 132/80 char mode

	  setColumns132(false);
	  break;

	// With ? - prefix, Smooth scroll,
	// without, insert/replace mode
      case 4:

	if (question_mark) {
	    setSmoothScroll(false);
	} else {
	    setInsertMode(false);
	}
	break;
	  
      case 5: // Inverse video
	  display.setScreenReversed(false);
	  break;

      case 6: // Origin mode relative/absolute
	  display.setDisplayMode(Display.MODE_ORIGIN_RELATIVE, false);
	  break;

      case 7: // Wrap-around off
	  display.setDisplayMode(Display.MODE_AUTO_WRAP, false);
	  break;
		
      case 8: // Autorepeat
	  setModeAutoRepeat(false);
	  break;

      case 9: // Interlace on/off (???)
	  setModeInterlace(false);
	  break;

      case 12: // Echo on/off:
	  setModeEcho(tfalse);
	  break;

      case 18: // Print formfeed / don't print ff
	  setModePrintFF(false);
	  break;

      case 19: // Print full screen / scrolling region
	  setModePrintFullScreen(false);
	  break;

      case 20: // (only without the question mark?),
	// Enter -> CR+LF / CR
	setModeNewline(false);
	break;

      case 21: // Rumoured to mean "Set cursor to block" (on Irix)
	// not implemented...
	if (!preventVTErrors) {
	  master.doWarningLF("Warning: ESC + [ + (?) + 21 + l gotten; possibly means 'cursor to xxx'; skipping.");
	}
	return;

      case 25: // Cursor on / off
	  display.setDisplayMode(Display.MODE_CURSOR_VISIBLE, false);
	  break;

      case 33: // Rumoured to mean "Wyse Steady Cursor Mode", not handled

	if (!preventVTErrors) {
	  master.doWarningLF("Warning: ESC + [ + (?) + 33 + l gotten; possibly means 'Wyse Steady Cursor Mode'; skipping.");
	}
	return;

      case 34: // Rumoured to mean "Wyse Underline Cursor Mode", not handled
	// Or alternatively; with the question mark can also mean DEC's
	// right-to-left / left-to-right mode:

	if (!preventVTErrors) {
	  master.doWarningLF("Warning: ESC + [ + (?) + 34 + l gotten; possibly means 'Wyse Underline Cursor Mode'; skipping, or 'left-to-right' mode set (DEC)");
	}
	return;

      case 35: // DEC, 'Invoke Macro'
      case 36: // DEC, Hebrew encoding
      case 38: // DEC, Graphics (Tek) / text
      case 40: // DEC, Enable/disable 80/132 switch
      case 42: // (the answer to the Question of Life, Universe and
	       // everything), but.... also....
	       // Nat Repl Char enable / disable, by DEC (whatever
	       // that may mean)
      case 66: // Encore??? Numeric keypad, application/numeric
      case 68: // Typewriter, data process/typewriter (DEC).
	       // What on earth does that mean?
	if (!preventVTErrors) {
	  master.doWarningLF("Warning: recognized but unimplemented mode ("
			     +mode+") to set " +"with ESC + [ + ?");
	}
	break;

      default:

	if (!preventVTErrors) {
	  master.doWarningLF("Warning: Unknown mode ("+mode+") to reset "
			     +"with ESC + [ + ?");
	}
	return;

      }
    }
  }

  public void
  setEmulationLevel(int lvl)
  {
    modeVT52 = false;
    VTMode = lvl;
  }

  public void
  setSpeed(int speed)
  {
    bpsLimit = speed;

    if (speed < 1) {
      bpsLimit = 0;
      bpsLimitTime = 0;
    } else {
      // Assuming 8n1 means we need 9 bits for each char, thus:
      bpsWaitPerChar = 9000.0 / (double) speed;
      /* Let's use up to ~50 ms for reading (optimization); we don't
       * want to use too much CPU time for doing slow-down...
       */
      bpsMaxCharsPerRead = (int) (50.0 / bpsWaitPerChar) + 1;
      bpsLimitTime = 0; // Will be set to a correct value when reading next
                        // char
    }
 }

  /* *** Sending VT stuff: *** */
  private byte [] arrowCodes = new byte[3]; {
    arrowCodes[0] = BYTE_ESC;
  };

  public void
  sendArrow(int dir)
  {
    int send_size;

    if (VTMode == VT52 || modeVT52) {
      send_size = 1;
    } else {
	if (modeCursorKeyApplication) {
	    arrowCodes[1] = (byte) 'O';
	} else {
	    arrowCodes[1] = BYTE_BRACKET;
	}
	send_size = 2;
    }

    switch (dir) {
    case CODE_CURSOR_UP:
      arrowCodes[send_size] = (byte) 'A';
      break;
    case CODE_CURSOR_DOWN:
      arrowCodes[send_size] = (byte) 'B';
      break;
    case CODE_CURSOR_RIGHT:
      arrowCodes[send_size] = (byte) 'C';
      break;
    case CODE_CURSOR_LEFT:
      arrowCodes[send_size] = (byte) 'D';
      break;
    }
    sendBytes(arrowCodes, 0, send_size + 1, true);
  }

  /* Internal state data about the VT-code read in: */
  private int [] VTAttr = new int[20];
  private int VTAttrCount = 0;
  private byte VTCommand; // The 'last letter', if present

  // A small utility function that dumps list of read-in
  // VT attributes:
  private void
  dumpVTAttrs(boolean flush)
  {  
    for (int j = 0; j < VTAttrCount; j++) {
      if (j > 0)
	master.doWarning(", ");
      master.doWarning("" + VTAttr[j]);
    }
    if (flush)
      master.doWarningLF("");
  }

  /* This function reads in a list of semicolon-separated
   * ascii-numbers:    
   */
  public byte
  getVTAttrs(byte b)
  throws VTCommandCancelled, VTCommandInterrupted,
    VTCommandInterrupted8Bit
  {
    int currVal = 0;
    VTAttrCount = 0;

    do {

      if (b == BYTE_ESC)
	throw new VTCommandInterrupted();
      if (b == BYTE_ESC_8BIT)
	throw new VTCommandInterrupted8Bit();
      // These control chars cancel the current command:
      if (b == BYTE_CAN || b == BYTE_SUB)
	throw new VTCommandCancelled();

      if (b >= (byte) '0' && b <= (byte) '9') {
	currVal = currVal * 10 + (int) (b - (byte) '0');
      } else {
	if (VTAttrCount == VTAttr.length) {
	  int [] tmp = VTAttr;
	  VTAttr = new int[VTAttrCount + 10];
	  System.arraycopy(tmp, 0, VTAttr, 0, VTAttrCount);
	}
	VTAttr[VTAttrCount++] = currVal;
	if (b != (byte) ';') {
	  return b;
	}
	currVal = 0;
      }
      b = getNextByte(false);

    } while (true);
  }

  private char[] VTCode = new char [4];
  private int VTCodeLength;

  public char checkChar(byte b)
  throws VTCommandCancelled, VTCommandInterrupted,
    VTCommandInterrupted8Bit
  {
    if (b == BYTE_ESC)
      throw new VTCommandInterrupted();
    if (b == BYTE_ESC_8BIT)
      throw new VTCommandInterrupted8Bit();
    if (b == BYTE_CAN || b == BYTE_SUB)
      throw new VTCommandCancelled();
    return (char) b;
  }

  /* This function reads in a VT-code, and then either discards it
   * (if it was cancelled) or passes it for further processing
   * to another function.
   */
  public void handleVTCode(byte b)
  {
    char c, code;
    String db;

    // This loop is needed in case a VT-code is interrupted by
    // another VT-code
  main_loop:

     while (true) {

       try {

	 VTCodeLength = VTAttrCount = 0;
	 c = checkChar(b);
	 
	 // Now we can already decide whether it is a short code...
	 // Shouldn't cause false positives?
	 if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
	     (c >= '0' && c <= '9')) {

	   if (dumpVTCodes) {
	     master.doWarning(" <(SHORT)_ESC "+c+">");
	   }
	   handleShortCodes(b);
	   return;
	 }
	 
	 VTCode[VTCodeLength++] = c;
	 
	/* Then there are more specialized VT-codes, which begin
	 * with a punctuation character:
	 */
	 // These are the 'normal' vt100 codes:
	if (VTCode[0] == CHAR_BRACKET) {

	  b = getNextByte(false);
	  c = checkChar(b);

	  // Now we might already get some numeric arguments:
	  if ((c >= '0' && c <= '9') || c == ';' || c == '-') {
	    b = getVTAttrs(b);
	    // No need to check now, getVTAttrs has already checked
	    // it...
	    c = (char) b;
	  }

	  /* Before or after args... we may have special chars indicating
	   * vendor-specific extensions:
	   */
	  if (c == '?' || c == '>' || c == '"' || c == '!' || c == '$') {
	    code = c;
	    if (dumpVTCodes) {
	      master.doWarning(" <ESC_[_"+code);
	    }
	    b = getNextByte(false);
	    c = checkChar(b);
	  } else {
	    code = '[';
	    if (dumpVTCodes)
	      master.doWarning(" <ESC_[");
	  }

	  // Now we might already get some numeric arguments:
	  if ((c >= '0' && c <= '9') || c == ';' || c == '-') {
	    b = getVTAttrs(b);
	    // No need to check now, getVTAttrs has already checked
	    // it...
	    c = (char) b;
	  }

	  if (VTAttrCount > 0) {
	    if (dumpVTCodes) {
	      db = "_(";
	      for (int x = 0; x < VTAttrCount; x++) {
		if (x > 0) db += ",";
		db = db + "" + VTAttr[x];
	      }
	      db = db + ")_" + c + ">";
	      master.doWarning(db);
	    }
	  } else {
	    if (dumpVTCodes)
	      master.doWarning("_(-)_"+c+">");
	  }
	  
	  switch (code) {
	  case '[':
	    handleBracketCodes(c);
	    return;

	  case '?':
	    handleQuestionCodes(c);
	    return;

	  case '!':
	    handleExclamationCodes(c);
	    return;

	  case '>':
	    handleGtCodes(c);
	    return;

	  case '"':
	    handleQuoteCodes(c);
	    return;

	  case '$':
	    handleDollarCodes(c);
	    return;

	  default:
	    master.doWarningLF("Warning: Internal error in Terminal.handleVTCode()!");
	  }	  
	  return;
	} // if (code == BRACKET)

	if (dumpVTCodes) {
	  db = " <(SPEC)_ESC_(";
	  for (int x = 0; x < VTAttrCount; x++) {
		if (x > 0) db += ",";
		db = db + "" + VTAttr[x];
	  }
	  db = db +")_" + c + ">";
	  master.doWarning(db);
	}

	handleSpecialCodes(c);
	return;

	/* Should we print a checker-board char, then? */
	/* (some docs claim that should result in interrupted ESC-code) */
       } catch (VTCommandCancelled e1) {
	 if (dumpVTCodes) {
	   master.doWarning("<ESC_CAN>");
	 }
	 return;
       } catch (VTCommandInterrupted e2)  {
	 if (dumpVTCodes) {
	   master.doWarning("<ESC_ESC>");
	 }
	 b = getNextByte(true);
	 continue main_loop;
       } catch (VTCommandInterrupted8Bit e3) {
	 if (dumpVTCodes) {
	   master.doWarning("<ESC_ESC/8>");
	 }
	 b = BYTE_BRACKET;
	 continue main_loop;
       }
     }
  }

  private byte getNextByte()
      throws IOException
  {
      if (mInputPtr >= mInputSize) {
	  mInputPtr = 0;
	  mInputSize = mConnection.getBytes(mInputBuffer);
	  // End of connection?
	  if (mInputSize < 0) {
	      throw new IOException("End-of-connection");
	  }
      }
      
      return mInputBuffer[mInputPtr++];
  }

  /**
   * This is the method that reads stuff from the connection
   * as long as the connection is open.
   */
  public void handleConnection(JiveConnection conn)
  {
      setConnection(conn);

  main_loop:

    try {
	while (true) {
	    byte b = getNextByte(true); // getNextByte() handles ctrl chars...
	    
	    /* Then we'll check for ESC-codes -> ANSI/VT-100 codes. */
	    if (b == BYTE_ESC) {
		handleVTCode(getNextByte(false));
	    } else if (b == BYTE_ESC_8BIT) {
		handleVTCode(BYTE_BRACKET);
	    } else  if (b >= (byte) 128 && b <= (byte) 160) {
		
		/* List from Kermit-docs. Probably these could also be directly
		 * calculated...
		 */
		switch ((int) b & 0xFF) {
		case 0x84:
		    b = (byte) 'D';
		    break;
		case 0x85:
		    b = (byte) 'E';
		    break;
		case 0x88:
		    b = (byte) 'H';
		    break;
		case 0x8D:
		    b = (byte) 'M';
		    break;
		case 0x8E:
		    b = (byte) 'N';
		    break;
		case 0x8F:
		    b = (byte) 'O';
		    break;
		case 0x90:
		    b = (byte) 'P';
		    break;
		case 0x96:
		    b = (byte) 'V';
		    break;
		case 0x97:
		    b = (byte) 'W';
		    break;
		case 0x9B: // Already handled earlier:
		    master.doWarningLF("Warning: Internal problems; re-receiving 0x9B!");
		    continue main_loop;
		case 0x9C:
		    b = (byte) '\\';
		    break;
		case 0x9D:
		    b = (byte) ']';
		    break;
		case 0x9E:
		    b = (byte) '^';
		    break;
		case 0x9F:
		    b = (byte) '_';
		    break;
		default:
		    
		    if (mDebugVT) {
			master.doWarningLF("Warning: Unknown upper-area ctrl code ("
					   +((int) b & 0xFF)+" received, ignoring.");
		    }
		    continue main_loop;
		}
		handleVTCode(b);
	    } else {
		int i;

		/* Let's break on any 'weird' (control) characters:
		 * (actually, any non-printable char)
		 */
		for (i = mInputPtr - 1; mInputPtr < mInputSize; ++mInputPtr) {
		    b = mInputBuffer[inPtr];
		    if (((int)b & 0x007F) < 32) {
			break;
		    }
		}
		if (dumpVTCodes) {
		    master.doWarning("\""+new String(inBuffer, i, inPtr - i)
				     +"\"(" + (inPtr - i)+")");
		}
		// Need to update character attributes? (ie. they have been changed)
		if (mCharAttrsChanged) {
		    applyCharAttrs();
		}
		
		display.printCharacters(mInputBuffer, i, mInputPtr - i);
	    }
	} // while (true)
    } catch (IOException ex) {
	// Connection closed...
    }

    setConnection(null);
  }

  /* These functions locally echo a character(s) on the terminal display.
   * Called by a JiveTerm-instance; for the user it looks
   * as if the server had output text normally:
   */
  public void
  echoByte(byte x)
  {
    synchronized (echoBuffer) {

      // Overflow?
      if (echoSize == ECHO_BUFFER_SIZE) {
	if (mDebugVT) {
	  master.doWarning("VT-warning: Local echo buffer overflow.");
	}
	return;
      }

      echoBuffer[echoSize] = x;

      // The buffer was empty?
      if (echoSize++ == 0)
	interrupt(); // In case this thread has been blocked by read()
      else interrupt();
    }
  }

  public void
  echoBytes(byte [] x)
  {
    synchronized (echoBuffer) {

      // Overflow?
      if ((echoSize + x.length) > ECHO_BUFFER_SIZE) {
	if (mDebugVT) {
	  master.doWarning("VT-warning: Local echo buffer overflow.");
	}
	return;
      }

      System.arraycopy(x, 0, echoBuffer, echoSize, x.length);
      // The buffer was empty?
      if (echoSize == 0) {
	echoSize += x.length;
	interrupt(); // In case this thread has been blocked by read()
      } else {
	echoSize += x.length;
      }
    }
  }

  // This function sends a byte to the server through the connection
  public boolean
  sendByte(byte x, boolean flush)
  {
    return connection.sendByte(x, flush);
  }

  public boolean
  sendBytes(byte [] x, boolean flush)
  {
    return connection.sendBytes(x, flush);
  }

  public synchronized boolean
  sendBytes(byte [] x, int offset, int length, boolean flush)
  {
    return connection.sendBytes(x, offset,length, flush);
  }

  public synchronized byte []
  sendString(String x, boolean line_feed, boolean flush)
  {
    byte[] foo = x.getBytes();

    if (line_feed) {
      connection.sendBytes(foo, false);
      if (NVTAscii) 
	connection.sendBytes(LINEFEED_CRLF, flush); // or should it be bare CR?
      else
	connection.sendBytes(LINEFEED_SSH, flush); // or should it be bare CR?

    } else {
      connection.sendBytes(foo, flush);
    }

    // Hmmh. Perhaps it should return the string ending with the linefeed?
    return foo;
  }

  // This is may be called by JiveTerm (or Display) to send various VT-
  // control codes. Right now it's not used by anything, though:
  synchronized void
  sendCtrlCode(byte code_key, int [] args, boolean flush)
  {
    String code;
    Hashtable codes = VT100ctrlCodes;
    
    /* Actually, what to send when an arrow-key is pressed depends on
     * a few factors; whether the application wants to handle
     * arrow keys etc:
     */
    code = (String) codes.get(new Integer(code_key));
    
    if (code == null) {
      master.doWarning("Warning: Unknown ctrl code ("+code_key+") to send!");
      return;
    }
    
    int i = code.indexOf('*');
    
    // Need to substitute args?
    if (i >= 0) {
      if (args != null && args.length > 0) {
	StringBuffer arg_s = new StringBuffer(20);
	
	arg_s.append(code.substring(0, i));
	for (int j = 0; j < args.length; j++) {
	  if (j > 0)
	    arg_s.append(";");
	  arg_s.append(args[j]);
	}
	arg_s.append(code.substring(i+1));
	code = new String(arg_s);
      } else {
	code = code.substring(0, i) + code.substring(i+1);
      }
    } else if (args != null && args.length > 0) {
      master.doWarning("Warning: Passing args to ctrl code ("+code+") that doesn't accept arguments.");
    }
    sendString(code, false, flush);  
  }

/***** End of Terminal *******/
}

  /* Actually, we could use an exception or two, as markers... */
final class
VTCommandCancelled
extends Exception
{
}

final class
VTCommandInterrupted
extends Exception
{
}

final class
VTCommandInterrupted8Bit
extends Exception
{
}
