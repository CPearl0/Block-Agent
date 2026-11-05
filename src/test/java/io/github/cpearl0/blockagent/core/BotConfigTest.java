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

    @Test
    void renderedEvidenceBytesDefaultsWithoutRewritingExistingFiles() throws Exception {
        Path created = directory.resolve("fresh.json");
        BotConfig fresh = BotConfig.load(created);
        assertEquals(131072, fresh.agent().maxEvidenceBytes());
        assertEquals(
                BotConfig.Agent.DEFAULT_RENDERED_EVIDENCE_BYTES,
                fresh.agent().renderedEvidenceBytes());
        assertEquals(
                BotConfig.Agent.DEFAULT_RENDERED_EVIDENCE_BYTES,
                JsonSupport.readObject(created, 100_000)
                        .getAsJsonObject("agent")
                        .get("renderedEvidenceBytes")
                        .getAsInt());
        assertTrue(fresh.survival().autoResumeAfterDeath());
        assertTrue(fresh.survival().hostThreatPolicy());
        assertEquals(8, fresh.survival().maxDeathResumes());

        Path existing = directory.resolve("legacy-agent.json");
        byte[] body =
                """
                {"provider":{"baseUrl":"http://127.0.0.1/v1","model":"m"},"agent":{"maxEvidenceBytes":131072},"survival":{"autoResumeAfterDeath":true,"hostThreatPolicy":true,"maxDeathResumes":8}}
                """
                        .stripIndent()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(existing, body);
        BotConfig legacy = BotConfig.load(existing);
        assertEquals(131072, legacy.agent().maxEvidenceBytes());
        assertEquals(
                BotConfig.Agent.DEFAULT_RENDERED_EVIDENCE_BYTES,
                legacy.agent().renderedEvidenceBytes());
        assertTrue(legacy.survival().autoResumeAfterDeath());
        assertTrue(legacy.survival().hostThreatPolicy());
        assertEquals(8, legacy.survival().maxDeathResumes());
        assertArrayEquals(body, Files.readAllBytes(existing));

        Path tuned = directory.resolve("tuned.json");
        JsonSupport.writeAtomic(
                tuned, JsonSupport.of("agent", JsonSupport.of("renderedEvidenceBytes", 12288)));
        BotConfig explicit = BotConfig.load(tuned);
        assertEquals(12288, explicit.agent().renderedEvidenceBytes());
        assertEquals(131072, explicit.agent().maxEvidenceBytes());
        assertTrue(explicit.survival().hostThreatPolicy());
    }
}
