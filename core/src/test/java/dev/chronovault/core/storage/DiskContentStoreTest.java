package dev.chronovault.core.storage;

import dev.chronovault.core.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiskContentStoreTest {

    @TempDir Path tempDir;

    @Test
    void storesAndMaterializes() throws Exception {
        DiskContentStore store = new DiskContentStore(tempDir.resolve("objects"));

        Path file = tempDir.resolve("payload.txt");
        Files.writeString(file, "hello chronovault — temporal back gärden");

        String hash = store.put(file);
        assertEquals(Hashing.hashString("hello chronovault — temporal back gärden"), hash);

        assertTrue(store.contains(hash));

        Path restored = tempDir.resolve("out.txt");
        var materialized = store.materialize(hash, restored);
        assertTrue(materialized.isPresent());
        assertEquals("hello chronovault — temporal back gärden", Files.readString(restored));
    }

    @Test
    void deduplicatesIdenticalContent() throws Exception {
        DiskContentStore store = new DiskContentStore(tempDir.resolve("objects"));
        Path a = tempDir.resolve("a.bin");
        Path b = tempDir.resolve("b.bin");
        Files.write(a, new byte[]{1, 2, 3, 4, 5});
        Files.write(b, new byte[]{1, 2, 3, 4, 5});

        String ha = store.put(a);
        String hb = store.put(b);

        assertEquals(ha, hb);
        assertEquals(1, store.count());
    }

    @Test
    void integrityVerificationDetectsCorruption() throws Exception {
        DiskContentStore store = new DiskContentStore(tempDir.resolve("objects"));
        Path file = tempDir.resolve("f.txt");
        Files.writeString(file, "content for integrity check");

        String hash = store.put(file);
        assertTrue(store.verify(hash));

        Path stored = store.getObjectPath(hash);
        Files.writeString(stored, "corrupted-garbage-not-compressed");
        assertFalse(store.verify(hash));
    }

    @Test
    void largeFileStreamsThroughMemorySafePath() throws Exception {
        DiskContentStore store = new DiskContentStore(tempDir.resolve("objects"));
        Path big = tempDir.resolve("big.bin");
        byte[] chunk = new byte[1024 * 1024];
        java.util.Arrays.fill(chunk, (byte) 0x5a);
        try (var out = Files.newOutputStream(big)) {
            for (int i = 0; i < 16; i++) out.write(chunk);  // 16 MB
        }

        String hash = store.put(big);
        assertTrue(store.contains(hash));
        assertTrue(store.verify(hash));
    }

    @Test
    void unicodeAndSpacesHandled() throws Exception {
        DiskContentStore store = new DiskContentStore(tempDir.resolve("objects"));
        Path weird = tempDir.resolve("winte-r grimpe fichier.java");
        Files.writeString(weird, "public class Winte{rgrimpe{}}", StandardCharsets.UTF_8);
        String hash = store.put(weird);
        Path out = tempDir.resolve("weird out.txt");
        store.materialize(hash, out);
        assertEquals("public class Winte{rgrimpe{}}", Files.readString(out));
    }
}