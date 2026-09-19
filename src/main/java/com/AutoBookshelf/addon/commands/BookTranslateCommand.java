package com.AutoBookshelf.addon.commands;

import com.AutoBookshelf.addon.utils.BookUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BookTranslateCommand extends Command {

    // Bounded pool: a few pages can translate concurrently
    // without unboundedly hammering the free endpoint
    // the way a fully-parallel commonPool() dispatch could.
    private static final int TRANSLATE_POOL_SIZE = 2;
    private static final ExecutorService TRANSLATE_POOL = Executors.newFixedThreadPool(TRANSLATE_POOL_SIZE);

    private static final int MAX_TRANSLATE_ATTEMPTS = 8;

    // Global request throttle: no two page requests may actually start closer
    // than this apart (millis + random jitter). Even a small pool will 429 the
    // free endpoint if every page fires back-to-back.
    private static final long REQUEST_MIN_INTERVAL_MILLIS = 250;
    private static final long REQUEST_INTERVAL_JITTER = 250;

    // Cap for the exponential retry backoff; Google's free endpoint can stay
    // rate-limited for a while, so let retries keep trying without growing unbounded.
    private static final long REQUEST_MAX_RETRY_DELAY_MILLIS = 30_000;

    private static final Random RANDOM = new Random();
    private static final AtomicLong LAST_REQUEST_START_NANOS = new AtomicLong();

    // Codes Google Translate accepts for the `tl` parameter (ISO 639-1 alpha-2).
    private static final List<Language> LANGUAGES = List.of(
        new Language("af", "Afrikaans"),
        new Language("sq", "Albanian"),
        new Language("am", "Amharic"),
        new Language("ar", "Arabic"),
        new Language("hy", "Armenian"),
        new Language("az", "Azerbaijani"),
        new Language("eu", "Basque"),
        new Language("be", "Belarusian"),
        new Language("bn", "Bengali"),
        new Language("bs", "Bosnian"),
        new Language("bg", "Bulgarian"),
        new Language("ca", "Catalan"),
        new Language("ceb", "Cebuano"),
        new Language("ny", "Chichewa"),
        new Language("zh-CN", "Chinese (Simplified)"),
        new Language("zh-TW", "Chinese (Traditional)"),
        new Language("co", "Corsican"),
        new Language("hr", "Croatian"),
        new Language("cs", "Czech"),
        new Language("da", "Danish"),
        new Language("nl", "Dutch"),
        new Language("en", "English"),
        new Language("eo", "Esperanto"),
        new Language("et", "Estonian"),
        new Language("tl", "Filipino"),
        new Language("fi", "Finnish"),
        new Language("fr", "French"),
        new Language("fy", "Frisian"),
        new Language("gl", "Galician"),
        new Language("ka", "Georgian"),
        new Language("de", "German"),
        new Language("el", "Greek"),
        new Language("gu", "Gujarati"),
        new Language("ht", "Haitian Creole"),
        new Language("ha", "Hausa"),
        new Language("haw", "Hawaiian"),
        new Language("he", "Hebrew"),
        new Language("hi", "Hindi"),
        new Language("hmn", "Hmong"),
        new Language("hu", "Hungarian"),
        new Language("is", "Icelandic"),
        new Language("ig", "Igbo"),
        new Language("id", "Indonesian"),
        new Language("ga", "Irish"),
        new Language("it", "Italian"),
        new Language("ja", "Japanese"),
        new Language("jw", "Javanese"),
        new Language("kn", "Kannada"),
        new Language("kk", "Kazakh"),
        new Language("km", "Khmer"),
        new Language("rw", "Kinyarwanda"),
        new Language("ko", "Korean"),
        new Language("ku", "Kurdish"),
        new Language("ky", "Kyrgyz"),
        new Language("lo", "Lao"),
        new Language("la", "Latin"),
        new Language("lv", "Latvian"),
        new Language("lt", "Lithuanian"),
        new Language("lb", "Luxembourgish"),
        new Language("mk", "Macedonian"),
        new Language("mg", "Malagasy"),
        new Language("ms", "Malay"),
        new Language("ml", "Malayalam"),
        new Language("mt", "Maltese"),
        new Language("mi", "Maori"),
        new Language("mr", "Marathi"),
        new Language("mn", "Mongolian"),
        new Language("my", "Myanmar (Burmese)"),
        new Language("ne", "Nepali"),
        new Language("no", "Norwegian"),
        new Language("or", "Odia"),
        new Language("ps", "Pashto"),
        new Language("fa", "Persian"),
        new Language("pl", "Polish"),
        new Language("pt", "Portuguese"),
        new Language("pa", "Punjabi"),
        new Language("ro", "Romanian"),
        new Language("ru", "Russian"),
        new Language("sm", "Samoan"),
        new Language("gd", "Scots Gaelic"),
        new Language("sr", "Serbian"),
        new Language("st", "Sesotho"),
        new Language("sn", "Shona"),
        new Language("sd", "Sindhi"),
        new Language("si", "Sinhala"),
        new Language("sk", "Slovak"),
        new Language("sl", "Slovenian"),
        new Language("so", "Somali"),
        new Language("es", "Spanish"),
        new Language("su", "Sundanese"),
        new Language("sw", "Swahili"),
        new Language("sv", "Swedish"),
        new Language("tg", "Tajik"),
        new Language("ta", "Tamil"),
        new Language("tt", "Tatar"),
        new Language("te", "Telugu"),
        new Language("th", "Thai"),
        new Language("tr", "Turkish"),
        new Language("tk", "Turkmen"),
        new Language("uk", "Ukrainian"),
        new Language("ur", "Urdu"),
        new Language("ug", "Uyghur"),
        new Language("uz", "Uzbek"),
        new Language("vi", "Vietnamese"),
        new Language("cy", "Welsh"),
        new Language("xh", "Xhosa"),
        new Language("yi", "Yiddish"),
        new Language("yo", "Yoruba"),
        new Language("zu", "Zulu")
    );

    private record Language(String code, String name) {
    }

    private static String getLanguageName(String code) {
        for (Language language : LANGUAGES) {
            if (language.code().equals(code)) return language.name();
        }
        return code;
    }

    private static CompletableFuture<Suggestions> suggestLanguages(CommandContext<CommandSource> context, SuggestionsBuilder builder) {
        for (Language language : LANGUAGES) {
            builder.suggest(language.code(), new LiteralMessage(language.name()));
        }
        return builder.buildFuture();
    }

    private enum Output {CHAT, EXPORT, GUI}

    public BookTranslateCommand() {
        super("booktranslate", "Translates the held written or writable book into another language.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // 1. Display in chat (default)
        builder.executes(ctx -> {
            runTranslation("en", 1, -1, Output.CHAT);
            return SINGLE_SUCCESS;
        });
        builder.then(literal("lang")
            .then(argument("language", StringArgumentType.word())
                .suggests(BookTranslateCommand::suggestLanguages)
                .executes(ctx -> {
                    String lang = StringArgumentType.getString(ctx, "language");
                    runTranslation(lang, 1, -1, Output.CHAT);
                    return SINGLE_SUCCESS;
                })
                .then(argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        String lang = StringArgumentType.getString(ctx, "language");
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        runTranslation(lang, page, page, Output.CHAT);
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("endPage", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            String lang = StringArgumentType.getString(ctx, "language");
                            int start = IntegerArgumentType.getInteger(ctx, "page");
                            int end = IntegerArgumentType.getInteger(ctx, "endPage");
                            if (start > end) {
                                int t = start;
                                start = end;
                                end = t;
                            }
                            runTranslation(lang, start, end, Output.CHAT);
                            return SINGLE_SUCCESS;
                        })
                    )
                )
            )
        );

        // 2. Export to .txt
        builder.then(literal("export")
            .executes(ctx -> {
                runTranslation("en", 1, -1, Output.EXPORT);
                return SINGLE_SUCCESS;
            })
            .then(argument("language", StringArgumentType.word())
                .suggests(BookTranslateCommand::suggestLanguages)
                .executes(ctx -> {
                    String lang = StringArgumentType.getString(ctx, "language");
                    runTranslation(lang, 1, -1, Output.EXPORT);
                    return SINGLE_SUCCESS;
                })
                .then(argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        String lang = StringArgumentType.getString(ctx, "language");
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        runTranslation(lang, page, page, Output.EXPORT);
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("endPage", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            String lang = StringArgumentType.getString(ctx, "language");
                            int start = IntegerArgumentType.getInteger(ctx, "page");
                            int end = IntegerArgumentType.getInteger(ctx, "endPage");
                            if (start > end) {
                                int t = start;
                                start = end;
                                end = t;
                            }
                            runTranslation(lang, start, end, Output.EXPORT);
                            return SINGLE_SUCCESS;
                        })
                    )
                )
            )
        );

        // 3. Open in book GUI (reflowed into new page count)
        builder.then(literal("gui")
            .executes(ctx -> {
                runTranslation("en", 1, -1, Output.GUI);
                return SINGLE_SUCCESS;
            })
            .then(argument("language", StringArgumentType.word())
                .suggests(BookTranslateCommand::suggestLanguages)
                .executes(ctx -> {
                    String lang = StringArgumentType.getString(ctx, "language");
                    runTranslation(lang, 1, -1, Output.GUI);
                    return SINGLE_SUCCESS;
                })
                .then(argument("page", IntegerArgumentType.integer(1))
                    .executes(ctx -> {
                        String lang = StringArgumentType.getString(ctx, "language");
                        int page = IntegerArgumentType.getInteger(ctx, "page");
                        runTranslation(lang, page, page, Output.GUI);
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("endPage", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            String lang = StringArgumentType.getString(ctx, "language");
                            int start = IntegerArgumentType.getInteger(ctx, "page");
                            int end = IntegerArgumentType.getInteger(ctx, "endPage");
                            if (start > end) {
                                int t = start;
                                start = end;
                                end = t;
                            }
                            runTranslation(lang, start, end, Output.GUI);
                            return SINGLE_SUCCESS;
                        })
                    )
                )
            )
        );
    }

    private void runTranslation(String targetLang, int startPage, int endPage, Output output) {
        ItemStack book = BookUtils.getHeldBook(mc.player);
        if (book == null) {
            error("You must hold a written or writable book!");
            return;
        }

        BookUtils.BookContent content = BookUtils.checkHeldBook(book);
        if (content == null || content.pages().isEmpty()) {
            error("This book has no content!");
            return;
        }

        List<String> allPages = content.pages();
        int totalPages = allPages.size();
        int start = Math.max(1, startPage);
        int end = (endPage == -1) ? totalPages : Math.min(endPage, totalPages);
        if (start > end) {
            error("Invalid page range.");
            return;
        }

        List<String> pagesToTranslate = allPages.subList(start - 1, end);
        String rangeInfo = (start == end) ? "page " + start : "pages " + start + "-" + end;
        info("§6Translating " + rangeInfo + " of " + totalPages + " to " + getLanguageName(targetLang) + " (" + targetLang + ")...");

        AtomicInteger completed = new AtomicInteger();
        int totalToTranslate = pagesToTranslate.size();
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (String page : pagesToTranslate) {
            CompletableFuture<String> future = page.isBlank() ? CompletableFuture.completedFuture("") : translateTextAsync(targetLang, page);
            future.whenComplete((result, error) -> {
                int done = completed.incrementAndGet();
                if (done % 30 == 0 || done == totalToTranslate) {
                    mc.executeSync(() -> info("§6Translating progress: " + done + "/" + totalToTranslate + " pages translated..."));
                }
            });
            futures.add(future);
        }

        int finalStart = start;
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenAccept(v -> {
                List<String> translated = new ArrayList<>();
                for (int i = 0; i < futures.size(); i++) {
                    String t = futures.get(i).join();
                    if (t == null) {
                        int failedPage = finalStart + i;
                        mc.executeSync(() -> error("Translation failed on page " + failedPage + ". Check the log for the HTTP error."));
                        return;
                    }
                    translated.add(t);
                }

                mc.executeSync(() -> {
                    switch (output) {
                        case EXPORT -> writeTranslationFile(content, finalStart, translated);
                        case GUI -> openTranslatedGui(content, translated);
                        default -> printTranslation(rangeInfo, finalStart, translated);
                    }
                });
            })
            .exceptionally(e -> {
                mc.executeSync(() -> error("Translation error: " + e.getMessage()));
                return null;
            });
    }

    private void printTranslation(String rangeInfo, int startPage, List<String> translatedPages) {
        info("§6<Translated Book (" + rangeInfo + ")>");
        for (int i = 0; i < translatedPages.size(); i++) {
            int actualPage = startPage + i;
            info("§7--- Page " + actualPage + " ---");
            String text = translatedPages.get(i);
            for (String line : text.split("\n")) {
                if (line.length() > 120) {
                    for (int j = 0; j < line.length(); j += 120) {
                        info("§f" + BookUtils.escapePercent(line.substring(j, Math.min(j + 120, line.length()))));
                    }
                } else {
                    info("§f" + BookUtils.escapePercent(line));
                }
            }
        }
        info("§6========================");
    }

    private void openTranslatedGui(BookUtils.BookContent content, List<String> translatedPages) {
        if (translatedPages.isEmpty()) {
            error("Translation produced no content.");
            return;
        }

        String title = content.type() == BookUtils.BookType.WRITTEN && content.title() != null
            ? content.title() : "Translated";
        String author = content.author() != null ? content.author()
            : mc.player.getName().getString();

        ItemStack translatedBook = BookUtils.createWrittenBook(title, author, translatedPages);
        int pageCount = BookUtils.getPageCount(translatedBook);
        BookUtils.openBookGui(translatedBook, 1);
        info("§aOpened translated book in GUI. §7" + pageCount + " page(s)");
    }

    private void writeTranslationFile(BookUtils.BookContent content, int startPage, List<String> translatedPages) {
        String rawTitle = content.type() == BookUtils.BookType.WRITTEN ? content.title() : "book_and_quill";
        String safeTitle = (rawTitle == null ? "book" : rawTitle).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safeTitle.length() > 50) safeTitle = safeTitle.substring(0, 50);

        StringBuilder fileContent = new StringBuilder();
        for (int i = 0; i < translatedPages.size(); i++) {
            int pageNum = startPage + i;
            fileContent.append("===== Page ").append(pageNum).append(" =====\n\n");
            fileContent.append(translatedPages.get(i).trim()).append("\n\n");
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String fileName = safeTitle + "_" + timestamp + ".txt";
        Path outDir = mc.runDirectory.toPath().resolve("AutoBookshelf");

        try {
            Files.createDirectories(outDir);
            Path outFile = outDir.resolve(fileName);
            Files.writeString(outFile, fileContent.toString(), StandardCharsets.UTF_8);
            info("§aExported translation to §f" + outFile.toAbsolutePath());
        } catch (Exception e) {
            error("Failed to write file: " + e.getMessage());
        }
    }

    private CompletableFuture<String> translateTextAsync(String targetLang, String textToTranslate) {
        return CompletableFuture.supplyAsync(() -> {
            int attempts = 0;
            while (true) {
                try {
                    String urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                        + URLEncoder.encode(targetLang, StandardCharsets.UTF_8)
                        + "&dt=t&q=" + URLEncoder.encode(textToTranslate, StandardCharsets.UTF_8);

                    String raw;
                    while (true) {
                        // Recreated on every attempt: HttpURLConnection is single-use,
                        // so retrying on the same object just reads the cached status.
                        throttleRequestStart();
                        HttpURLConnection conn = null;
                        try {
                            conn = (HttpURLConnection) new URI(urlString).toURL().openConnection();
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(8000);
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                            int status = conn.getResponseCode();
                            if (status >= 200 && status < 300) {
                                try (InputStream stream = conn.getInputStream()) {
                                    raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                                }
                                JsonArray root = JsonParser.parseString(raw).getAsJsonArray();
                                StringBuilder result = new StringBuilder();
                                if (!root.isEmpty() && root.get(0).isJsonArray()) {
                                    JsonArray segments = root.get(0).getAsJsonArray();
                                    for (JsonElement el : segments) {
                                        if (el.isJsonArray()) {
                                            JsonArray segment = el.getAsJsonArray();
                                            if (!segment.isEmpty()) {
                                                String translatedSegment = segment.get(0).getAsString();
                                                if (translatedSegment != null && !translatedSegment.isEmpty()) {
                                                    result.append(translatedSegment);
                                                }
                                            }
                                        }
                                    }
                                }
                                return result.toString();
                            }

                            // Transient failures (rate limit, 5xx) get an exponential,
                            // jittered backoff; hard 4xx fail fast.
                            if ((status == 429 || status >= 500) && attempts < MAX_TRANSLATE_ATTEMPTS - 1) {
                                attempts++;
                                long retryDelay = Math.min(REQUEST_MAX_RETRY_DELAY_MILLIS, 1200L * (1L << (attempts - 1))) + RANDOM.nextLong(1500);
                                sleep(retryDelay);
                                continue;
                            }
                            // Any non-2xx we're not retrying further is a real failure.
                            // Log the body for diagnostics but return null so the caller's
                            // `t == null` check reports it as a failed page instead of
                            // silently treating the error body as translated text.
                            String errorBody = readErrorBody(conn);
                            System.err.println("BookTranslate: HTTP " + status + " for page translation: " + errorBody);
                            return null;
                        } catch (IOException e) {
                            // Transient connection-level failures (connect/read timeout,
                            // connect reset, DNS) are retried through the same exponential,
                            // jittered backoff; pacing and attempt budget are untouched.
                            if (attempts < MAX_TRANSLATE_ATTEMPTS - 1) {
                                attempts++;
                                long retryDelay = Math.min(REQUEST_MAX_RETRY_DELAY_MILLIS, 1200L * (1L << (attempts - 1))) + RANDOM.nextLong(1500);
                                sleep(retryDelay);
                                continue;
                            }
                            e.printStackTrace();
                            return null;
                        } finally {
                            if (conn != null) conn.disconnect();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }
        }, TRANSLATE_POOL);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Enforces a global minimum gap between consecutive request *starts* (with
    // jitter), so an entire book can't burst the free endpoint at once even
    // though pages translate concurrently. Blocks the worker until a slot opens.
    private static void throttleRequestStart() {
        while (true) {
            long now = System.nanoTime();
            long last = LAST_REQUEST_START_NANOS.get();
            if (last == 0) {
                if (LAST_REQUEST_START_NANOS.compareAndSet(0, now)) return;
                continue;
            }
            long intervalMillis = REQUEST_MIN_INTERVAL_MILLIS + RANDOM.nextLong(REQUEST_INTERVAL_JITTER + 1);
            long gapMillis = intervalMillis - (now - last) / 1_000_000L;
            if (gapMillis <= 0) {
                if (LAST_REQUEST_START_NANOS.compareAndSet(last, now)) return;
                continue;
            }
            sleep(gapMillis);
        }
    }

    private static String readErrorBody(HttpURLConnection conn) {
        try (InputStream errorStream = conn.getErrorStream()) {
            if (errorStream == null) return "";
            byte[] bytes = errorStream.readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            return body.length() > 300 ? body.substring(0, 300) : body;
        } catch (Exception ignored) {
            return "";
        }
    }
}
