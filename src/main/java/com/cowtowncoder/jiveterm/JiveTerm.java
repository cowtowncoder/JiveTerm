/**************************************
				       
Project:
    JiveTerm - A VT52/VT100/VT102-compliant
    telnet/terminal program
    written in pure java.

    (C) 1998-2000 Tatu Saloranta (aka Doomdark),
    doomdark@iki.fi.

Module:
    JiveTerm.java

Description:
    The main application class that encapsulates
    high level activities, and coordinates
    data transfer between network connection
    and Display/Terminal classes.

Last changed:
  28-Aug-2000, TSa

Changes:

  06-Feb-99, TSa:
    Now supports CR+LR / LF settings via VT-codes.
  24-Apr-99, TSa:
    Created Connection-class, JiveTerm modified too.
  08-May-99, TSa:
    Now adds iconified/deiconified - listeners; Win95
    focus-handling breaks otherwise. The idea is gotten
    from MindTerm (thanks Mats) :-)

**************************************/

package com.cowtowncoder.jiveterm;

import java.util.*;
import java.io.*;
import java.net.*;
import java.awt.event.*;
import java.awt.*;
import java.applet.Applet;
import java.lang.reflect.*;

import jiveterm.*;

/**** Small helper classes first: *****/

final class
InputTextField
extends TextField
{
  protected String [] buffer;
  protected int bufferSize;
  protected int currBufferLine = 0, currLine = 0;
  protected boolean onlyNumbers = false;
  protected int maxLen = -1;
  
  protected byte [] passwdBuffer = new byte[40];
  
  InputTextField(int width_in_chars, int buffer_size, Font f)
  {
    super(width_in_chars);

    if ((bufferSize = buffer_size) > 0) {
      buffer = new String[bufferSize = buffer_size];
    }
    if (f != null) {
      setFont(f);
    }
    
    /* Some local anonymous classes are used as handlers here: */
    
    addKeyListener(new KeyAdapter() {
      
      public void keyPressed(KeyEvent e) {
	int key = e.getKeyCode();
	
	if (e.isControlDown()) {
	  catchCtrl(e);
	} else if (key == KeyEvent.VK_ENTER) {
	  /* No. We better not catch this here. Let's wait for the
	   * action-event.
	   */
	} else if (key == KeyEvent.VK_UP) {
	  if (currLine > 0) {
	    setText(buffer[currLine -= 1]);
	  }
	  e.consume();
	} else if (key == KeyEvent.VK_DOWN) {
	  if (currLine < currBufferLine)
	    setText(buffer[currLine += 1]);
	  e.consume();
	} else if (key == KeyEvent.VK_TAB) {
	  catchTab(e);
	} else if (onlyNumbers && !e.isActionKey() && (key != KeyEvent.VK_DELETE
	  ) && (key != KeyEvent.VK_BACK_SPACE) &&
		   !(key >= KeyEvent.VK_0 && key <= KeyEvent.VK_9)) {
	  e.consume();
	}
      }
      
    });
    
    addActionListener(new ActionListener() {
      
      public void
	actionPerformed(ActionEvent e)
	{
	  String str = getText();
	  if (bufferSize > 0 && str.length() > 0) {
	    buffer[currBufferLine] = str;
	    currBufferLine += 1;
	    /* Don't have to scroll the history? */
	    if (currBufferLine < bufferSize) ;
	    else { /* Hmmh. Have to. */
	      currBufferLine -= 1;
	      System.arraycopy(buffer, 1, buffer, 0, bufferSize - 1);
	      buffer[currBufferLine] = null;
	    }
	    currLine = currBufferLine;
	    setText(null);
	  }
	  catchEnter(e, str);
	}
      
      
    });
  }
  
  public void
  setNumeric(boolean x)
  {
    onlyNumbers = x;
  }

  public void
  setMaxLen(int x)
  {
    maxLen = x;
  }

  /* These are to be overridden by extending classes: */
  public void
  catchTab(KeyEvent e)
  {
  }
 
 public void
  catchEnter(ActionEvent e, String str)
  {
  }

  public void
  catchCtrl(KeyEvent e)
  {
  // There are certain default action(s):
    if (e.getKeyCode() == KeyEvent.VK_K) {
      /* Ctrl+K clears the row */
      setText(null);
      e.consume();
    }
  }
}


/**************************************

Helper class for making speed-selection
menu...

**************************************/

class SpeedMenu
extends Menu
{
  private final static int [] speeds = {
    0, 38400, 28800, 14400, 9600, 2400, 1200, 300
  };

  protected CheckboxMenuItem [] menus;
  JiveTerm master;

  public SpeedMenu(JiveTerm m)
  {
    super("Terminal speed");
    master = m;

    menus = new CheckboxMenuItem[speeds.length];
    for (int i = 0; i < speeds.length; i++) {
      CheckboxMenuItem foop;
      if (speeds[i] == 0) {
	foop = new CheckboxMenuItem("Full speed ahead!",true);
      } else {
	foop = new CheckboxMenuItem("" + speeds[i]+" bps", false);
      }
      menus[i] = foop;
      foop.addItemListener(new ItemListener() {
	public void itemStateChanged(ItemEvent e) {
	  if (e.getStateChange() == ItemEvent.SELECTED) {
	    Object x = e.getItemSelectable();
	    for (int r = 0; r < menus.length; r++) {
	      if (x == menus[r]) {
		master.setSpeed(speeds[r]);
	      } else {
		menus[r].setState(false);
	      }
	    }
	  }
	}
      });
      add(foop);
    }
  }
}

