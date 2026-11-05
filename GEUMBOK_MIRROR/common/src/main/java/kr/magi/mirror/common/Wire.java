package kr.magi.mirror.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Framing is inside authenticated TLS. No receiver-to-sender application messages exist. */
public final class Wire {
    public static final int MAGIC = 0x47424d31;
    public static final int CONFIG = 1, FRAME = 2, MAX_PACKET = 512 * 1024;

    public static final class Packet {
        public final int type, flags;
        public final long sequence, presentationUs;
        public final byte[] data;
        public Packet(int type, int flags, long sequence, long presentationUs, byte[] data) {
            this.type = type; this.flags = flags; this.sequence = sequence;
            this.presentationUs = presentationUs; this.data = data;
        }
    }

    public static void write(DataOutputStream output, Packet packet) throws IOException {
        validate(packet.type, packet.flags, packet.data.length);
        output.writeInt(MAGIC);
        output.writeByte(packet.type);
        output.writeByte(packet.flags);
        output.writeLong(packet.sequence);
        output.writeLong(packet.presentationUs);
        output.writeInt(packet.data.length);
        output.write(packet.data);
        output.flush();
    }

    public static Packet read(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) throw new ProtocolException("Invalid framing");
        int type = input.readUnsignedByte();
        int flags = input.readUnsignedByte();
        long sequence = input.readLong();
        long time = input.readLong();
        int length = input.readInt();
        validate(type, flags, length);
        if (sequence < 0 || time < 0) throw new ProtocolException("Invalid frame metadata");
        byte[] data = new byte[length];
        input.readFully(data);
        return new Packet(type, flags, sequence, time, data);
    }

    private static void validate(int type, int flags, int length) throws IOException {
        if ((type != CONFIG && type != FRAME) || flags < 0 || flags > 1 || length < 1 || length > MAX_PACKET) {
            throw new ProtocolException("Invalid packet bounds");
        }
    }

    public static final class ProtocolException extends IOException {
        public ProtocolException(String message) {super(message);}
    }

    private Wire() { }
}
