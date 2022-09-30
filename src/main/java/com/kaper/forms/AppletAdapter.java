package com.kaper.forms;

import java.applet.Applet;
import java.applet.AppletContext;
import java.applet.AppletStub;
import java.applet.AudioClip;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.Label;
import java.awt.Panel;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.swing.ImageIcon;

/**
 * Code split from AppletViewer into a separate class.
 * See AppletViewer comment at the top, referring to:
 * http://www.java2s.com/Code/Java/Swing-JFC/AppletViewerasimpleAppletViewerprogram.htm
 * and it's original copyright message.
 * <p>
 * AppletAdaptor: partial implementation of AppletStub and AppletContext.
 * This code does not yet implement ALL overridden methods. But enough for us to run our oracle forms apps.
 * <p>
 * Original Author:  Ian Darwin, https://www.darwinsys.com/, for Learning Tree Course 478
 */
public class AppletAdapter extends Panel implements AppletStub, AppletContext {

    /**
     * A link back to the "parent" AppletViewer.
     */
    private final AppletViewer appletViewer;

    /**
     * The status line at the bottom.
     */
    private final Label status;

    /**
     * StreamKey map.
     */
    private final Map<String, InputStream> streamMap = new HashMap<>();

    /**
     * The parameters as we did read from the html page, from the last embed tag in it.
     * We did add the java_documentbase ourselves (set to start url), and we prefixed a base-url to java_codebase and serverURL.
     * The rest is taken 1-on-1 from the HTML page.
     */
    public static Map<String, String> appletParameters;

    /**
     * Construct the GUI for an Applet Status window.
     */
    public AppletAdapter(Map<String, String> appletParameters, AppletViewer appletViewer) {
        this.appletViewer = appletViewer;
        AppletAdapter.appletParameters = appletParameters;

        // Must do this very early on, since the Applet's
        // Constructor or its init() may use showStatus()
        add(status = new Label());

        // Give "status" the full width
        status.setSize(getSize().width, status.getSize().height);

        // now it can be said
        showStatus("AppletAdapter constructed");
    }

    // ****************** AppletStub ***********************

    /**
     * Called when the applet wants to be resized.
     */
    @Override
    public void appletResize(int w, int h) {
        // applet.setSize(w, h);
        Logger.logInfo("NOT-YET-IMPLEMENTED: appletResize: " + w + "/" + h);
    }

    /**
     * Gets a reference to the applet's context.
     */
    @Override
    public AppletContext getAppletContext() {
        return this;
    }

    /**
     * Gets the code base URL.
     */
    @Override
    public URL getCodeBase() {
        try {
            return new URL(getParameter("java_codebase"));
        } catch (Exception e) {
            Logger.logInfo("getCodeBase error: " + e.getMessage());
            e.printStackTrace();
            return getClass().getResource(".");
        }
    }

    /**
     * Gets the document URL.
     */
    @Override
    public URL getDocumentBase() {
        try {
            return new URL(getParameter("java_documentbase"));
        } catch (Exception e) {
            Logger.logInfo("getDocumentBase error: " + e.getMessage());
            e.printStackTrace();
            return getClass().getResource(".");
        }
    }

    /**
     * Returns the value of the named parameter in the HTML tag.
     * <p>
     * You can override any of the values from the page by passing in a system property where
     * the parameter name is prefixed with "override_".
     * Example, to override separateFrame="true" to separateFrame="false" pass in: '-Doverride_separateFrame=false'
     * Overrides are handled in the property load of the OracleFormsRunner class!
     * on the java command line.
     */
    @Override
    public String getParameter(String name) {
        String value = appletParameters.get(name);
        return value != null ? value.trim() : null;
    }

    /**
     * Determines if the applet is active.
     */
    @Override
    public boolean isActive() {
        return true;
    }

    // ************************ AppletContext ************************

    /**
     * Finds and returns the applet with the given name.
     */
    @Override
    public Applet getApplet(String an) {
        Logger.logInfo("NOT-YET-IMPLEMENTED: getApplet " + an);
        return null;
    }

    /**
     * Finds all the applets in the document.
     */
    @Override
    public Enumeration<Applet> getApplets() {
        Logger.logInfo("NOT-YET-IMPLEMENTED: getApplets");
        class AppletLister implements Enumeration<Applet> {
            public boolean hasMoreElements() {
                return false;
            }

            public Applet nextElement() {
                return null;
            }
        }
        return new AppletLister();
    }

    /**
     * Create an audio clip for the given URL of a .au file.
     */
    @Override
    public AudioClip getAudioClip(URL u) {
        Logger.logInfo("NOT-YET-IMPLEMENTED: getAudioClip " + u);
        return null;
    }

    /**
     * Look up and create an Image object that can be paint()ed.
     */
    @Override
    public Image getImage(URL u) {
        try {
            return new ImageIcon(u).getImage();
        } catch (Exception e) {
            Logger.logInfo("getImage error " + u + " / " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Request to show some document URL.
     * <p>
     * If "Desktop" does not work, you can set a browser to use by passing in JVM option:
     * -Doverride_browser=firefox
     * This value will default to firefox, but can be replaced by the browser (or command line command) to use.
     */
    @Override
    public void showDocument(URL u) {
        String browser = getParameter("browser");
        if (browser == null && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(u.toURI());
            } catch (Exception e) {
                Logger.logInfo("Desktop class not supported. Please pass in a browser or command to use as jvm option: -Doverride_browser=firefox");
                e.printStackTrace();
            }
        } else {
            try {
                if (browser == null) {
                    browser = "firefox";
                }
                Runtime.getRuntime().exec(new String[] { browser, u.toExternalForm() });
            } catch (Exception e) {
                Logger.logInfo("Error executing command: " + browser + " \"" + u.toExternalForm() + "\"");
                e.printStackTrace();
            }
        }
    }

    /**
     * as above but with a Frame target.
     * We ignore target here.
     */
    @Override
    public void showDocument(URL u, String frame) {
        showDocument(u);
    }

    /**
     * Called by the Applet to display a message in the bottom status line.
     */
    @Override
    public void showStatus(String msg) {
        if (msg == null) {
            msg = "";
        }
        Logger.logInfo("Status: " + msg);
        status.setText(msg);
    }

    /**
     * Associate the stream with the key.
     */
    @Override
    public void setStream(String key, InputStream stream) {
        streamMap.put(key, stream);
    }

    @Override
    public InputStream getStream(String key) {
        return streamMap.get(key);
    }

    @Override
    public Iterator<String> getStreamKeys() {
        return streamMap.keySet().iterator();
    }
}
