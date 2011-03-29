/**************************************
				       
Project:
    JiveTerm - A VT52/VT100/VT102-compliant
    telnet/terminal program
    written in pure java.

    (C) 1998-2001 Tatu Saloranta
    tatu.saloranta@iki.fi.

Module:
    JiveConnection.java

Description:
    Abstract base class that defines interface to various
    connections (currently ssh and telnet - connections).

Last changed:
  23-Sep-2001, TSa

Changes:

**************************************/

package com.cowtowncoder.jiveterm;

import java.net.*;
import java.io.*;
import java.awt.Dimension;

public abstract class JiveConnection
    implements Runnable
{
    public final static int IN_BUFFER_SIZE = 4096; // Could be doubled
    public final static int OUT_BUFFER_SIZE = 4096;

    // Buffering; input buffer.
    private final byte[] mInputBuffer = new byte[IN_BUFFER_SIZE];
    private int mInputPtr, mInputSize;

    // Buffering; output buffer:
    private final Object mOutputLock = new Object();
    private byte[] mOutputBuffer = new byte[OUT_BUFFER_SIZE];
    private byte[] mOutputBuffer2 = new byte[OUT_BUFFER_SIZE];
    private int mOutputPtr, mOutputSize;

    // Debug-related:
    public final static boolean DEBUG_CONNECTION = false;

    /* Stuff required by slow-down modes (used to simulate VT-terms that
     * have fixed rate connection to main-frames, 9600 bps etc). Not very
     * useful, but sometimes interesting to see... 
     */
    private int mBpsLimit; /* If > 0, BPS to limit our input to. Used to
			   * simulate actual serial line connected (VT)
			   * terminals (mostly for fun, VT-animations etc)
			   */
    private double mBpsLimitTime = 0.0;
    private final static long mBpsMinWait = 5; // Smallest delay we are to wait,
    private final static long mBpsWaitOverhead = 2; // Just a guess, that's all
    // (in milliseconds)
    private double mBpsWaitPerChar = 0.0;// Time (in msecs) each char takes
    private int mBpsMaxCharsPerRead = 20; // Max chars in each loop to read

    // Base class construction:
    public JiveConnection()
    {
	mInputSize = mOutputSize = 0;
	mInputPtr = mOutputPtr = 0;
    }

/***** Simple set-/get-functions: *****/

    // Only really used in telnet-mode...
    public void setSendNAWS(boolean x);

/***** Connection initialization/close: ********/

    public boolean connect() throws Exception;
    /**
     * This method is called after opening the physical connection,
     * to initialize the logical connection (if necessary).
     */
    public void initializeConnection();
    public boolean disconnect() throws IOException;

    /* When this is called, someone else has already closed the
     * connection, so we need not do much, mostly just mark connection
     * as closed. If the JiveTerm instance itself is calling
     * us, we need not even inform it about disconnect...
     */
    public void informDisconnect(boolean inform_master);

    public synchronized void sendNAWS(int x, int y, boolean force);

/**** Then the functions for reading data from the server: ******/

    /**
     * Method called by terminal to get one or more actual 'content' bytes.
     * This means that the possible connection-level (telnet, ssh)
     * control codes have been handled, and necessary (or at least
     * useful) character manipulations have been done (on telnet,
     * changing line-feeds to be single character, not NVT-linefeed)
     *
     * @param result Byte array in which the read in content data is
     *   returned
     *
     * @return Number of bytes read in; always at least 1, except
     *   if the connection has been closed (in which case -1 is
     *   returned)
     */
    public int getBytes(byte[] result);

    protected boolean getMoreInput()
    {
	while (true) {
	    try {
		mInputSize = readBytes(mInputBuffer);
	    } catch (InterruptedIOException ie) {
		// Can we be interrupted? If so, let's loop:
		/* Hmmh. Here we _SHOULD_ check if whoever interrupted us
		 * really wants attention...
		 */
		continue;
	    } catch (IOException e) {
		/* We probably just get java.net.SocketException: 'Socket
		 * closed' exceptions here?
		 */
		mInputSize = 0;
	    }
	    mInputPtr = 0;

	    // Connection closed?
	    return (mInputSize > 0);
	}
    }

    /**
     * Method used to handle slow-down due to bps constraints (if
     * we are restricting bps). The limit is only applied to downstream
     * traffic, assuming that's where most data goes.
     */
    protected void maintainBpsLimit()
    {
	double now = (double) System.currentTimeMillis();
	
	mBpsLimitTime += mBpsWaitPerChar;
	if (now > mBpsLimitTime)
	    mBpsLimitTime = now + mBpsWaitPerChar;
	
	int delay = (int) (mBpsLimitTime - now - mBpsWaitOverhead);
	
	if (delay >= mBpsMinWait) {
	    try {
		Thread.sleep(delay);
	    } catch (InterruptedException ie) { }
	}
    }

    /* This function simply returns the next available byte without
     * processing it in any way. Used when reading Telnet-codes.
     * For this reason, echoed chars are _NOT_ multiplexed in here;
     * they might mess up the codes otherwise.
     */
    private byte getNextRawByte()
	throws IOException
    {
	if (mInputPtr >= mInputSize) {
	    if (!getMoreBytes()) {
		throw new IOException("END-OF-CONNECTION");
	    }
	}

	/* Bps-delay checks: */
	if (mBpsLimit > 0) {
	    maintainBpsLimit();
	}
      
	return mInputBuffer[mInputPtr++];
    }

    /**
     * This is the method that actually reads in byte(s) from
     * the open connection.
     */
    private int readBytes(byte[] buffer) throws IOException;

/**** Then the functions for sending data to the server: ******/

    public synchronized boolean sendByte(byte x, boolean flush);
    public synchronized boolean sendBytes(byte [] x, boolean flush)
    {
	return sendBytes(x, 0, x.length, flush);
    }
    public final boolean sendBytes(byte [] x, int offset, int length,
				   boolean flush);

    // Debugging:
    private void doError(String s)
    {
	System.err.println(s);
    }
}
