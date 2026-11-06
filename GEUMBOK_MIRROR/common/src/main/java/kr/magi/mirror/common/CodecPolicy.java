package kr.magi.mirror.common;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.os.Build;
import java.util.Locale;

public final class CodecPolicy {
    public static String requireHardware(MediaCodec codec) {
        MediaCodecInfo info=codec.getCodecInfo();
        String name=info.getName();
        String lower=name.toLowerCase(Locale.ROOT);
        if(Build.VERSION.SDK_INT>=29) {
            if(!info.isHardwareAccelerated()) throw new IllegalStateException("Hardware AVC codec required");
        } else if(lower.startsWith("omx.google.") || lower.startsWith("c2.android.")) {
            throw new IllegalStateException("Software AVC codec rejected");
        }
        // Before API29, a vendor codec name still requires physical verification.
        return name.replaceAll("[^a-zA-Z0-9._-]", "");
    }
    private CodecPolicy() { }
}
