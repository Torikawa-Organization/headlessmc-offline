package io.github.headlesshq.headlessmc.lwjgl.redirections.stb;

import static io.github.headlesshq.headlessmc.lwjgl.api.Redirection.of;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.headlesshq.headlessmc.lwjgl.api.RedirectionManager;

/**
 * Redirections for {@code org.lwjgl.stb.STBTruetype} font functions.
 * <p>
 * Parses the TrueType/OpenType {@code name} table from the font data passed
 * to {@code stbtt_InitFont} and uses it to serve real font name strings from
 * {@code stbtt_GetFontNameString}. This allows mods like Meteor Client that
 * use STB for font loading to work correctly in headless mode.
 */
public class STBTrueTypeRedirections {
    /**
     * Descriptor for stbtt_InitFont(STBTTFontinfo, ByteBuffer) -> boolean.
     * Overrides the simple {@code of(true)} redirect from
     * {@link io.github.headlesshq.headlessmc.lwjgl.redirections.ForgeDisplayWindowRedirections}.
     */
    public static final String INIT_FONT_DESC = "Lorg/lwjgl/stb/STBTruetype;"
            + "stbtt_InitFont(Lorg/lwjgl/stb/STBTTFontinfo;"
            + "Ljava/nio/ByteBuffer;)Z";

    /**
     * Descriptor for stbtt_GetFontNameString(STBTTFontinfo, int, int, int,
     * int) -> ByteBuffer.
     */
    public static final String GET_FONT_NAME_STRING_DESC = "Lorg/lwjgl/stb/STBTruetype;"
            + "stbtt_GetFontNameString(Lorg/lwjgl/stb/STBTTFontinfo;IIII)"
            + "Ljava/nio/ByteBuffer;";

    /**
     * Descriptor for stbtt_GetNumberOfFonts(ByteBuffer) -> int.
     */
    public static final String GET_NUMBER_OF_FONTS_DESC = "Lorg/lwjgl/stb/STBTruetype;"
            + "stbtt_GetNumberOfFonts(Ljava/nio/ByteBuffer;)I";

    /**
     * Descriptor for stbtt_ScaleForPixelHeight(STBTTFontinfo, float) -> float.
     */
    public static final String SCALE_FOR_PIXEL_HEIGHT_DESC = "Lorg/lwjgl/stb/STBTruetype;"
            + "stbtt_ScaleForPixelHeight(Lorg/lwjgl/stb/STBTTFontinfo;F)F";

    /**
     * Descriptor for stbtt_GetFontVMetrics(STBTTFontinfo, IntBuffer, IntBuffer,
     * IntBuffer) -> void.
     */
    public static final String GET_FONT_VMETRICS_DESC = "Lorg/lwjgl/stb/STBTruetype;"
            + "stbtt_GetFontVMetrics(Lorg/lwjgl/stb/STBTTFontinfo;"
            + "Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;)V";

    /**
     * Stores parsed name records keyed by the STBTTFontinfo instance.
     * WeakHashMap so entries are cleaned up when fontInfo is GCd.
     */
    private static final Map<Object, List<NameRecord>> FONT_NAMES = new WeakHashMap<>();

    public static void redirect(RedirectionManager manager) {
        // Override the simple of(true) redirect: parse the name table
        // from the font data and store it, then return true.
        manager.redirect(INIT_FONT_DESC, (obj, desc, type, args) -> {
            Object fontInfo = args[0];
            ByteBuffer fontData = (ByteBuffer) args[1];
            if (fontData != null && fontInfo != null) {
                try {
                    List<NameRecord> names = parseNameTable(fontData);
                    if (!names.isEmpty()) {
                        synchronized (FONT_NAMES) {
                            FONT_NAMES.put(fontInfo, names);
                        }
                    }
                } catch (Exception e) {
                    // If parsing fails, we still return true so the
                    // existing Forge DisplayWindow path keeps working.
                }
            }

            return true;
        });

        // Look up stored name records for the given fontInfo instance.
        manager.redirect(GET_FONT_NAME_STRING_DESC,
                (obj, desc, type, args) -> {
                    Object fontInfo = args[0];
                    int platformID = (int) args[1];
                    int encodingID = (int) args[2];
                    int languageID = (int) args[3];
                    int nameID = (int) args[4];
                    List<NameRecord> names;
                    synchronized (FONT_NAMES) {
                        names = FONT_NAMES.get(fontInfo);
                    }

                    if (names == null) {
                        return null;
                    }

                    // Exact match first
                    for (NameRecord nr : names) {
                        if (nr.platformID == platformID
                                && nr.encodingID == encodingID
                                && nr.languageID == languageID
                                && nr.nameID == nameID) {
                            return ByteBuffer.wrap(nr.data);
                        }
                    }

                    // Fallback: match only on nameID (any platform)
                    for (NameRecord nr : names) {
                        if (nr.nameID == nameID) {
                            return ByteBuffer.wrap(nr.data);
                        }
                    }

                    return null;
                });

        // Return 1 for a single font (most .ttf files).
        manager.redirect(GET_NUMBER_OF_FONTS_DESC, of(1));

        // Return a non-zero scale factor so that callers don't get 0.0f
        // which could cause division-by-zero issues.
        manager.redirect(SCALE_FOR_PIXEL_HEIGHT_DESC,
                (obj, desc, type, args) -> {
                    float pixelHeight = (float) args[1];
                    // A typical font might have unitsPerEm=2048, so scale
                    // is pixelHeight / unitsPerEm. Return a plausible value.
                    return pixelHeight / 2048.0f;
                });

        // Fill the ascent/descent/lineGap IntBuffers with plausible values.
        manager.redirect(GET_FONT_VMETRICS_DESC,
                (obj, desc, type, args) -> {
                    java.nio.IntBuffer ascent = (java.nio.IntBuffer) args[1];
                    java.nio.IntBuffer descent = (java.nio.IntBuffer) args[2];
                    java.nio.IntBuffer lineGap = (java.nio.IntBuffer) args[3];
                    // Typical values for a 2048 unitsPerEm font
                    if (ascent != null)
                        ascent.put(0, 1800);
                    if (descent != null)
                        descent.put(0, -400);
                    if (lineGap != null)
                        lineGap.put(0, 0);
                    return null;
                });
    }

