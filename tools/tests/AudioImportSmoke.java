import com.jme3.gde.core.assets.AudioImportConverter;
import com.jme3.asset.AssetInfo;
import com.jme3.audio.AudioBuffer;
import com.jme3.audio.AudioKey;
import com.jme3.audio.plugins.OGGLoader;
import java.io.InputStream;
import java.nio.*;
import java.nio.file.*;
import org.bytedeco.javacv.*;

public class AudioImportSmoke {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "audio-");
        for (String ext : new String[]{"mp3", "mp4"}) {
            Path source = root.resolve("source." + ext);
            boolean video = ext.equals("mp4");
            int channels = video ? 2 : 1;
            try (FFmpegFrameRecorder out = new FFmpegFrameRecorder(source.toFile(), video ? 16 : 0, video ? 16 : 0, channels)) {
                out.setFormat(ext);
                out.setAudioCodecName(ext.equals("mp3") ? "libmp3lame" : "aac");
                out.setSampleRate(44100);
                out.setAudioBitrate(128000);
                if (video) {
                    out.setVideoCodecName("mpeg4");
                    out.setFrameRate(25);
                }
                out.start();
                ShortBuffer samples = ShortBuffer.allocate(44100 * channels);
                for (int i = 0; i < 44100; i++) {
                    short value = (short) (Math.sin(i * 2 * Math.PI * 440 / 44100) * 1000);
                    for (int channel = 0; channel < channels; channel++) {
                        samples.put(value);
                    }
                }
                samples.flip();
                out.recordSamples(44100, channels, samples);
                if (video) {
                    try (Frame frame = new Frame(16, 16, Frame.DEPTH_UBYTE, 3)) {
                        for (int i = 0; i < 25; i++) {
                            out.record(frame);
                        }
                    }
                }
                out.stop();
            }
            Path ogg = root.resolve(ext + ".ogg");
            AudioImportConverter.convert(source, ogg, 48000, 160000);
            checkPlayable(ogg, 48000);
            if (!video) {
                for (int rate : new int[]{22050, 44100, 48000}) {
                    for (int bitrate : new int[]{96000, 128000, 160000, 192000, 256000}) {
                        Path variant = root.resolve(rate + "-" + bitrate + ".ogg");
                        AudioImportConverter.convert(source, variant, rate, bitrate);
                        checkPlayable(variant, rate);
                    }
                }
            }
            if (!Files.exists(source)) {
                throw new AssertionError("Original lost");
            }
        }
        Path legacy = root.resolve("legacy.mp3.ogg");
        try (FFmpegFrameRecorder out = new FFmpegFrameRecorder(legacy.toFile(), 2)) {
            out.setFormat("ogg");
            out.setAudioCodecName("flac");
            out.setSampleRate(44100);
            out.start();
            out.recordSamples(44100, 2, ShortBuffer.allocate(44100 * 2));
            out.stop();
        }
        var repair = AudioImportConverter.class.getDeclaredMethod("repair", Path.class);
        repair.setAccessible(true);
        repair.invoke(null, legacy);
        checkPlayable(legacy, 44100);
        byte[] repaired = Files.readAllBytes(legacy);
        try {
            repair.invoke(null, legacy);
            throw new AssertionError("Repair must reject existing Vorbis");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            if (!(expected.getCause() instanceof java.io.IOException)
                    || !java.util.Arrays.equals(repaired, Files.readAllBytes(legacy))) {
                throw expected;
            }
        }
        Path invalid = root.resolve("bad.mp4");
        Path silent = root.resolve("silent.mp4");
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(silent.toFile(), 16, 16, 0);
                Frame frame = new Frame(16, 16, Frame.DEPTH_UBYTE, 3)) {
            recorder.setFormat("mp4");
            recorder.setVideoCodecName("mpeg4");
            recorder.start();
            recorder.record(frame);
            recorder.stop();
        }
        try {
            AudioImportConverter.convert(silent, root.resolve("silent.ogg"), 44100, 160000);
            throw new AssertionError("Video without audio accepted");
        } catch (java.io.IOException expected) {
            if (Files.exists(root.resolve("silent.ogg"))) {
                throw new AssertionError("Silent video left partial output");
            }
        }
        Files.writeString(invalid, "not media");
        Path failed = root.resolve("bad.ogg");
        try {
            AudioImportConverter.convert(invalid, failed, 44100, 160000);
            throw new AssertionError("Invalid input accepted");
        } catch (java.io.IOException expected) {
            if (Files.exists(failed)) {
                throw new AssertionError("Partial output survived");
            }
        }
        System.out.println("JNI_AUDIO_IMPORT_PASS");
    }

    private static void checkPlayable(Path path, int rate) throws Exception {
        try (InputStream source = Files.newInputStream(path)) {
            var decoded = (AudioBuffer) new OGGLoader().load(new AssetInfo(null,
                    new AudioKey(path.getFileName().toString(), false)) {
                @Override
                public InputStream openStream() {
                    return source;
                }
            });
            if (decoded.getSampleRate() != rate || decoded.getDuration() < .9f) {
                throw new AssertionError("jME3 could not decode the complete OGG: " + path);
            }
        }
    }
}
