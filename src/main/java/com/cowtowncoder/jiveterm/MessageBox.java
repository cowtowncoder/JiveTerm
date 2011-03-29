/**************************************
				       
Project:
    JiveTerm.

    A VT52/VT100/VT102-compliant
    telnet/terminal program
    written in pure java.

    (C) 1998-99 Tatu Saloranta (aka Doomdark),
    doomdark@iki.fi.

Module:
    MessageBox.java

Description:
    A simple class that displays various
    information for the user. Usually modal,
    and has to be dismissed by pressing
    OK-button.

Last changed:
  30-Jan-99, TSa

**************************************/

package com.cowtowncoder.jiveterm;

import java.awt.*;
import java.awt.event.*;
import java.net.*;
import java.io.*;
import java.util.*;

import jiveterm.*;

public final class
MessageBox
extends Dialog
{
  private Label lConnectText;
  private Label lConnectText2;
  private Button bOk;
  private Color fg, bg, border;
  private Insets iBorders = new Insets(6, 8, 4, 8);

  public
  MessageBox(Frame parent, String title, String [] msg,
	 Color f, Color b, Color br)
  {
    super(parent, title, true);

    fg = f;
    bg = b;
    border = br;

    setBackground(bg);
    setForeground(fg);
    
    setLayout(new GridBagLayout());
    GridBagConstraints gbc;

    for (int i = 0; i < msg.length; i++) {
      Label l = new Label(msg[i]);
      l.setBackground(bg);
      l.setForeground(fg);
      gbc = new GridBagConstraints();
      gbc.gridx = 0;
      gbc.gridy = i;
      gbc.anchor = GridBagConstraints.WEST;
      gbc.fill = GridBagConstraints.BOTH;
      gbc.weightx = 100.0;
      gbc.weighty = 1.0;
      add(l, gbc);
    }

    gbc = new GridBagConstraints();
    gbc.fill = GridBagConstraints.NONE;
    gbc.anchor = GridBagConstraints.SOUTH;
    gbc.gridx = 0;
    gbc.gridy = msg.length;
    gbc.weighty = 100.0;
    bOk = new Button("Ok");
    /* Let's handle Cancel'ing in a straight-forward way: */
    bOk.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
	setVisible(false);
      }
    });

    /* For some reason, these key adapters don't seem to work on
     * Linux???
     */
    bOk.addKeyListener(new KeyAdapter() {
      public void keyTyped(KeyEvent e) {
	char c = e.getKeyChar();
	if (c == '\n' || c == '\r') {
	  e.consume();
	  setVisible(false);
	}
      }
    });

    addKeyListener(new KeyAdapter() {
      public void keyTyped(KeyEvent e) {
	char c = e.getKeyChar();
	if (c == '\n' || c == '\r') {
	  e.consume();
	  setVisible(false);
	}
      }
    });

    bOk.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    add(bOk, gbc);

    doLayout();
    pack();

    //      setSize(200, 100);
    /*    Dimension scr_s = Toolkit.getDefaultToolkit().getScreenSize();
    Dimension my_size = getSize();

    setLocation(scr_s.width / 2 - my_size.width / 2,
		scr_s.height / 2 - my_size.height / 2);
    setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    */

    if (parent != null) {
      Dimension scr_s = Toolkit.getDefaultToolkit().getScreenSize();
      Rectangle r = parent.getBounds();
      Dimension d = getSize();

      int x = r.x + (r.width / 2 - d.width / 2);
      int y = r.y + (r.height / 2 - d.height / 2);
      if ((x + d.width) > scr_s.width)
	x = scr_s.width - d.width;
      if ((y + d.height) > scr_s.height)
	x = scr_s.height - d.height;
      if (x < 0)
	x = 0;
      if (y < 0)
	y = 0;
      setLocation(x, y);
    }

    //show();
  }

  public void
  updateFocus()
  {
    bOk.requestFocus();
  }

  /* We need to override this method to draw borders: */
  public void
  paint(Graphics g)
  {
    super.paint(g);
    
    if (border != null) {
      Dimension d = getSize();    
      // should never be null but...
      if (d != null) {
	g.setColor(border);
	g.drawRect(0, 0, d.width - 1, d.height - 1);
	g.drawRect(2, 2, d.width - 5, d.height - 5);
	g.setColor(Color.black);
	g.drawRect(1, 1, d.width - 3, d.height - 3);
      }
    }
  }

  /* And this as well: */
  public Insets
  getInsets()
  {
    return iBorders;
  }

  public void
  update(Graphics g)
  {
    paint(g);
  }

  public void
  setTexts(String a, String b)
  {
    if (a == null)
      lConnectText.setText("");
    else lConnectText.setText(a);
    
    if (b == null)
      lConnectText2.setText("");
    else lConnectText2.setText(b);
    
    repaint();
  }
  
  public void
  waitForOk(String title)
  {
    bOk.setLabel(title);
    repaint();
    while (true) {
      try { Thread.sleep(100); } catch (InterruptedException e) {}
    }
  }

  public void
  hideButton()
  {
    bOk.setVisible(false);
  }
}
