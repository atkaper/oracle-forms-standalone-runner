package com.kaper.forms;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringEscapeUtils;

/**
 * -------------------------------------------------------------------------------------------------------------------------
 * Oracle Forms 11 Applet Runner.
 * -------------------------------------------------------------------------------------------------------------------------
 * <p>
 * Warning: requirement - you need a JAVA 8 JDK (or runtime) to (build and) start this thing!
 * <p>
 * At the office, we still use some old oracle-forms-11 applications. The "normal" users will run these from windows,
 * in the Edge browser switched to old Internet-Explorer compatibility mode...
 * As I do run a Linux machine, I do not have Edge, and needed something else to run the Oracle-Forms applications.
 * <p>
 * This application will read the html page from the URL as passed in on the command line, find the latest applet in there,
 * and will try to start it. It does read the parameters from the applet definition, and changes some of them to simulate
 * as if we were started from a browser on the given URL.
 * The code will also download the listed applet JAR files (in memory) to be able to start the applet.
 * <p>
 * Example start:
 * java -jar oracle-forms-runner-1.0.0-SNAPSHOT.jar "http://eforms-tst3.ecom.somewhere.nl:8888/forms/frmservlet?config=emda"
 * <p>
 * Created by Thijs Kaper, September 24, 2022.
 * <p>
 * -------------------------------------------------------------------------------------------------------------------------
 * This code is heavily based on the code from the next url (it is like a minor tweak on the original to fill in some
 * blanks / "todos", and it adds reading of the html starter page, and I have done some code refactoring):
 * http://www.java2s.com/Code/Java/Swing-JFC/AppletViewerasimpleAppletViewerprogram.htm
 * See the AppletViewer class for the original copyright notice.
 * Original Author:  Ian Darwin, https://www.darwinsys.com/, for Learning Tree Course 478
 * -------------------------------------------------------------------------------------------------------------------------
 */

public class OracleFormsRunner {

    /**
     * Download starter page, parse its parameters, construct the GUI, load the Applet, start it running.
     */
    public static void main(String[] args) throws Exception {
        Logger.logInfo("---");
        Logger.logInfo("Oracle Forms - Standalone Applet Runner - created by Thijs Kaper - 24 Sept, 2022");
        Logger.logInfo("---");
        String javaVersion = System.getProperty("java.version");
        Logger.logInfo("Java Version: " + javaVersion);
        if (!javaVersion.startsWith("1.8.")) {
            Logger.logInfo("WARNING: your java version does not start with '1.8.' so perhaps you are not using java 8...?");
            Logger.logInfo("If you get any errors running this code, double check that you use a java 8, or something which is compatible with running old Applets!");
        }
        Logger.logInfo("---");

        // check for start argument url
        if (args.length != 1) {
            Logger.logInfo("Please pass the start URL as parameter");
            Logger.logInfo("Example: java -jar oracle-forms-runner-1.0.0-SNAPSHOT.jar \"http://eforms-tst3.ecom.somewhere.nl:8888/forms/frmservlet?config=emda\"");
            return;
        }

        // read html document on given start URL, it will contain the oracle forms applet start code and parameters
        Map<String, String> parameters = loadAppletPropertiesFromHtmlLaunchPage(args[0]);
        if (parameters == null || parameters.isEmpty()) {
            Logger.logInfo("No start parameters in the html page?");
            return;
        }

        // check that we know what code to download
        if (!parameters.containsKey("java_archive")) {
            Logger.logInfo("Missing java_archive attribute in last <embed ..> tag, no clue what jars to download - giving up");
            return;
        }

        // load jars in memory, and add to runtime classpath
        String[] jars = parameters.get("java_archive").replaceAll("\\s+", "").split(",");
        for (String jar : jars) {
            String jarUrl = parameters.get("java_codebase") + "/" + jar;
            Logger.logInfo("Download/Activate jar: " + jarUrl);
            addSoftwareLibrary(new URL(jarUrl));
        }

        // check if we know WHAT APPLET class to start
        if (!parameters.containsKey("java_code")) {
            Logger.logInfo("Missing java_code attribute in last <embed ..> tag");
            return;
        }
        Logger.logInfo("---");
        // Go start the applet... (using our wrapper system)
        AppletViewer viewer = new AppletViewer(parameters.get("java_code"), parameters);


        // Ok, this is an ugly hack, but no clue how to do this nicely.
        // Here we monitor every second if the Applet still has any components showing...
        // If not, then we assume the user wants to close the application.
        while(true) {
            Thread.sleep(1000);
            if (viewer.theApplet.getComponentCount() == 0) {
                Logger.logInfo("### EXIT ###");
                viewer.mainFrame.setVisible(false);
                viewer.mainFrame.dispose();
                viewer.theApplet.destroy();
                System.exit(0);
            }
        }
    }

