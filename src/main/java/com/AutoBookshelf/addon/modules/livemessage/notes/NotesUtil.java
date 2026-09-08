package com.AutoBookshelf.addon.modules.livemessage.notes;

import com.AutoBookshelf.addon.modules.livemessage.util.LivemessageUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.AutoBookshelf.addon.modules.livemessage.LiveMessage.logError;

public class NotesUtil {
    private static final Path NOTES_FILE = LivemessageUtil.LIVEMESSAGE_FOLDER.resolve("notes.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<NoteEntry>>() {
    }.getType();

    public static List<NoteEntry> load() {
        try {
            if (!Files.exists(NOTES_FILE)) {
                return new ArrayList<>();
            }
            try (FileReader reader = new FileReader(NOTES_FILE.toFile())) {
                List<NoteEntry> notes = GSON.fromJson(reader, LIST_TYPE);
                return notes != null ? notes : new ArrayList<>();
            }
        } catch (Exception e) {
            logError("Failed to load notes", e);
            return new ArrayList<>();
        }
    }

    public static void save(List<NoteEntry> notes) {
        try {
            Files.createDirectories(LivemessageUtil.LIVEMESSAGE_FOLDER);
            try (FileWriter writer = new FileWriter(NOTES_FILE.toFile())) {
                GSON.toJson(notes, LIST_TYPE, writer);
            }
        } catch (IOException e) {
            logError("Failed to save notes", e);
        }
    }
}
