package com.jme3.gde.core.assets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import com.jme3.asset.AssetInfo;
import com.jme3.audio.AudioKey;
import com.jme3.audio.AudioStream;
import com.jme3.audio.plugins.OGGLoader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VORBIS;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name;

/** In-process FFmpeg JNI audio import. No command-line executable or runtime download.
 * The first audio stream is decoded; MP4 video is not exported. Channels above stereo
 * are downmixed. FFmpeg's built-in Vorbis encoder requires stereo, so mono is
 * duplicated when libvorbis is unavailable. The caller owns a worker thread
 * and private staging destination.
 * 第一步：在导入对话框选择 OGG 采样率和码率，默认 44100 Hz、160 kbps。
 * 第二步：工作线程调用 convert；无音轨或失败时删除临时输出并保留源文件。
 * 第三步：通过 jME3 OGGLoader 验证后才提交 OGG，游戏无需 JNI 编码器。
 * 转换在 SDK 状态栏显示进度；已知时长时按解码时间显示百分比，未知时长显示活动条。
 */
public final class AudioImportConverter {
    private AudioImportConverter() {
    }

    /** Repairs a previous import that wrote FLAC in an Ogg container.
     * 第一步：在资源菜单选择 Repair converted OGG。
     * 第二步：后台确认文件为 Ogg FLAC，验证新 Vorbis 后原位替换。
     * 文件名及现有引用保留。失败时原文件仍可用来重试。
     */
    static void repair(Path source) throws IOException {
        byte[] page;
        try (InputStream input = Files.newInputStream(source)) {
            page = input.readNBytes(27 + 255 + 5);
        }
        if (page.length < 33 || page[0] != 'O' || page[1] != 'g' || page[2] != 'g'
                || page[3] != 'S' || (page[5] & 2) == 0) {
            throw new IOException("Expected an Ogg audio file");
        }
        int packet = 27 + Byte.toUnsignedInt(page[26]);
        if (packet + 5 > page.length || page[packet] != 0x7f || page[packet + 1] != 'F'
                || page[packet + 2] != 'L' || page[packet + 3] != 'A' || page[packet + 4] != 'C') {
            throw new IOException("Repair applies only to Ogg FLAC from the earlier converter");
        }
        var original = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes.class);
        Path staging = Files.createTempDirectory(source.getParent(), ".ogg-repair-");
        Path output = staging.resolve("repaired.ogg");
        try {
            convert(source, output, 44100, 160000);
            var current = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes.class);
            if (original.size() != current.size() || !original.lastModifiedTime().equals(current.lastModifiedTime())
                    || !java.util.Objects.equals(original.fileKey(), current.fileKey())) {
                throw new IOException("The original audio changed during repair");
            }
            Files.move(output, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(staging);
        }
    }

    public static void convert(Path source, Path output, int sampleRate, int bitRate) throws IOException {
        if (Files.exists(output)) {
            throw new IOException("Audio output already exists: " + output);
        }
        if (sampleRate < 8000 || sampleRate > 96000 || bitRate < 32000 || bitRate > 320000) {
            throw new IllegalArgumentException("Unsupported audio rate");
        }
        boolean complete = false;
        var progress = org.netbeans.api.progress.ProgressHandle.createHandle("Audio to OGG: " + source.getFileName());
        progress.setInitialDelay(0);
        progress.start();
        progress.progress("Opening audio");
        try (FFmpegFrameGrabber input = new FFmpegFrameGrabber(source.toFile())) {
            input.setOption("protocol_whitelist", "file,pipe");
            input.setVideoStream(-1);
            input.start();
            long duration = input.getLengthInTime();
            if (duration > 0) {
                progress.switchToDeterminate(100);
            }
            int reported = -1;
            int channels = Math.min(2, input.getAudioChannels());
            if (channels < 1) {
                throw new IOException("No audio track in " + source.getFileName());
            }
            var libvorbis = avcodec_find_encoder_by_name("libvorbis");
            boolean useLibvorbis = libvorbis != null && !libvorbis.isNull();
            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output.toFile(), useLibvorbis ? channels : 2)) {
                recorder.setFormat("ogg");
                // JavaCV may fall back to the container's default FLAC encoder if only the name is set.
                recorder.setAudioCodec(AV_CODEC_ID_VORBIS);
                recorder.setAudioCodecName(useLibvorbis ? "libvorbis" : "vorbis");
                if (!useLibvorbis) {
                    recorder.setAudioOption("strict", "experimental");
                }
                recorder.setSampleRate(sampleRate);
                recorder.setAudioBitrate(bitRate);
                recorder.start();
                long frames = 0;
                Frame frame;
                while ((frame = input.grabSamples()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.io.InterruptedIOException("Audio import cancelled");
                    }
                    if (frame.samples != null) {
                        recorder.recordSamples(frame.sampleRate, frame.audioChannels, frame.samples);
                        frames++;
                        if (duration > 0) {
                            int percent = (int) Math.max(0, Math.min(99,
                                    100.0 * input.getTimestamp() / duration));
                            if (percent > reported) {
                                reported = percent;
                                progress.progress("Encoding audio: " + percent + "%", percent);
                            }
                        }
                    }
                }
                recorder.stop();
                if (frames == 0) {
                    throw new IOException("No decodable audio samples in " + source.getFileName());
                }
            }
            progress.progress("Verifying OGG output");
            verifyVorbis(output);
            complete = true;
        } catch (Exception | LinkageError failure) {
            complete = false;
            throw new IOException("Cannot convert audio: " + failure.getMessage(), failure);
        } finally {
            progress.finish();
            if (!complete) {
                Files.deleteIfExists(output);
            }
        }
    }

    private static void verifyVorbis(Path output) throws IOException {
        // Read a bounded PCM block through the same decoder as editor preview and game playback.
        try (InputStream source = Files.newInputStream(output)) {
            AudioStream stream = (AudioStream) new OGGLoader().load(new AssetInfo(null,
                    new AudioKey(output.getFileName().toString(), true, false)) {
                @Override
                public InputStream openStream() {
                    return source;
                }
            });
            try {
                if (stream.readSamples(new byte[4096], 0, 4096) <= 0) {
                    throw new IOException("The Vorbis output contains no playable samples");
                }
            } finally {
                stream.close();
            }
        }
    }
}
