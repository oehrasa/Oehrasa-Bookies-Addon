package com.AutoBookshelf.addon.modules;

import com.AutoBookshelf.addon.Addon;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.settings.DoubleSetting.Builder;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.*;

public class SignRender extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender = this.settings.createGroup("Render");
    private final SettingGroup sgClustering = this.settings.createGroup("Clustering");
    private final SettingGroup sgOptimization = this.settings.createGroup("Optimization");
    private final Setting<Double> maxDistance = this.sgGeneral
        .add(
            new Builder().name("max-distance").description("Maximum distance to render signs (blocks).")
                .defaultValue(1024.0)
                .min(16.0)
                .max(1024.0)
                .sliderRange(16.0, 1024.0)
                .build()
        );
    private final Setting<Integer> maxSigns = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                .name("max-signs")
                .description("Maximum number of signs to render.")
                .defaultValue(500)
                .min(5)
                .max(500)
                .sliderRange(5, 1000)
                .build()
        );
    private final Setting<Boolean> filterEmpty = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("filter-empty")
                .description("Hide empty signs.")
                .defaultValue(true)
                .build()
        );
    private final Setting<Boolean> multilineDisplay = this.sgGeneral
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("multiline-display")
                .description("Display sign text as multiple lines as they appear on the sign.")
                .defaultValue(true)
                .build()
        );
    private final Setting<SettingColor> textColor = this.sgRender
        .add(
            new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                .name("text-color")
                .description("Text color.")
                .defaultValue(new SettingColor(255, 255, 255, 255)).build()
        );
    private final Setting<SettingColor> backgroundColor = this.sgRender
        .add(
            new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                .name("background-color")
                .description("Background color.")
                .defaultValue(new SettingColor(0, 0, 0, 120)).build()
        );
    private final Setting<Boolean> showBackground = this.sgRender
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("show-background")
                .description("Show background behind text.")
                .defaultValue(true)
                .build()
        );
    private final Setting<Boolean> forceDefaultFont = this.sgRender
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("force-default-font")
                .description("Force sign text to render using the default font, even if a custom GUI font is selected in your Meteor config.")
                .defaultValue(false)
                .build()
        );
    private final Setting<Boolean> enableClustering = this.sgClustering
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("enable-clustering")
                .description("Group nearby signs to prevent overlap.")
                .defaultValue(true)
                .build()
        );
    private final Setting<Double> clusterRadius = this.sgClustering
        .add(
            new Builder().name("cluster-radius").description("Screen distance in pixels to group signs.")
                .defaultValue(100.0)
                .min(20.0)
                .max(500.0)
                .sliderRange(20.0, 200.0)
                .visible(this.enableClustering::get)
                .build()
        );
    private final Setting<SignRender.ClusterMode> clusterMode = this.sgClustering
        .add(
            new meteordevelopment.meteorclient.settings.EnumSetting.Builder<SignRender.ClusterMode>()
                .name("cluster-mode")
                .description("How to display clustered signs.")
                .defaultValue(SignRender.ClusterMode.Count)
                .visible(this.enableClustering::get)
                .build()
        );
    private final Setting<Integer> cycleTime = this.sgClustering
        .add(
            new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                .name("cycle-time")
                .description("Time in milliseconds between cycling signs.")
                .defaultValue(2000)
                .min(500)
                .max(10000)
                .sliderRange(500, 5000)
                .visible(() -> (Boolean) this.enableClustering.get() && this.clusterMode.get() == SignRender.ClusterMode.Cycle)
                .build()
        );
    private final Setting<Integer> maxClusterDisplay = this.sgClustering
        .add(
            new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                .name("max-cluster-display")
                .description("Maximum signs to show in a cluster.")
                .defaultValue(5)
                .min(1)
                .max(10)
                .sliderRange(1, 10)
                .visible(() -> (Boolean) this.enableClustering.get() && this.clusterMode.get() != SignRender.ClusterMode.Count)
                .build()
        );
    private final Setting<Boolean> showClusterCount = this.sgClustering
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("show-cluster-count")
                .description("Show number of signs in cluster.")
                .defaultValue(true)
                .visible(this.enableClustering::get)
                .build()
        );
    private final Setting<SettingColor> clusterCountColor = this.sgClustering
        .add(
            new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
                .name("cluster-count-color")
                .description("Color for cluster count indicator.")
                .defaultValue(new SettingColor(255, 200, 100, 255)).visible(() -> (Boolean) this.enableClustering.get() && (Boolean) this.showClusterCount.get())
                .build()
        );
    private final Setting<Double> stackSpacing = this.sgClustering
        .add(
            new Builder().name("stack-spacing").description("Vertical spacing between stacked signs.")
                .defaultValue(5.0)
                .min(0.0)
                .max(20.0)
                .sliderRange(0.0, 20.0)
                .visible(() -> (Boolean) this.enableClustering.get() && this.clusterMode.get() == SignRender.ClusterMode.Stack)
                .build()
        );
    private final Setting<Boolean> cullOffScreen = this.sgOptimization
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("cull-off-screen")
                .description("Don't process signs that are off-screen.")
                .defaultValue(true)
                .build()
        );
    private final Setting<Boolean> prioritizeClosest = this.sgOptimization
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("prioritize-closest")
                .description("Always show closest signs first.")
                .defaultValue(true)
                .build()
        );
    private final Setting<Boolean> cacheSignText = this.sgOptimization
        .add(
            new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name("cache-sign-text")
                .description("Cache sign text for better performance.")
                .defaultValue(true)
                .build()
        );
    private final Setting<Integer> updateInterval = this.sgOptimization
        .add(
            new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                .name("update-interval")
                .description("Ticks between full sign updates.")
                .defaultValue(20)
                .min(1)
                .max(100)
                .sliderRange(1, 100)
                .visible(this.cacheSignText::get)
                .build()
        );
    private final Vector3d tempVec = new Vector3d();
    private final List<SignRender.SignRenderData> allSigns = new ArrayList<>();
    private final List<SignRender.SignCluster> clusters = new ArrayList<>();
    private final Map<BlockPos, SignRender.SignRenderData> signCache = new HashMap<>();
    private int updateTicker = 0;
    private int globalCycleIndex = 0;
    private long lastGlobalCycleTime = 0L;
    private GuiGraphicsExtractor graphics;

    public SignRender() {
        super(Addon.CATEGORY2, "Sign-Render", "Renders sign text through walls with advanced clustering.");
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (this.mc.level != null && this.mc.player != null) {
            this.graphics = event.graphics;
            this.updateTicker++;
            boolean fullUpdate = !(Boolean) this.cacheSignText.get() || this.updateTicker >= (Integer) this.updateInterval.get();
            if (fullUpdate) {
                this.updateTicker = 0;
                this.collectSigns();
            } else {
                this.updateSignPositions();
            }

            if ((Boolean) this.enableClustering.get() && !this.allSigns.isEmpty()) {
                this.createClusters();
            }

            this.renderSigns();
        }
    }

    private void collectSigns() {
        this.allSigns.clear();
        this.signCache.clear();
        Vec3 playerPos = this.mc.player.position();
        double maxDist = (Double) this.maxDistance.get();
        List<SignRender.SignRenderData> tempSignList = new ArrayList<>();

        for (BlockEntity blockEntity : Utils.blockEntities()) {
            try {
                if (blockEntity instanceof SignBlockEntity || blockEntity instanceof HangingSignBlockEntity) {
                    BlockPos signPos = blockEntity.getBlockPos();
                    Vec3 signVec = Vec3.atCenterOf(signPos);
                    double distance = playerPos.distanceTo(signVec);
                    if (!(distance > maxDist)) {
                        List<String> lines = this.extractSignLines(blockEntity);
                        if (!lines.isEmpty() || !(Boolean) this.filterEmpty.get()) {
                            SignRender.SignRenderData signData = new SignRender.SignRenderData(signPos, lines, signVec);
                            signData.distance = distance;
                            signData.updateScreenPosition(this.tempVec);
                            if (signData.onScreen || !(Boolean) this.cullOffScreen.get()) {
                                signData.scale = 1.0;
                                signData.color = new Color((Color) this.textColor.get());
                                tempSignList.add(signData);
                                this.signCache.put(signPos, signData);
                            }
                        }
                    }
                }
            } catch (Exception var13) {
            }
        }

        if ((Boolean) this.prioritizeClosest.get()) {
            tempSignList.sort(Comparator.comparingDouble(s -> s.distance));
        }

        int limit = Math.min(tempSignList.size(), (Integer) this.maxSigns.get());

        for (int i = 0; i < limit; i++) {
            this.allSigns.add(tempSignList.get(i));
        }

        if (this.globalCycleIndex >= this.allSigns.size() && !this.allSigns.isEmpty()) {
            this.globalCycleIndex = 0;
        }
    }

    private void updateSignPositions() {
        if (this.mc.player != null) {
            Vec3 playerPos = this.mc.player.position();
            Iterator<SignRender.SignRenderData> iterator = this.allSigns.iterator();

            while (iterator.hasNext()) {
                SignRender.SignRenderData sign = iterator.next();
                sign.distance = playerPos.distanceTo(sign.worldPos);
                sign.updateScreenPosition(this.tempVec);
                sign.scale = 1.0;
                sign.color = new Color((Color) this.textColor.get());
            }
        }
    }

    private void createClusters() {
        this.clusters.clear();

        for (SignRender.SignRenderData clustered : this.allSigns) {
            ;
        }

        List<SignRender.SignRenderData> toCluster = new ArrayList<>(this.allSigns);
        Set<SignRender.SignRenderData> clustered = new HashSet<>();
        double radiusSq = (Double) this.clusterRadius.get() * (Double) this.clusterRadius.get();

        while (!toCluster.isEmpty()) {
            SignRender.SignRenderData seed = toCluster.remove(0);
            if (!clustered.contains(seed) && seed.onScreen) {
                SignRender.SignCluster cluster = new SignRender.SignCluster();
                cluster.addSign(seed);
                clustered.add(seed);

                for (SignRender.SignRenderData other : toCluster) {
                    if (other.onScreen && !clustered.contains(other)) {
                        double dx = seed.screenX - other.screenX;
                        double dy = seed.screenY - other.screenY;
                        double distSq = dx * dx + dy * dy;
                        if (distSq <= radiusSq) {
                            cluster.addSign(other);
                            clustered.add(other);
                        }
                    }
                }

                cluster.calculateCenter();
                if (cluster.signs.size() > 1) {
                    this.clusters.add(cluster);
                }
            }
        }
    }

    private void renderSigns() {
        if (!this.allSigns.isEmpty()) {
            TextRenderer textRenderer = (TextRenderer) (this.forceDefaultFont.get() ? VanillaTextRenderer.INSTANCE : TextRenderer.get());
            if ((Boolean) this.enableClustering.get() && !this.clusters.isEmpty()) {
                this.renderWithClusters(textRenderer);
            } else {
                this.renderAllSigns(textRenderer);
            }
        }
    }

    private void renderWithClusters(TextRenderer textRenderer) {
        long currentTime = System.currentTimeMillis();
        Set<SignRender.SignRenderData> rendered = new HashSet<>();

        for (SignRender.SignCluster cluster : this.clusters) {
            switch ((SignRender.ClusterMode) this.clusterMode.get()) {
                case Stack:
                    this.renderStackedCluster(cluster, textRenderer, rendered);
                    break;
                case Cycle:
                    this.renderCyclingCluster(cluster, textRenderer, currentTime, rendered);
                    break;
                case Count:
                    this.renderCountCluster(cluster, textRenderer, rendered);
                    break;
                case Smart:
                    this.renderSmartCluster(cluster, textRenderer, rendered);
            }
        }

        for (SignRender.SignRenderData sign : this.allSigns) {
            if (!rendered.contains(sign) && sign.onScreen) {
                this.renderSignAtPosition(sign, textRenderer, sign.screenX, sign.screenY);
            }
        }
    }

    private void renderAllSigns(TextRenderer textRenderer) {
        if ((Boolean) this.enableClustering.get() && this.clusterMode.get() == SignRender.ClusterMode.Cycle && !this.allSigns.isEmpty()) {
            long currentTime = System.currentTimeMillis();
            if (this.lastGlobalCycleTime == 0L) {
                this.lastGlobalCycleTime = currentTime;
            }

            if (currentTime - this.lastGlobalCycleTime >= ((Integer) this.cycleTime.get()).intValue()) {
                this.globalCycleIndex = (this.globalCycleIndex + 1) % this.allSigns.size();
                this.lastGlobalCycleTime = currentTime;
            }

            if (this.globalCycleIndex >= this.allSigns.size()) {
                this.globalCycleIndex = 0;
            }

            SignRender.SignRenderData currentSign = this.allSigns.get(this.globalCycleIndex);
            if (currentSign.onScreen) {
                this.renderSignAtPosition(currentSign, textRenderer, currentSign.screenX, currentSign.screenY);
                if ((Boolean) this.showClusterCount.get() && this.allSigns.size() > 1) {
                    textRenderer.begin(this.graphics, currentSign.scale, false, true);
                    double lineHeight = textRenderer.getHeight();
                    textRenderer.end();
                    double signHeight = this.multilineDisplay.get() && !currentSign.lines.isEmpty() ? currentSign.lines.size() * lineHeight + 8.0 : lineHeight + 8.0;
                    String indicator = String.format("[%d/%d]", this.globalCycleIndex + 1, this.allSigns.size());
                    this.renderTextAtScreenPos(
                        indicator, currentSign.screenX, currentSign.screenY + signHeight / 2.0 + 15.0, 0.7, (Color) this.clusterCountColor.get(), textRenderer
                    );
                }
            }
        } else {
            for (SignRender.SignRenderData sign : this.allSigns) {
                if (sign.onScreen) {
                    this.renderSignAtPosition(sign, textRenderer, sign.screenX, sign.screenY);
                }
            }
        }
    }

    private void renderStackedCluster(SignRender.SignCluster cluster, TextRenderer textRenderer, Set<SignRender.SignRenderData> rendered) {
        double baseX = cluster.centerX;
        double baseY = cluster.centerY;
        double offsetY = 0.0;
        int count = 0;

        for (SignRender.SignRenderData sign : cluster.signs) {
            if (count >= (Integer) this.maxClusterDisplay.get()) {
                break;
            }

            this.renderSignAtPosition(sign, textRenderer, baseX, baseY + offsetY);
            rendered.add(sign);
            textRenderer.begin(this.graphics, sign.scale, false, true);
            double lineHeight = textRenderer.getHeight();
            textRenderer.end();
            double signHeight = this.multilineDisplay.get() && !sign.lines.isEmpty() ? sign.lines.size() * lineHeight + 8.0 : lineHeight + 8.0;
            offsetY += signHeight + this.stackSpacing.get();
            count++;
        }

        if ((Boolean) this.showClusterCount.get() && cluster.signs.size() > (Integer) this.maxClusterDisplay.get()) {
            String countText = "+" + (cluster.signs.size() - (Integer) this.maxClusterDisplay.get()) + " more";
            this.renderTextAtScreenPos(countText, baseX, baseY + offsetY, 0.8, (Color) this.clusterCountColor.get(), textRenderer);
        }
    }

    private void renderCyclingCluster(SignRender.SignCluster cluster, TextRenderer textRenderer, long currentTime, Set<SignRender.SignRenderData> rendered) {
        SignRender.SignRenderData currentSign = cluster.getCurrentSign(currentTime, (Integer) this.cycleTime.get());
        if (currentSign != null) {
            this.renderSignAtPosition(currentSign, textRenderer, cluster.centerX, cluster.centerY);
            rendered.addAll(cluster.signs);
            if ((Boolean) this.showClusterCount.get() && cluster.signs.size() > 1) {
                textRenderer.begin(this.graphics, currentSign.scale, false, true);
                double lineHeight = textRenderer.getHeight();
                textRenderer.end();
                double signHeight = this.multilineDisplay.get() && !currentSign.lines.isEmpty() ? currentSign.lines.size() * lineHeight + 8.0 : lineHeight + 8.0;
                String indicator = String.format("[%d/%d]", cluster.cycleIndex + 1, cluster.signs.size());
                this.renderTextAtScreenPos(
                    indicator, cluster.centerX, cluster.centerY + signHeight / 2.0 + 15.0, 0.7, (Color) this.clusterCountColor.get(), textRenderer
                );
            }
        }
    }

    private void renderCountCluster(SignRender.SignCluster cluster, TextRenderer textRenderer, Set<SignRender.SignRenderData> rendered) {
        SignRender.SignRenderData primary = cluster.primarySign;
        this.renderSignAtPosition(primary, textRenderer, cluster.centerX, cluster.centerY);
        rendered.addAll(cluster.signs);
        if (cluster.signs.size() > 1) {
            textRenderer.begin(this.graphics, primary.scale, false, true);
            double lineHeight = textRenderer.getHeight();
            textRenderer.end();
            double signHeight = this.multilineDisplay.get() && !primary.lines.isEmpty() ? primary.lines.size() * lineHeight + 8.0 : lineHeight + 8.0;
            String countText = "(" + cluster.signs.size() + " signs)";
            this.renderTextAtScreenPos(
                countText, cluster.centerX, cluster.centerY + signHeight / 2.0 + 10.0, 0.8, (Color) this.clusterCountColor.get(), textRenderer
            );
        }
    }

    private void renderSmartCluster(SignRender.SignCluster cluster, TextRenderer textRenderer, Set<SignRender.SignRenderData> rendered) {
        int displayCount = Math.min(cluster.signs.size(), (Integer) this.maxClusterDisplay.get());
        if (displayCount == 1) {
            this.renderSignAtPosition(cluster.signs.get(0), textRenderer, cluster.centerX, cluster.centerY);
            rendered.add(cluster.signs.get(0));
        } else {
            double radius = 30.0 + displayCount * 5.0;
            double angleStep = (Math.PI * 2) / displayCount;

            for (int i = 0; i < displayCount; i++) {
                SignRender.SignRenderData sign = cluster.signs.get(i);
                double angle = i * angleStep - (Math.PI / 2);
                double offsetX = Math.cos(angle) * radius;
                double offsetY = Math.sin(angle) * radius;
                this.renderSignAtPosition(sign, textRenderer, cluster.centerX + offsetX, cluster.centerY + offsetY);
                rendered.add(sign);
            }

            if ((Boolean) this.showClusterCount.get() && cluster.signs.size() > displayCount) {
                String countText = "+" + (cluster.signs.size() - displayCount);
                this.renderTextAtScreenPos(countText, cluster.centerX, cluster.centerY, 0.9, (Color) this.clusterCountColor.get(), textRenderer);
            }
        }
    }

    private void renderSignAtPosition(SignRender.SignRenderData sign, TextRenderer textRenderer, double centerX, double centerY) {
        if ((Boolean) this.multilineDisplay.get() && !sign.lines.isEmpty()) {
            this.renderMultilineSign(sign, textRenderer, centerX, centerY);
        } else if (!sign.fullText.isEmpty()) {
            this.renderSingleLineSign(sign, textRenderer, centerX, centerY);
        }
    }

    private void renderMultilineSign(SignRender.SignRenderData sign, TextRenderer textRenderer, double centerX, double centerY) {
        textRenderer.begin(this.graphics, sign.scale, false, true);
        double lineHeight = textRenderer.getHeight();
        List<Double> lineWidths = new ArrayList<>();
        double maxWidth = 0.0;

        for (String line : sign.lines) {
            double width = line.isEmpty() ? 0.0 : textRenderer.getWidth(line);
            lineWidths.add(width);
            maxWidth = Math.max(maxWidth, width);
        }

        double totalHeight = sign.lines.size() * lineHeight;
        double bgPadding = 4.0;
        double bgWidth = maxWidth + bgPadding * 2.0;
        double bgHeight = totalHeight + bgPadding * 2.0;
        double bgLeft = centerX - bgWidth / 2.0;
        double bgTop = centerY - bgHeight / 2.0;
        textRenderer.end();
        if ((Boolean) this.showBackground.get()) {
            Color bgColor = new Color(
                ((SettingColor) this.backgroundColor.get()).r,
                ((SettingColor) this.backgroundColor.get()).g,
                ((SettingColor) this.backgroundColor.get()).b,
                (int) (((SettingColor) this.backgroundColor.get()).a * (sign.color.a / 255.0))
            );
            Renderer2D.COLOR.begin();
            Renderer2D.COLOR.quad(bgLeft, bgTop, bgWidth, bgHeight, bgColor);
            Renderer2D.COLOR.render();
        }

        textRenderer.begin(this.graphics, sign.scale, false, true);
        for (int i = 0; i < sign.lines.size(); i++) {
            String line = sign.lines.get(i);
            if (!line.isEmpty()) {
                double lineWidth = lineWidths.get(i);
                double textX = centerX - lineWidth / 2.0;
                double textY = bgTop + bgPadding + i * lineHeight;
                textRenderer.render(line, textX, textY, sign.color);
            }
        }

        textRenderer.end();
    }

    private void renderSingleLineSign(SignRender.SignRenderData sign, TextRenderer textRenderer, double centerX, double centerY) {
        this.renderTextAtScreenPos(sign.fullText, centerX, centerY, sign.scale, sign.color, textRenderer);
    }

    private void renderTextAtScreenPos(String text, double screenX, double screenY, double scale, Color color, TextRenderer textRenderer) {
        textRenderer.begin(this.graphics, scale, false, true);
        double textWidth = textRenderer.getWidth(text);
        double textHeight = textRenderer.getHeight();
        double bgPadding = 4.0;
        double elementWidth = textWidth + bgPadding * 2.0;
        double elementHeight = textHeight + bgPadding * 2.0;
        double elementLeft = screenX - elementWidth / 2.0;
        double elementTop = screenY - elementHeight / 2.0;
        textRenderer.end();
        if ((Boolean) this.showBackground.get()) {
            Color bgColor = new Color(
                ((SettingColor) this.backgroundColor.get()).r,
                ((SettingColor) this.backgroundColor.get()).g,
                ((SettingColor) this.backgroundColor.get()).b,
                (int) (((SettingColor) this.backgroundColor.get()).a * (color.a / 255.0))
            );
            Renderer2D.COLOR.begin();
            Renderer2D.COLOR.quad(elementLeft, elementTop, elementWidth, elementHeight, bgColor);
            Renderer2D.COLOR.render();
        }

        textRenderer.begin(this.graphics, scale, false, true);
        textRenderer.render(text, elementLeft + bgPadding, elementTop + bgPadding, color);
        textRenderer.end();
    }

    private List<String> extractSignLines(BlockEntity blockEntity) {
        List<String> lines = new ArrayList<>();

        try {
            SignText frontText = null;
            SignText backText = null;
            if (blockEntity instanceof SignBlockEntity sign) {
                frontText = sign.getFrontText();
                backText = sign.getBackText();
            } else if (blockEntity instanceof HangingSignBlockEntity sign) {
                frontText = sign.getFrontText();
                backText = sign.getBackText();
            }

            if (frontText != null) {
                List<String> frontLines = this.extractTextLines(frontText);
                if (!frontLines.isEmpty()) {
                    lines.addAll(frontLines);
                }
            }

            if (backText != null && lines.isEmpty()) {
                List<String> backLines = this.extractTextLines(backText);
                if (!backLines.isEmpty()) {
                    lines.addAll(backLines);
                }
            }
        } catch (Exception var7) {
        }

        return lines;
    }

    private List<String> extractTextLines(SignText signText) {
        List<String> lines = new ArrayList<>();

        try {
            Component[] messages = signText.getMessages(false);
            if (messages != null) {
                for (Component message : messages) {
                    if (message != null) {
                        String line = this.safeExtractString(message);
                        if (!line.isEmpty()) {
                            lines.add(line);
                        }
                    }
                }
            }
        } catch (Exception var9) {
        }

        return lines;
    }

    private String safeExtractString(Component text) {
        if (text == null) {
            return "";
        }

        try {
            String result = text.getString();
            return result == null ? "" : this.cleanSignText(result);
        } catch (Exception e) {
            try {
                String literal = text.tryCollapseToString();
                if (literal != null) {
                    return this.cleanSignText(literal);
                }
            } catch (Exception var4) {
            }

            return "";
        }
    }

    private String cleanSignText(String text) {
        if (text != null && !text.isEmpty()) {
            text = text.replaceAll("\u00a7.", "");
            text = text.replaceAll("&[0-9a-fklmnor]", "");
            if (text.contains("{\"") || text.contains("[\"")) {
                text = text.replaceAll("\\{\".*?\":\"(.*?)\".*?\\}", "$1");
                text = text.replaceAll("\\[\"(.*?)\"\\]", "$1");
            }

            text = text.replaceAll("\\{[^\\s].*?\\}", "");
            text = text.replaceAll("[\\p{C}&&[^\\s]]", "");
            text = text.replaceAll("[\\u0000-\\u001F\\u007F-\\u009F]", "");
            text = text.replaceAll("[\\[\\]{}\"']", "");
            text = text.replaceAll("\\s+", " ").trim();
            if (text.length() > 100) {
                text = text.substring(0, 97) + "...";
            }

            return text;
        } else {
            return "";
        }
    }

    public enum ClusterMode {
        Stack("Stack vertically"),
        Cycle("Cycle through signs"),
        Count("Show count only"),
        Smart("Smart layout");

        private final String description;

        ClusterMode(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return this.description;
        }
    }

    private static class SignCluster {
        final List<SignRender.SignRenderData> signs = new ArrayList<>();
        double centerX;
        double centerY;
        SignRender.SignRenderData primarySign;
        int cycleIndex = 0;
        long lastCycleTime = 0L;

        void addSign(SignRender.SignRenderData sign) {
            this.signs.add(sign);
            sign.onScreen = true;
        }

        void calculateCenter() {
            if (!this.signs.isEmpty()) {
                this.signs.sort(Comparator.comparingDouble(s -> s.distance));
                this.primarySign = this.signs.get(0);
                this.centerX = this.primarySign.screenX;
                this.centerY = this.primarySign.screenY;
            }
        }

        SignRender.SignRenderData getCurrentSign(long currentTime, int cycleTimeMs) {
            if (this.signs.isEmpty()) {
                return null;
            }

            if (this.signs.size() == 1) {
                return this.signs.get(0);
            }

            if (this.lastCycleTime == 0L) {
                this.lastCycleTime = currentTime;
            }

            if (currentTime - this.lastCycleTime >= cycleTimeMs) {
                this.cycleIndex = (this.cycleIndex + 1) % this.signs.size();
                this.lastCycleTime = currentTime;
            }

            return this.signs.get(this.cycleIndex);
        }
    }

    private static class SignRenderData {
        final BlockPos pos;
        final List<String> lines;
        final String fullText;
        final Vec3 worldPos;
        double distance;
        double screenX;
        double screenY;
        boolean onScreen = false;
        double renderWidth;
        double renderHeight;
        double scale;
        Color color;

        SignRenderData(BlockPos pos, List<String> lines, Vec3 worldPos) {
            this.pos = pos;
            this.lines = new ArrayList<>(lines);
            this.fullText = String.join(" ", lines).trim();
            this.worldPos = worldPos;
        }

        void updateScreenPosition(Vector3d tempVec) {
            tempVec.set(this.worldPos.x, this.worldPos.y + 0.5, this.worldPos.z);
            if (NametagUtils.to2D(tempVec, 1.0)) {
                this.screenX = tempVec.x;
                this.screenY = tempVec.y;
                this.onScreen = true;
            } else {
                this.onScreen = false;
            }
        }
    }
}
