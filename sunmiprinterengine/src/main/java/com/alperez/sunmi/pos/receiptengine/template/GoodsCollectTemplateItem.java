package com.alperez.sunmi.pos.receiptengine.template;

import androidx.annotation.NonNull;

import com.alperez.sunmi.pos.receiptengine.escpos.Charset;
import com.alperez.sunmi.pos.receiptengine.escpos.ESCUtils;
import com.alperez.sunmi.pos.receiptengine.parammapper.JsonMappableEntity;
import com.alperez.sunmi.pos.receiptengine.parammapper.ParameterValueMapper;
import com.alperez.sunmi.pos.receiptengine.print.PosPrinterParams;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Locale;

public class GoodsCollectTemplateItem extends BaseTemplateItem {

    private final String json;
    private final ColumnTemplate columnItemsTemplate;
    private final ColumnTemplate columnWeightTemplate;
    private final ColumnTemplate columnAmountTemplate;
    private final RowTotalTemplate rowTotalTemplate;
    private final int optItemsStartPadding;
    private final Locale locale;
    private final GoodsCollectDataItem[] collectedItems;





    public GoodsCollectTemplateItem(JSONObject jObj, @NonNull ParameterValueMapper valueMapper) throws JSONException {
        super(jObj);
        json = jObj.toString();
        columnItemsTemplate = new ColumnTemplate(jObj.getJSONObject("column_items"));
        columnWeightTemplate = new ColumnTemplate(jObj.getJSONObject("column_weight"));
        columnAmountTemplate = new ColumnTemplate(jObj.getJSONObject("column_amount"));
        rowTotalTemplate = new RowTotalTemplate(jObj.getJSONObject("row_total"));
        optItemsStartPadding = jObj.optInt("opt_items_start_padding", 0);
        if (jObj.has("locale")) {
            try {
                String[] sLoc = jObj.getString("locale").split("-");
                locale = new Locale(sLoc[0], sLoc[1], "");
            } catch (Exception e) {
                throw new JSONException("wrong Locale value - "+jObj.getString("locale"));
            }
        } else {
            locale = Locale.getDefault();
        }



        collectedItems = valueMapper.mapObjectArrayValue(jObj.getString("data"));

        splitColumn1 = new String[1+collectedItems.length][];
        splitColumn2 = new String[1+collectedItems.length][];
        splitColumn3 = new String[1+collectedItems.length][];
    }



    @Override
    public String getJson() {
        return json;
    }


    static final String TYPE_JSON_VALUE = "goods_collect_table";

    @Override
    public String getTypeJsonValue() {
        return TYPE_JSON_VALUE;
    }


    private Collection<byte[]> printerRawData;
    private Charset rawDataCharset;
    private PosPrinterParams rawDataPrinterParams;

    @Override
    public Collection<byte[]> getPrinterRawData(Charset charset, PosPrinterParams printerParams) throws UnsupportedEncodingException {


        if ((printerRawData == null) || (this.rawDataCharset != charset) || !printerParams.equals(this.rawDataPrinterParams)) {
            synchronized (this) {
                if ((printerRawData == null) || (this.rawDataCharset != charset) || !printerParams.equals(this.rawDataPrinterParams)) {
                    printerRawData = buildPrinterRawData(charset, printerParams);
                }
            }
        }
        return this.printerRawData;
    }




    /***************************  The Main method to build receipt RAW data  **********************/
    private final String[][] splitColumn1;
    private final String[][] splitColumn2;
    private final String[][] splitColumn3;
    private String[] totalValueText;
    private String[] totalTitleText;
    private int finColumn3Width; //target width of the column #3
    private int finColumn2Width; //target width of the column #2
    private int finColumn1Width;
    private int finStartPaddingColumnOne;


