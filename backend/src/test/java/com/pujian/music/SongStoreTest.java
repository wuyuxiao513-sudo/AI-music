package com.pujian.music;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SongStoreTest {
    @TempDir Path temp;

    @Test
    void savesEditsAndReloadsSongs() {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var store = new SongStore(temp.toString(), mapper);
        var original = store.create(new SongInput("夏夜", "流行", "温暖", "海边", "晚风", "第一句", "钢琴"));
        var edited = store.update(original.id(), new SongInput("夏夜重写", "民谣", "治愈", "海边", "晚风", "修改后的歌词", "木吉他"));
        var reloaded = new SongStore(temp.toString(), mapper).find(original.id());

        assertEquals("夏夜重写", reloaded.title());
        assertEquals("修改后的歌词", reloaded.lyrics());
        assertEquals(original.createdAt(), reloaded.createdAt());
        assertEquals(edited.updatedAt(), reloaded.updatedAt());
        assertEquals(1, store.all().size());
    }

    @Test
    void separatesSongsAndDeletesOnlySelectedOne() {
        var store = new SongStore(temp.toString(), new ObjectMapper().registerModule(new JavaTimeModule()));
        var first = store.create(new SongInput("第一首", "说唱", "快乐", "", "", "", ""));
        var second = store.create(new SongInput("第二首", "古典", "宁静", "", "", "", ""));
        store.delete(first.id());
        assertEquals(second.id(), store.all().get(0).id());
        assertThrows(ApiException.class, () -> store.find(first.id()));
    }

    @Test
    void corruptSongsFileIsBackedUpAndDegradesToEmptyLibrary() throws IOException {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Files.createDirectories(temp);
        Files.writeString(temp.resolve("songs.json"), "{ this is not valid json ]");

        var reopened = new SongStore(temp.toString(), mapper);
        assertTrue(reopened.all().isEmpty(), "损坏文件应被降级为空库，而不是抛 500");

        long backups;
        try (var entries = Files.list(temp)) {
            backups = entries.filter(p -> p.getFileName().toString().startsWith("songs.corrupted-")).count();
        }
        assertEquals(1, backups, "原损坏文件应被重命名备份");
        assertFalse(Files.exists(temp.resolve("songs.json")), "主 songs.json 不应保留损坏内容");

        // 降级后仍可正常写入新作品，服务不瘫痪。
        var created = reopened.create(new SongInput("新开始", "流行", "温暖", "", "", "", ""));
        assertEquals(1, new SongStore(temp.toString(), mapper).all().size());
        assertNotNull(created.id());
    }

    @Test
    void rejectsIdsContainingPathSeparators() {
        var store = new SongStore(temp.toString(), new ObjectMapper().registerModule(new JavaTimeModule()));
        assertThrows(ApiException.class, () -> store.find("../etc/passwd"));
        assertThrows(ApiException.class, () -> store.find(".."));
        assertThrows(ApiException.class, () -> store.find("a/b"));
    }
}
