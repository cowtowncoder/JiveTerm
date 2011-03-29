/**************************************
				       
Project:
    JiveTerm - A VT52/VT100/VT102-compliant
    telnet/terminal program
    written in pure java.

    (C) 1998-99 Tatu Saloranta (aka Doomdark),
    doomdark@iki.fi.

Module:
    JiveSSHClient.java

Description:
    The bridge-class between JiveTerm and
    MindBright's SSH-implementation (MindTerm).

Last changed:
  27-Apr-99, TSa

Changes:

**************************************/

package jiveterm;

import java.io.*;
import java.net.*;

import jiveterm.*;

import mindbright.ssh.*;
import mindbright.security.*;
import mindbright.terminal.TerminalListener;
import mindbright.terminal.Terminal;

public final class
JiveSSHClient
extends
  SSHClient
implements
  SSHConsole, Terminal
{
  // A simple utility class, because we need to override some
  // of the default settings of SSHClientUserAdaptor...
  // (could use inner classes as well I guess)
  static public class
  MyUser
  extends SSHClientUserAdaptor
  {
    public MyUser(String host, int port)
    {
      super(host, port);
    }

    public boolean wantPTY() { return true; }
    public String getDisplay() { return "localhost:2.0"; }
  }

  // Constants:
  public final static int SSH_BUFFER_SIZE = 2048;
    // Shouldn't need much more; this is only for the stuff that
    // Terminal sends us, and these should be small strings
    // (control codes and keyboard input)

  // Variables:

  protected JiveTerm jiveterm;
  protected Connection connection;

  protected String hostName;
  protected int port;
  protected InetAddress hostAddress;

  protected OutputStream toTerminal;

  private static boolean initDone = false;

  public
  JiveSSHClient(JiveTerm jt, String host, int port, Connection c)
  {
    super(
	  new SSHPasswordAuthenticator("doomdark", "!beOS!", "3des"),
	  new MyUser(host, port)
	 );
    
    jiveterm = jt;
    connection = c;
    setConsole(this); // Did I mention we are a valid SSHConsole too? =)
    //setDefaultProperties();
  }

/*** Initializations SSH-system requires; called from JiveTerm: ***/

  private final static void markInitDone() { initDone = true; }

  public static void
  initSSH()
  {
    if (!initDone) {
      // It takes some time to initialize the generator it seems, so
      // let's kick a thread running:
      Thread tmp = new Thread() {
	public void run() {
	  System.err.println("DEBUG: initing ssh...");
	  long now = System.currentTimeMillis();
	  SSH.initSeedGenerator();
	  now = System.currentTimeMillis() - now;
	  System.err.println("DEBUG: took "+now+" msecs to init SSH!");
	  markInitDone();
	}
      };
      // Let's not slow the whole thing down...
      tmp.setPriority(JiveTerm.JIVESSH_PRIORITY);
      tmp.start();
    }
  }

  public void
  linkTerminal(OutputStream o)
  {
    toTerminal = o;
  }

  public boolean
  connect()
    throws /*UnknownHostException, FileNotFoundException,*/ Exception
  {

    if (!initDone) {
      long delay = 200;
      long max_delay = 500;
      long delay_shift = 50;

      System.err.print("Waiting for SSH-init...");

      while (true) {
	try {
	  Thread.sleep(delay);
	  if (delay < max_delay)
	    delay += delay_shift;
	} catch  (InterruptedException ie) { }

	if (initDone)
	  break;
	System.err.print(".");
      }
    }

    bootSSH(true);
    setInteractive();
    return true;
  }

  // How should we handle disconnect-request?
  public boolean
  disconnect()
  {
    forcedDisconnect();
    return true;
  }

  /** Implementation of SSHConsole: */
  public Terminal getTerminal() { return this; } // As we are a Terminal too...

  // This function is to write text on (local) terminal (from SSH-server):
  public void
  stdoutWriteString(byte [] b)
  {
      stdoutWriteString(new String(b));
  }

  public void
  stdoutWriteString(String str)
  {
/*
Display.ssh_rec = System.currentTimeMillis();
*/
    //System.err.println(" <- "+(System.currentTimeMillis() % 1000));
    try {
      toTerminal.write(str.getBytes());
      toTerminal.flush();
    } catch (IOException ie) {
      System.err.println("Error on JiveSSH.stdoutWriteString: "+ie);
    }
  }

  // Similar to stdoutWriteString(); we could try to mark error
  // differently, if it'd be of some use?
  public void
  stderrWriteString(byte [] b)
  {
      stderrWriteString(new String(b));
  }

  public void
  stderrWriteString(String str)
  {
    //System.err.println("DEBUG: JiveSSH, stderrW('"+str+"')");
    try {
      toTerminal.write(str.getBytes());
      toTerminal.flush();
    } catch (IOException ie) {
      System.err.println("Error on JiveSSH.stderrWriteString: "+ie);
    }
  }

  // This function is to write text on (local) terminal, 'locally',
  // ie. NOT from the ssh-server (?)
  public void
  print(String str)
  {
    //System.err.println("DEBUG: JiveSSH, print('"+str+"')");
  }

  // As print(), but adds a trailing linefeed...
  public void
  println(String str)
  {
    //System.err.println("DEBUG: JiveSSH, println('"+str+"')");
  }

  // This function is called when (if) the server accepts the
  // connection:
  public void
  serverConnect(SSHChannelController controller, Cipher sndCipher)
  {
    //connection.setController(controller);
    System.err.println("DEBUG: JiveSSH; serverConnect!");
  }

  // This function is called when the server ends the connection:
  public void
  serverDisconnect(String reason)
  {
    //connection.setController(null);
    System.err.println("DEBUG: JiveSSH; serverDisconnect!");
    connection.informDisconnect(true);
  }

  /** end of implementation of SSHConsole. */

  /** Implementation of mindbright.terminal.Terminal class;
    * necessary because Console needs to return a valid
    * terminal object... Not much more than a dummy implementation;
    * only few more important functions are actually really implemented.
    */
  public String terminalType() { return "vt100"; }
  public int    rows() { return jiveterm.getWindowSizeInChars().height; }
  public int    cols() { return jiveterm.getWindowSizeInChars().width; }
  public int    vpixels() { return jiveterm.getWindowSizeInPixels().width; }
  public int    hpixels() { return jiveterm.getWindowSizeInPixels().height; }

  public void write(char c) {}
  public void write(char[] c, int off, int len) {}
  public void write(String str) {}
  public void writeLineDrawChar(char c) {}

  public void addTerminalListener(TerminalListener listener) {}

  public void sendBytes(byte[] b) {}

  public void doBell() {}
  public void doBS() {}
  public void doTab() {}
  public void doCR() {}
  public void doLF() {}

  public void resetWindow() {}
  public void setWindow(int top, int bottom) {}
  public void setWindow(int top, int right, int bottom, int left) {}
  public int  getWindowTop() { return 0; }
  public int  getWindowBottom() { return 0;}
  public int  getWindowLeft() { return 0;}
  public int  getWindowRight() { return 0;}

  public int getCursorV() { return 0;}
  public int getCursorH() { return 0;}

  public void cursorSetPos(int v, int h, boolean relative) {}
  public void cursorUp(int n) {}
  public void cursorDown(int n) {}
  public void cursorForward(int n) {}
  public void cursorBackward(int n) {}
  public void cursorIndex(int n) {}
  public void cursorIndexRev(int n) {}

  public void cursorSave() {}
  public void cursorRestore() {}

  public void scrollUp(int n) {}
  public void scrollDown(int n) {}

  public void clearBelow() {}
  public void clearAbove() {}
  public void clearScreen() {}
  public void clearRight() {}
  public void clearLeft() {}
  public void clearLine() {}

  public void setInsertMode(boolean val) {}
  public void insertChars(int n) {}
  public void insertLines(int n) {}
  public void deleteChars(int n) {}
  public void deleteLines(int n) {}

  public void    setOption(int opt, boolean val) {}
  public boolean getOption(int opt) { return false; }

  public void    setAttribute(int attr, boolean val) {}
  public boolean getAttribute(int attr) { return false; }
  public void    setForegroundColor(int c) {}
  public void    setBackgroundColor(int c) {}
  public void    clearAllAttributes() {}

  public void clearTab(int i) { }
  public void setTab(int i) { }
  public void resetTabs() { }
  public void clearAllTabs() { }

  public void resetInterpreter() { }

  /** end of implementation of mindbright.terminal.Terminal */

  /*** And then few functions for sending the data to the server: ***/
  // Note that locking needs to be somewhere else; for performance reasons
  // it's not done here (but earlier in Connection.java, for example)
  public final boolean
  sendByte(byte x, boolean flush)
  {
//long now = System.currentTimeMillis();
    try {
      SSHPduOutputStream stdinPdu;
      stdinPdu = new SSHPduOutputStream(SSH.CMSG_STDIN_DATA, sndCipher);
      stdinPdu.writeInt(1);
      stdinPdu.write((int) x);
      controller.transmit(stdinPdu);
    } catch (Exception ex) {
      System.err.println("Error in SSH-send: "+ex);
      return false;
    }

//System.err.print(" "+(now % 1000)+"+"+(System.currentTimeMillis() - now));
    return true;
  }
  public final boolean
  sendBytes(byte [] x, boolean flush)
  {
//long now = System.currentTimeMillis();
    try {
      SSHPduOutputStream stdinPdu;
      stdinPdu = new SSHPduOutputStream(SSH.CMSG_STDIN_DATA, sndCipher);
      stdinPdu.writeInt(x.length);
      stdinPdu.write(x);
      controller.transmit(stdinPdu);
    } catch (Exception ex) {
      System.err.println("Error in SSH-send: "+ex);
      return false;
    }
//System.err.print(" "+(now % 1000)+"+"+(System.currentTimeMillis() - now));
    return true;
  }

  public final boolean
  sendBytes(byte [] x, int offset, int length, boolean flush)
  {
//long now = System.currentTimeMillis();
    try {
      SSHPduOutputStream stdinPdu;
      stdinPdu = new SSHPduOutputStream(SSH.CMSG_STDIN_DATA, sndCipher);
      stdinPdu.writeInt(length);
      stdinPdu.write(x, offset, length);
      controller.transmit(stdinPdu);
    } catch (Exception ex) {
      System.err.println("Error in SSH-send: "+ex);
      return false;
    }
//System.err.print(" "+(now % 1000)+"+"+(System.currentTimeMillis() - now));
    return true;
  }

  /**** And then misc functions: ****/

  // This isn't much more than a wrapper for the 'real' function
  // defined by mindbright.ssh.SSHClient, but as we only want this one
  // class to be directly hooked to MindTerm's classes (ie. could
  // ship telnet-only client without mindterm, and let people download
  // MindTerm separately), we'll put it here:

  public final void
  notifyWindowSizeChange(int ch_rows, int ch_cols,
			 int px_vert, int px_horiz)
  {
    signalWindowChanged(ch_rows, ch_cols, px_vert, px_horiz);
  }
}
