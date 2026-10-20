package client;

import javafx.animation.PauseTransition;
import javafx.scene.media.AudioClip;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// Nhạc nền và hiệu ứng âm thanh dùng chung trạng thái bật/tắt âm thanh.
final class GameAudio {
    private static final Map<String, Double> LEVELS =
            Map.of(
                    "click",
                    .68,
                    "key",
                    .30,
                    "guess",
                    .56,
                    "invite",
                    .40,
                    "hint",
                    .34,
                    "turn",
                    .35,
                    "warning",
                    .36,
                    "countdown",
                    .25,
                    "win",
                    .62,
                    "lose",
                    .58);
    private final Map<String, AudioClip> clips = new HashMap<>();
    private final Map<String, Long> lastPlayed = new HashMap<>();
    private final Set<String> unavailable = new HashSet<>();
    private boolean muted;
    private boolean resultCue;
    private boolean musicUnavailable;
    private MediaPlayer music;
    private PauseTransition musicRestore;

    void play(String name) {
        if (name.equals("win") || name.equals("lose")) {
            stop();
            resultCue = true;
            resetMusicVolume();
            if (music != null) music.pause();
        }
        if (muted || !LEVELS.containsKey(name) || unavailable.contains(name)) return;
        long now = System.nanoTime();
        long gap = name.equals("key") ? 65_000_000L : 100_000_000L;
        if (now - lastPlayed.getOrDefault(name, 0L) < gap) return;
        try {
            AudioClip clip = clips.get(name);
            if (clip == null) {
                var resource = getClass().getResource("/sound/" + name + ".wav");
                if (resource == null) throw new IllegalStateException("Thiếu hiệu ứng: " + name);
                clip = new AudioClip(resource.toExternalForm());
                clips.put(name, clip);
            }
            clip.stop();
            clip.play(LEVELS.get(name));
            if (name.equals("guess")) duckMusic();
            lastPlayed.put(name, now);
        } catch (RuntimeException ex) {
            unavailable.add(name);
            System.err.println("Không phát được âm thanh (" + name + "): " + ex.getMessage());
        }
    }

    void resumeMusic() {
        resultCue = false;
        resetMusicVolume();
        if (musicUnavailable) return;
        try {
            if (music == null) {
                var resource = getClass().getResource("/sound/background.mp3");
                if (resource == null) throw new IllegalStateException("Thiếu nhạc nền");
                music = new MediaPlayer(new Media(resource.toExternalForm()));
                music.setCycleCount(MediaPlayer.INDEFINITE);
                music.setVolume(.12);
                music.setOnError(
                        () -> System.err.println("Không phát được nhạc nền: " + music.getError()));
            }
            if (!muted) music.play();
        } catch (RuntimeException ex) {
            musicUnavailable = true;
            System.err.println("Không phát được nhạc nền: " + ex.getMessage());
        }
    }

    void mute(boolean value) {
        muted = value;
        if (value) {
            stop();
            resetMusicVolume();
            if (music != null) music.pause();
        } else if (!resultCue && music != null) music.play();
    }

    private void duckMusic() {
        if (music == null || muted || resultCue) return;
        resetMusicVolume();
        music.setVolume(.045);
        musicRestore = new PauseTransition(Duration.millis(1050));
        musicRestore.setOnFinished(e -> resetMusicVolume());
        musicRestore.play();
    }

    private void resetMusicVolume() {
        if (musicRestore != null) {
            musicRestore.stop();
            musicRestore = null;
        }
        if (music != null) music.setVolume(.12);
    }

    void stop() {
        clips.values().forEach(AudioClip::stop);
    }

    void close() {
        stop();
        resetMusicVolume();
        if (music != null) music.dispose();
    }
}
