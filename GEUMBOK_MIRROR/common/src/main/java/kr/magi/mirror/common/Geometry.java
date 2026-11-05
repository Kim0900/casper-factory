package kr.magi.mirror.common;

public final class Geometry {
    public enum Mode { FIT, FILL, REGION }
    public final float scale, translateX, translateY;

    private Geometry(float scale, float translateX, float translateY) {
        this.scale = scale;
        this.translateX = translateX;
        this.translateY = translateY;
    }

    public static Geometry calculate(int sourceWidth, int sourceHeight, int viewWidth, int viewHeight,
                                     Mode mode, float centerX, float centerY, float zoom) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewWidth <= 0 || viewHeight <= 0
                || !Float.isFinite(centerX) || !Float.isFinite(centerY) || !Float.isFinite(zoom)
                || zoom < 1 || zoom > 4 || centerX < 0 || centerX > 1 || centerY < 0 || centerY > 1) {
            throw new IllegalArgumentException("Invalid display geometry");
        }
        float regionWidth = sourceWidth;
        float regionHeight = sourceHeight;
        float left = 0, top = 0;
        if (mode == Mode.REGION) {
            regionWidth /= zoom;
            regionHeight /= zoom;
            left = Math.max(0, Math.min(sourceWidth - regionWidth, centerX * sourceWidth - regionWidth / 2));
            top = Math.max(0, Math.min(sourceHeight - regionHeight, centerY * sourceHeight - regionHeight / 2));
        }
        float horizontal = viewWidth / regionWidth;
        float vertical = viewHeight / regionHeight;
        float scale = mode == Mode.FILL ? Math.max(horizontal, vertical) : Math.min(horizontal, vertical);
        return new Geometry(scale, (viewWidth - regionWidth * scale) / 2 - left * scale,
                (viewHeight - regionHeight * scale) / 2 - top * scale);
    }
}
