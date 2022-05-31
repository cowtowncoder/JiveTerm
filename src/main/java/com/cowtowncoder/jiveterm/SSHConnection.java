/**************************************
				       
Project:
    JiveTerm - A VT52/VT100/VT102-compliant
    telnet/terminal program
    written in pure java.

    (C) 1998-2001 Tatu Saloranta
    tatu.saloranta@iki.fi.

Module:
    Connection.java

Description:
    Simple utility class that represents SSH-connections

Last changed:
    23-Sep-1999, TSa

Changes:

**************************************/

package com.cowtowncoder.jiveterm;

import java.net.*;
import java.io.*;
import java.awt.Dimension;

import mindbright.ssh.*;

public final class SSHConnection
implements JiveConnection
{
  /** Constants: */
  public final static int SSH_PORT = 22;

  /** Method variables: */
  protected int port;
  protected String hostName;
  protected InetAddress hostAddress;
  protected Terminal terminal = null;
  protected JiveTerm jiveterm = null;
  protected JiveSSHClient ssh = null;

  // Debug-stuff:
  public final static boolean debugConnection = true;

  /** If the host name is invalid, instantiating throws the
    * UnknowHostException.
    */
  public SSHConnection(JiveTerm master, String host, int port)
    throws UnknownHostException
  {
      super(false, false);
      jiveterm = master;
      this.port = port;
      hostName = host;
      hostAddress = InetAddress.getByName(host); // May throw the exception...
  }

/***** Simple set-/get-functions: *****/

    // Only used in telnet-mode:
    public final void setSendNAWS(boolean x) { }

/***** Connection initialization/close: ********/

  /** This method returns a valid Terminal-object if the connection
   * succeeded; otherwise throws an exception that indicates what
   * went wrong.
   */
  public Terminal
  connect()
    throws Exception
  {
      Terminal t = new Terminal(jiveterm, this);
      
      ssh = new JiveSSHClient(jiveterm, hostName, port, this);
      // Connect may throw various exceptions, including
      // HostUnknownException, FileNotFound and various
      // IO- and Net- exceptions.
      ssh.connect();
      
      // Now we need to link the ssh-module to the Terminal...
      // (these methods may throw exceptions):
      PipedInputStream pi1 = new PipedInputStream();
      PipedOutputStream po1 = new PipedOutputStream(pi1);
      t.setConnection(pi1, false, false);
      ssh.linkTerminal(po1);
      return (terminal = t);
  }

  public boolean disconnect()
    throws IOException
  {
    terminal = null;

    ssh.disconnect();
    ssh = null; // To let it be gc:ed...

    return true;
  }

  // When this is called, someone else has already closed the
  // connection, so we need not do much, mostly just mark connection
  // as closed. If the JiveTerm instance itself is calling
  // us, we need not even inform it about disconnect...
  public void
  informDisconnect(boolean inform_master)
  {
    terminal = null;

    ssh = null; // To let it be gc:ed...

    if (inform_master)
      jiveterm.informDisconnect();
  }

  // The last output window size sent to the server:
  private int windowX = 0, windowY = 0;

  // Note that it's either NAWS (telnet), or something else (SSH):
  public final synchronized void
  sendNAWS(int x, int y, boolean force)
  {
    if (!jiveterm.isConnected())
      return;

    Dimension wsize = null;
    if (x < 0 || y < 0)
      wsize = jiveterm.getWindowSizeInChars();

    if (x < 0)
      x = wsize.width;
    if (y < 0)
      y = wsize.height;
    if (!force && x == windowX && y == windowY)
      return;
    windowX = x;
    windowY = y;

    if (ssh == null) {
	jiveterm.doWarningLF("Debug: Can't send window-size-change - notify, connection not open.");
	return;
    }

    Dimension px = jiveterm.getWindowSizeInPixels();
    ssh.notifyWindowSizeChange(y, x, px.height, px.width);
  }

/**** Then the functions for sending data to the server: ******/
  public final synchronized boolean
  sendByte(byte x, boolean flush)
  {
      return ssh.sendByte(x, flush);
  }

  public final synchronized boolean
  sendBytes(byte [] x, boolean flush)
  {
      return ssh.sendBytes(x, flush);
  }

  public final synchronized boolean
  sendBytes(byte [] x, int offset, int length, boolean flush)
  {
      return ssh.sendBytes(x, offset, length, flush);
  }
}
