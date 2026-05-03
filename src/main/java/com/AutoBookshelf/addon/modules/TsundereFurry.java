package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.util.math.random.Random;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TsundereFurry extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAnimal = settings.createGroup("Animal");
    private final SettingGroup sgTsundere = settings.createGroup("Tsundere");

    private final Setting<TransformationMode> mode = sgGeneral.add(new EnumSetting.Builder<TransformationMode>()
        .name("mode").description("How to transform outgoing messages.")
        .defaultValue(TransformationMode.Tsundere)
        .build());

    private final Setting<Animal> animal = sgAnimal.add(new EnumSetting.Builder<Animal>()
        .name("animal").description("Animal to turn into.")
        .defaultValue(Animal.Cat)
        .visible(() -> mode.get() != TransformationMode.Tsundere)
        .build());

    private final Setting<Boolean> swearReplacement = sgTsundere.add(new BoolSetting.Builder()
        .name("swear-replace").description("Replace swear words with tsundere alternatives.")
        .defaultValue(true)
        .visible(() -> mode.get() != TransformationMode.Animal)
        .build());

    private final Setting<List<String>> swearWords = sgTsundere.add(new StringListSetting.Builder()
        .name("swear-words").description("Words to replace.")
        .defaultValue(List.of("fuck", "shit", "bitch", "damn", "ass", "cunt", "whore", "dick", "fucking", "motherfucker", "bastard", "slut", "asshole"))
        .visible(() -> swearReplacement.get() && mode.get() != TransformationMode.Animal)
        .build());

    private final Setting<List<String>> swearReplacements = sgTsundere.add(new StringListSetting.Builder()
        .name("swear-replace").description("Possible replacements.")
        .defaultValue(List.of("baka", "b- baka", "dummy", "silly", "idiot", "silly goof", "stupid senpai", "hmph", "tchh"))
        .visible(() -> swearReplacement.get() && mode.get() != TransformationMode.Animal)
        .build());

    private final Setting<Boolean> addSuffix = sgTsundere.add(new BoolSetting.Builder()
        .name("add-suffix")
        .description("Add a random tsundere suffix to the message.")
        .defaultValue(true)
        .visible(() -> mode.get() != TransformationMode.Animal)
        .build());

    private final Setting<List<String>> suffixes = sgTsundere.add(new StringListSetting.Builder()
        .name("suffix-list")
        .description("Possible suffixes.")
        .defaultValue(List.of(
            ", but it's not that I like you or anything!",
            ", hmph!",
            ", dummy!",
            ", baka!",
            ", don't think this means I care!",
            ", it's not like I wanted to say that...",
            ", geez...",
            ", whatever!",
            ", you're so clueless, nya~",
            ", but I'm only doing this because I feel like it!"
        ))
        .visible(() -> addSuffix.get() && mode.get() != TransformationMode.Animal).build());

    private final Setting<Integer> suffixChance = sgTsundere.add(new IntSetting.Builder()
        .name("suffix-chance").description("Chance (0-100) to add a suffix.")
        .defaultValue(40)
        .range(0, 100)
        .sliderRange(0, 100)
        .visible(() -> addSuffix.get() && mode.get() != TransformationMode.Animal)
        .build());

    private final Setting<Boolean> stutter = sgTsundere.add(new BoolSetting.Builder()
        .name("stutter")
        .description("Add a cutesy stutter to the first word.")
        .defaultValue(false)
        .visible(() -> mode.get() != TransformationMode.Animal)
        .build());

    private final Setting<Integer> stutterChance = sgTsundere.add(new IntSetting.Builder()
        .name("stutter-chance")
        .description("Chance (0-100) to stutter.")
        .defaultValue(50)
        .range(0, 100)
        .sliderRange(0, 100)
        .visible(() -> stutter.get() && mode.get() != TransformationMode.Animal)
        .build());

    private final Setting<Boolean> addPrefix = sgTsundere.add(new BoolSetting.Builder()
        .name("add-prefix")
        .description("Add a random tsundere phrase at the beginning.")
        .defaultValue(false)
        .visible(() -> mode.get() != TransformationMode.Animal)
        .build());

    private final Setting<List<String>> prefixes = sgTsundere.add(new StringListSetting.Builder()
        .name("prefix-list")
        .description("Possible prefixes.")
        .defaultValue(List.of(
            "Hmph... ",
            "Not that I want to say, but... ",
            "It's not like I enjoy this, but... ",
            "Ugh, fine! ",
            "Don't get the wrong idea! ",
            "Just so you know, I'm only helping because I'm bored. "
        ))
        .visible(() -> addPrefix.get() && mode.get() != TransformationMode.Animal)
        .build());

    private final Setting<Integer> prefixChance = sgTsundere.add(new IntSetting.Builder()
        .name("prefix-chance")
        .description("Chance (0-100) to add a prefix.")
        .defaultValue(35)
        .range(0, 100)
        .sliderRange(0, 100)
        .visible(() -> addPrefix.get() && mode.get() != TransformationMode.Animal)
        .build()); // The odds are pretty good eh

    private final Map<Animal, List<String>> animalSounds = new HashMap<>();
    private static final Pattern TOKEN_RE = Pattern.compile("[A-Za-z0-9]+(?:'[A-Za-z0-9]+)?|_|[^\\w\\s]|\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> ALWAYS_STICKY = new HashSet<>(Arrays.asList(
        "whore", "whores", "cunt", "cunts", "bitch", "bitches", "asses", "asshole", "assholes", "asshat", "asshats",
        "fucker", "fuckers", "jackass", "jackasses", "smartasses", "pussies", "bastard", "bastards", "dick", "dicks",
        "slut", "sluts", "motherfucker", "motherfuckers", "fuckface"
    ));
    private static final Set<String> STICKY_HINT = new HashSet<>(Arrays.asList(
        "hi", "hey", "hello", "you", "an", "all", "these", "those", "this", "that", "go", "yall", "y'all",
        "are", "the", "hate", "be", "like", "at", "silly", "stupid", "say", "be"
    ));
    private static final Set<String> PLURAL_HINT = new HashSet<>(Arrays.asList("these", "those", "all"));

    private static final Map<String, List<String>> PHRASE_REPLACEMENTS = new HashMap<>();
    static {
        PHRASE_REPLACEMENTS.put(cleanKey("this tps sucks"), List.of("I'm a stupid baka"));
        PHRASE_REPLACEMENTS.put(cleanKey("mb gng"), List.of("I'm a stupid baka"));
        PHRASE_REPLACEMENTS.put(cleanKey("sorry"), List.of("I'm a stupid baka"));
        PHRASE_REPLACEMENTS.put(cleanKey("brb"), List.of(
            "it's not that I care about you or anything!",
            "don't think I care or anything!",
            "It's not like I'm concerned for you or anything!",
            "but I'm not worried, hmph!",
            "be safe, you silly baka! It'd be hard to replace you...",
            "don't think I care or anything!"
        ));
        PHRASE_REPLACEMENTS.put(cleanKey("youre dumb"), List.of("it's not like I like you or anything!"));
        PHRASE_REPLACEMENTS.put(cleanKey("i know"), List.of("don't think I didn't know!", "I know, b- baka!"));
        PHRASE_REPLACEMENTS.put(cleanKey("ik"), List.of("don't think I didn't know!", "I know, b- baka!"));
        PHRASE_REPLACEMENTS.put(cleanKey("im cool"), List.of("I'm not c- cute!"));
        PHRASE_REPLACEMENTS.put(cleanKey("wtf"), List.of("what the, baka!", "what the, b- baka!"));
        PHRASE_REPLACEMENTS.put(cleanKey("thank you"), List.of(
            "I didn't do it for your thanks, baka! But... you're welcome.",
            "Hmph, don't think this makes us friends! But... thanks for saying that."
        ));
        PHRASE_REPLACEMENTS.put(cleanKey("good morning"), List.of(
            "Morning? Only because I have to say it, I guess... Good morning, baka."
        ));
        PHRASE_REPLACEMENTS.put(cleanKey("good night"), List.of(
            "It's not like I wanted to say good night! But... sleep well, I guess."
        ));
        PHRASE_REPLACEMENTS.put(cleanKey("im sorry"), List.of(
            "I- I'm not the one who should apologise! But... fine, I forgive you, baka."
        ));
        PHRASE_REPLACEMENTS.put(cleanKey("i almost died"), List.of(
            "I- I'm perfectly fine! Don't worry about me, idiot.",
            "Why would I need you to worry? But... thanks, I guess."
        ));
        PHRASE_REPLACEMENTS.put(cleanKey("bye"), List.of(
            "Don't be late or I'll be mad! Yeah, whatever... bye.",
            "See you, I guess? Don't get into trouble or I'll never forgive you!"
        ));
        PHRASE_REPLACEMENTS.put(cleanKey("cya"), List.of(
            "What are you going to do if I get lost? At least hold my hand later!",
            "I only saved you so we can finish our battle later!"
        ));
    }

    public TsundereFurry() {
        super(Addon.CATEGORY, "Tsundere-Furry", "Transforms outgoing chat messages into animal sounds, tsundere, or both :>");
        animalSounds.put(Animal.Rabbit, List.of("chirrup", "purr", "purrrrr", "prrr", "grunt", "peko", "thump", "buni", "nurf", "pyon"));
        animalSounds.put(Animal.Cat, List.of("meow", "mreow", "mrew", "purr", "purrrrr", "prrr", "mew", "rawr", "nya", "buhhh", "nyaa~", "miaw <3"));
    }

    @EventHandler
    public void onSendMessage(SendMessageEvent event) {
        if (event.isCancelled()) return;
        String message = event.message;
        if (message.startsWith("/")) return;   // i spent way too long on reinventing the wheel
        event.message = applyTransformation(message);
    }

    private boolean redirecting = false;

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (redirecting) return;

        if (!(event.packet instanceof CommandExecutionC2SPacket) &&
            !(event.packet instanceof ChatCommandSignedC2SPacket)) {
            return;
        }

        String command;
        if (event.packet instanceof CommandExecutionC2SPacket cmdPacket) {
            command = "/" + cmdPacket.command();
        } else {
            command = "/" + ((ChatCommandSignedC2SPacket) event.packet).command();
        }

        if (!isWhisperCommand(command)) return;

        String transformed = transformWhisperCommand(command);   // "/msg Steve hello, baka!"

        event.cancel();                     // Stop the original packet
        redirecting = true;                // Prevent rehandling the new packet
        try {
            if (transformed.startsWith("/")) {
                mc.player.networkHandler.sendChatCommand(transformed.substring(1));
            } else {
                mc.player.networkHandler.sendChatMessage(transformed);
            }
        } finally {
            redirecting = false;
        }
    }

    private boolean isWhisperCommand(String cmd) {
        String lower = cmd.toLowerCase();
        return lower.startsWith("/msg ") || lower.startsWith("/whisper ")
            || lower.startsWith("/l ") || lower.startsWith("/r ") || lower.startsWith("/w ");
    }

    private String transformWhisperCommand(String input) {
        String lower = input.toLowerCase();
        boolean hasTarget = lower.startsWith("/msg ") || lower.startsWith("/whisper ") || lower.startsWith("/w ");

        if (hasTarget) {
            String[] parts = input.split(" ", 3);
            if (parts.length < 3) return input;   // incomplete, leave as it is
            return parts[0] + " " + parts[1] + " " + applyTransformation(parts[2]);
        } else {
            // /r or /l without name
            String[] parts = input.split(" ", 2);
            if (parts.length < 2) return input;
            return parts[0] + " " + applyTransformation(parts[1]);
        }
    }

    private String applyTransformation(String raw) {
        return switch (mode.get()) {
            case Animal -> convertToAnimalSounds(raw);
            case Tsundere -> tsundereTransform(raw);
            case AnimalThenTsundere -> tsundereTransform(convertToAnimalSounds(raw));
            case TsundereThenAnimal -> convertToAnimalSounds(tsundereTransform(raw));
        };
    }

    private String convertToAnimalSounds(String message) {
        List<String> sounds = animalSounds.get(animal.get());
        if (sounds == null || sounds.isEmpty()) return message;

        Map<String, String> wordToSound = new HashMap<>();
        StringBuffer out = new StringBuffer();
        Pattern wordPattern = Pattern.compile("[a-zA-Z]+");
        Matcher matcher = wordPattern.matcher(message);

        while (matcher.find()) {
            String word = matcher.group();
            String lower = word.toLowerCase();
            wordToSound.putIfAbsent(lower, sounds.get(Random.create().nextInt(sounds.size())));
            String replacement = wordToSound.get(lower);
            replacement = matchCase(word, replacement);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String tsundereTransform(String text) {
        if (text == null || text.isEmpty()) return text;

        text = applyWholePhrases(text);
        text = applyTsundereTokens(text);

        if (addPrefix.get() && Random.create().nextInt(100) < prefixChance.get()) {
            List<String> prefixList = prefixes.get();
            if (!prefixList.isEmpty()) {
                text = prefixList.get(Random.create().nextInt(prefixList.size())) + text;
            }
        }

        if (addSuffix.get() && Random.create().nextInt(100) < suffixChance.get()) {
            List<String> suffixList = suffixes.get();
            if (!suffixList.isEmpty()) {
                text += suffixList.get(Random.create().nextInt(suffixList.size()));
            }
        }

        if (stutter.get() && Random.create().nextInt(100) < stutterChance.get()) {
            text = stutterText(text);
        }

        return text;
    }

    private String applyWholePhrases(String text) {
        String cleaned = cleanKey(text);
        if (PHRASE_REPLACEMENTS.containsKey(cleaned)) {
            List<String> options = PHRASE_REPLACEMENTS.get(cleaned);
            return options.get(Random.create().nextInt(options.size()));
        }
        return text;
    }

    private static String cleanKey(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z\\s]", "").trim();
    }
    // How much of a Tsundere do YOU think oehrasa is? xD
    private String applyTsundereTokens(String text) {
        if (!swearReplacement.get()) return text;
        List<String> words = swearWords.get();
        if (words.isEmpty()) return text;

        Matcher m = TOKEN_RE.matcher(text);
        List<String> tokens = new ArrayList<>();
        boolean anyAlpha = false;
        while (m.find()) {
            String tok = m.group(0);
            tokens.add(tok);
            if (tok.chars().anyMatch(Character::isLetter)) anyAlpha = true;
        }
        if (!anyAlpha) return text;

        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).contains("\n")) tokens.set(i, " ");
        }

        boolean profane = false;
        boolean pluralFlag = false;
        boolean bakaMode = false;

        List<String> replacements = swearReplacements.get();

        for (int i = 0; i < tokens.size(); i++) {
            String tok = tokens.get(i);
            String lt = tok.toLowerCase();

            if (isWord(tok) && words.contains(lt)) {
                bakaMode = true;

                int prevIdx = prevSigIndex(tokens, i - 1);
                String pword = prevIdx >= 0 ? tokens.get(prevIdx).toLowerCase() : null;
                int nextIdx = nextSigIndex(tokens, i + 1);
                String nword = nextIdx >= 0 ? tokens.get(nextIdx).toLowerCase() : null;

                boolean sticky = (pword != null && STICKY_HINT.contains(pword) && !lt.equals("fucking") && !lt.equals("damn")) || ALWAYS_STICKY.contains(lt);
                boolean pluralish = (lt.endsWith("s") && !lt.equals("ass") && !lt.equals("dumbass")) || (pword != null && PLURAL_HINT.contains(pword));

                if (lt.equals("fuck") && "you".equals(nword) && nextIdx >= 0) {
                    String rep = flip(" you baka", " you b- baka");
                    tokens.set(i, matchCase(tok, rep));
                    if (nextIdx > i) {
                        tokens.subList(i + 1, nextIdx + 1).clear();
                    }
                    profane = true;
                    continue;
                }

                if (sticky) {
                    String rep = pluralish ? flip("bakas", "b- bakas") : flip("baka", "b- baka");
                    tokens.set(i, matchCase(tok, rep));
                    if ("an".equals(pword) && prevIdx >= 0) {
                        tokens.set(prevIdx, matchCase(tokens.get(prevIdx), "a"));
                    }
                } else {
                    if (pluralish) pluralFlag = true;
                    profane = true;
                    String rep = replacements.get(Random.create().nextInt(replacements.size()));
                    tokens.set(i, matchCase(tok, rep));
                }
            }
        }

        if (profane) {
            boolean anyAlphaLeft = false;
            for (String t : tokens) if (t != null && t.chars().anyMatch(Character::isLetter)) { anyAlphaLeft = true; break; }
            if (!anyAlphaLeft) {
                String rep = pluralFlag ? flip("bakas!", "b- bakas!") : flip("baka!", "b- baka!");
                return matchCase(tokens.isEmpty() ? "" : tokens.getLast(), rep);
            }

            int qm = 0;
            while (!tokens.isEmpty() && tokens.getLast().matches("[?!., ]")) {
                String last = tokens.removeLast();
                if (last.equals("?")) qm++;
            }
            String suffix = pluralFlag ? flip(", bakas", ", b- bakas") : flip(", baka", ", b- baka");
            if (!tokens.isEmpty()) tokens.set(tokens.size()-1, tokens.getLast() + suffix);
            else tokens.add(matchCase("", suffix));
            for (int q = 0; q < qm; q++) tokens.add("?");
            tokens.add("!");
        }

        if (bakaMode && !tokens.isEmpty()) {
            String last = tokens.getLast();
            if (!(last.endsWith("!") || last.endsWith("?"))) tokens.add("!");
        }

        StringBuilder out = new StringBuilder();
        for (String t : tokens) out.append(t);
        return out.toString();
    }

    private String stutterText(String text) {
        if (text.isEmpty()) return text;
        int i = 0;
        while (i < text.length() && !Character.isLetter(text.charAt(i))) i++;
        if (i >= text.length()) return text;
        return text.substring(0, i) + text.charAt(i) + "- " + text.substring(i);
    }

    private boolean isWord(String tok) {
        if (tok == null) return false;
        for (char c : tok.toCharArray()) if (Character.isLetter(c)) return true;
        return false;
    }

    private int prevSigIndex(List<String> tokens, int idx) {
        for (int i = idx; i >= 0; i--) {
            String t = tokens.get(i);
            if (t == null || t.trim().isEmpty() || t.equals("\"") || t.equals("'")) continue;
            return i;
        }
        return -1;
    }

    private int nextSigIndex(List<String> tokens, int idx) {
        for (int i = idx; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t == null || t.trim().isEmpty() || t.equals("\"") || t.equals("'")) continue;
            return i;
        }
        return -1;
    }

    private String matchCase(String original, String replacement) {
        if (original == null || original.isEmpty()) return replacement;
        if (original.equals(original.toUpperCase())) return replacement.toUpperCase();
        if (Character.isUpperCase(original.charAt(0))) {
            return replacement.substring(0,1).toUpperCase() + replacement.substring(1);
        }
        return replacement;
    }

    private String flip(String a, String b) {
        return Random.create().nextBoolean() ? a : b;
    }

    public enum TransformationMode {
        Animal,
        Tsundere,
        AnimalThenTsundere,
        TsundereThenAnimal
    }

    public enum Animal {
        Rabbit,
        Cat
    }
}
