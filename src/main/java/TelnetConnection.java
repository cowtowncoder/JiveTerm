/**************************************
				       
Project:
    JiveTerm - A VT52/VT100/VT102-compliant
    telnet/terminal program
    written in pure java.

    (C) 1998-2001 Tatu Saloranta, tatu.saloranta@iki.fi

Module:
    TelnetConnection.java

Description:
    Simple utility class that represents telnet-connections

Last changed:
    24-Sep-1999, TSa

Changes:

**************************************/

package jiveterm;

import java.net.*;
import java.io.*;
import java.awt.Dimension;

public final class TelnetConnection
implements JiveConnection
{
    /** Constants: */
    public final static int TELNET_PORT = 23;

    /* ** Telnet protocol commands and constants: ** */

    // Telnet commands:
    public final static byte TELNET_EOF = (byte) 236;
    public final static byte TELNET_SUSP = (byte) 237;
    public final static byte TELNET_ABORT = (byte) 238;
    public final static byte TELNET_EOR = (byte) 239;
    public final static byte TELNET_SUBOPTION_END = (byte) 240;
    public final static byte TELNET_NOP = (byte) 241;
    public final static byte TELNET_DM = (byte) 242;
    public final static byte TELNET_BRK = (byte) 243;
    public final static byte TELNET_IP = (byte) 244;
    public final static byte TELNET_AO = (byte) 245;
    public final static byte TELNET_AYT = (byte) 246;
    public final static byte TELNET_EC = (byte) 247;
    public final static byte TELNET_EL = (byte) 248;
    public final static byte TELNET_GA = (byte) 249;
    public final static byte TELNET_SUBOPTION = (byte) 250;
    public final static byte TELNET_WILL = (byte) 251;
    public final static byte TELNET_WONT = (byte) 252;
    public final static byte TELNET_DO = (byte) 253;
    public final static byte TELNET_DONT = (byte) 254;
    public final static byte TELNET_IAC = (byte) 255;
    
    // Telnet-suboptions:
    public final static byte TN_OPTION_BINARY_TRANSMISSION = (byte) 0;
    public final static byte TN_OPTION_ECHO = (byte) 1;
    public final static byte TN_OPTION_RECONNECTION = (byte) 2;
    public final static byte TN_OPTION_SUPPRESS_GA = (byte) 3;
    public final static byte TN_OPTION_APPROX_MSG_SIZE = (byte) 4;
    public final static byte TN_OPTION_STATUS = (byte) 5;
    public final static byte TN_OPTION_TIMING_MARK = (byte) 6;
    public final static byte TN_OPTION_REMOTE_CTRL_TRANS_AND_ECHO = (byte) 7;
    public final static byte TN_OPTION_OUTPUT_LINE_WIDTH = (byte) 8;
    public final static byte TN_OPTION_OUTPUT_PAGE_SIZE = (byte) 9;
    public final static byte TN_OPTION_OUTPUT_CR_DISP = (byte) 10;
    public final static byte TN_OPTION_OUTPUT_HOR_TAB_STOPS = (byte) 11;
    public final static byte TN_OPTION_OUTPUT_HOR_TAB_DISP = (byte) 12;
    public final static byte TN_OPTION_OUTPUT_FF_DISP = (byte) 13;
    public final static byte TN_OPTION_OUTPUT_VERT_TAB_STOPS = (byte) 14;
    public final static byte TN_OPTION_OUTPUT_VERT_TAB_DISP = (byte) 15;
    public final static byte TN_OPTION_OUTPUT_LF_DISP = (byte) 16;
    public final static byte TN_OPTION_EXTENDED_ASCII = (byte) 17;
    public final static byte TN_OPTION_LOGOUT = (byte) 18;
    public final static byte TN_OPTION_BYTE_MACRO = (byte) 19;
    public final static byte TN_OPTION_DE_TERMINAL = (byte) 20;
    public final static byte TN_OPTION_SUPDUP = (byte) 21;
    public final static byte TN_OPTION_SUPDUP_OUT = (byte) 22;
    public final static byte TN_OPTION_SEND_LOCATION = (byte) 23;
    public final static byte TN_OPTION_TERM_TYPE = (byte) 24;
    public final static byte TN_OPTION_EOR = (byte) 25;
    public final static byte TN_OPTION_TACACS_UI = (byte) 26;
    public final static byte TN_OPTION_OUTPUT_MARKING = (byte) 27;
    public final static byte TN_OPTION_TERMINAL_LOC_NR = (byte) 28;
    public final static byte TN_OPTION_3270_REGIME = (byte) 29;
    public final static byte TN_OPTION_X3_PAD = (byte) 30;
    public final static byte TN_OPTION_NAWS = (byte) 31;
    public final static byte TN_OPTION_TERM_SPEED = (byte) 32;
    public final static byte TN_OPTION_REMOTE_FLOW_CTRL = (byte) 33;
    public final static byte TN_OPTION_LINE_MODE = (byte) 34;
    public final static byte TN_OPTION_X_DISP_LOC = (byte) 35;
    public final static byte TN_OPTION_ENV = (byte) 36;
    public final static byte TN_OPTION_AUTH = (byte) 37;
    public final static byte TN_OPTION_ENCR = (byte) 38;
    public final static byte TN_OPTION_NEW_ENV = (byte) 39;

    // Bytes that need to be converted at this level:
    public final static byte BYTE_NULL = (byte) 0x00;
    public final static byte BYTE_CR = 0x0D;
    public final static byte BYTE_LF = 0x0A;

    // Constants, short protocol messages:
    private final static byte[] IACTelnetInit = {
	TELNET_IAC, TELNET_DO, TN_OPTION_SUPPRESS_GA,
	TELNET_IAC, TELNET_WILL, TN_OPTION_TERM_TYPE,
	TELNET_IAC, TELNET_WILL, TN_OPTION_NAWS
    };
    /* Several messages we will send to the TCP-connection: */
    private final static byte[] IACReply = new byte[3];
    static {
	IACReply[0] = TELNET_IAC;
	IACReply[1] = 0;
	IACReply[2] = 0;
    }
    private final static byte[] IACVT102TermTypeReply =
    { TELNET_IAC, TELNET_SUBOPTION, 24, 0,
      (byte) 'v', (byte) 't', (byte) '1', (byte) '0', (byte) '2',
      TELNET_IAC, TELNET_SUBOPTION_END
    };
    private final static byte[] IACVT220TermTypeReply =
    { TELNET_IAC, TELNET_SUBOPTION, 24, 0,
      (byte) 'v', (byte) 't', (byte) '2', (byte) '2', (byte) '0',
      TELNET_IAC, TELNET_SUBOPTION_END
    };
    private final static byte[] IACVT320TermTypeReply =
    { TELNET_IAC, TELNET_SUBOPTION, 24, 0,
      (byte) 'v', (byte) 't', (byte) '3', (byte) '2', (byte) '0',
      TELNET_IAC, TELNET_SUBOPTION_END
    };
    private final static byte[] IACVT420TermTypeReply =
    { TELNET_IAC, TELNET_SUBOPTION, 24, 0,
      (byte) 'v', (byte) 't', (byte) '4', (byte) '2', (byte) '0',
      TELNET_IAC, TELNET_SUBOPTION_END
    };
    private final static byte[] IACColorXTermTermTypeReply = {
	TELNET_IAC, TELNET_SUBOPTION, 24, 0,
	(byte) 'x', (byte) 't', (byte) 'e', (byte) 'r', (byte) 'm',
	(byte) '-', (byte) 'c', (byte) 'o', (byte) 'l', (byte) 'o', (byte) 'r',
	TELNET_IAC, TELNET_SUBOPTION_END
    };

    private byte[] IACTermTypeReply = IACVT220TermTypeReply;

    /* Some NVT-ascii character conversions:
     */
    public final static byte [] LINEFEED = { (byte) '\r', (byte) '\n' };
    // In theory, NVT (required by Telnet) requires \r\0, not bare \r.
    // Still... somehow it just does not work ok.
    /* So. Now vttest 'accepts' the codes sent, which, I guess, is
     * enough. Too bad it doesn't make any sense... :-)
     * (damn would it be too easy if we just used single \n all the time???)
     */
    public final static byte [] LINEFEED_CRLF = {
	(byte) '\r', (byte) '\n', (byte) '\n'
    };
    public final static byte [] LINEFEED_CR = { (byte) '\r', (byte) '\n' };
    public final static byte [] DISPLAY_LINEFEED = { (byte) '\r', (byte) '\n' };
    public final static byte [] LINEFEED_SSH = { (byte) '\n' };


    /* ** Method variables: ** */
    protected int port;
    protected String hostName;
    protected InetAddress hostAddress;
    protected Terminal terminal = null;
    protected JiveTerm jiveterm = null;

    // For telnet-connections:
    protected boolean sendNAWS = false;
    protected Socket telnetSocket = null;
    protected InputStream mInput;
    protected OutputStream mOutput;
    protected boolean mSendTelnetCodes;
    
    // Debug-stuff:
    public final static boolean debugConnection = true;

    private final static Hashtable telnetOptions = new Hashtable();
    private final static Hashtable telnetSuboptions = new Hashtable();

  /** If the host name is invalid, instantiating throws the
    * UnknowHostException.
    */
    public TelnetConnection(JiveTerm master, String host, int port,
			    boolean sendTNC)
	throws UnknownHostException
    {
	super(true, true, TELNET_IAC);
	jiveterm = master;
	this.port = port;
	hostName = host;
	mSendTelnetCodes = sendTNC;
	hostAddress = InetAddress.getByName(host); // May throw the exception...
    }

/***** Simple set-/get-functions: *****/

    // Only used in telnet-mode:
    public final void setSendNAWS(boolean x)
    {
	sendNAWS = x;
    }

/***** Connection initialization/close: ********/
    
    /** This method returns a valid Terminal-object if the connection
     * succeeded; otherwise throws an exception that indicates what
     * went wrong.
     */
    public boolean connect() throws Exception
    {
	telnetSocket = new Socket(hostAddress, port);
      
	/* When trying to get the input/output stream(s), we may
	 * get an exception...
	 */
	/* Note: similar to vanilla unix telnet-clients, telnet-protocol
	 * handling (default echo etc) depends on whether we are connecting
	 * to 'real' telnet-server, or another port.
	 */
	try {
	    mInput = telnetSocket.getInputStream();
	    mOutput = telnetSocket.getOutputStream();
	    // 3rd arg -> are we having a 'real' telnet-connection?
	} catch (IOException e) {
	    // If so, we better (try to) close the socket:
	    try {
		telnetSocket.close();
	    } catch (IOException e2) {
	    }
	    // Even so, we better throw the first exception as it probably
	    // also resulted in the second exception...
	    throw e;
	}

	return true;
    }

    public void initializeConnection()
    {
	if (mSendTelnetCodes) {
	    sendBytes(sIACTelnetInit, true);
	}
    }

  public boolean
  disconnect()
    throws IOException
  {
    terminal = null;

    Socket foo = telnetSocket;
    telnetSocket = null;
    foo.close();
    return true;
  }

    /* When this is called, someone else has already closed the
     * connection, so we need not do much, mostly just mark connection
     * as closed. If the JiveTerm instance itself is calling
     * us, we need not even inform it about disconnect...
     */
    public void
    informDisconnect(boolean inform_master)
    {
	terminal = null;
	Socket foo = telnetSocket;
	telnetSocket = null;
	try {
	    foo.close();
	}catch (IOException ie) {
	    System.err.println("Warning: Couldn't close the telnet-socket: "+ie);
	    ; // What can we do even if closing fails?
	}
    
	if (inform_master) {
	    jiveterm.informDisconnect();
	}
    }

    /* ** Method(s) for reading data from connection: ** */
    private int readBytes(byte[] buffer) throws IOException
    {
	return mInputStream.read(buffer);
    }

    public int getBytes(byte[] result)
    {
	byte b;
	int resultPtr = 0;
	int len = result.length;

	loop:
	do {
	    // No stuff in input buffer?
	    if (mInputPtr >= mInputSize) {
		/* If we have gotten something, let's just return it
		 * now, and read more stuff in later:
		 */
		if (resultPtr > 0) {
		    break loop;
		}
		/* .. but it this is in fact 'later' (or previous bytes
		 * were control codes that were handled), we have to
		 * read more:
		 */
		if (!getMoreInput()) {
		    /* Will only fail when connection closed (or a similar
		     * fatal exception caught?)
		     */
		    return -1;
		}
	    }
	    
	    // This is just an 'explicit inline':
	    /* bps-delay checks: */
	    if (mBpsLimit > 0) {
		maintainBpsLimit();
	    }
	    
	    byte b = mInputBuffer[mInputPtr];

	    /* We need to handle:
	     * - In-band telnet control codes (options, suboptions) and
	     * - NVT linefeeds
	     */
	    switch (b) {
	    case TELNET_IAC:
		/* Same as with reading new stuff; if we already got
		 * something, let's return it as handling control codes
		 * may require reading in more stuff:
		 */
		if (resultPtr > 0) {
		    break loop;
		}
		++mInputPtr;
		resultPtr = handleTelnetCodes(result, resultPtr);
		break;

	    /* If we get \r, it may be either part of NVT linefeed (\r\n),
	     * stand-alone \r (\r\0). Other combinations are illegal,
	     * although it may make sense to just let them through.
	     */
	    case BYTE_CR:
		/* Hmmh. If we don't have the next byte, but do have output
		 * ready, let's return it instead of possibly stalling:
		 */
		if (++mInputPtr >= mInputSize && resultPtr > 0) {
		    --mInputPtr;
		    break loop;
		}
		b = getNextRawByte();
		switch (b) {
		case BYTE_NULL:
		    b = BYTE_CR;
		    break;
		case BYTE_LF:
		    break;
		default:
		    // Not legal, but not lethal either...
		    if (debugConnection) {
			master.doWarning("Warning: Unknown \\r - sequence; \\r was followed by a character with ascii-code of "+((int) b & 0xFF)+".");
		    }
		    --mInputPtr; // Let's add \r to result, and push this byte back
		    b = BYTE_CR;
		    break;
		}
		result[resultPtr++] = b;
		break;
		
	    /* NULLs are actually legal things to send, but since terminal
	     * has no use for them, let's strip them out. (can be changed
	     * if they are ever needed) 
	     */
	    case BYTE_NULL:
		break;
	    default:
		++mInputPtr;
		result[resultPtr++] = b;
	    }
	} while (resultPtr < len);
	
	return resultPtr;
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
	
	if (!sendNAWS || mOutput == null) {
	    if (debugConnection) {
		if (mOutput == null)
		    jiveterm.doWarningLF("Debug: Can't send NAWS, connection not open.");
		else 
		    jiveterm.doWarningLF("Debug: Can't send NAWS, window size = "+x+" x "+y+ ", server hasn't given permission.");
	    }
	    return;
	}
	
	if (debugConnection) {
	    jiveterm.doWarningLF("Debug: Sending NAWS, window size = "+x+" x "+y);
	}
	
	try {
	    DataOutputStream dout = new DataOutputStream(mOutput);
	    dout.write(Terminal.TELNET_IAC);
	    dout.write(Terminal.TELNET_SUBOPTION);
	    dout.write(Terminal.TN_OPTION_NAWS);
	    dout.writeShort((short) x);
	    dout.writeShort((short) y);
	    dout.write(Terminal.TELNET_IAC);
	    dout.write(Terminal.TELNET_SUBOPTION_END);
	} catch (IOException e) {
	    jiveterm.doWarningLF("Error: got exception when sending NAWS: "+e);
	    return;
	}
    }

/**** Then the functions for sending data to the server: ******/
    public final synchronized boolean
    sendByte(byte x, boolean flush)
    {
	if (mOutput == null)
	    return false;
	try {
	    mOutput.write(x);
	    if (flush)
		mOutput.flush();
	} catch (IOException e) {
	    return false;
	}
	return true;
    }

    public final synchronized boolean
    sendBytes(byte [] x, int offset, int length, boolean flush)
    {
	if (mOutput == null) {
	    return false;
	}
	try {
	    mOutput.write(x, offset, length);
	    if (flush) {
		mOutput.flush();
	    }
	} catch (IOException e) {
	    return false;
	}
	return true;
  }

    /**
     * This method will handle control code, if it turns out to be one.
     */
    private int handleTelnetCodes(byte[] result, int resultPtr)
    {
	/* Catch is only needed so we can handle end-of-connections 'inside'
	 * telnet control codes. This should be fairly unusual, but is
	 * possible.
	 */
	try {
	    byte b = getNextRawByte();
	    boolean send_naws = false;

	    // Is it just a quoted IAC-code? Let's just return then:
	    if (b == TELNET_IAC) {
		result[resultPtr++] = b;
		return resultPtr;
	    }

	    /* First, are we dealing with suboptions? */
	    if (b == TELNET_SUBOPTION) {
		return handleSuboption(result, resultPtr);
	    }
    
	    /* If not, it's a "major" option: */
	    c = getNextRawByte();

	    if (debugTelnet) {
		master.doWarningLF("DEBUG: Received IAC "+getOptionName(b)
				   +" "+getSuboptionName(c));
	    }
    
	    /* If they are asking permission to do echoing, let them: */
	    switch (c) {
	    case TN_OPTION_ECHO:
		switch (b) {
		case TELNET_WILL:
		    IACReply[1] = TELNET_DO;
		    master.setEcho(0, false);
		    break;
		case TELNET_WONT:
		    IACReply[1] = TELNET_DONT;
		    master.setEcho(1, false);
		    break;
		case TELNET_DO:
		    IACReply[1] = TELNET_WILL;
		    master.setEcho(1, false);
		    break;
		case TELNET_DONT:
		    IACReply[1] = TELNET_WONT;
		    master.setEcho(0, false);
		    break;
		}
		break;
		
	    case TN_OPTION_SUPPRESS_GA:
		
		/* They are telling they either will or won't suppress GA.
		 * However, we'll just consider them always doing so. *grin*
		 */
		if (b == TELNET_WILL || b == TELNET_WONT)
		    return;
		IACReply[1] = TELNET_WILL;
		break;
      
	    case TN_OPTION_TERM_TYPE:
		
		if (b == TELNET_DO) {
		    IACReply[1] = TELNET_WILL;
		    //master.doWarning("DEBUG: Ok to send term type.");
		}
		break;
      
	    case TN_OPTION_NAWS: /* Comment out if problematic: */
		
		if (b == TELNET_DO) {
		    connection.setSendNAWS(true);
		    IACReply[1] = TELNET_WILL;
		    /* Now we have to send the size update? */
		    send_naws = true; // Can't send it right away, before replying.
		} else if (b == TELNET_DONT) {
		    connection.setSendNAWS(true);
		    IACReply[1] = TELNET_WONT;
		}
		break;

	    default:
      
		/* We'll simply say "no go" for every other proposal. Ain't we nasty. */
		if (b == TELNET_WILL || b == TELNET_WONT) {
		    IACReply[1] = TELNET_DONT;
		} else {
		    IACReply[1] = TELNET_WONT;
		}
		break;
	    }
    
	    if (debugTelnet) {
		master.doWarningLF("DEBUG: Sending reply "
				   +getOptionName(IACReply[1]));
    }
    IACReply[2] = c;
    sendBytes(IACReply, true);
    
    if (send_naws) {
      connection.sendNAWS(-1, -1, true);
    }
	} catch (IOException ie) {
	    // Ok, connection actually closed. Not a big deal.
	}

	return resultPtr;
  }

    private int handleSuboption(byte[] result, int resultPtr)
	throws IOException
    {
	b = getNextRawByte();
	
	if (debugTelnet) {
	    master.doWarningLF("DEBUG: Received IAC "
			       +getOptionName(TELNET_SUBOPTION)
			       + " "
			       +getSuboptionName(b));
	}
	switch (b) {
	    
	case TN_OPTION_TERM_TYPE:
	    
	    b = getNextRawByte();
	    if (b == (byte) 1) {
		if (getNextRawByte() != TELNET_IAC) {
		    master.doWarningLF("ERROR: TELNET_IAC expected after sub-option term type.");
		}
		if (getNextRawByte() != TELNET_SUBOPTION_END) {
		    master.doWarningLF("ERROR: TELNET_IAC+TELNET_SUBOPTION_END expected after sub-option term type.");
		}
		
		if (debugTelnet) {
		    master.doWarningLF("DEBUG: Sending term-type reply '"+
				       new String(IACTermTypeReply)+"'");
		}
		sendBytes(IACTermTypeReply, false);
	    }
	    break;
	}
	return;
    }

    /* ** Debug-support: * **/

    /**
     * This method is only used when debugging, and thus we want to use
     * lazy initialization for the hash table being used to store option
     * names.
     *
     * @param i bytecode of the telnet option
     * 
     * @return Name of the telnet option, if recognized, null otherwise.
     */
    private String getOptionName(int i)
    {
	if (telnetOptions.size() < 1) {
	    telnetOptions.put(new Integer(TELNET_EOF), "END-OF-FILE");
	    telnetOptions.put(new Integer(TELNET_SUSP), "SUSPEND-CURR-PROC");
	    telnetOptions.put(new Integer(TELNET_ABORT), "ABORT-PROCESS");
	    telnetOptions.put(new Integer(TELNET_EOR), "END-OF-RECORD");
	    telnetOptions.put(new Integer(TELNET_SUBOPTION_END), "SUBOPTION-END");
	    telnetOptions.put(new Integer(TELNET_NOP), "NOP");
	    telnetOptions.put(new Integer(TELNET_DM), "DATA-MARK");
	    telnetOptions.put(new Integer(TELNET_BRK), "BREAK");
	    telnetOptions.put(new Integer(TELNET_IP), "INTERRUPT-PROC");
	    telnetOptions.put(new Integer(TELNET_AO), "ABORT-OUTPUT");
	    telnetOptions.put(new Integer(TELNET_AYT), "U-THERE?");
	    telnetOptions.put(new Integer(TELNET_EC), "ESC-CHAR");
	    telnetOptions.put(new Integer(TELNET_EL), "ERASE-LINE");
	    telnetOptions.put(new Integer(TELNET_GA), "GO-AHEAD");
	    telnetOptions.put(new Integer(TELNET_SUBOPTION), "SUBOPTION-BEGIN");
	    telnetOptions.put(new Integer(TELNET_WILL), "WILL");
	    telnetOptions.put(new Integer(TELNET_WONT), "WONT");
	    telnetOptions.put(new Integer(TELNET_DO), "DO");
	    telnetOptions.put(new Integer(TELNET_DONT), "DONT");
	    telnetOptions.put(new Integer(TELNET_IAC), "IAC");
	}
	return (String) telnetOptions.get(new Integer(i));
    }

    /**
     * This method is only used when debugging, and thus we want to use
     * lazy initialization for the hash table being used to store
     * sub-option names.
     *
     * @param i bytecode of the telnet option
     * 
     * @return Name of the telnet suboption, if recognized, null otherwise.
     */
    private String getSuboptionName(int i)
    {
	if (telnetSuboptions.size() < 1) {
	    telnetSuboptions.put(new Integer(TN_OPTION_BINARY_TRANSMISSION),
			     "BIN-XMIT");
	    telnetSuboptions.put(new Integer(TN_OPTION_ECHO), "ECHO");
	    telnetSuboptions.put(new Integer(TN_OPTION_RECONNECTION), "RECONNECT");
	    telnetSuboptions.put(new Integer(TN_OPTION_SUPPRESS_GA), "SUPPRESS-GA");
	    telnetSuboptions.put(new Integer(TN_OPTION_APPROX_MSG_SIZE), "APPROX-MSG-SIZE");
	    telnetSuboptions.put(new Integer(TN_OPTION_STATUS), "STATUS");
	    telnetSuboptions.put(new Integer(TN_OPTION_TIMING_MARK), "TIMING-MARK");
	    telnetSuboptions.put(new Integer(TN_OPTION_REMOTE_CTRL_TRANS_AND_ECHO),
				 "REM-CTRL-TNE");
	    telnetSuboptions.put(new Integer(TN_OPTION_OUTPUT_LINE_WIDTH),
				 "OUTP-LINE-WIDTH");
	    telnetSuboptions.put(new Integer(TN_OPTION_OUTPUT_PAGE_SIZE),
				 "OUTP-PAGE-SIZE");
	    telnetSuboptions.put(new Integer(TN_OPTION_OUTPUT_CR_DISP),
				 "OUTP-CR-DISP");
	    telnetSuboptions.put(new Integer(TN_OPTION_OUTPUT_HOR_TAB_STOPS),
				 "OUTP-HOR-TAB-STOPS");
	    telnetSuboptions.put(new Integer(TN_OPTION_OUTPUT_HOR_TAB_DISP),
				 "OUTP-HOR-TAB-DISP");
	    telnetSuboptions.put(new Integer(TN_OPTION_OUTPUT_FF_DISP),
				 "OUTP-FF-DISP");
	    telnetSuboptions.put(new Integer(TN_OPTION_OUTPUT_VERT_TAB_STOPS),
				 "OUTP-VERT-TAB-STOPS");
	    telnetSuboptions.put(new Integer(TN_OPTION_OUTPUT_VERT_TAB_DISP),
				 "OUTP-VERT-TAB_DISP");
	    telnetSuboptions.put(new Integer(TN_OPTION_OUTPUT_LF_DISP),
				 "OUTP-LF_DISP");
	    telnetSuboptions.put(new Integer(TN_OPTION_EXTENDED_ASCII), "XTENDED-ASCII");
	    telnetSuboptions.put(new Integer(TN_OPTION_LOGOUT), "LOGOUT");
	    telnetSuboptions.put(new Integer(TN_OPTION_BYTE_MACRO), "BYTE-MACRO");
	    telnetSuboptions.put(new Integer(TN_OPTION_DE_TERMINAL), "DATA-ENTRY-TERM");
	    telnetSuboptions.put(new Integer(TN_OPTION_SUPDUP), "SUPDUP");
	    telnetSuboptions.put(new Integer(TN_OPTION_SUPDUP_OUT), "SUPDUP");
	    telnetSuboptions.put(new Integer(TN_OPTION_SEND_LOCATION), "SUPDUP-LOC");
	    telnetSuboptions.put(new Integer(TN_OPTION_TERM_TYPE), "TERM-TYPE");
	    telnetSuboptions.put(new Integer(TN_OPTION_EOR), "END-OF-RECORD");
	    telnetSuboptions.put(new Integer(TN_OPTION_TACACS_UI), "TACACS-UI");
	    telnetSuboptions.put(new Integer(TN_OPTION_OUTPUT_MARKING), "OUTP-MARKING");
	    telnetSuboptions.put(new Integer(TN_OPTION_TERMINAL_LOC_NR), "TERM-LOC-NR");
	    telnetSuboptions.put(new Integer(TN_OPTION_3270_REGIME), "3270-REGIME");
	    telnetSuboptions.put(new Integer(TN_OPTION_X3_PAD), "X3-PAD");
	    telnetSuboptions.put(new Integer(TN_OPTION_NAWS), "NAWS");
	    telnetSuboptions.put(new Integer(TN_OPTION_TERM_SPEED), "TERM-SPEED");
	    telnetSuboptions.put(new Integer(TN_OPTION_REMOTE_FLOW_CTRL), "REM-FLOW-CTRL");
	    telnetSuboptions.put(new Integer(TN_OPTION_LINE_MODE), "LINEMODE");
	    telnetSuboptions.put(new Integer(TN_OPTION_X_DISP_LOC), "X-DISP-LOC");
	    telnetSuboptions.put(new Integer(TN_OPTION_ENV), "ENV-VARS");
	    telnetSuboptions.put(new Integer(TN_OPTION_AUTH), "AUTH-OPT");
	    telnetSuboptions.put(new Integer(TN_OPTION_ENCR), "ENCR-OPT");
	    telnetSuboptions.put(new Integer(TN_OPTION_NEW_ENV), "NEW-ENV");
	}
	return (String) telnetSuboptions.get(new Integer(i));
    }
}
