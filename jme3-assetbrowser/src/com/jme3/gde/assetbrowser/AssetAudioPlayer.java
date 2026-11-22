package com.jme3.gde.assetbrowser;

import com.jme3.audio.AudioBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.sound.sampled.*;
import org.openide.util.RequestProcessor;

/** Browser-only PCM playback, independent of the scene's OpenAL context.
 * 第一步：后台解码 OGG/WAV 后调用 play；第二步：停止、换资产或关闭窗口调用 stop。
 * Device access runs on a serial worker; stale play requests are discarded.
 */
final class AssetAudioPlayer {
    private final RequestProcessor worker = new RequestProcessor("Asset audio preview", 1, true);
    private final AtomicLong generation = new AtomicLong();
    private Clip clip;

    void play(AudioBuffer buffer, Consumer<Exception> failure) {
        long ticket = generation.incrementAndGet();
        worker.post(() -> {
            close();
            if (generation.get() != ticket) {
                return;
            }
            try {
                var data = buffer.getData().duplicate();
                data.rewind();
                byte[] pcm = new byte[data.remaining()];
                data.get(pcm);
                AudioFormat format = new AudioFormat(buffer.getSampleRate(), buffer.getBitsPerSample(),
                        buffer.getChannels(), buffer.getBitsPerSample() != 8, false);
                clip = AudioSystem.getClip();
                clip.open(format, pcm, 0, pcm.length);
                Clip playing = clip;
                playing.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP
                            && playing.getFramePosition() >= playing.getFrameLength()) {
                        worker.post(() -> {
                            if (clip == playing) {
                                close();
                            }
                        });
                    }
                });
                if (generation.get() == ticket) {
                    clip.start();
                } else {
                    close();
                }
            } catch (Exception error) {
                close();
                if (generation.get() == ticket) {
                    failure.accept(error);
                }
            }
        });
    }

    void stop() {
        generation.incrementAndGet();
        worker.post(this::close);
    }

    private void close() {
        if (clip != null) {
            clip.stop();
            clip.close();
            clip = null;
        }
    }
}
