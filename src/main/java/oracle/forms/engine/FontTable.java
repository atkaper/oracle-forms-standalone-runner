package oracle.forms.engine;

/**
 * Decompiled from "frmall.jar".
 * We need this to add a font-mapping-"table" class to fix my Linux display issues.
 */
public interface FontTable {
    int sStartFontSize = 1;

    int sEndFontSize = 72;

    int[] getFontArray(String paramString);
}