    private Collection<byte[]> buildPrinterRawData(Charset charset, PosPrinterParams printerParams) throws UnsupportedEncodingException {
        this.rawDataCharset = charset;
        this.rawDataPrinterParams = printerParams;

        int sc_w = 1;
        if (sc_w < printerParams.characterScaleWidthLimits()[0]) sc_w = printerParams.characterScaleWidthLimits()[0];
        else if (sc_w > printerParams.characterScaleWidthLimits()[1]) sc_w = printerParams.characterScaleWidthLimits()[1];
        final int maxPrintW = printerParams.lineLengthFromScaleWidth(sc_w);


        //----  Build cells text  ----
        // 1-st index -> num of Row
        // 2-nd index -> split parts of a cell
        fillInSplitColumnsWithOriginalContent();
        final int nCollectedItems = this.collectedItems.length;


        //----  Build "Total" title and value texts  ----
        totalTitleText = new String[]{rowTotalTemplate.title};
        totalValueText = buildTotalValueText();

        //----  Calculate final width of the column 3 and split content  ----
        finColumn3Width = calculateColumn3FinalWidth();

        //----  Calculate final width of the column 2 and split content  ----
        finColumn2Width = calculateColumn2FinalWidth();


        //----  Calculate final width of the column 1 and split content  ----
        finColumn1Width = maxPrintW - 4 - finColumn2Width - finColumn3Width;
        finStartPaddingColumnOne  = (finColumn1Width >= 15) ? this.optItemsStartPadding : 0;
        checkAndSplitColumn1Content();


        //----  Check and split Total title if necessary  ----
        checkAndSplitTotalTitle();


        //---------  Build character table as the Video-memory  -----------------
        final int numOrigRows = nCollectedItems + 2;
        int[] numPrintedRowsForTableRows = new int[numOrigRows];
        for (int i_row=0; i_row < (numOrigRows - 1); i_row++) {
            int max = splitColumn1[i_row].length;
            if (max < splitColumn2[i_row].length) {
                max = splitColumn2[i_row].length;
            }
            if (max < splitColumn3[i_row].length) {
                max = splitColumn3[i_row].length;
            }
            numPrintedRowsForTableRows[i_row] = max;
        }
        numPrintedRowsForTableRows[numOrigRows-1] = Math.max(totalTitleText.length, totalValueText.length);


        int charTableH = nCollectedItems+2+1; //num of horizontal lines;
        for (int i=0; i < numOrigRows; i++) charTableH += numPrintedRowsForTableRows[i];
        char[][] charTable = new char[charTableH][maxPrintW];
        int [][] boldTable = new int [charTableH][maxPrintW];
        for (int origRowIndex=0, prnRowIndex = 0; origRowIndex <= numOrigRows; origRowIndex++) {
            if (origRowIndex == 0) {
                printStartHorizontalBorder(charTable[prnRowIndex ++]);
            } else if (origRowIndex == (numOrigRows - 1)) {
                printPreEndHorizontalBorder(charTable[prnRowIndex ++]);
            } else if (origRowIndex == numOrigRows) { // End-of-cycle !!!!!!!!!!!!!
                printEndHorizontalBorder(charTable[prnRowIndex ++]);
                continue;
            } else {
                printMiddleHorizontalBorder(charTable[prnRowIndex ++]);
            }
            for (int j=0; j < numPrintedRowsForTableRows[origRowIndex]; j++) {
                if (origRowIndex < (numOrigRows - 1)) {
                    printContentEmptyRow(charTable[prnRowIndex ++]);
                } else {
                    printLastContentEmptyRow(charTable[prnRowIndex ++]);
                }
            }
        }

        int[] rowStartIndexesInChartable = new int[numOrigRows];
        for (int i=0, rowStartIndex = 1; i<numOrigRows; i++) {
            rowStartIndexesInChartable[i] = rowStartIndex;
            rowStartIndex += (numPrintedRowsForTableRows[i]+1);
        }


        //----  fill in Column 1 with content  ----
        for (int i=0; i < numOrigRows-1; i++) {
            int vertiPos = rowStartIndexesInChartable[i];
            int horizPos = 1;
            final TextAlign align = (i==0) ? columnItemsTemplate.titleAlign : columnItemsTemplate.contentAlign;
            final boolean isBold = (i==0) ? columnItemsTemplate.isTitleBold : columnItemsTemplate.isContentBold;
            String[] cellRows = splitColumn1[i];
            for (int j=0; j<cellRows.length; j++, vertiPos++) {
                printTextIntoCell(charTable, cellRows[j], vertiPos, horizPos, finColumn1Width, align, finStartPaddingColumnOne);
                if (isBold) {
                    boldTable[vertiPos][horizPos] = +1;
                    boldTable[vertiPos][horizPos + finColumn1Width] = -1;
                }
            }
        }

        //----  fill in Column 2 with content  ----
        for (int i=0; i < numOrigRows-1; i++) {
            int vertiPos = rowStartIndexesInChartable[i];
            int horizPos = 1 + finColumn1Width + 1;
            final TextAlign align = (i==0) ? columnWeightTemplate.titleAlign : columnWeightTemplate.contentAlign;
            final boolean isBold = (i==0) ? columnWeightTemplate.isTitleBold : columnWeightTemplate.isContentBold;
            String[] cellRows = splitColumn2[i];
            for (int j=0; j<cellRows.length; j++, vertiPos++) {
                printTextIntoCell(charTable, cellRows[j], vertiPos, horizPos, finColumn2Width, align, 0);
                if (isBold) {
                    boldTable[vertiPos][horizPos] = +1;
                    boldTable[vertiPos][horizPos + finColumn2Width] = -1;
                }
            }
        }

        //----  fill in Column 3 with content  ----
        for (int i=0; i < numOrigRows-1; i++) {
            int vertiPos = rowStartIndexesInChartable[i];
            int horizPos = 1 + finColumn1Width + 1 + finColumn2Width + 1;
            final TextAlign align = (i==0) ? columnAmountTemplate.titleAlign : columnAmountTemplate.contentAlign;
            final boolean isBold = (i==0) ? columnAmountTemplate.isTitleBold : columnAmountTemplate.isContentBold;
            String[] cellRows = splitColumn3[i];
            for (int j=0; j<cellRows.length; j++, vertiPos++) {
                printTextIntoCell(charTable, cellRows[j], vertiPos, horizPos, finColumn3Width, align, 0);
                if (isBold) {
                    boldTable[vertiPos][horizPos] = +1;
                    boldTable[vertiPos][horizPos + finColumn3Width] = -1;
                }
            }
        }

        //----  fill in "total" cell title  ----
        int vertiPos = rowStartIndexesInChartable[numOrigRows-1];
        int horizPos = 1;
        final int totalTitleCellWidth = finColumn1Width + 1 + finColumn2Width;
        for (int i=0; i<totalTitleText.length; i++, vertiPos++) {
            printTextIntoCell(charTable,  totalTitleText[i], vertiPos, horizPos, totalTitleCellWidth, rowTotalTemplate.titleAlign, finStartPaddingColumnOne);
            if (rowTotalTemplate.isBold) {
                boldTable[vertiPos][horizPos] = +1;
                boldTable[vertiPos][horizPos + totalTitleCellWidth] = -1;
            }
        }

        //----  fill in "total" cell value  ----
        vertiPos = rowStartIndexesInChartable[numOrigRows-1];
        horizPos = 1 + totalTitleCellWidth + 1;
        for (int i=0; i<totalValueText.length; i++, vertiPos++) {
            printTextIntoCell(charTable,  totalValueText[i], vertiPos, horizPos, finColumn3Width, rowTotalTemplate.valueAlign, 0);
            if (rowTotalTemplate.isBold) {
                boldTable[vertiPos][horizPos] = +1;
                boldTable[vertiPos][horizPos + finColumn3Width] = -1;
            }
        }

        Collection<byte[]> dataset = new LinkedList<>();
        dataset.add(ESCUtils.setLineSpacing(printerParams.reducedLineSpacingValue()));
        dataset.add(ESCUtils.setBoldEnabled(false));
        dataset.add(ESCUtils.setTextAlignment(TextAlign.ALIGN_LEFT));

        for (int prnRowIndex = 0; prnRowIndex < charTableH; prnRowIndex ++) {
            StringBuilder sb = new StringBuilder(charTable[prnRowIndex].length);
            for (int prnColIndex=0; prnColIndex<maxPrintW; prnColIndex++) {
                if (boldTable[prnRowIndex][prnColIndex] > 0) {
                    sb.append(ESCUtils.toUnicodeCharacter(ESCUtils.setBoldEnabled(true)));
                } else if (boldTable[prnRowIndex][prnColIndex] < 0) {
                    sb.append(ESCUtils.toUnicodeCharacter(ESCUtils.setBoldEnabled(false)));
                }
                sb.append(charTable[prnRowIndex][prnColIndex]);
            }
            dataset.add(sb.toString().getBytes(charset.getEncodingStdName()));
        }



        dataset.add(ESCUtils.nextLine(1));
        dataset.add(ESCUtils.setLineSpacingDefault());
        return dataset;
    }
    /*====================  End of the Main method to build receipt RAW data  ====================*/



