package dev.wacamera.recorder;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared by orderly recording completion and explicit interrupted-session recovery. */
final class RecordingFinalizer {
    private RecordingFinalizer() {}

    record Video(int width, int height, int fps, long frames, long startNanos) {
        Video {
            if (width < 16 || height < 16 || width > 1920 || height > 1080 || (width & 1) != 0 || (height & 1) != 0
                    || fps < 1 || fps > 60 || frames <= 0 || frames > (long) fps * 3601) throw new IllegalArgumentException("Invalid video metadata");
        }
        double seconds() { return (double) frames / fps; }
    }

    static List<String> command(Path ffmpeg, Path session, Path target, Video video,
                                AudioCapture.Metadata audio) throws IOException {
        if (Files.getFileStore(session).getUsableSpace() < Files.size(session.resolve("video.fragmented.mp4")) + 64L * 1024 * 1024) {
            throw new IOException("Not enough disk space to finalize the recording");
        }
        List<String> command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "warning", "-nostdin", "-n",
                "-i", session.resolve("video.fragmented.mp4").toString()));
        if (audio == null) {
            command.addAll(List.of("-map", "0:v:0", "-c:v", "copy", "-an"));
        } else {
            command.addAll(List.of("-i", session.resolve("audio.wav").toString()));
            double offset = audio.offsetSeconds(video.startNanos());
            if (!Double.isFinite(offset) || Math.abs(offset) > 3601) throw new IOException("Invalid audio/video clock offset");
            StringBuilder filter = new StringBuilder("[1:a]atempo=").append(number(audio.tempoRatio()));
            if (offset < 0) filter.append(",atrim=start=").append(number(-offset));
            filter.append(",asetpts=PTS-STARTPTS");
            if (offset > 0) filter.append(",adelay=").append(Math.round(offset * 48_000)).append("S:all=1");
            filter.append(",apad,atrim=duration=").append(number(video.seconds())).append("[aligned]");
            command.addAll(List.of("-filter_complex", filter.toString(), "-map", "0:v:0", "-map", "[aligned]", "-c:v", "copy",
                    "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2"));
        }
        command.addAll(List.of("-t", number(video.seconds()), "-movflags", "+faststart", target.toString()));
        return command;
    }

    static void validate(Path ffprobe, Path file, Path reportDirectory, Video expected, boolean audioExpected) throws IOException {
        Path report = reportDirectory.resolve("ffprobe.json");
        ProcessTools.run(List.of(ffprobe.toString(), "-v", "error", "-show_entries",
                "stream=codec_type,codec_name,width,height,nb_frames,duration:format=duration", "-of", "json", file.toString()),
                report, reportDirectory.resolve("ffprobe-stderr.log"), Duration.ofSeconds(30));
        try {
            JsonObject json = new Gson().fromJson(Files.readString(report), JsonObject.class);
            boolean videoFound = false, audioFound = false, audioPresent = false;
            for (JsonElement element : json.getAsJsonArray("streams")) {
                JsonObject stream = element.getAsJsonObject();
                String type = stream.get("codec_type").getAsString();
                if (type.equals("video")) {
                    videoFound = stream.get("codec_name").getAsString().equals("h264")
                            && stream.get("width").getAsInt() == expected.width() && stream.get("height").getAsInt() == expected.height();
                    if (!stream.has("nb_frames") || stream.get("nb_frames").getAsString().equals("N/A")
                            || stream.get("nb_frames").getAsLong() != expected.frames()) throw new IOException("Saved video frame count does not match the recording");
                } else if (type.equals("audio")) {
                    audioPresent = true;
                    audioFound = stream.get("codec_name").getAsString().equals("aac");
                }
            }
            if (!videoFound || (audioExpected && !audioFound)) throw new IOException("Saved MP4 is missing an expected H.264/AAC stream");
            if (!audioExpected && audioPresent) throw new IOException("Saved silent MP4 unexpectedly contains an audio stream");
            double length = json.getAsJsonObject("format").get("duration").getAsDouble();
            if (!Double.isFinite(length) || Math.abs(length - expected.seconds()) > 0.15) throw new IOException("Saved MP4 duration does not match the recording");
        } catch (RuntimeException e) { throw new IOException("Could not verify the saved MP4", e); }
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.9f", value); }
}
