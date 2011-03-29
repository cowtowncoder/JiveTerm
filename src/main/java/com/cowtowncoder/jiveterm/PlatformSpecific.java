/**************************************

Project:
    JiveTerm.

    A VT52/VT100/VT102-compliant
    telnet/terminal program
    written in pure java.

    (C) 1998-99 Tatu Saloranta (aka Doomdark),
    doomdark@iki.fi.

Module:
    PlatformSpecific.java
Last changed:
    15-Apr-99.
Description:
    An utility class that contains
    (or, at least, should contain!)
    all platform-specific code. Also
    returns information about various
    platform-specific optimizations;
    other modules ask for this information.

Changes:

**************************************/

package com.cowtowncoder.jiveterm;

import java.awt.Color;

public final class
PlatformSpecific
{
  // Not a complete list; just 'generic platforms' that are
  // used at the moment (ie. MacOS, AmigaOS, BeOS etc will
  // be added as required):
  public final static int GEN_PLATFORM_UNIX = 1;
  public final static int GEN_PLATFORM_WINDOWS = 2;
  public final static int GEN_PLATFORM_OTHER = -1;

  // Same here:
  public final static int OS_LINUX = 1;
  public final static int OS_OTHER_UNIX = 2;
  public final static int OS_WINDOWS_3X = 3;
  public final static int OS_WINDOWS_9X = 4;
  public final static int OS_WINDOWS_NT = 5;
  public final static int OS_OTHER = -1;

  private static int genPlatform; // GEN_PLATFORM_xxx
  private static int OS; // OS_xxx

  // This should work from static initialization block too me thinks,
  // but appears not to. Thus, needs to be called as the very
  // first thing... From JiveTerm.main(), for example:
  public final static void
  initialize()
  {
    String os_name = System.getProperty("os.name");

    // Shouldn't happen?
    if (os_name == null)
      os_name = "unknown";
    else os_name = os_name.toLowerCase();
      
    // A windows-system of some kind?
    if (os_name.startsWith("windows")) {
      genPlatform = GEN_PLATFORM_WINDOWS;
      OS = OS_WINDOWS_9X;
      
      // A linux-system?
    } else if (os_name.startsWith("linux")) {
      genPlatform = GEN_PLATFORM_UNIX;
      OS = OS_LINUX;
    } else {
      genPlatform = GEN_PLATFORM_OTHER;
      OS = OS_OTHER;
    }
  }

  public final static int getGenericPlatform() { return genPlatform; }
  public final static int getOS() { return OS; }

  /** Returns whether we should try to reduce the number of distinct
    * Image-instances. Currently seems to be so that on Windows 95
    * at least we need to do so. On X-windows systems, on the other hand,
    * we don't want to have too big Images, and thus will create
    * much more Images.
    */
  public final static boolean
  limitNrOfImages()
  {
    return genPlatform == GEN_PLATFORM_WINDOWS;
  }

  /** As there seems to be problems with calculating the real font
    * widths, this function should be called after using a FontMetrics-
    * instance. Currently in practice it seems that on Windows,
    * at least on JDK Java VM, we need to modify the reported width
    * a bit. Weird.
    */
  public final static int
  getActualFontWidth(int width)
  {
    if (genPlatform != GEN_PLATFORM_WINDOWS)
      return width;
    return (width - 1) / 2; // Or should it be (width / 2) - 1 ???
  }

  /** Hrmmrhm. When drawing cursor using XOR drawing mode, the actual
    * colour to use when setting the mode is different on different
    * platforms (at least on linux JDK 1.1.7 vs. Win95 JDK 118).
    */
  public final static Color
  getCursorXORColour()
  {
    if (genPlatform == GEN_PLATFORM_WINDOWS)
      return Color.black;
    else return Color.white;
  }
}