    private void fillInSplitColumnsWithOriginalContent() {
        // 1-st index -> num of Row
        // 2-nd index -> split parts of a cell
        splitColumn1[0] = columnItemsTemplate.title.split("\\n");
        splitColumn2[0] = columnWeightTemplate.title.split("\\n");
        splitColumn3[0] = columnAmountTemplate.title.split("\\n");
        for (int i=0; i < this.collectedItems.length; i++) {
            splitColumn1[i+1] = new String[]{this.collectedItems[i].categoryName};
            splitColumn2[i+1] = new String[]{""+this.collectedItems[i].collectedWeight};
            splitColumn3[i+1] = new String[]{TextUtils.formatPrice(this.collectedItems[i].amount, this.collectedItems[i].currencyScale, false, locale)};
        }
    }

    private String[] buildTotalValueText() {
        double total = 0;
        int maxScale = 0;
        for (int i=0; i < this.collectedItems.length; i++) {
            total += TextUtils.buildPriceValue(this.collectedItems[i].amount, this.collectedItems[i].currencyScale);
            if (maxScale < this.collectedItems[i].currencyScale) {
                maxScale = this.collectedItems[i].currencyScale;
            }
        }

        for (int i=0; i<maxScale; i++) total *= 10;

        return new String[]{TextUtils.formatPrice((long)total, maxScale, rowTotalTemplate.valueFormatAsCurrency, locale)};
    }

