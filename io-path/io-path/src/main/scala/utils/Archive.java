package io.github.karimagnusson.io.path.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.stream.Stream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;


public class Archive {

    public static void gzip(Path inPath, Path outPath) throws IOException {

        try (InputStream in = Files.newInputStream(inPath);
             OutputStream fout = Files.newOutputStream(outPath);
             BufferedOutputStream out = new BufferedOutputStream(fout);
             GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(out)) {
            
            final byte[] buffer = new byte[1024];
            int n = 0;
            while (-1 != (n = in.read(buffer))) {
                gzOut.write(buffer, 0, n);
            }
        }
    }

    public static void ungzip(Path inPath, Path outPath) throws IOException {

        try (InputStream fin = Files.newInputStream(inPath);
             BufferedInputStream in = new BufferedInputStream(fin);
             OutputStream out = Files.newOutputStream(outPath);
             GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in)) {
            
            final byte[] buffer = new byte[1024];
            int n = 0;
            while (-1 != (n = gzIn.read(buffer))) {
                out.write(buffer, 0, n);
            }
        }
    }

    public static void tar(Path inDir, Path outFile, boolean isGzip) throws IOException {

        try (BufferedOutputStream bos = new BufferedOutputStream(
                new FileOutputStream(outFile.toFile()));
             TarArchiveOutputStream tos = new TarArchiveOutputStream(
                isGzip ? new GzipCompressorOutputStream(bos) : bos)) {

            Stream<Path> paths = Files.walk(inDir);

            for (Path path : paths.toList()) {

                if (path.toFile().isHidden()) {
                    continue;
                }

                TarArchiveEntry tarEntry = new TarArchiveEntry(
                                                  path.toFile(),
                                                  inDir.getParent().relativize(path).toString());

                tos.putArchiveEntry(tarEntry);
                if (path.toFile().isFile()) {
                    tos.write(Files.readAllBytes(path));
                }
                tos.closeArchiveEntry();
            }
            tos.finish();
        }
    }

    public static void untar(Path inFile, Path outDir, boolean isGzip) throws IOException {
        
        try (BufferedInputStream bis = new BufferedInputStream(
                new FileInputStream(inFile.toFile()));
             TarArchiveInputStream tar = new TarArchiveInputStream(
                isGzip ? new GzipCompressorInputStream(bis) : bis)) {
            
            Files.createDirectories(outDir);

            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {

                Path dest = outDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(tar, dest);
                }
            }
        }
    }
}






