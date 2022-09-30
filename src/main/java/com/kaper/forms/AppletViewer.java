package com.kaper.forms;

import java.applet.Applet;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Map;
import javax.swing.JFrame;

/**
 * --------------------------------------------------------------------------------------
 * This code is heavily based on the code from the next url (it is like a minor
 * tweak on the original to fill in some blanks / "todos", and it adds reading
 * of the html starter page):
 * http://www.java2s.com/Code/Java/Swing-JFC/AppletViewerasimpleAppletViewerprogram.htm
 * Here is the copyright notice from the original code:
 * --------------------------------------------------------------------------------------
 * <p>
 * Copyright (c) Ian F. Darwin, http://www.darwinsys.com/, 1996-2002.
 * All rights reserved. Software written by Ian F. Darwin and others.
 * $Id: LICENSE,v 1.8 2004/02/09 03:33:38 ian Exp $
 * <p>
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AND CONTRIBUTORS 'AS IS'
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * <p>
 * Java, the Duke mascot, and all variants of Sun's Java "steaming coffee
 * cup" logo are trademarks of Sun Microsystems. Sun's, and James Gosling's,
 * pioneering role in inventing and promulgating (and standardizing) the Java
 * language and environment is gratefully acknowledged.
 * <p>
 * The pioneering role of Dennis Ritchie and Bjarne Stroustrup, of AT&T, for
 * inventing predecessor languages C and C++ is also gratefully acknowledged.
 * <p>
 * AppletViewer - a simple Applet Viewer program.
 *
 * @author Ian Darwin, https://www.darwinsys.com/, for Learning Tree Course 478
 */
public class AppletViewer {

    /**
     * The main Frame of this program.
     */
    public final JFrame mainFrame;

    /**
     * The AppletAdapter (gives AppletStub, AppletContext, showStatus).
     */
    private static AppletAdapter appletAdapter = null;

    /**
     * The Applet instance we are running, or null. Can not be a JApplet
     * until all the entire world is converted to JApplet.
     */
    public Applet theApplet = null;

    /**
     * The default width of the Applet.
     */
    private static final int WIDTH = 400;

    /**
     * The default height of the Applet.
     */
    private static final int HEIGHT = 300;

    /**
     * Construct the GUI for an Applet Viewer
     */
    public AppletViewer(String appName, Map<String, String> embedParameters) {
        mainFrame = new JFrame("OracleFormsRunner / AppletViewer");
        mainFrame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                Logger.logInfo("### EXIT ###");
                mainFrame.setVisible(false);
                mainFrame.dispose();
                theApplet.destroy();
                System.exit(0);
            }
        });

        // Show screen resolution. For oracle forms you can override it using -Doverride_clientDPI=... to scale the forms.
        // For example, my resolution is 96, but if I override this to 120 it is a better (slightly bigger) size.
        Logger.logInfo("Screen Resolution: " + mainFrame.getToolkit().getScreenResolution());

        Container contentPane = mainFrame.getContentPane();
        contentPane.setLayout(new BorderLayout());

        // Instantiate the AppletAdapter which gives us AppletStub and AppletContext.
        appletAdapter = new AppletAdapter(embedParameters, this);

        // The AppletAdapter also gives us showStatus.
        // Therefore, must add() it very early on, since the Applet's
        // Constructor or its init() may use showStatus()
        contentPane.add(BorderLayout.SOUTH, appletAdapter);

        showStatus("Loading Applet " + appName);

        int width = embedParameters.containsKey("WIDTH") ? Integer.parseInt(embedParameters.get("WIDTH")) : WIDTH;
        int height = embedParameters.containsKey("HEIGHT") ? Integer.parseInt(embedParameters.get("HEIGHT")) : HEIGHT;

        theApplet = loadApplet(appName, width, height);
        if (theApplet == null) {
            return;
        }

        // Now right away, tell the Applet how to find showStatus et al.
        theApplet.setStub(appletAdapter);

        // Connect the Applet to the Frame.
        contentPane.add(BorderLayout.CENTER, theApplet);

        Dimension d = theApplet.getSize();
        d.height += appletAdapter.getSize().height;
        mainFrame.setSize(d);
        mainFrame.setVisible(true);    // make the Frame and all in it appear

        showStatus("Applet " + appName + " loaded");

        // Here we pretend to be a browser!
        theApplet.init();
        theApplet.start();

        // re-check/draw contents (is needed on my system in case we pass in jvm option: -Doverride_separateFrame=false)
        mainFrame.repaint();
        mainFrame.revalidate();
    }

    /**
     * Load the Applet into memory.
     */
    private Applet loadApplet(String appletName, int w, int h) {
        Applet applet;
        try {
            // Construct an instance (as if using no-argument constructor)
            applet = (Applet) Class.forName(appletName).newInstance();
        } catch (ClassNotFoundException e) {
            showStatus("Applet subclass " + appletName + " did not load");
            return null;
        } catch (Exception e) {
            showStatus("Applet " + appletName + " did not instantiate");
            return null;
        }
        applet.setSize(w, h);
        return applet;
    }

    public void showStatus(String s) {
        appletAdapter.getAppletContext().showStatus(s);
    }

}