    private int calculateColumn3FinalWidth() {
        final int nCollectedItems = this.collectedItems.length;
        int maxCellW_col3 = 0; //Maximum width of original cells content
        for (int i=1; i <= nCollectedItems; i++) {
            for (String part : splitColumn3[i]) {
                if (maxCellW_col3 < part.length()) {
                    maxCellW_col3 = part.length();
                }
            }
        }
        if (maxCellW_col3 < totalValueText[0].length()) {
            maxCellW_col3 = totalValueText[0].length();
        }


        if ((columnAmountTemplate.maxWidth > 0) && (maxCellW_col3 > columnAmountTemplate.maxWidth)) {
            //Need more split
            int column3Width = columnAmountTemplate.maxWidth;
            for (int i=1; i <= nCollectedItems; i++) {
                String valueAmount = splitColumn3[i][0];
                if (valueAmount.length() > column3Width) {
                    splitColumn3[i] = TextUtils.splitTextByLines(valueAmount, column3Width);
                }
            }
            if (totalValueText[0].length() > column3Width) {
                totalValueText = TextUtils.splitTextByLines(totalValueText[0], column3Width);
            }
            return column3Width;
        } else {
            return Math.max(maxCellW_col3, columnAmountTemplate.minWidth);
        }
    }

    private int calculateColumn2FinalWidth() {
        final int nCollectedItems = this.collectedItems.length;
        int maxCellW_col2 = 0; //Maximum width of original cells content
        for (int i=1; i <= nCollectedItems; i++) {
            for (String part : splitColumn2[i]) {
                if (maxCellW_col2 < part.length()) {
                    maxCellW_col2 = part.length();
                }
            }
        }


        if ((columnWeightTemplate.maxWidth > 0) && (maxCellW_col2 > columnWeightTemplate.maxWidth)) {
            //Need more split
            int columnWidth = columnWeightTemplate.maxWidth;
            for (int i=1; i <= nCollectedItems; i++) {
                String valueWeight = splitColumn2[i][0];
                if (valueWeight.length() > columnWidth) {
                    splitColumn2[i] = TextUtils.splitTextByLines(valueWeight, columnWidth);
                }
            }
            return columnWidth;
        } else {
            return Math.max(maxCellW_col2, columnWeightTemplate.minWidth);
        }
    }


