package com.veyra.gybeyond;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 红果下载产物容器修复（数据已是明文，仅容器头仍标 encv/senca 加密）。
 * 参照 gybeyond1/juku-backend 红果下载器 HongguoDecrypt.java 的容器部分：
 *  - stsd：encv→avc1/hvc1、enca→mp4a，去掉 sinf 子 box
 *  - 重建 moov：剔除 senc/saio/saiz/sgpd/sbgp 等加密元数据
 *  - stco/co64：moov 缩小后修正 chunk 偏移
 * 修复后文件即普通明文 MP4，可直接播放/合并。
 */
public final class ContainerFixer {

    private static final List<String> CONTAINER_TYPES = Arrays.asList(
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "meta");
    private static final List<String> CRYPTO_BOXES = Arrays.asList("senc", "saio", "saiz", "sgpd", "sbgp");

    private static final class Box {
        String typ;
        int off, size, hdr;
        List<Box> children;
    }

    private static List<Box> parseBoxes(byte[] data, int start, int end) {
        List<Box> boxes = new ArrayList<>();
        int off = start;
        while (off + 8 <= end) {
            ByteBuffer bb = ByteBuffer.wrap(data, off, 8).order(ByteOrder.BIG_ENDIAN);
            int size = bb.getInt();
            String typ = new String(data, off + 4, 4);
            int hdr = 8;
            if (size == 1) {
                size = (int) ByteBuffer.wrap(data, off + 8, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                hdr = 16;
            } else if (size == 0) {
                size = end - off;
            }
            if (size < hdr || off + size > end) break;
            List<Box> children = null;
            if (CONTAINER_TYPES.contains(typ)) {
                children = parseBoxes(data, off + hdr, off + size);
            }
            Box b = new Box();
            b.typ = typ; b.off = off; b.size = size; b.hdr = hdr; b.children = children;
            boxes.add(b);
            off += size;
        }
        return boxes;
    }

    private static int getInt32(byte[] data, int off) {
        return ByteBuffer.wrap(data, off, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static long getInt64(byte[] data, int off) {
        return ByteBuffer.wrap(data, off, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private static void putInt32(byte[] data, int off, int val) {
        ByteBuffer.wrap(data, off, 4).order(ByteOrder.BIG_ENDIAN).putInt(val);
    }

    private static void walkStbl(List<Box> boxes, List<Box> out) {
        for (Box b : boxes) {
            if ("stbl".equals(b.typ)) out.add(b);
            if (b.children != null) walkStbl(b.children, out);
        }
    }

    /** stsd 打补丁：encv→avc1/hvc1、enca→mp4a，去 sinf。返回 box.off -> 新内容。 */
    private static void findStsd(Box box, byte[] data, Map<Integer, byte[]> patches) {
        if ("stsd".equals(box.typ)) {
            int content = box.off + 8;
            int count = getInt32(data, content + 4);
            int p = content + 8;
            ByteArrayBuilder result = new ByteArrayBuilder();
            for (int e = 0; e < count; e++) {
                int esize = getInt32(data, p);
                String etyp = new String(data, p + 4, 4);
                if ("encv".equals(etyp) || "enca".equals(etyp)) {
                    int hdrSize = "encv".equals(etyp) ? 78 : 28;
                    String newType;
                    if ("encv".equals(etyp)) {
                        boolean isH265 = false;
                        int q2 = p + 8 + hdrSize;
                        while (q2 + 8 <= p + esize) {
                            int s3 = getInt32(data, q2);
                            String t3 = new String(data, q2 + 4, 4);
                            if ("hvcC".equals(t3)) { isH265 = true; break; }
                            if ("avcC".equals(t3)) break;
                            if (s3 <= 0) break;
                            q2 += s3;
                        }
                        newType = isH265 ? "hvc1" : "avc1";
                    } else {
                        newType = "mp4a";
                    }
                    ByteArrayBuilder entry = new ByteArrayBuilder();
                    entry.write(new byte[4], 0, 4);
                    entry.writeBytes(newType.getBytes());
                    entry.write(data, p + 8, hdrSize);
                    int q = p + 8 + hdrSize;
                    while (q + 8 <= p + esize) {
                        int s2 = getInt32(data, q);
                        String t2 = new String(data, q + 4, 4);
                        if (s2 == 1) s2 = (int) getInt64(data, q + 8);
                        else if (s2 == 0) s2 = p + esize - q;
                        if (!"sinf".equals(t2)) entry.write(data, q, s2);
                        if (s2 <= 0) break;
                        q += s2;
                    }
                    byte[] fullEntry = entry.toByteArray();
                    putInt32(fullEntry, 0, fullEntry.length);
                    result.write(fullEntry, 0, fullEntry.length);
                } else {
                    result.write(data, p, esize);
                }
                p += esize;
            }
            int hdrLen = content + 8 - box.off;
            byte[] entries = result.toByteArray();
            byte[] fullResult = new byte[hdrLen + entries.length];
            System.arraycopy(data, box.off, fullResult, 0, hdrLen);
            System.arraycopy(entries, 0, fullResult, hdrLen, entries.length);
            putInt32(fullResult, 0, fullResult.length);
            putInt32(fullResult, 12, count);
            patches.put(box.off, fullResult);
        }
        if (box.children != null) {
            for (Box c : box.children) findStsd(c, data, patches);
        }
    }

    private static byte[] rebuildBox(Box box, byte[] data, Map<Integer, byte[]> patches) {
        if (box.children == null) {
            byte[] patched = patches.get(box.off);
            if (patched != null) return patched;
            return Arrays.copyOfRange(data, box.off, box.off + box.size);
        }
        ByteArrayBuilder body = new ByteArrayBuilder();
        for (Box c : box.children) {
            if (CRYPTO_BOXES.contains(c.typ)) continue;
            byte[] sub = rebuildBox(c, data, patches);
            body.write(sub, 0, sub.length);
        }
        byte[] bodyBytes = body.toByteArray();
        ByteArrayBuilder result = new ByteArrayBuilder();
        if (box.hdr == 8) {
            byte[] hdr = new byte[8];
            putInt32(hdr, 0, 8 + bodyBytes.length);
            System.arraycopy(data, box.off + 4, hdr, 4, 4);
            result.write(hdr, 0, 8);
        } else {
            byte[] hdr = new byte[16];
            putInt32(hdr, 0, 1);
            System.arraycopy(data, box.off + 4, hdr, 4, 4);
            ByteBuffer.wrap(hdr, 8, 8).order(ByteOrder.BIG_ENDIAN).putLong(16 + bodyBytes.length);
            result.write(hdr, 0, 16);
        }
        result.write(bodyBytes, 0, bodyBytes.length);
        return result.toByteArray();
    }

    private static void findStco(Box box, byte[] data, int delta) {
        if ("stco".equals(box.typ) || "co64".equals(box.typ)) {
            int nc = getInt32(data, box.off + 12);
            if ("stco".equals(box.typ)) {
                for (int i = 0; i < nc; i++) {
                    int v = getInt32(data, box.off + 16 + i * 4);
                    if (v >= delta) putInt32(data, box.off + 16 + i * 4, v - delta);
                }
            } else {
                for (int i = 0; i < nc; i++) {
                    long v = getInt64(data, box.off + 16 + i * 8);
                    if (v >= delta) ByteBuffer.wrap(data, box.off + 16 + i * 8, 8).order(ByteOrder.BIG_ENDIAN).putLong(v - delta);
                }
            }
        }
        if (box.children != null) for (Box c : box.children) findStco(c, data, delta);
    }

    /** 对重建后的 moov 字节流内的 stco/co64 做偏移修正。
     *  moov 在文件开头（红果布局 ftyp+moov+mdat）时，moov 缩小会让其后的 mdat 整体前移 delta，
     *  stco（指向 moov 之后的样本偏移）必须减 delta；moov 在文件末尾（ffmpeg cenc 布局 ftyp+mdat+moov）时，
     *  mdat 位置不受影响，stco 保持原值。判定：仅修正 >= moovEnd 的偏移。 */
    private static void fixStcoInBytes(byte[] moovBytes, int delta, long moovEnd) {
        List<Box> top = parseBoxes(moovBytes, 0, moovBytes.length);
        for (Box b : top) walkStcoFix(b, moovBytes, delta, moovEnd);
    }

    private static void walkStcoFix(Box box, byte[] data, int delta, long moovEnd) {
        if ("stco".equals(box.typ) || "co64".equals(box.typ)) {
            int nc = getInt32(data, box.off + 12);
            if ("stco".equals(box.typ)) {
                for (int i = 0; i < nc; i++) {
                    int v = getInt32(data, box.off + 16 + i * 4);
                    if (v >= moovEnd && v >= delta) putInt32(data, box.off + 16 + i * 4, v - delta);
                }
            } else {
                for (int i = 0; i < nc; i++) {
                    long v = getInt64(data, box.off + 16 + i * 8);
                    if (v >= moovEnd && v >= delta) ByteBuffer.wrap(data, box.off + 16 + i * 8, 8).order(ByteOrder.BIG_ENDIAN).putLong(v - delta);
                }
            }
            return;
        }
        if (box.children != null) for (Box c : box.children) walkStcoFix(c, data, delta, moovEnd);
    }

    /** 修复加密容器头为普通明文 MP4。in=RemoteVideo 明文数据+加密头的文件，out=可播放明文 MP4。 */
    public static void fix(File in, File out) throws Exception {
        byte[] data;
        try (FileInputStream fis = new FileInputStream(in)) {
            data = new byte[fis.available()];
            int read = 0;
            while (read < data.length) {
                int n = fis.read(data, read, data.length - read);
                if (n <= 0) break;
                read += n;
            }
        }
        int total = data.length;
        List<Box> top = parseBoxes(data, 0, total);
        Box moov = null;
        for (Box b : top) if ("moov".equals(b.typ)) { moov = b; break; }
        if (moov == null) throw new Exception("no moov box");

        Map<Integer, byte[]> patches = new HashMap<>();
        for (Box tb : top) findStsd(tb, data, patches);

        byte[] newMoov = rebuildBox(moov, data, patches);
        int delta = moov.size - newMoov.length;
        if (delta < 0) throw new Exception("moov 重写变大，偏移修正不安全");
        // stco/co64 是绝对文件偏移；仅当 moov 位于 mdat 之前（红果布局）时，其后的 mdat 随 moov
        // 缩小整体前移 delta，样本偏移需要减 delta；moov 在 mdat 之后（ffmpeg cenc 布局）则保持不变。
        fixStcoInBytes(newMoov, delta, (long) moov.off + moov.size);

        try (FileOutputStream fos = new FileOutputStream(out)) {
            int off = 0;
            while (off < total) {
                int size = getInt32(data, off);
                String typ = new String(data, off + 4, 4);
                int hdr = 8;
                if (size == 1) {
                    size = (int) getInt64(data, off + 8);
                    hdr = 16;
                } else if (size == 0) {
                    size = total - off;
                }
                if ("moov".equals(typ)) {
                    fos.write(newMoov);
                } else {
                    fos.write(data, off, size);
                }
                off += size;
            }
        }
    }

    private static final class ByteArrayBuilder {
        private byte[] buf = new byte[64];
        private int len = 0;

        void ensure(int extra) {
            if (len + extra > buf.length) {
                int n = buf.length;
                while (n < len + extra) n <<= 1;
                buf = Arrays.copyOf(buf, n);
            }
        }

        void write(byte[] src, int off, int n) {
            ensure(n);
            System.arraycopy(src, off, buf, len, n);
            len += n;
        }

        void writeBytes(byte[] src) { write(src, 0, src.length); }

        byte[] toByteArray() { return Arrays.copyOf(buf, len); }
    }

    private ContainerFixer() {}
}
