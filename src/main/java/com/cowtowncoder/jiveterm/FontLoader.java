/**************************************

Project:
    JiveTerm.

    A VT52/VT100/VT102-compliant
    telnet/terminal program
    written in pure java.

    (C) 1998-99 Tatu Saloranta (aka Doomdark),
    doomdark@iki.fi.

Module:
    FontLoader.java
Last changed:
    15-Apr-99.
Description:
    Class that 'loads' the double-sized
    (width/height/both) fonts in when
    required.

Changes:

  27-Jan-99, TSa:
    - Chopped font images to smaller sizes;
      now the size is configurable
  28-Jan-98, TSa:
    - Began implementing graphics codes
  15-Apr-99, TSa:
    - Some changes due to compatibility
      problems on Windows-platform...

**************************************/

package com.cowtowncoder.jiveterm;

import java.awt.*;
import java.awt.image.*;
import java.util.*;

import jiveterm.*;

public final class
FontLoader
implements Runnable
{
  public int IMAGES_PER_CHARSET, CHARS_PER_IMAGE;
  public final static int CHARS_PER_CHARSET = 192;
  public final static int NR_OF_SYMBOLS = 31;

  private final static boolean debugFonts = true;

  Display display; // Needed for certain AWT-calls as the listener, and
                   // we also need to inform it when we're done
  Font myFont, myBFont;
  Image dwFonts = null;
  Image dwbFonts = null;
  Image dhFonts = null;
  Image dhbFonts = null;

  // 0137 (95) -> Blank
  // 0140 (96) -> Diamond
  //           -> Checkerboard
  //           -> Digraph: HT (horiz tab?)
  //           -> Digraph: FF (form feed)
  //           -> Digraph: CR (carriage return)
  //           -> Digraph: LF (linefeed)
  //           -> Degree symbol (o)
  //           -> +/- - symbol
  // 0150 (104) -> Digraph NL
  //           -> Digraph VT
  //           -> SE - corner
  //           -> NE - corner
  //           -> NW - corner
  //           -> SW - corner
  //           -> crossing lines (+?)
  //           -> Horiz line, scan 1
  // 0160 (112) -> Horiz line, scan 3
  //           -> Horiz line, scan 5
  //           -> Horiz line, scan 7
  //           -> Horiz line, scan 9
  //           -> Left "T" I-
  //           -> Right "T" -I
  //           -> Bottom "T" I_
  //           -> Top "T" T
  // 0170 (120) -> Vertical bar (|)
  //           -> Less/equal < + =
  //           -> Greater/equal > + =
  //           -> Pi symbol
  //           -> Not equal = plus /
  //           -> UK pound symbol ( )
  // 0176 (126) -> Centered dot

  int group = 1;

  public FontLoader(Display d, Font f, Font bf)
  {
    display = d;
    myFont = f;
    myBFont = bf;

    // Do we need to limit nr of distinct images? (for Windows, yes;
    // otherwise we'll run into probs it seems)
    // Currently there are 192 chars per charset, so we'll either
    // choose 12 images with 16 chars each (windows), or
    // 96 images with 2 chars each (other platforms).
    if (PlatformSpecific.limitNrOfImages()) {
      CHARS_PER_IMAGE = 16;
    } else {
      //CHARS_PER_IMAGE = 2;
      CHARS_PER_IMAGE = 2;
    }
    IMAGES_PER_CHARSET = CHARS_PER_CHARSET / CHARS_PER_IMAGE;
  }

  public void
  run()
  {
    FontMetrics fm = display.getFontMetrics(myFont);
    int font_width = PlatformSpecific.getActualFontWidth(fm.getMaxAdvance());
    int font_height = fm.getHeight();
    int font_base = font_height - fm.getDescent();
    int w, h, i;

    Thread.currentThread().setPriority(JiveTerm.FONTLOADER_PRIORITY);

    Image [] chars = new Image[IMAGES_PER_CHARSET];
    Image [] bchars = new Image[IMAGES_PER_CHARSET];

    // Hmmh. We don't know when we get the gfx buffers... But as
    // this is a separate thread, we can wait. *grin*
    w = font_width;
    h = CHARS_PER_IMAGE * font_height;
    for (i = 0; i < IMAGES_PER_CHARSET; i++) {
      chars[i] = display.createImage(w, h);
      if (chars[i] == null) {
	if (debugFonts) {
	  System.err.println("DEBUG: Waiting for gfx buffer #"+i+"!");
	}
	try { Thread.sleep(200); } catch (InterruptedException ie) { }
	i -= 1;
	continue;
      }
      bchars[i] = display.createImage(w = font_width,
		     h = CHARS_PER_IMAGE * font_height);
    }

    if (debugFonts) {
      System.err.println("DEBUG: Gfx buffers gotten ok.");
    }

long start = System.currentTimeMillis();

    int ch = 32;

    for (i = 0; i < IMAGES_PER_CHARSET; i++) {

      Graphics g = chars[i].getGraphics();
      Graphics g2 = bchars[i].getGraphics();

      g.setColor(Color.black);
      g.fillRect(0, 0, w, h);
      g.setColor(Color.white);
      g.setFont(myFont);

      g2.setColor(Color.black);
      g2.fillRect(0, 0, w, h);
      g2.setColor(Color.white);
      g2.setFont(myBFont);

      for (int j = 0; j < CHARS_PER_IMAGE; j++, ch++) {

	if (ch == 128)
	  ch = 160;

	g.drawString("" + (char) ch, 0,
		   font_base + j * font_height);
	g2.drawString("" + (char) ch, 0,
		   font_base + j * font_height);
      }

      g.dispose();
      g2.dispose();
    }

    Image [] ia = getDoubleWidthFonts(chars, w, h, display, false);
    Image [] ib = getDoubleHeightFonts(chars, w, h, display, false);

    long time = System.currentTimeMillis();

    // Weird World. Seems like bigger 'step' is, faster the
    // system goes... (thus, 1; could be anything between 1 and
    // IMAGES_PER_CHARSET itself)
    int step = IMAGES_PER_CHARSET / 1;
      // ia.length has to be dividable by step...

    for (i = 0; i < ia.length; i += step) {
      for (int j = 0; j < step; j++) {
	waitForImage(ia[i + j], group, false);
	waitForImage(ib[i + j], group, j == (step - 1));
      }
      group += 1;
    }

    if (debugFonts) {
      System.err.println("DEBUG: waitForImage() (non-bold) took "
			 + ((System.currentTimeMillis() - time))
			 +" ms, with step="+step);
    }

    Image [] ia2 = getDoubleWidthFonts(bchars, w, h, display, true);
    Image [] ib2 = getDoubleHeightFonts(bchars, w, h, display, true);

    time = System.currentTimeMillis();

    for (i = 0; i < ia2.length; i += step) {
      for (int j = 0; j < step; j++) {
	waitForImage(ia2[i + j], group, false);
	waitForImage(ib2[i + j], group, j == (step - 1));
      }
      group += 1;
    }

    if (debugFonts) {
      System.err.println("DEBUG: waitForImage() (bold) took "
			 + ((System.currentTimeMillis() - time))
			 +" ms, with step="+step);
    }

    doGfxFonts(font_width, font_height, display, myFont, myBFont,
	       font_base, fm);

    if (debugFonts) {
      System.err.println("--- Fonts done, took "+
			 + (System.currentTimeMillis() - start)
			 +" ms, with step="+step+" ---- ");
    }
 }

  // This returns an image that has character codes from 32 to 255
  // (in theory; some might not show up anyways) but that have double
  // the normal width of the fonts in the character set.
  // Fonts are in one column, 32 (space) is at the top, 255 at the
  // bottom
  public Image []
  getDoubleWidthFonts(Image [] orig_image, int orig_x, int orig_y,
		      Display display, boolean bold)
  {
    long time = System.currentTimeMillis(); 

    // We'll create the font images as 12 blocks:
    Image [] imgs = new Image[IMAGES_PER_CHARSET];

    display.setDoubleFont(bold ? Display.DW_FONT_BOLD :
			  Display.DW_FONT_NORMAL,imgs);

    for (int i = 0; i < IMAGES_PER_CHARSET; i++) {
      Image img = display.createImage(orig_x * 2, orig_y);
      Graphics g = img.getGraphics();
      g.setColor(Color.black);
      g.fillRect(0, 0, orig_x * 2, orig_y);

      for (int j = 0; j < CHARS_PER_IMAGE; j++) {
	g.drawImage(orig_image[i], 0, - (j * orig_y),
		  orig_x * 2, orig_y, display);
      }

      imgs[i] = display.createImage(new FilteredImageSource(
	   img.getSource(), new TransparencyFilter()));

      g.dispose();
    }

    if (debugFonts) {
      System.err.println("DEBUG: Double width fonts (bold="+bold+") done, took "
			 + ((System.currentTimeMillis() - time))
			 +" ms.");
    }

    return imgs;
  }

  // Much like getDoubleWidthFonts() except that in this case the
  // height is doubled in addition to width.
  public Image []
  getDoubleHeightFonts(Image [] orig_image, int orig_x, int orig_y,
		       Display display, boolean bold)
  {
    long time = System.currentTimeMillis(); 

    // We'll create the font images as 12 blocks:
    Image [] imgs = new Image[IMAGES_PER_CHARSET];

    display.setDoubleFont(bold ? Display.DH_FONT_BOLD :
			  Display.DH_FONT_NORMAL,imgs);

    for (int i = 0; i < IMAGES_PER_CHARSET; i++) {
      Image img = display.createImage(orig_x * 2, orig_y * 2);
      Graphics g = img.getGraphics();
      g.setColor(Color.black);
      g.fillRect(0, 0, orig_x * 2, orig_y * 2);

      for (int j = 0; j < CHARS_PER_IMAGE; j++) {
	g.drawImage(orig_image[i], 0, - (j * orig_y * 2),
		    orig_x * 2, orig_y * 2, display);
      }
      imgs[i] = display.createImage(new FilteredImageSource(
	    img.getSource(), new TransparencyFilter()));
      g.dispose();
    }

System.err.println("DEBUG: Double height fonts (bold="+bold+") done, took "
+ ((System.currentTimeMillis() - time))
+" ms.");

    return imgs;
  }

  // A small utility function that draws a digraph; a symbol that consists
  // of two separate characters. Draws largely suboptimal glyphs but...
  public void
  drawDigraph(Graphics g, int x, Font f, int base, int descent,
	      String a, String b)
  {
    g.setFont(f);
    g.drawString(a, -x / 3, base);
    g.drawString(b, x / 3, base+descent);
  }

  public void
  doGfxFonts(int orig_x, int orig_y, Display display, Font font,
	     Font bfont, int font_base, FontMetrics fm)
  {
    Image [] imgs = new Image[6 * NR_OF_SYMBOLS];
    Image base_img, bbase_img;
    Graphics g, g2;
    int i, j, x, y;
    int desc = fm.getDescent();

    display.setGfxFont(imgs);

    /* First we'll create the basic symbols: */
    /* ... along with the bolded ones, because they can easily be drawn
     * anyway.
     */

    // Images are created and cleared:

    // To be able to draw symbols, we need to calculate certain
    // key points and sizes:
    int horiz_center_a = (orig_x - 1) / 2;
    int vert_center_a = (orig_y - 1) / 2;
    int horiz_center_b = (orig_x) / 2;
    int vert_center_b = (orig_y) / 2;

    long time = System.currentTimeMillis();
    for (i = 0; i < NR_OF_SYMBOLS; i++) {

      base_img = display.createImage(orig_x, orig_y);
      bbase_img = display.createImage(orig_x, orig_y);

      g = base_img.getGraphics();
      g.setColor(Color.black);
      g.fillRect(0, 0, orig_x, orig_y);
      g.setColor(Color.white);
      g2 = bbase_img.getGraphics();
      g2.setColor(Color.black);
      g2.fillRect(0, 0, orig_x, orig_y);
      g2.setColor(Color.white);

      switch (i + 0140) {

      case 0140: // Diamond

	g.drawLine(horiz_center_a, 0, 0, vert_center_a);
	g.drawLine(horiz_center_b, 0, orig_x - 1, vert_center_a);
	g.drawLine(horiz_center_a, orig_y - 1, 0, vert_center_b);
	g.drawLine(horiz_center_b, orig_y - 1, orig_x - 1, vert_center_b);

	x = (orig_x - 2) / 2;
	g2.drawLine(x, 0, 0, vert_center_a);
	g2.drawLine(x+1, 0, 1, vert_center_a);
	g2.drawLine(x, 0, orig_x - 1, vert_center_a);
	g2.drawLine(x+1, 0, orig_x - 2, vert_center_a);
	g2.drawLine(x, orig_y - 1, 0, vert_center_b);
	g2.drawLine(x+1, orig_y - 1, 1, vert_center_b);
	g2.drawLine(x, orig_y - 1, orig_x - 1, vert_center_b);
	g2.drawLine(x+1, orig_y - 1, orig_x - 2, vert_center_b);
	break;

      case 0141: // Checkerboard; bolded identical to non-bolded

	boolean a, b;
	int step_x = orig_x / 5;
	int step_y = orig_y / 5;

	for (y = 0, a = true; y < orig_y; y += step_y, a = !a) {
	  for (j = 0; j < step_y; j++) {
	    for (x = 0, b = a; x < orig_x; x += step_x, b = !b) {
	      if (!b)
		continue;
	      g.drawLine(x, y + j, x + step_x - 1, y + j);
	      g2.drawLine(x, y + j, x + step_x - 1, y + j);
	    }
	  }
	}
	break;

	// ......

      case 0142: // Digraph HT

	drawDigraph(g, orig_x, font, font_base, desc, "h", "t");
	drawDigraph(g2, orig_x, bfont, font_base, desc, "h", "t");
	break;

      case 0143: // Digraph FF

	drawDigraph(g, orig_x, font, font_base, desc, "f", "f");
	drawDigraph(g2, orig_x, bfont, font_base, desc, "f", "f");
	break;

      case 0144: // Digraph CR

	drawDigraph(g, orig_x, font, font_base, desc, "c", "r");
	drawDigraph(g2, orig_x, bfont, font_base, desc, "c", "r");
	break;

      case 0145: // Digraph LF

	drawDigraph(g, orig_x, font, font_base, desc, "l", "f");
	drawDigraph(g2, orig_x, bfont, font_base, desc, "l", "f");
	break;

      case 0150: // Digraph NL

	drawDigraph(g, orig_x, font, font_base, desc, "n", "l");
	drawDigraph(g2, orig_x, bfont, font_base, desc, "n", "l");
	break;

      case 0151: // Digraph VT

	drawDigraph(g, orig_x, font, font_base, desc, "v", "t");
	drawDigraph(g2, orig_x, bfont, font_base, desc, "v", "t");
	break;

      case 0146: // Degree symbol (~= o, but upper)

	g.setFont(font);
	g.drawString("\u00B0", 0, font_base);
	g2.setFont(font);
	g2.drawString("\u00B0", 0, font_base);
	break;

      case 0147: // +/- symbol

	g.setFont(font);
	g.drawString("\u00B1", 0, font_base);
	g2.setFont(font);
	g2.drawString("\u00B1", 0, font_base);
	break;

      case 0152: // SE-corner

	g.drawLine(horiz_center_a, 0, horiz_center_a, vert_center_a);
	g.drawLine(horiz_center_a, vert_center_a, 0, vert_center_a);

	g2.drawLine(horiz_center_a, 0, horiz_center_a, vert_center_a);
	g2.drawLine(horiz_center_a + 1, 0, horiz_center_a + 1, vert_center_a);
	g2.drawLine(horiz_center_a, vert_center_a, 0, vert_center_a);
	g2.drawLine(horiz_center_a, vert_center_a + 1, 0, vert_center_a + 1);
	break;

      case 0153: // NE-corner

	g.drawLine(0, vert_center_a, horiz_center_a, vert_center_a);
	g.drawLine(horiz_center_a, vert_center_a, horiz_center_a, orig_y-1);

	g2.drawLine(0, vert_center_a, horiz_center_a, vert_center_a);
	g2.drawLine(0, vert_center_a + 1, horiz_center_a, vert_center_a + 1);
	g2.drawLine(horiz_center_a, vert_center_a, horiz_center_a, orig_y-1);
	g2.drawLine(horiz_center_a+1, vert_center_a, horiz_center_a+1, orig_y-1);
	break;

      case 0154: // NW-corner
	g.drawLine(horiz_center_a, orig_y-1, horiz_center_a, vert_center_a);
	g.drawLine(horiz_center_a, vert_center_a, orig_x - 1, vert_center_a);

	g2.drawLine(horiz_center_a, orig_y-1, horiz_center_a, vert_center_a);
	g2.drawLine(horiz_center_a+1, orig_y-1, horiz_center_a+1, vert_center_a);
	g2.drawLine(horiz_center_a, vert_center_a, orig_x - 1, vert_center_a);
	g2.drawLine(horiz_center_a, vert_center_a+1, orig_x - 1, vert_center_a+1);
	break;

      case 0155: // SW-corner
	g.drawLine(horiz_center_a, 0, horiz_center_a, vert_center_a);
	g.drawLine(horiz_center_a, vert_center_a, orig_x-1, vert_center_a);

	g2.drawLine(horiz_center_a, 0, horiz_center_a, vert_center_a);
	g2.drawLine(horiz_center_a+1, 0, horiz_center_a+1, vert_center_a);
	g2.drawLine(horiz_center_a, vert_center_a, orig_x-1, vert_center_a);
	g2.drawLine(horiz_center_a, vert_center_a+1, orig_x-1, vert_center_a+1);
	break;

      case 0156: //Crossing lines
	g.drawLine(horiz_center_a, 0, horiz_center_a, orig_y - 1);
	g.drawLine(0, vert_center_a, orig_x-1, vert_center_a);

	g2.drawLine(horiz_center_a, 0, horiz_center_a, orig_y - 1);
	g2.drawLine(horiz_center_a+1, 0, horiz_center_a+1, orig_y - 1);
	g2.drawLine(0, vert_center_a, orig_x-1, vert_center_a);
	g2.drawLine(0, vert_center_a+1, orig_x-1, vert_center_a+1);
	break;

      case 0157: // Horiz line, scan 1 (top)

	g.drawLine(0, 0, orig_x-1, 0);

	g2.drawLine(0, 0, orig_x-1, 0);
	g2.drawLine(0, 1, orig_x-1, 1);
	break;
       
      case 0160: // Horiz line, scan 3 (upper)

	g.drawLine(0, vert_center_a / 2,
		   orig_x-1, vert_center_a / 2);

	g2.drawLine(0, vert_center_a / 2,
		   orig_x-1, vert_center_a / 2);
	g2.drawLine(0, vert_center_a / 2 + 1,
		   orig_x-1, vert_center_a / 2 + 1);
	break;

      case 0161: // Horiz line, scan 5 (center)

	g.drawLine(0, vert_center_a, orig_x-1, vert_center_a);

	g2.drawLine(0, vert_center_a, orig_x-1, vert_center_a);
	g2.drawLine(0, vert_center_a+1, orig_x-1, vert_center_a+1);
	break;

      case 0162: // Horiz line, scan 7 (lower)

	g.drawLine(0, (vert_center_a + orig_y - 1) / 2,
		   orig_x-1, (vert_center_a + orig_y - 1) / 2);

	g2.drawLine(0, (vert_center_a + orig_y - 1) / 2,
		   orig_x-1, (vert_center_a + orig_y - 1) / 2);
	g2.drawLine(0, (vert_center_a + orig_y - 1) / 2 + 1,
		   orig_x-1, (vert_center_a + orig_y - 1) / 2 + 1);
	break;

      case 0163: // Horiz line, scan 9 (bottom)

	g.drawLine(0, orig_y-1, orig_x-1, orig_y-1);

	g2.drawLine(0, orig_y-1, orig_x-1, orig_y-1);
	g2.drawLine(0, orig_y-2, orig_x-1, orig_y-2);
	break;

      case 0164: // Left "T" (I-)

	g.drawLine(horiz_center_a, 0, horiz_center_a, orig_y - 1);
	g.drawLine(horiz_center_a, vert_center_a, orig_x-1, vert_center_a);

	g2.drawLine(horiz_center_a, 0, horiz_center_a, orig_y - 1);
	g2.drawLine(horiz_center_a+1, 0, horiz_center_a+1, orig_y - 1);
	g2.drawLine(horiz_center_a, vert_center_a, orig_x-1, vert_center_a);
	g2.drawLine(horiz_center_a, vert_center_a+1, orig_x-1, vert_center_a+1);
	break;

      case 0165: // Right "T" (-I)

	g.drawLine(horiz_center_a, 0, horiz_center_a, orig_y - 1);
	g.drawLine(horiz_center_a, vert_center_a, 0, vert_center_a);

	g2.drawLine(horiz_center_a, 0, horiz_center_a, orig_y - 1);
	g2.drawLine(horiz_center_a + 1, 0, horiz_center_a + 1, orig_y - 1);
	g2.drawLine(horiz_center_a, vert_center_a, 0, vert_center_a);
	g2.drawLine(horiz_center_a, vert_center_a + 1, 0, vert_center_a + 1);
	break;

      case 0166: // Bottom "T" (I_)

	g.drawLine(horiz_center_a, 0, horiz_center_a, vert_center_a);
	g.drawLine(0, vert_center_a, orig_x-1, vert_center_a);

	g2.drawLine(horiz_center_a, 0, horiz_center_a, vert_center_a);
	g2.drawLine(horiz_center_a+1, 0, horiz_center_a+1, vert_center_a);
	g2.drawLine(0, vert_center_a, orig_x-1, vert_center_a);
	g2.drawLine(0, vert_center_a+1, orig_x-1, vert_center_a+1);
	break;

      case 0167: // Top "T" (T)

	g.drawLine(horiz_center_a, vert_center_a, horiz_center_a, orig_y - 1);
	g.drawLine(0, vert_center_a, orig_x-1, vert_center_a);

	g2.drawLine(horiz_center_a, vert_center_a, horiz_center_a, orig_y - 1);
	g2.drawLine(horiz_center_a+1, vert_center_a, horiz_center_a+1,orig_y - 1);
	g2.drawLine(0, vert_center_a, orig_x-1, vert_center_a);
	g2.drawLine(0, vert_center_a+1, orig_x-1, vert_center_a+1);
	break;

      case 0170: // Vertical bar (|)
	g.drawLine(horiz_center_a, 0, horiz_center_a, orig_y - 1);

	g2.drawLine(horiz_center_a, 0, horiz_center_a, orig_y - 1);
	g2.drawLine(horiz_center_a+1, 0, horiz_center_a+1, orig_y - 1);
	break;

      case 0171: // <=

	g.setFont(font);
	g.drawString("<", 0, font_base);
	g.drawString("_", 0, font_base);

	g2.setFont(bfont);
	g2.drawString("<", 0, font_base);
	g2.drawString("_", 0, font_base);
	break;

      case 0172: // >=

	g.setFont(font);
	g.drawString(">", 0, font_base);
	g.drawString("_", 0, font_base);

	g2.setFont(bfont);
	g2.drawString(">", 0, font_base);
	g2.drawString("_", 0, font_base);
	break;

      case 0173: // Pi. Not in ISO-latin-1, unfortunately?

	// If getAscent() did return height of 'a' etc, this would work:
	//y = font_base - fm.getAscent();
	// But.. perhaps we better use another heuristic:
	y = font_base - (fm.getMaxAscent() / 2);
	x = orig_x / 6;
	if (x < 1)
	  x = 1;
	g.drawLine(x, y, orig_x - x - 1, y);
	g.drawLine(2 * x, y, 2 * x, font_base);
	g.drawLine(orig_x - 2 * x - 1, y, orig_x - 2 * x - 1, font_base);

	g2.drawLine(x, y, orig_x - x - 1, y);
	g2.drawLine(x, y+1, orig_x - x - 1, y+1);
	g2.drawLine(2 * x, y, 2 * x, font_base);
	g2.drawLine(2 * x + 1, y, 2 * x + 1, font_base);
	g2.drawLine(orig_x - 2 * x - 1, y, orig_x - 2 * x - 1, font_base);
	g2.drawLine(orig_x - 2 * x, y, orig_x - 2 * x, font_base);
	break;

      case 0174: // !=

	g.setFont(font);
	g.drawString("=", 0, font_base);
	g.drawString("/", 0, font_base);

	g2.setFont(bfont);
	g2.drawString("=", 0, font_base);
	g2.drawString("/", 0, font_base);
	break;

      case 0175: // Pound

	g.setFont(font);
	g.drawString("£", 0, font_base);

	g2.setFont(bfont);
	g2.drawString("£", 0, font_base);
	break;

      case 0176: // Centered dot

	g.setFont(font);
	g.drawString("\u00B7", 0, font_base);

	g2.setFont(bfont);
	g2.drawString("\u00B7", 0, font_base);
	break;

      default:
	System.err.println(
"Internal error in generating gfx symbol #"+Integer.toOctalString(i + 0140)
+", but will try to go on...");
      }

      // After drawing these symbols, we need to 'add the transparency':
      imgs[i] = display.createImage(new FilteredImageSource(
	   base_img.getSource(), new TransparencyFilter()));
      imgs[NR_OF_SYMBOLS + i] = display.createImage(new FilteredImageSource(
	   bbase_img.getSource(), new TransparencyFilter()));
      g.dispose();
      g2.dispose();
    }

    /* Now we need to wait to make sure we can continue with drawing... */

    for (i = 0; i < NR_OF_SYMBOLS - 1; i++) {
      waitForImage(imgs[i], group, false);
      waitForImage(imgs[NR_OF_SYMBOLS + i], group, false);
    }
    waitForImage(imgs[NR_OF_SYMBOLS-1], group, false);
    waitForImage(imgs[(2 * NR_OF_SYMBOLS)-1], group, true);

    group += 1;

    /* We can now make doubled versions of non-bold symbols without
     * waiting: 
     */

    // A kludge to prevent excessive paint()s on Display:
    display.addSkipRepaint(NR_OF_SYMBOLS);

    for (i = 0; i < NR_OF_SYMBOLS; i++) {
      base_img = display.createImage(orig_x * 2, orig_y);
      g = base_img.getGraphics();
      g.setColor(Color.black);
      g.fillRect(0, 0, orig_x * 2, orig_y);
      g.drawImage(imgs[i], 0, 0, orig_x * 2, orig_y, display);
      imgs[NR_OF_SYMBOLS*2+i]= display.createImage(new FilteredImageSource(
	    base_img.getSource(), new TransparencyFilter()));
      g.dispose();
      base_img = display.createImage(orig_x * 2, orig_y * 2);
      g = base_img.getGraphics();
      g.setColor(Color.black);
      g.fillRect(0, 0, orig_x * 2, orig_y * 2);
      g.drawImage(imgs[i], 0, 0, orig_x * 2, orig_y * 2, display);
      imgs[NR_OF_SYMBOLS*3+i]= display.createImage(new FilteredImageSource(
	    base_img.getSource(), new TransparencyFilter()));
      g.dispose();
    }

    // Kludge-off... :-)
    display.clearSkipRepaint();

    /* Before doubling the bold symbols, we need to wait, though: */
    for (i = 0; i < NR_OF_SYMBOLS - 1; i++) {
      waitForImage(imgs[NR_OF_SYMBOLS + i], group, false);
    }
    waitForImage(imgs[2 * NR_OF_SYMBOLS-1], group, true);
    group += 1;

    // Kludge-on...
    display.addSkipRepaint(NR_OF_SYMBOLS);

    /* And then we'll draw the doubled versions: */
    for (i = 0; i < NR_OF_SYMBOLS; i++) {
      base_img = display.createImage(orig_x * 2, orig_y);
      g = base_img.getGraphics();
      g.setColor(Color.black);
      g.fillRect(0, 0, orig_x * 2, orig_y);
      g.drawImage(imgs[NR_OF_SYMBOLS + i], 0, 0, orig_x * 2, orig_y, display);
      imgs[NR_OF_SYMBOLS*4+i]= display.createImage(new FilteredImageSource(
	    base_img.getSource(), new TransparencyFilter()));
      g.dispose();
      base_img = display.createImage(orig_x * 2, orig_y * 2);
      g = base_img.getGraphics();
      g.setColor(Color.black);
      g.fillRect(0, 0, orig_x * 2, orig_y * 2);
      g.drawImage(imgs[NR_OF_SYMBOLS + i], 0, 0, orig_x * 2,
		  orig_y * 2, display);
      imgs[NR_OF_SYMBOLS*5+i]= display.createImage(new FilteredImageSource(
	    base_img.getSource(), new TransparencyFilter()));
      g.dispose();
    }

    // And the final kludge-off:
    display.clearSkipRepaint();

    /* Finally, we better wait for all the other symbols to get drawn: */
    for (i = 0; i < NR_OF_SYMBOLS; i++) {
      waitForImage(imgs[2 * NR_OF_SYMBOLS + i], group, false);
      waitForImage(imgs[3 * NR_OF_SYMBOLS + i], group, false);
      waitForImage(imgs[4 * NR_OF_SYMBOLS + i], group, false);
      waitForImage(imgs[5 * NR_OF_SYMBOLS + i], group,
		   (i == (NR_OF_SYMBOLS - 1)) ? true : false );
    }

    // For now, let's just copy the symbols as is to bold/dw/dh sections:

    if (debugFonts) {
      System.err.println("DEBUG: waitForImage() (gfx) took "
			 + ((System.currentTimeMillis() - time))
			 +" ms.");
    }
  }

  MediaTracker tr = null;
  int last_group = -1;

  void
  waitForImage(Image im, int group, boolean do_wait)
  {
    if (group != last_group || tr == null)
      tr = new MediaTracker(display);
    // Not really sure if we need to use different groups...
    try {
      tr.addImage(im, group);
      if (do_wait) {
	tr.waitForID(group);
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}

// We also need a filter to make the image transparent. C'est la vie.
final class
TransparencyFilter
extends RGBImageFilter
{
  TransparencyFilter()
  {
    canFilterIndexColorModel = true; // Not of much use when  filtering
    // Images other than GIFs/JPGs from files, unfortunately
  }

  public int
  filterRGB(int x, int y, int rgb)
  {
    //return rgb | 0xFF000000;
    /* Black -> transparent, white -> opaque (2-color image) */

    // Black? (ie. if blue component is under half of its max intensity)
    if ((rgb & 0xFF) < 0x80)
      return 0x00000000;
    //return rgb & 0x00FFFFFF;

    // No, assumed to be white:
    return 0xFFFFFFFF;
    //return (rgb | 0xFFFFFFFF);
  }
}
