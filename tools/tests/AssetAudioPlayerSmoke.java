package com.jme3.gde.assetbrowser;

import com.jme3.audio.AudioBuffer;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import org.openide.util.RequestProcessor;

/** Silent native Java Sound lifecycle regression. */
public final class AssetAudioPlayerSmoke {
    public static void main(String[] args) throws Exception {
        AssetAudioPlayer player = new AssetAudioPlayer();
        AudioBuffer buffer = new AudioBuffer();
        buffer.setupFormat(2, 16, 44100);
        buffer.updateData(ByteBuffer.allocateDirect(44100 * 4));
        var field = AssetAudioPlayer.class.getDeclaredField("worker");
        field.setAccessible(true);
        RequestProcessor worker = (RequestProcessor) field.get(player);
        var clipField = AssetAudioPlayer.class.getDeclaredField("clip");
        clipField.setAccessible(true);
        AtomicReference<Exception> failure = new AtomicReference<>();
        for (int i = 0; i < 4; i++) {
            player.play(buffer, failure::set);
            worker.post(() -> {}).waitFinished();
            if (failure.get() != null || clipField.get(player) == null) {
                throw new AssertionError("Preview failed", failure.get());
            }
            player.stop();
            worker.post(() -> {}).waitFinished();
            if (clipField.get(player) != null) {
                throw new AssertionError("Preview line leaked");
            }
        }
        player.play(buffer, failure::set);
        player.stop();
        worker.post(() -> {}).waitFinished();
        if (clipField.get(player) != null) {
            throw new AssertionError("Stale play survived close");
        }
        System.out.println("ASSET_AUDIO_PLAYER_PASS");
        System.exit(0);
    }
}