/**************************************

And this is the client class, that does
supervising things...
But first a handler class to take care
of the menus.

***************************************/

final public class
JiveTerm
extends Applet
implements LayoutManager
{
  Panel sitePanel = null;
	/* Panel that contains connect-button etc */
  public Display display = null;
	/* Display window that prints the stuff from the server */
  Scrollbar scrBar = null;

  /* And components they contain: */

  // sitePanel:
  InputTextField siteText, portText;
  Button connectButton;

  /* Active upper-level entities we'll have: */
  Thread displayThread = null;
	/* Thread that updates the display. */
  public Terminal terminal = null;
	/* Terminal object that communicates with the server */

  //private boolean SSHMode = true; // SSH or telnet?
  private boolean SSHMode = false; // SSH or telnet?
  private JiveConnection connection = null;
  //private Socket connection = null; /* TCP-connection to/from server (telnet). */

  /***** Menus we'll have (as an application / stand-alone applet). ****/
  protected Menu fileMenu, optionsMenu, emulationMenu, displayMenu, debugMenu;
  protected MenuItem fileMenuQuit, fileMenuOpen, fileMenuClose;
  protected Menu emulationMenuVT;
  protected CheckboxMenuItem emulationMenuVT52, emulationMenuVT100;
  protected CheckboxMenuItem emulationMenuAllowVTResize,
    emulationMenuAllow8BitCodes;
  protected SpeedMenu emulationMenuSpeed;

  protected CheckboxMenuItem optionsMenuWrap, optionsMenuEcho, optionsMenuDesc;
  protected CheckboxMenuItem optionsMenuEndOnInput, optionsMenuEndOnOutput;

  protected CheckboxMenuItem displayMenuBell;
  protected MenuItem displayMenuRedraw, displayMenuReset;

  protected MenuItem debugMenuDumpChars, debugMenuDumpAttrs;

  // Default states for certain menus:
  public final static boolean DEF_XXX = false;

  /***** Parameters & other stuff related to initializing apps/applets: ****/
  public Hashtable params = null;

  protected Frame mainFrame = null;
  protected boolean isApplet = true;
  protected boolean doEcho = false; // By default no echo for 'real' telnet,
    // and echo for other ports
  protected boolean linemode = false; // Likewise, by default off
  protected boolean scrollOnInput = false;

  protected boolean allowVTResize = true;
  protected boolean allow8BitCodes = true;

  protected long origTime = System.currentTimeMillis();

  /************ Certain constants: ****************/

  public final static String DEF_TITLE = "JiveTerm (application) V1.0a";
  public final static Dimension DEF_SIZE = new Dimension(640, 480);
  public final static Color defPanelBackground = Color.lightGray;

  public final static int JIVESSH_PRIORITY = Thread.MIN_PRIORITY;
  public final static int FONTLOADER_PRIORITY = Thread.MIN_PRIORITY;

  /***** Mode-flags: *****/
  private boolean modeNewline = false; // Will be reset by Display/Terminal

  /* And what should constructor do, after all... */
  // Perhaps add the listeners?
  public
  JiveTerm()
    {
      super();
    }

  public void
    init()
    {
    String str;

      startJiveTerm("SansSerif", 12);

      /* Some parameters may be set now... */
	if ((str = getParameter("site")) != null) {
	  siteText.setText(str);
	}
	if ((str = getParameter("port")) != null) {
	  portText.setText(str);
	}

      // Also, no need to let user enter text before getting connected...
	//display.requestFocus();
	updateFocus();
	updateTitle();
    }
  
  public String
    getParameter(String key)
    {
      if (params == null)
	return super.getParameter(key);
      return (String) params.get(key);
    }

    public void
    start()
    {

      /* Applications need a frame to draw in, unlike applets. */
      if (!isApplet) {
	mainFrame.addWindowListener(new WindowAdapter() {
	  public void
	  windowClosing(WindowEvent e)
	  {
	    System.exit(0);
	  }
	  /* New, 08-May-1999, TSa: */
	  public void windowDeiconified(WindowEvent e)  { updateFocus(); }
	});
	mainFrame.add("Center", this);
	mainFrame.setVisible(true);
	
	mainFrame.repaint();
	
	/* But how can an applet use menus then??? */
	// They can't.
      } else {
	validate();
      }
      /* Too bad we are only now really ready to do displaying... */
      //initLayout();
      //doLayout();

      /* We also need to kick SSH-system to make it init things... */
      if (SSHMode) {
	  /* Let's use dynamic dispatching here, so there's no need to
	   * have the SSH classes around in non-ssh mode:
	   */
	  try {
	      Class c = Class.forName("jiveterm.JiveSSHClient");
	      Method m = c.getMethod("initSSH()", new Class[0]);
	      m.invoke(null, new Object[0]);
	      //JiveSSHClient.initSSH();
	  } catch (Exception e) {
	      System.err.println("Warning: couldn't initialize JiveSSHClient: "+e);
	  }
      }
    }
  
  public void
    stop()	
    {
/* Do nothing. We are not that desperate. .*giggle* */
/* Note, though, that this means applet won't react in any way to
 * resizing or used browsing to other page(s). Not that it should
 * really matter. When he/she quits netscape, destroy() will be
 * sent ok.
 */
    }

  public void
  destroy()
  {
    if (connection != null) {
      doDisconnect(true);
    }
  }
  
/* And these don't seem to get called, in any case: */
    public void
    setSize(int width, int height)
    {
System.err.print("DEBUG: setSize(x,y) called, x="+width+", y="+height+".\n");
	super.setSize(width, height);
    }

    public void
    setSize(Dimension d)
    {
System.err.print("DEBUG: setSize(dim) called, x="+d.width+", y="+d.height+".\n");
	super.setSize(d);
    }

  public String [] []
  getParameterInfo()
  {
    String [] [] p = {
      { "site", "string", "site to connect to" },
      { "port", "0-65535", "port number to connect to" },
    };
    return p;
  }
  
  /* In case we're running as an application, we need to: */
  public static void
  main(String args[])
  {
    // Hrmh. This is not a perfect place to call it but:
    PlatformSpecific.initialize();

    JiveTerm app = new JiveTerm();
    Frame f = new Frame("");
    //f.setSize(DEF_SIZE);
    
    app.params = new Hashtable();
    
    switch (args.length) {
    case 2:
      app.params.put("port", args[1]);
    case 1:
      app.params.put("site", args[0]);
    case 0:
      break;
    default:
      app.doError("Usage: [java] JiveTerm [host] [port].\n");
    }
    
    app.isApplet = false;
    app.mainFrame = f;
    
    /* We better add the menus too: */
    app.addMenus(f);
    app.init();
    app.start();
    
    // And this should take care of layouting the stuff...
    f.pack();
  }
  
  public boolean
  doConnect(String dest, int port)
  {
    InetAddress addr;
    Cursor old_cursor = null;
    Component cursor_comp;

    if (connection != null) {
      doWarning("Trying to connect when already connected!");
      return false;
    }

    /* A quick hack; let's default to localhost: */
    if (dest == null || dest.length() == 0) {
      dest = "localhost";
      siteText.setText(dest);
    }

    // The default echo-behaviour: for 'real' telnet, echo is
    // off by default, otherwise it's on. In addition, the standard
    // procedure is to default to linemode for these non-telnet
    // services.
    // SSH acts much like a standard telnet.
    
    if (SSHMode || port == Connection.TELNET_PORT) {
      setEcho(0, false);
      setLinemode(false);
    } else {
      setEcho(1, false);
      setLinemode(true);
    }

    /* At this point, we better change the shape of the cursor: */
    if (mainFrame != null) {
      cursor_comp = mainFrame;
    } else {
      cursor_comp = display;
    }
    old_cursor = cursor_comp.getCursor();
    cursor_comp.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    
    try {
      connection = new Connection(this, dest, port, SSHMode,
	       (!SSHMode && port == Connection.TELNET_PORT), !SSHMode);

      terminal = connection.connect();

    } catch (Error e) {

      e.printStackTrace();
      System.err.println("DEBUG: "+e);

      // But if something goes wrong, we need to clear things up
      // and indicate the reason for failure:

      connection = null;
      terminal = null;
      cursor_comp.setCursor(old_cursor);
     
      MessageBox x;

      if (e instanceof UnknownHostException) {
	x = new MessageBox(mainFrame,"No such host on the net!",
				      new String [] {
	  " ", "    Unknown host '"+dest+"'!    " , " "
	    },
	  SystemColor.windowText,
	  SystemColor.window,
	  SystemColor.windowBorder
	 );
      } else {
	x = new MessageBox(mainFrame,"Can't open the connection!",
				      new String [] {
	  " ", "    Can't open the connection  ",
	    "    to host '"+dest+"':    " ,
	    e.toString()
	    },
		   SystemColor.windowText,
		   SystemColor.window,
		   SystemColor.windowBorder
	    );
      }
      x.updateFocus();
      x.show();
      return false;
    }

    cursor_comp.setCursor(old_cursor);

    /* And then we need to connect terminal with server via TCP-socket. */
    
    /* Code common to both ssh- and telnet-connections: */
      
    connectButton.setLabel("Disconnect");
    siteText.setEditable(false);
    portText.setEditable(false);
    if (fileMenuOpen != null)
      fileMenuOpen.setEnabled(false);
    if (fileMenuClose != null)
      fileMenuClose.setEnabled(true);
    
    updateTitle();
    
    /* And we need to update the window too. */
    validate();
    doLayout(); // Is this necessary?
    
    /* Then we need an object to catch the BS server sends us, as well as
     * connect it to the display.
     */
    
    /* We also need to make sure output display gets updated regularly... */
    displayThread = new Thread(display);
    
    /* We need to make one pipe for communication between terminal and
     * display (same for both ssh and telnet):
     */
    try {
      PipedInputStream pi1 = new PipedInputStream();
      PipedOutputStream po1 = new PipedOutputStream(pi1);
      display.setInput(new BufferedInputStream(pi1));
      terminal.setPipe(new BufferedOutputStream(po1));
    } catch (IOException e) {
      doError("Can't open and/or connect 2 pipes needed in inter-thread communication!");
    }
       
    /* So, let's kick start the threads in question. */
    displayThread.start();
    terminal.start();
    
    /* Now we better make the text field get the focus, as well. */
    updateFocus();
    
    return true;
  }

  public boolean
  doDisconnect(boolean suppress_errors)
  {
    if (connection == null) {
      doWarning("Trying to disconnect while not connected.\n");
      return true;
    }

    Connection tmp_conn = connection;
    Terminal tmp_term = terminal;

    // Let's mark the connection as closed, as the first thing:
    terminal = null;
    connection = null;

    doWarning("Disconnecting.\n");
    try {
      tmp_conn.disconnect();

      // There shouldn't be many problems when closing it but...
    } catch (IOException e) {
      if (!suppress_errors) {
	MessageBox x = new MessageBox(mainFrame,
		  "Error at closing the connection!",
	 new String [] {
	  " ", "    Error when closing the connection:  ",
	  e.toString(), ""
	 },
	  SystemColor.windowText,
	  SystemColor.window,
	  SystemColor.windowBorder
	);
	x.updateFocus();
	x.show();
      }
      return false;
    }

    siteText.setEditable(true);
    portText.setEditable(true);
    connectButton.setLabel(" Connect ");
    if (fileMenuOpen != null)
      fileMenuOpen.setEnabled(true);
    if (fileMenuClose != null)
      fileMenuClose.setEnabled(false);
    
    updateTitle();
    
    validate();
    doLayout(); // necesary?
    
    /* And probably put the focus to the site-field */
    /* (ie. not use updateFocus(), which would probably locate it
     * in some other field...
     */
    siteText.requestFocus();
    
    /* Now... the order in which they are stopped may be important;
     * if we stop the thread that is now executing... *grin*
     */
    /* Shouldn't explicitly use stop(), as JDK1.2 doesn't have it? */
    if (Thread.currentThread() == tmp_term) {
      displayThread.stop();
      tmp_term.stop();
    } else {
      tmp_term.stop();
      displayThread.stop();
    }
    return true;
  }

  public boolean
  informDisconnect()
  {
    Terminal tmp_term = terminal;

    // Let's mark the connection closed:
    Connection c = connection;
    connection = null;
    terminal = null;

    siteText.setEditable(true);
    portText.setEditable(true);
    updateTitle();

    validate();
    doLayout(); // necessary?
    
    if (fileMenuOpen != null)
      fileMenuOpen.setEnabled(true);
    if (fileMenuClose != null)
      fileMenuClose.setEnabled(false);
    connectButton.setLabel(" Connect ");
    
    /* And probably put the focus to the site-field */
    siteText.requestFocus();

    c.informDisconnect(false);
    
    /* Now... the order in which they are stopped may be important;
     * if we stop the thread that is now executing... *grin*
     */
    if (Thread.currentThread() == terminal) {
      displayThread.stop();
      tmp_term.stop();
    } else {
      tmp_term.stop();
      displayThread.stop();
      }
    /* We'll never get past that one, as we just stopped our own
     * execution. Neat?
     */
    return true;
  }
  
  public void
    doQuit()
    {
      if (connection != null) {
	doDisconnect(true);
      }
      if (isApplet) {
	// ... and how does one actually stop an applet?
      } else System.exit(0);
    }

  private int col = 0;
  private final static int DEBUG_COLS = 80;

  public void
  doWarning(String s)
  {
    int orig = col;
    col += s.length();
    if (col > DEBUG_COLS && orig > (DEBUG_COLS / 2)) {
      col = 0;
      System.out.println(s);
    } else {
      System.out.print(s);
    }
    System.out.flush();
  }

  public void
  doWarningLF(String s)
  {
    col = 0;
    System.out.println(s);
    System.out.flush();
  }

  public void
  doError(String s)
  {
    doWarning(s);
    System.exit(1);
  }

  public void
    addMenus(Frame f)
    {
      MenuBar mb = new MenuBar();

      fileMenu = new Menu("File");
      optionsMenu = new Menu("Options");
      emulationMenu = new Menu("VT-Emulation");
      displayMenu = new Menu("Display");
      debugMenu = new Menu("Debug");

      fileMenuQuit = new MenuItem("Quit");
      fileMenuQuit.addActionListener(new ActionListener() {
	public void actionPerformed(ActionEvent e) {
		doQuit();
	}
      });
      // No short cuts, terminal may need to send 'em:
      //fileMenuQuit.setShortcut(new MenuShortcut(KeyEvent.VK_Q));

      fileMenuOpen = new MenuItem("Open connection");
      fileMenuOpen.addActionListener(new ActionListener() {
	public void actionPerformed(ActionEvent e) {
	  connectButton.dispatchEvent(new ActionEvent(connectButton,
		ActionEvent.ACTION_PERFORMED,"Menu, Open"));
	}
      });
      //fileMenuOpen.setShortcut(new MenuShortcut(KeyEvent.VK_O));

      fileMenuClose = new MenuItem("Close connection");
      fileMenuClose.addActionListener(new ActionListener() {
	public void actionPerformed(ActionEvent e) {	
	  connectButton.dispatchEvent(new ActionEvent(connectButton,
		ActionEvent.ACTION_PERFORMED,"Menu, Close"));
	}
      });
      //fileMenuClose.setShortcut(new MenuShortcut(KeyEvent.VK_C));

      displayMenuBell = new CheckboxMenuItem("Bell->beep");
      displayMenuBell.setState(false);
      displayMenuBell.addItemListener(new ItemListener() {
	public void itemStateChanged(ItemEvent e) {
	  display.toggleBell();
	}
      });

      displayMenuRedraw = new MenuItem("Redraw");
      displayMenuRedraw.addActionListener(new ActionListener() {
	public void actionPerformed(ActionEvent e) {
	  display.redraw();
	}
      });
      displayMenuReset = new MenuItem("Reset terminal");
      displayMenuReset.addActionListener(new ActionListener() {
	public void actionPerformed(ActionEvent e) {
	  if (terminal != null)
	    terminal.sendReset();
	}
      });

      debugMenuDumpChars = new MenuItem("Dump chars");
      debugMenuDumpChars.addActionListener(new ActionListener() {
	public void actionPerformed(ActionEvent e) {
	  display.dumpChars();
	}
      });
      debugMenuDumpAttrs = new MenuItem("Dump attrs");
      debugMenuDumpAttrs.addActionListener(new ActionListener() {
	public void actionPerformed(ActionEvent e) {
	  display.dumpAttrs();
	}
      });

      optionsMenuWrap = new CheckboxMenuItem("Line wrap");
	optionsMenuWrap.setState(false);
      optionsMenuWrap.addItemListener(new ItemListener() {
	public void itemStateChanged(ItemEvent e) {
	  setWrap(-1);
	}
      });
      //optionsMenuWrap.setShortcut(new MenuShortcut(KeyEvent.VK_W));

      optionsMenuEcho = new CheckboxMenuItem("Local echo");
      optionsMenuEcho.setState(true);
      optionsMenuEcho.addItemListener(new ItemListener() {
	public void itemStateChanged(ItemEvent e) {
	  setEcho(-1, true);
	}
      });

      optionsMenuEndOnInput = new CheckboxMenuItem("Scroll to end on key");
      optionsMenuEndOnInput.setState(false);
      scrollOnInput = false;
      optionsMenuEndOnInput.addItemListener(new ItemListener() {
	public void itemStateChanged(ItemEvent e) {
	  toggleScrollOnInput();
	}
      });
      optionsMenuEndOnOutput = new CheckboxMenuItem("Scroll to end on text");
      optionsMenuEndOnOutput.setState(false);
      optionsMenuEndOnOutput.addItemListener(new ItemListener() {
	public void itemStateChanged(ItemEvent e) {
	  display.toggleScrollOnOutput();
	}
      });
      //optionsMenuEcho.setShortcut(new MenuShortcut(KeyEvent.VK_E));

      emulationMenuVT = new Menu("VT-emulation");
      emulationMenuVT52 = new CheckboxMenuItem("VT52");
      emulationMenuVT100 = new CheckboxMenuItem("VT100");
      emulationMenuVT.add(emulationMenuVT52);
      emulationMenuVT.add(emulationMenuVT100);
      emulationMenuAllowVTResize = new CheckboxMenuItem("Allow VT to resize window");
      emulationMenuAllowVTResize.setState(true);
      allowVTResize = true;
      emulationMenuAllowVTResize.addItemListener(new ItemListener() {
	public void itemStateChanged(ItemEvent e) {
	  toggleAllowVTResize();
	}
      });
      emulationMenuAllow8BitCodes = new CheckboxMenuItem("Ok to send 8-bit codes");
      emulationMenuAllow8BitCodes.setState(true);
      allow8BitCodes = true;
      emulationMenuAllow8BitCodes.addItemListener(new ItemListener() {
	public void itemStateChanged(ItemEvent e) {
	  toggleAllow8BitCodes();
	}
      });
      emulationMenuSpeed = new SpeedMenu(this);

      fileMenu.add(fileMenuOpen);
      fileMenu.add(fileMenuClose);
      fileMenuClose.setEnabled(false);
      fileMenu.addSeparator();
      fileMenu.add(fileMenuQuit);

      debugMenu.add(debugMenuDumpChars);
      debugMenu.add(debugMenuDumpAttrs);

      optionsMenu.add(optionsMenuWrap);
      optionsMenu.add(optionsMenuEcho);
      optionsMenu.add(optionsMenuEndOnInput);
      optionsMenu.add(optionsMenuEndOnOutput);

      emulationMenu.add(emulationMenuVT);
      emulationMenu.add(emulationMenuSpeed);
      emulationMenu.addSeparator();
      emulationMenu.add(emulationMenuAllowVTResize);
      emulationMenu.add(emulationMenuAllow8BitCodes);

      displayMenu.add(displayMenuBell);
      displayMenu.addSeparator();
      displayMenu.add(displayMenuRedraw);
      displayMenu.add(displayMenuReset);

      mb.add(fileMenu);
      mb.add(optionsMenu);
      mb.add(emulationMenu);
      mb.add(displayMenu);
      mb.add(debugMenu);

      f.setMenuBar(mb);
    }

  /*********************************

  This function initializes the GUI:

  *********************************/
  
  public void
  startJiveTerm(String font_name, int font_size)
  {
  Font f;

    if (displayThread == null) {
      
/* We need to initialize the window... */
      setLayout(this);

      
      /* Upper part contains info about server we are connecting/ed. */
	f = new Font(font_name, Font.PLAIN, font_size);
	setFont(f);
	sitePanel = new Panel();
	sitePanel.setBackground(defPanelBackground);
	sitePanel.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2));
	siteText = new InputTextField(32, 0, f);
      
	portText = new InputTextField(5, 0, f);
	portText.setNumeric(true);
	portText.setMaxLen(6);
	Label l1 = new Label("Site:");
	Label l2 = new Label("Port:");

      /* Buttons' actions are also defined here, clean or not. */
      connectButton = new Button(" Connect ");
 
     connectButton.addKeyListener(new KeyAdapter() {

       //public void keyTyped(KeyEvent e) {}

	public void keyTyped(KeyEvent e) {

	  char c = e.getKeyChar();

          //if (e.getKeyCode() == KeyEvent.VK_ENTER) {
	  if (c == '\n' || c == '\r') {
	    
	    e.consume();

	   if (connection == null) {
	    int pn;
	    try { // If no port specified, we'll use std telnet port, 23.
	      pn = Integer.parseInt(portText.getText());
	    } catch (NumberFormatException ne) {
	      if (portText.getText().length() == 0) {
		if (SSHMode)
		  pn = Connection.SSH_PORT;
		else
		  pn = Connection.TELNET_PORT;
	      } else return;
	    }
	    doConnect(siteText.getText(), pn);
	   } else {
	    doDisconnect(false);
	   }

	  }
	}
      });

      connectButton.addActionListener(new ActionListener() {
	public void actionPerformed(ActionEvent e) {
	  if (connection == null) {
	    int pn;
	    try { // If no port specified, we'll use std telnet port, 23.
	      pn = Integer.parseInt(portText.getText());
	    } catch (NumberFormatException ne) {
	      if (portText.getText().length() == 0)
		pn = 23;
	      else
		return;
	    }
	    doConnect(siteText.getText(), pn);
	  } else {
	    doDisconnect(false);
	  }
	}
      });

      sitePanel.add(l1);
      sitePanel.add(siteText);
      sitePanel.add(l2);
      sitePanel.add(portText);
      sitePanel.add(connectButton);

/* The output window is in the middle... */
/* ... and scrollbar on its left side. */

      display = new Display(this, "Monospaced", 12);

	/* We want to catch key events over the display.... */
      display.addKeyListener(new KeyAdapter() {
	
	// Hmmh. We need to catch tab/ctrl+c earlier:
	public void keyPressed(KeyEvent e) {
	  int key = e.getKeyCode();

	  if (terminal == null)
	    return;

	  switch (key) {

	  case KeyEvent.VK_TAB:

	    //terminal.sendByte(Terminal.CHAR_TAB);
	    //System.err.println("DEBUG: Tab eaten!");
	    // For some reason we _should_ 'eat' it. Probably to
	    // prevent AWT from using it for moving the focus around...
	    // I hope this won't cause compatibility problems.
	    e.consume();
	    break;

	    // Terminal instance better send appropriate codes:
	  case KeyEvent.VK_UP:

	    // What should we do if we are to echo these ourselves?
	    if (!doEcho)
	      terminal.sendArrow(Terminal.CODE_CURSOR_UP);
	    break;

	  case KeyEvent.VK_DOWN:

	    if (!doEcho)
	      terminal.sendArrow(Terminal.CODE_CURSOR_DOWN);
	    break;

	  case KeyEvent.VK_LEFT:

	    if (!doEcho)
	      terminal.sendArrow(Terminal.CODE_CURSOR_LEFT);
	    break;

	  case KeyEvent.VK_RIGHT:

	    if (!doEcho)
	      terminal.sendArrow(Terminal.CODE_CURSOR_RIGHT);
	    break;

	  default:
	    return;

	  }
	  if (scrollOnInput)
	    display.setBufferMode(false, true);
	}

	public void keyTyped(KeyEvent e) {

	  int key = e.getKeyCode();
	  int key_c = e.getKeyChar();

	  if (terminal == null)
	    return;

/*
Display.ssh_key1 = System.currentTimeMillis();
*/

	  // Should we care about control codes?
	  //if (e.isControlDown()) { }

	  // Enter is bit different, though, as it should be transferred
	  // in the 2-byte NVT ascii sequence:

	  if (key_c == Terminal.CHAR_LF || key_c == Terminal.CHAR_CR) {
	    //System.err.println("Debug: Sending LF.");

	    e.consume();
	    if (doEcho)
	      terminal.echoBytes(Terminal.DISPLAY_LINEFEED);

	    if (SSHMode)
	      terminal.sendBytes(Terminal.LINEFEED_SSH, true);
	    else if (modeNewline)
	      terminal.sendBytes(Terminal.LINEFEED_CRLF, true);
	    else terminal.sendBytes(Terminal.LINEFEED_CR, true);
	    
	    // No terminal-side command buffer:
	    //} else if (key == KeyEvent.VK_UP) {
	    //} else if (key == KeyEvent.VK_DOWN) {

	  } else if (key_c == Terminal.CHAR_TAB) {

	    //System.err.println("DEBUG: Tab gotten!");
	    /* Probably TAB needs special handling, so as not to
	     * transfer focus to another component:
	     */
	    
	    //System.err.println("Sending TAB.");
	    e.consume();
	    if (doEcho)
	      terminal.echoByte(Terminal.BYTE_TAB);
	    terminal.sendByte(Terminal.BYTE_TAB, true);
	    
	  } else {

	    if (key_c > 0 && key_c < 256) {
/*
Display.ssh_sent = System.currentTimeMillis();
*/
	      e.consume();
	      if (doEcho) {
		byte b = (byte) key_c;

		if (b == Terminal.BYTE_BS || b == Terminal.BYTE_DEL) {
		  terminal.echoBytes(Terminal.DISPLAY_ERASE);
		} else if (key_c >= 32) {
		  terminal.echoByte(b);
		}
	      }
	      terminal.sendByte((byte) key_c, true);
/*
Display.ssh_sent2 = System.currentTimeMillis();
*/
	    } else {
	      System.err.println("Weird char 0x"+Integer.toHexString((int) key_c & 0xFFFF));
	    }

	  }

	  if (scrollOnInput)
	    display.setBufferMode(false, true);
	}
      });

      // Also, we better let user re-focus using mouse, when connected
      // (when not connected let's not react):
      display.addMouseListener(new MouseAdapter() {
	public void mouseClicked(MouseEvent e) {
	  if (terminal != null)
	    updateFocus();
	}
      });

      scrBar = new Scrollbar(Scrollbar.VERTICAL, 0,
			     display.sizeInCharsH, 0,
			     Display.BUFFER_LINES - display.sizeInCharsH);

      scrBar.addAdjustmentListener(new AdjustmentListener() {
	public void
	  adjustmentValueChanged(AdjustmentEvent e)
	  {
	    int i = e.getValue();
	    int max = scrBar.getMaximum() - scrBar.getVisibleAmount();

	    if (display.isBufferMode()) {
	      if (i == max) {
		display.setBufferMode(false, false);
	      }
	    } else {
	      if (i != max) {
		display.setBufferMode(true, false);
		display.topBufferRow = -1;
		// Let's mark the buffer invalid, to be sure.
	      }
	    }
	    if (display.isBufferMode()) {
	      display.paintBuffer(i, true, false);
	    }
	    display.repaint();
	  }
      });

	add(scrBar);
	add(display);

/* And rightmost part contains various specific output windows: */

	add(sitePanel);

	//validate();
	//doLayout();
    }
  }

  public long
  getTime()
  {
    return System.currentTimeMillis() - origTime;
  }

  /* Depending on what we are doing, let's redirect focus to the
   * field user _probably_ needs next:
   */
  public void
  updateFocus()
  {
    if (terminal != null) {
      display.requestFocus();
    } else if (siteText.getText().length() > 0) {
      if (portText.getText().length() > 0) {
	connectButton.requestFocus();
      } else {
	portText.requestFocus();
      }
    } else {
      siteText.requestFocus();
    }
  }

  public void
  updateTitle()
  {
    if (terminal != null) {
      String port = portText.getText();
      if (port.length() > 0)
	port = "port "+port;
      else port = "(telnet)";
      
      if (isApplet) {
	showStatus("Connected to "+siteText.getText()+", "+port);
      } else {
	mainFrame.setTitle("Connected to "+siteText.getText()+", "+port);
      }
      
      // FOO!
      //sitePanel.setVisible(false);
      sitePanel.setVisible(true);
    } else {
      if (isApplet) {
	showStatus(DEF_TITLE);
      } else {
	mainFrame.setTitle(DEF_TITLE);
      }
      if (sitePanel != null) {
	sitePanel.setVisible(true);
      }
      //doLayout();
    }
    repaint();
  }

