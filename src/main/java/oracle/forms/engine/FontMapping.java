package oracle.forms.engine;

import com.kaper.forms.AppletAdapter;
import com.kaper.forms.Logger;

/**
 * I decompiled "oracle.forms.engine.FontMapping_sunos" from "frmall.jar", and changed implementation
 * to simply take a given nr of pixels of the font size. The original class had mapping tables, for font
 * names: Dialog, DialogInput, MonoSpaced, SansSerif, Serif. This new implementation has a more generic name,
 * which is loaded dynamically by detecting code in "frmall.jar":
 * The code "oracle.forms.engine.FontEntry" has a method mapFontSize which uses this data to
 * change font-sizes. It only works, if you set start parameter "mapFonts" to "yes", and you are not running
 * on one of the normally supported OSes (Windows, and SunOS). For those, internal mapping tables will be used.
 */
public class FontMapping implements FontTable {
    public int[] getFontArray(String paramString) {
        int downsizeFontPixels = Integer.parseInt(AppletAdapter.appletParameters.getOrDefault("downsizeFontPixels", "1"));
        Logger.logInfo("getFontArray " + paramString + " -> making font a bit smaller -> downsizeFontPixels=" + downsizeFontPixels);

        // Construct mapping list as being font size changed by given # of pixels DOWN.
        int[] arrayOfInt = new int[72];
        for (int s = 0; s < arrayOfInt.length; s++) {
            arrayOfInt[s] = (s + 1) - downsizeFontPixels;
        }
        return arrayOfInt;
    }
}
