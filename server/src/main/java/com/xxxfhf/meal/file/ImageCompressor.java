package com.xxxfhf.meal.file;

import com.xxxfhf.meal.common.BizException;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * 上传图片压缩：限制长边 + 重编码 JPEG。
 *
 * 小程序端最大的展示位是盒饭详情封面（100% 宽 × 420rpx，3x 屏约 1035×630），
 * 故长边压到 1200 已足够，再大的像素没有任何一块屏幕用得上，只白耗流量。
 *
 * 处理顺序：降采样解码 → 铺白底转 RGB → 按 EXIF 摆正 → 等比缩小 → 编码 JPEG。
 * 顺序不能换：先摆正再缩，否则旋转后长宽会与预期不符。
 *
 * 口径：即使压完比原图大也一律存 JPEG（纯色块 PNG 会略增几 KB），
 * 库里只保留一种格式，不回退原图 —— 别当成遗漏去"修"。
 */
final class ImageCompressor {

    /** 压缩后统一扩展名。JPEG 兼容性最好，体积远小于 PNG */
    static final String OUTPUT_EXT = "jpg";

    /** 解码时最多跳过的像素倍数，防止超大图过度丢细节 */
    private static final int MAX_SUBSAMPLE = 8;

    private final int maxEdge;
    private final float quality;

    ImageCompressor(int maxEdge, float quality) {
        this.maxEdge = maxEdge;
        this.quality = quality;
    }

    byte[] compress(MultipartFile file) {
        BufferedImage raw = read(file);
        BufferedImage rgb = toRgb(raw);
        BufferedImage oriented = applyOrientation(rgb, ExifOrientation.read(file));
        BufferedImage scaled = downscale(oriented);
        return encodeJpeg(scaled);
    }

    /**
     * 降采样解码：直接按整数倍丢像素读取，避免 4000×3000 整图进堆。
     * 不做这一步，单请求峰值可达 48MB，几个并发就 OOM。
     */
    private BufferedImage read(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            ImageInputStream iis = ImageIO.createImageInputStream(in);
            if (iis == null) {
                throw new IOException("无法创建图像输入流");
            }
            try (iis) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (!readers.hasNext()) {
                    throw new IOException("没有匹配的图片解码器");
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width <= 0 || height <= 0) {
                        throw new IOException("图片尺寸异常");
                    }
                    ImageReadParam param = reader.getDefaultReadParam();
                    int step = subsampleStep(width, height);
                    if (step > 1) {
                        param.setSourceSubsampling(step, step, 0, 0);
                    }
                    return reader.read(0, param);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | RuntimeException ex) {
            throw BizException.param("图片无法解析，请更换图片");
        }
    }

    /** 解码后尺寸控制在最终尺寸的 1.5 倍左右，留余量给 bicubic 精细缩放 */
    private int subsampleStep(int width, int height) {
        long min = Math.min(width, height);
        long target = Math.max(1L, (long) (maxEdge * 1.5));
        long step = Math.round((double) min / target);
        return (int) Math.max(1L, Math.min(step, MAX_SUBSAMPLE));
    }

    /** JPEG 没有透明通道，PNG 的透明区域统一铺白，否则会变成黑块 */
    private BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /**
     * 按 EXIF Orientation 摆正。
     *
     * 用像素坐标映射而非 Graphics2D 连续变换：八种情形各写一行，肉眼可核对，
     * 不会像多次 transform 叠加那样出现"方向对了但镜像反了"的隐蔽错误。
     */
    private BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }
        int width = src.getWidth();
        int height = src.getHeight();
        boolean swap = orientation >= 5;
        int dw = swap ? height : width;
        int dh = swap ? width : height;
        int[] pixels = src.getRGB(0, 0, width, height, null, 0, width);
        int[] out = new int[dw * dh];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int nx;
                int ny;
                switch (orientation) {
                    case 2 -> {          // 水平镜像
                        nx = width - 1 - x;
                        ny = y;
                    }
                    case 3 -> {          // 旋转 180
                        nx = width - 1 - x;
                        ny = height - 1 - y;
                    }
                    case 4 -> {          // 垂直镜像
                        nx = x;
                        ny = height - 1 - y;
                    }
                    case 5 -> {          // 沿主对角线转置
                        nx = y;
                        ny = x;
                    }
                    case 6 -> {          // 顺时针 90
                        nx = height - 1 - y;
                        ny = x;
                    }
                    case 7 -> {          // 逆时针 90 后水平镜像
                        nx = height - 1 - y;
                        ny = width - 1 - x;
                    }
                    default -> {         // 8：逆时针 90
                        nx = y;
                        ny = width - 1 - x;
                    }
                }
                out[ny * dw + nx] = pixels[y * width + x];
            }
        }
        BufferedImage dst = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        dst.setRGB(0, 0, dw, dh, out, 0, dw);
        return dst;
    }

    /** 等比缩小到长边不超过 maxEdge；本来就够小则原样返回，不做放大 */
    private BufferedImage downscale(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        int longest = Math.max(width, height);
        if (longest <= maxEdge) {
            return src;
        }
        double scale = (double) maxEdge / longest;
        int tw = Math.max(1, (int) Math.round(width * scale));
        int th = Math.max(1, (int) Math.round(height * scale));
        BufferedImage dst = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private byte[] encodeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("缺少 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            ImageOutputStream ios = ImageIO.createImageOutputStream(out);
            if (ios == null) {
                throw new IOException("无法创建图像输出流");
            }
            try (ios) {
                writer.setOutput(ios);
                // metadata 传 null：不写回 EXIF，顺带去掉拍摄时间、GPS 等隐私信息
                writer.write(null, new IIOImage(image, null, null), param);
                return out.toByteArray();
            }
        } catch (IOException ex) {
            throw BizException.param("图片压缩失败，请重试");
        } finally {
            writer.dispose();
        }
    }
}