/***************************************

And now we try to be a layout manager...

***************************************/

  int siteY = 10, displayY = 10;
  int scrollX = 10, displayX = 10;
  int displayMinX = 10, displayMinY = 10;
  boolean layoutReady = false;
  Dimension currSize = new Dimension(10, 10),
	minSize = new Dimension(10, 10);

  public void
  initLayout()
  {
  Dimension x;

    if (display != null) {
      x = display.getPreferredSize();
      displayX = x.width;
      displayY = x.height;
      x = display.getMinimumSize();
      displayMinX = displayX;
      displayMinY = displayY;
    } else return;
    if (scrBar != null) {
      x = scrBar.getPreferredSize();
      if (x.width < 1)
	return;
      scrollX = x.width;
    } else return;
    
    if (sitePanel != null) {
      x = sitePanel.getPreferredSize();
      if (x.height < 1)
	return;
      siteY = x.height;
    } else return;
    //System.err.print("DEBUG: init layout ok.\n");
    layoutReady = true;
    
    // Actually, let's _not_ add the size for sitePanel,
    // because it won't be visible after connection:
    currSize.width = scrollX + displayX;
    currSize.height = displayY;	/* + siteY */
    
    updateFocus();
  }

  public void
  addLayoutComponent(String foo, Component c)
  {
  }

  public void
  removeLayoutComponent(Component c)
  {
  }

  public Dimension
  minimumLayoutSize(Container x)
  {
	if (!layoutReady)
		initLayout();
	minSize.width = scrollX + displayMinX;
	minSize.height = displayMinY;
	return minSize;
  }

  public Dimension
  preferredLayoutSize(Container c)
  {
	if (!layoutReady)
	  initLayout();
	currSize.width = scrollX + displayX;
	currSize.height = displayY;
	return currSize;
  }

  public void
  layoutContainer(Container c)
  {
	if (!layoutReady) {
	  initLayout();
	  if (!layoutReady) {
//doWarning("DEBUG: Couldn't init layout.\n");
	    return;
	  }
	}

	Dimension d = getSize();

	//System.err.println("DEBUG: Frame-size = "+d.width+" x "+d.height);

	currSize.width = d.width;
	currSize.height = d.height;
	//displayX = d.width - scrollX;
	//displayY = d.height;
	int disp_x = d.width - scrollX;
	int disp_y = d.height;

	if (terminal == null) {
	  sitePanel.setBounds(0, 0, d.width, siteY);
	  disp_y -= siteY;
	  scrBar.setBounds(0, siteY, scrollX, disp_y);
	  display.setBounds(scrollX, siteY, disp_x, disp_y);
	} else {
	  sitePanel.setBounds(0, 0, d.width, 0);
	  scrBar.setBounds(0, 0, scrollX, disp_y);
	  display.setBounds(scrollX, 0, disp_x, disp_y);
	}

	updateFocus();
  }

