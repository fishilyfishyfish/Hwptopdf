package io.github.fishy.hwptopdf;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes a PDF one page at a time so a long document never sits in memory.
 * Each page is a JPEG with an invisible text layer on top, which is what makes
 * the result searchable and selectable.
 */
final class PdfWriter implements Closeable {
    private static final int CATALOG = 1;
    private static final int PAGES = 2;
    private static final int FONT = 3;
    private static final int CID_FONT = 4;
    private static final int DESCRIPTOR = 5;
    private static final int FONT_FILE = 6;
    private static final int TO_UNICODE = 7;
    private static final int CID_TO_GID = 8;
    private static final int FIRST_FREE = 9;

    /** A TrueType font with one empty glyph: the text layer needs codes, not shapes. */
    private static final String GLYPHLESS_TTF =
            "AAEAAAAGAEAAAgAgZ2x5ZgAAAAAAAADwAAAAAWhlYWQtQ/cOAAAAbAAAADZoaGVhAyIDIgAAAKQAAAAk"
            + "aG10eAPoAAAAAADoAAAABGxvY2EAAAAAAAAA7AAAAARtYXhwAAIAAgAAAMgAAAAgAAEAAAABAADOjyVj"
            + "Xw889QADA+gAAAAA5xbbFwAAAADnFtsXAAAAAAAAAAAAAAADAAIAAAAAAAAAAQAAAyD/OAAAA+gAAAAA"
            + "AAAAAQAAAAAAAAAAAAAAAAAAAAEAAQAAAAEAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAPoAAAAAAAA"
            + "AAAAAA==";

    private static final String TO_UNICODE_CMAP =
            "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
            + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
            + "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
            + "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n"
            + "1 beginbfrange\n<0000> <FFFF> <0000>\nendbfrange\n"
            + "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";

    private final OutputStream out;
    private final List<Long> offsets = new ArrayList<>();
    private final List<Integer> pageObjects = new ArrayList<>();
    private long position;
    private int nextObject = FIRST_FREE;

    PdfWriter(OutputStream out) throws IOException {
        this.out = out;
        write("%PDF-1.5\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
        writeSharedObjects();
    }

    /**
     * @param textOps PDF text operators for the invisible layer, or an empty string
     */
    void addPage(byte[] jpeg, int pixelWidth, int pixelHeight,
                 double widthPt, double heightPt, String textOps) throws IOException {
        int page = nextObject++;
        int image = nextObject++;
        int contents = nextObject++;

        String w = number(widthPt);
        String h = number(heightPt);
        object(page, "<< /Type /Page /Parent " + PAGES + " 0 R /MediaBox [0 0 " + w + " " + h + "]"
                + " /Resources << /XObject << /Im0 " + image + " 0 R >> /Font << /F1 " + FONT + " 0 R >> >>"
                + " /Contents " + contents + " 0 R >>");
        stream(image, "/Type /XObject /Subtype /Image /Width " + pixelWidth + " /Height " + pixelHeight
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpeg);
        String content = "q " + w + " 0 0 " + h + " 0 0 cm /Im0 Do Q\n" + textOps;
        deflatedStream(contents, "", content.getBytes(StandardCharsets.US_ASCII));
        pageObjects.add(page);
    }

    /** Writes the page tree and cross-reference table. The output stream is left open. */
    void finish() throws IOException {
        StringBuilder kids = new StringBuilder();
        for (int page : pageObjects) {
            kids.append(page).append(" 0 R ");
        }
        object(PAGES, "<< /Type /Pages /Count " + pageObjects.size() + " /Kids [" + kids + "] >>");

        long xref = position;
        int count = nextObject;
        StringBuilder table = new StringBuilder("xref\n0 " + count + "\n0000000000 65535 f \n");
        for (int i = 1; i < count; i++) {
            table.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets.get(i)));
        }
        table.append("trailer\n<< /Size ").append(count).append(" /Root ").append(CATALOG)
                .append(" 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(table.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    private void writeSharedObjects() throws IOException {
        object(CATALOG, "<< /Type /Catalog /Pages " + PAGES + " 0 R >>");
        object(FONT, "<< /Type /Font /Subtype /Type0 /BaseFont /Glyphless /Encoding /Identity-H"
                + " /DescendantFonts [" + CID_FONT + " 0 R] /ToUnicode " + TO_UNICODE + " 0 R >>");
        object(CID_FONT, "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Glyphless"
                + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >>"
                + " /FontDescriptor " + DESCRIPTOR + " 0 R /DW 1000 /CIDToGIDMap " + CID_TO_GID + " 0 R >>");
        object(DESCRIPTOR, "<< /Type /FontDescriptor /FontName /Glyphless /Flags 4"
                + " /FontBBox [0 -200 1000 800] /ItalicAngle 0 /Ascent 800 /Descent -200"
                + " /CapHeight 700 /StemV 80 /FontFile2 " + FONT_FILE + " 0 R >>");
        byte[] font = Base64.getDecoder().decode(GLYPHLESS_TTF);
        deflatedStream(FONT_FILE, "/Length1 " + font.length, font);
        deflatedStream(TO_UNICODE, "", TO_UNICODE_CMAP.getBytes(StandardCharsets.US_ASCII));
        deflatedStream(CID_TO_GID, "", new byte[65536 * 2]);
    }

    private void object(int number, String body) throws IOException {
        begin(number);
        write((body + "\nendobj\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private void deflatedStream(int number, String dict, byte[] data) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(data.length / 2 + 16);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(compressed, deflater)) {
            stream.write(data);
        } finally {
            deflater.end();
        }
        stream(number, "/Filter /FlateDecode " + dict, compressed.toByteArray());
    }

    private void stream(int number, String dict, byte[] data) throws IOException {
        begin(number);
        write(("<< " + dict + " /Length " + data.length + " >>\nstream\n")
                .getBytes(StandardCharsets.US_ASCII));
        write(data);
        write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
    }

    private void begin(int number) throws IOException {
        while (offsets.size() <= number) {
            offsets.add(0L);
        }
        offsets.set(number, position);
        write((number + " 0 obj\n").getBytes(StandardCharsets.US_ASCII));
    }

    private void write(byte[] data) throws IOException {
        out.write(data);
        position += data.length;
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
