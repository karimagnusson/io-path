package io.github.karimagnusson.io.path.utils;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;


public class Gzip {

    public static byte[] compress(byte[] data) throws IOException {

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (GZIPOutputStream gos = new GZIPOutputStream(output)) {
            gos.write(data);
        }

        return output.toByteArray();
    }

    public static byte[] decompress(byte[] data) throws IOException {

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (GZIPInputStream gis = new GZIPInputStream(
                                      new ByteArrayInputStream(data))) {

            byte[] buffer = new byte[1024];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                output.write(buffer, 0, len);
            }
        }

        return output.toByteArray();
    }
}