    private void checkAndSplitColumn1Content() {
        final int nCollectedItems = this.collectedItems.length;

        int maxCellW_col1 = 0;
        for (int i=1; i <= nCollectedItems; i++) {
            for (String part : splitColumn1[i]) {
                if (maxCellW_col1 < part.length()) {
                    maxCellW_col1 = part.length();
                }
            }
        }


        if ((maxCellW_col1 + finStartPaddingColumnOne) > finColumn1Width) {
            //Need more split
            for (int i=1; i <= nCollectedItems; i++) {
                String valueItem = splitColumn1[i][0];
                if (valueItem.length() > (finColumn1Width - finStartPaddingColumnOne)) {
                    splitColumn1[i] = TextUtils.splitTextByLines(valueItem, (finColumn1Width - finStartPaddingColumnOne));
                }
            }
        }
    }

    private void checkAndSplitTotalTitle() {
        final int actualCellWidth = finColumn1Width + 1 + finColumn2Width - finStartPaddingColumnOne;
        if (totalTitleText[0].length() > actualCellWidth) {
            totalTitleText = TextUtils.splitTextByLines(totalTitleText[0], actualCellWidth);
        }
    }




    /************************  Printing character into 'Videomemory'  *****************************/
    private void printStartHorizontalBorder(char[] dst) {
        final int len = dst.length;
        final int delim_1 = finColumn1Width + 1;
        final int delim_2 = delim_1 + finColumn2Width + 1;
        dst[0] = '\u250C';
        for (int i=1; i < len-1; i++) {
            if (i == delim_1) {
                dst[i] = '\u252C';
            } else if (i == delim_2) {
                dst[i] = '\u252C';
            } else {
                dst[i] = '\u2500';
            }
        }
        dst[len-1] = '\u2510';
    }

    private void printMiddleHorizontalBorder(char[] dst) {
        final int len = dst.length;
        final int delim_1 = finColumn1Width + 1;
        final int delim_2 = delim_1 + finColumn2Width + 1;
        dst[0] = '\u251C';
        for (int i=1; i < len-1; i++) {
            if (i == delim_1) {
                dst[i] = '\u253C';
            } else if (i == delim_2) {
                dst[i] = '\u253C';
            } else {
                dst[i] = '\u2500';
            }
        }
        dst[len-1] = '\u2524';
    }

    private void printPreEndHorizontalBorder(char[] dst) {
        final int len = dst.length;
        final int delim_1 = finColumn1Width + 1;
        final int delim_2 = delim_1 + finColumn2Width + 1;
        dst[0] = '\u251C';
        for (int i=1; i < len-1; i++) {
            if (i == delim_1) {
                dst[i] = '\u2534';
            } else if (i == delim_2) {
                dst[i] = '\u253C';
            } else {
                dst[i] = '\u2500';
            }
        }
        dst[len-1] = '\u2524';
    }