/***************************************

And some other layout-related funcs:

***************************************/

  // A dummy function:
  public void
  doCompact()
  {
	repaint();
  }

  public void
  resizeToChars(int old_x, int old_y, int x, int y)
  {
    Dimension fsize = display.getCurrFontSize();
      
    // With applications, this should work ok:
    if (mainFrame != null) {

      Dimension curr = mainFrame.getSize();

      mainFrame.setSize(curr.width + (x - old_x) * fsize.width,
	      curr.height + (y - old_y) * fsize.height);
    // With applets, we probably won't be able to 'really'
    // resize things, but we can try to:
    } else {
      Dimension curr = getSize();

      setSize(curr.width + (x - old_x) * fsize.width,
	      curr.height + (y - old_y) * fsize.height);
    }

    Toolkit.getDefaultToolkit().sync();
  }

  // Called by Display to move scroll bar to the bottom most position:
  public void
  moveScrollBarToBottom()
  {
    scrBar.setValue(scrBar.getMaximum() - scrBar.getVisibleAmount());
  }

/***************************************

 Various functions setting/querying modes
 and such:

***************************************/

  public void setWrap(int x)
  {
    if (display == null) 
      return;
    switch (x) {
    case 0:
      display.setDisplayMode(Display.MODE_AUTO_WRAP, false);
      break;
    case -1:
	boolean state = !display.displayMode(Display.MODE_AUTO_WRAP);
      display.setDisplayMode(Display.MODE_AUTO_WRAP, state);
      break;
    default:
      display.setDisplayMode(Display.MODE_AUTO_WRAP, true);
    }
  }

  public void
  setEcho(int x, boolean from_menu)
  {
    switch (x) {
    case 0:
      doEcho = false;
      break;
    case -1:
      doEcho = !doEcho;
      break;
    default:
      doEcho = true;
    }
    if (!from_menu && optionsMenuEcho != null) {
	setEchoMenuState(doEcho);
    }
  }

  public void
  setLinemode(boolean state)
  {
    // This may also result in flushing the line buffer:
    linemode = state;
  }

  public void
  setSpeed(int speed)
  {
    if (terminal != null)
      terminal.setSpeed(speed);
  }

  private final void
  toggleAllowVTResize()
  {
    allowVTResize = !allowVTResize;
  }

  private final void
  toggleAllow8BitCodes()
  {
    allow8BitCodes = !allow8BitCodes;
  }

  public final boolean
  VTResizeOk()
  {
    return allowVTResize;
  }

  public final boolean
  send8BitCodesOk()
  {
    return allowVTResize;
  }

  public final void
  setModeNewline(boolean to)
  {
    modeNewline = to;
  }

  public final void
  toggleScrollOnInput()
  {
    scrollOnInput = !scrollOnInput;
  }

  public final Dimension
  getWindowSizeInChars()
  {
    if (display != null)
      return display.getSizeInChars();
    return new Dimension(Display.DEF_COLS, Display.DEF_ROWS);
  }

  public final Dimension
  getWindowSizeInPixels()
  {
    if (display != null)
      return display.getSize();
    // Should always have Display; this should never be needed:
    return new Dimension(Display.DEF_COLS * 8, Display.DEF_ROWS * 8);
  }

  public final boolean
  isConnected()
  {
    return terminal != null;
  }

  public final void
  setWindowSizeInChars(int x, int y, boolean force)
  {
    if (terminal != null) {
      connection.sendNAWS(x, y, force);
    }
  }

  public final void setWrapMenuState(boolean b) {
      if (optionsMenuWrap != null) {
	  optionsMenuWrap.setState(b);
      }
  }

  public final void setEchoMenuState(boolean b) {
      if (optionsMenuEcho != null) {
	  optionsMenuEcho.setState(b);
      }
  }
}