    /**
     * Parses the TrueType/OpenType {@code name} table from raw font data.
     * <p>
     * TrueType offset table layout:
     * 
     * <pre>
     *   uint32 sfVersion      (0x00010000 for TrueType, 'OTTO' for CFF)
     *   uint16 numTables
     *   uint16 searchRange
     *   uint16 entrySelector
     *   uint16 rangeShift
     * </pre>
     * 
     * Each table record:
     * 
     * <pre>
     *   uint32 tag
     *   uint32 checkSum
     *   uint32 offset
     *   uint32 length
     * </pre>
     * 
     * The {@code name} table (tag 0x6E616D65):
     * 
     * <pre>
     *   uint16 format
     *   uint16 count
     *   uint16 stringOffset
     *   NameRecord[count]:
     *     uint16 platformID
     *     uint16 encodingID
     *     uint16 languageID
     *     uint16 nameID
     *     uint16 length
     *     uint16 offset      (relative to stringOffset)
     * </pre>
     */
    private static List<NameRecord> parseNameTable(ByteBuffer original) {
        List<NameRecord> result = new ArrayList<>();
        ByteBuffer buf = original.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (buf.remaining() < 12) {
            return result;
        }

        buf.position(buf.position() + 4); // skip sfVersion
        int numTables = readUint16(buf);
        buf.position(buf.position() + 6); // skip searchRange, entrySelector, rangeShift

        // Find the 'name' table
        int nameTableOffset = -1;
        int nameTableLength = -1;
        int nameTag = 0x6E616D65; // 'name'
        for (int i = 0; i < numTables; i++) {
            if (buf.remaining() < 16) {
                return result;
            }

            int tag = buf.getInt();
            buf.getInt(); // checkSum
            int offset = buf.getInt();
            int length = buf.getInt();
            if (tag == nameTag) {
                nameTableOffset = offset;
                nameTableLength = length;
                break;
            }
        }

        if (nameTableOffset < 0
                || nameTableOffset + nameTableLength > original.limit()) {
            return result;
        }

        // Parse name table
        int basePos = original.position() + nameTableOffset;
        buf.position(basePos);
        if (buf.remaining() < 6) {
            return result;
        }

        readUint16(buf); // format (0 or 1)
        int count = readUint16(buf);
        int stringOffset = readUint16(buf);
        int stringStoragePos = basePos + stringOffset;

        for (int i = 0; i < count; i++) {
            if (buf.remaining() < 12) {
                break;
            }

            int platformID = readUint16(buf);
            int encodingID = readUint16(buf);
            int languageID = readUint16(buf);
            int nameID = readUint16(buf);
            int length = readUint16(buf);
            int offset = readUint16(buf);

            int dataStart = stringStoragePos + offset;
            if (dataStart >= 0
                    && dataStart + length <= original.limit()
                    && length > 0) {
                byte[] data = new byte[length];
                ByteBuffer slice = original.duplicate();
                slice.position(dataStart);
                slice.get(data);
                result.add(new NameRecord(
                        platformID, encodingID, languageID, nameID, data));
            }
        }

        return result;
    }

    private static int readUint16(ByteBuffer buf) {
        return buf.getShort() & 0xFFFF;
    }

    private static final class NameRecord {
        final int platformID;
        final int encodingID;
        final int languageID;
        final int nameID;
        final byte[] data;

        NameRecord(int platformID, int encodingID, int languageID,
                int nameID, byte[] data) {
            this.platformID = platformID;
            this.encodingID = encodingID;
            this.languageID = languageID;
            this.nameID = nameID;
            this.data = data;
        }
    }

}