    private void printEndHorizontalBorder(char[] dst) {
        final int len = dst.length;
        final int delim_2 = finColumn1Width + finColumn2Width + 2;
        dst[0] = '\u2514';
        for (int i=1; i < len-1; i++) {
            if (i == delim_2) {
                dst[i] = '\u2534';
            } else {
                dst[i] = '\u2500';
            }
        }
        dst[len-1] = '\u2518';
    }

    private void printContentEmptyRow(char[] dst) {
        final int len = dst.length;
        final int delim_1 = finColumn1Width + 1;
        final int delim_2 = delim_1 + finColumn2Width + 1;
        dst[0] = '\u2502';
        for (int i=1; i < len-1; i++) {
            if (i == delim_1 || i == delim_2) {
                dst[i] = '\u2502';
            } else {
                dst[i] = '\u0020';
            }
        }
        dst[len-1] = '\u2502';
    }

    private void printLastContentEmptyRow(char[] dst) {
        final int len = dst.length;
        final int delim_2 = finColumn1Width + finColumn2Width + 2;
        dst[0] = '\u2502';
        for (int i=1; i < len-1; i++) {
            if (i == delim_2) {
                dst[i] = '\u2502';
            } else {
                dst[i] = '\u0020';
            }
        }
        dst[len-1] = '\u2502';
    }

    private void printTextIntoCell(char[][] charTable, CharSequence text, int top, int cellLeft, int cellWidth, TextAlign align, int startPadding) {
        int printLen = Math.min(text.length(), (cellWidth - startPadding));
        int index = (align == TextAlign.ALIGN_RIGHT)
                ? cellLeft + cellWidth - (startPadding + printLen)
                : cellLeft + startPadding;
        for (int i=0; i<printLen; i++, index++) {
            charTable[top][index] = text.charAt(i);
        }
    }




    /**********************************************************************************************/
    public static class GoodsCollectDataItem extends JsonMappableEntity {
        final String categoryName;
        final int collectedWeight;
        final long amount;
        final int currencyScale;

        public GoodsCollectDataItem(JSONObject jObj) throws JSONException {
            super(jObj);
            this.categoryName = jObj.getString("full_category_name");
            this.collectedWeight = jObj.getInt("Qty");
            this.amount = jObj.getLong("amount");
            this.currencyScale = jObj.getInt("currency_scale");
        }
    }


    private static class ColumnTemplate {
        final String title;
        final TextAlign titleAlign;
        final boolean isTitleBold;
        final TextAlign contentAlign;
        final boolean isContentBold;
        final int minWidth, maxWidth;

        ColumnTemplate(JSONObject jObj) throws JSONException {
            title = jObj.getString("title").trim();
            titleAlign = TextAlign.fromJson(jObj.optString("title_align", TextAlign.ALIGN_LEFT.getJsonValue()));
            isTitleBold = jObj.optBoolean("title_bold", false);
            contentAlign = TextAlign.fromJson(jObj.optString("content_align", TextAlign.ALIGN_LEFT.getJsonValue()));
            isContentBold = jObj.optBoolean("content_bold", false);
            minWidth = jObj.optInt("min_width", 0);
            maxWidth = jObj.optInt("max_width", 0);
        }
    }

    private static class RowTotalTemplate {
        final String title;
        final boolean isBold;
        final TextAlign titleAlign;
        final TextAlign valueAlign;
        final boolean valueFormatAsCurrency;

        RowTotalTemplate(JSONObject jObj) throws JSONException {
            title = jObj.getString("title").trim();
            isBold = jObj.optBoolean("bold", false);
            titleAlign = TextAlign.fromJson(jObj.optString("align_title", TextAlign.ALIGN_LEFT.getJsonValue()));
            valueAlign = TextAlign.fromJson(jObj.optString("align_value", TextAlign.ALIGN_LEFT.getJsonValue()));
            valueFormatAsCurrency = jObj.optBoolean("format_value_as_currency", false);
        }
    }
}
