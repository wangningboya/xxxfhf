package com.xxxfhf.meal.file;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 读取 JPEG 里的 EXIF 方向标记（Orientation，tag 0x0112）。
 *
 * 为什么需要：手机竖拍的照片，像素其实是横着存的，"该转多少度"写在 EXIF 里。
 * JDK 的 ImageIO 不认这个标记，直接缩放会让商家的竖版菜品图躺倒，故先解析出来再摆正。
 *
 * 只扫文件头，不引入第三方库：方向信息永远在 APP1 段靠前位置，64KB 足够。
 */
final class ExifOrientation {

    /** EXIF 中的方向标签 */
    private static final int TAG_ORIENTATION = 0x0112;
    /** TIFF 数据类型：SHORT */
    private static final int TYPE_SHORT = 3;
    /** 单次读取上限，超过这个量还没找到就不用找了 */
    private static final int MAX_SCAN = 64 * 1024;

    private ExifOrientation() {
    }

    /**
     * @return 1~8；读不到、不是 JPEG、格式不完整一律返回 1（不旋转）
     */
    static int read(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return parse(in.readNBytes(MAX_SCAN));
        } catch (IOException ex) {
            return 1;
        }
    }

    private static int parse(byte[] b) {
        if (b.length < 4 || u8(b[0]) != 0xFF || u8(b[1]) != 0xD8) {
            return 1;
        }
        int pos = 2;
        while (pos + 4 <= b.length) {
            if (u8(b[pos]) != 0xFF) {
                pos++;
                continue;
            }
            int marker = u8(b[pos + 1]);
            // 填充字节与无长度的标记，直接跳过
            if (marker == 0xFF) {
                pos++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2;
                continue;
            }
            // 进入图像压缩数据后不会再有 EXIF
            if (marker == 0xDA || marker == 0xD9) {
                return 1;
            }
            int len = (u8(b[pos + 2]) << 8) | u8(b[pos + 3]);
            if (len < 2) {
                return 1;
            }
            int segStart = pos + 4;
            int segEnd = Math.min(pos + 2 + len, b.length);
            // APP1 里以 "Exif\0\0" 开头，其后才是 TIFF 结构
            if (marker == 0xE1 && segStart + 6 <= segEnd
                    && b[segStart] == 'E' && b[segStart + 1] == 'x'
                    && b[segStart + 2] == 'i' && b[segStart + 3] == 'f') {
                return readTiff(b, segStart + 6, segEnd);
            }
            pos = segEnd;
        }
        return 1;
    }

    private static int readTiff(byte[] b, int start, int end) {
        if (start + 8 > end) {
            return 1;
        }
        final boolean little;
        if (b[start] == 'I' && b[start + 1] == 'I') {
            little = true;
        } else if (b[start] == 'M' && b[start + 1] == 'M') {
            little = false;
        } else {
            return 1;
        }
        if (u16(b, start + 2, little) != 42) {
            return 1;
        }
        long ifd = start + u32(b, start + 4, little);
        if (ifd + 2 > end) {
            return 1;
        }
        int count = u16(b, (int) ifd, little);
        for (int i = 0; i < count; i++) {
            long entry = ifd + 2 + (long) i * 12;
            if (entry + 12 > end) {
                break;
            }
            int e = (int) entry;
            if (u16(b, e, little) != TAG_ORIENTATION) {
                continue;
            }
            if (u16(b, e + 2, little) != TYPE_SHORT) {
                return 1;
            }
            int value = u16(b, e + 8, little);
            return (value >= 1 && value <= 8) ? value : 1;
        }
        return 1;
    }

    private static int u8(byte v) {
        return v & 0xFF;
    }

    private static int u16(byte[] b, int pos, boolean little) {
        int hi = u8(b[pos]);
        int lo = u8(b[pos + 1]);
        return little ? (hi | (lo << 8)) : ((hi << 8) | lo);
    }

    private static long u32(byte[] b, int pos, boolean little) {
        long a = u8(b[pos]);
        long c = u8(b[pos + 1]);
        long d = u8(b[pos + 2]);
        long e = u8(b[pos + 3]);
        if (little) {
            return a | (c << 8) | (d << 16) | (e << 24);
        }
        return (a << 24) | (c << 16) | (d << 8) | e;
    }
}
