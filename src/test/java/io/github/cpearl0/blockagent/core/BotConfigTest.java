package io.github.cpearl0.blockagent.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotConfigTest {
    @TempDir Path directory;

    @Test
    void missingSurvivalSectionResumesByDefaultAndExplicitFlagsAreKept() throws Exception {
        Path created = directory.resolve("missing.json");
        BotConfig defaults = BotConfig.load(created);
        assertTrue(defaults.survival().autoResumeAfterDeath());
        assertTrue(defaults.survival().hostThreatPolicy());
        assertEquals(8, defaults.survival().maxDeathResumes());
        JsonObject written = JsonSupport.readObject(created, 100_000);
        assertTrue(written.getAsJsonObject("survival").get("autoResumeAfterDeath").getAsBoolean());
        assertTrue(written.getAsJsonObject("survival").get("hostThreatPolicy").getAsBoolean());
        assertEquals(8, written.getAsJsonObject("survival").get("maxDeathResumes").getAsInt());

        Path existing = directory.resolve("old.json");
        byte[] body =
                """
                {"provider":{"baseUrl":"http://127.0.0.1/v1","model":"m"},"customUserField":"keep"}
                """
                        .stripIndent()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(existing, body);
        BotConfig loaded = BotConfig.load(existing);
        assertTrue(loaded.survival().autoResumeAfterDeath());
        assertTrue(loaded.survival().hostThreatPolicy());
        assertEquals(8, loaded.survival().maxDeathResumes());
        assertArrayEquals(body, Files.readAllBytes(existing));

        Path off = directory.resolve("off.json");
        JsonSupport.writeAtomic(
                off,
                JsonSupport.of(
                        "survival",
                        JsonSupport.of(
                                "autoResumeAfterDeath",
                                false,
                                "hostThreatPolicy",
                                false,
                                "maxDeathResumes",
                                2)));
        BotConfig disabled = BotConfig.load(off);
        assertFalse(disabled.survival().autoResumeAfterDeath());
        assertFalse(disabled.survival().hostThreatPolicy());
        assertEquals(2, disabled.survival().maxDeathResumes());
    }
}
