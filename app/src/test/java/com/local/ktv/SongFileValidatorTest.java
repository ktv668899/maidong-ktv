package com.local.ktv;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SongFileValidatorTest {
    @Test
    public void rejectsLargeButShortPreview() throws IOException {
        File file = syntheticTransportStream(25_000L);
        try {
            SongFileValidator.Result result = SongFileValidator.INSTANCE.inspect(file, true);
            assertFalse(result.getValid());
            assertTrue(result.getReason().contains("preview too short"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void acceptsFullLengthTransportStream() throws IOException {
        File file = syntheticTransportStream(180_000L);
        try {
            SongFileValidator.Result result = SongFileValidator.INSTANCE.inspect(file, true);
            assertTrue(result.getReason(), result.getValid());
        } finally {
            file.delete();
        }
    }

    private static File syntheticTransportStream(long durationMs) throws IOException {
        File file = File.createTempFile("song-validator-", ".ts");
        int packets = (int) ((6L * 1024L * 1024L) / 188L + 100L);
        try (FileOutputStream output = new FileOutputStream(file)) {
            for (int index = 0; index < packets; index++) {
                long pts = index == packets - 1 ? durationMs * 90L : 0L;
                output.write(packetWithPts(pts));
            }
        }
        return file;
    }

    private static byte[] packetWithPts(long pts) {
        byte[] packet = new byte[188];
        java.util.Arrays.fill(packet, (byte) 0xff);
        packet[0] = 0x47;
        packet[1] = 0x40;
        packet[2] = 0x00;
        packet[3] = 0x10;
        packet[4] = 0x00;
        packet[5] = 0x00;
        packet[6] = 0x01;
        packet[7] = (byte) 0xe0;
        packet[8] = 0x00;
        packet[9] = 0x00;
        packet[10] = (byte) 0x80;
        packet[11] = (byte) 0x80;
        packet[12] = 0x05;
        packet[13] = (byte) (0x21 | (((pts >>> 30) & 0x07) << 1));
        packet[14] = (byte) (pts >>> 22);
        packet[15] = (byte) ((((pts >>> 15) & 0x7f) << 1) | 1);
        packet[16] = (byte) (pts >>> 7);
        packet[17] = (byte) (((pts & 0x7f) << 1) | 1);
        return packet;
    }
}