    /**
     * Here we load the HTML page from the given URL, find its last <embed>...</embed> tag, and load the parameters from that one.
     */
    private static Map<String, String> loadAppletPropertiesFromHtmlLaunchPage(String url) {
        // log the page URL we are trying to load
        Logger.logInfo("Start Url: " + url);

        // some settings need a "base url", e.g. the "http(s)://host/" part (without the rest of the initial start path).
        String baseUrl = url.replaceFirst("(https?://)([^/]+)/.*", "$1$2");
        Logger.logInfo("Base Url:" + baseUrl);

        // get the HTML contents
        String htmlFromUrl;
        try {
            htmlFromUrl = new Scanner(new URL(url).openStream(), "UTF-8").useDelimiter("\\A").next();
        } catch (IOException e) {
            Logger.logInfo("Error loading URL? " + e.getMessage());
            e.printStackTrace();
            return null;
        }

        // find last embed tag, assume that one will be the applet to start!
        int embedStart = htmlFromUrl.toLowerCase().lastIndexOf("<embed ");
        if (embedStart == -1) {
            Logger.logInfo("Could not find an <embed...> tag in the page?");
            Logger.logInfo("Here's what we got:");
            Logger.logInfo("---");
            Logger.logInfo(htmlFromUrl);
            Logger.logInfo("---");
            return null;
        }

        // from "<embed ..." start parsing key="value" pairs
        // Note: we should stop when we find a ">" bit we just keep looking all the way to the end of the doc
        // for key="value" pairs ;-) Does not seem to harm in our case.
        Pattern pattern = Pattern.compile("(\\w+)=(['\"])((?!\\2).+?)\\2", Pattern.MULTILINE);
        Matcher match = pattern.matcher(htmlFromUrl.substring(embedStart));
        Map<String, String> parameters = new LinkedHashMap<>();
        while (match.find()) {
            parameters.put(match.group(1), StringEscapeUtils.unescapeHtml4(match.group(3)));
        }

        // The oracle applet uses a java_documentbase" tag to indicate where the jar's can be loaded from
        // this is normally a path relative to the start URL. As we are not in browser, we update this
        // value here to be prefixed with the baseUrl.
        // Same for the "serverURL", I assume that that one is an oracle forms specific parameter.
        parameters.put("java_documentbase", url);
        if (!parameters.containsKey("java_codebase") || !parameters.containsKey("serverURL")) {
            Logger.logInfo("Missing java_codebase and/or serverURL attributes in last <embed ..> tag");
            return null;
        }
        if (!parameters.get("java_codebase").toLowerCase().startsWith("http")) {
            parameters.put("java_codebase", baseUrl + parameters.get("java_codebase"));
        }
        if (!parameters.get("serverURL").toLowerCase().startsWith("http")) {
            parameters.put("serverURL", baseUrl + parameters.get("serverURL"));
        }

        // Show the final result of all loaded parameters:
        Logger.logInfo("---");
        parameters.forEach((s, s2) -> Logger.logInfo(s + " = " + s2));

        // Add some overrides, if any passed in. You can do that using one or more jvm options: -Doverride_KEY=VALUE
        for(String name: System.getProperties().stringPropertyNames()) {
            if (name.startsWith("override_")) {
                String key = name.replaceFirst("^override_", "");
                String value = System.getProperty(name).trim();
                Logger.logInfo("> Property Override: " + key + " = " + value);
                parameters.put(key, value);
            }
        }
        Logger.logInfo("---");
        return parameters;
    }

    /**
     * Dynamically add a jar file URL to the classloader.
     */
    private static void addSoftwareLibrary(URL jarFileUrl) throws Exception {
        Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
        method.setAccessible(true);
        method.invoke(ClassLoader.getSystemClassLoader(), jarFileUrl);
    }
}
