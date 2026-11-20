plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.2"

stonecutter {
    parameters {
        if (current.parsed < "26.1") {
            replacements {
                string(true) {
                    // 1.21.x ships the plain GuiGraphics; the 26.x state-extract pipeline does not exist there.
                    replace("net.minecraft.client.gui.GuiGraphicsExtractor", "net.minecraft.client.gui.GuiGraphics")
                    // 1.21.11-specific spellings, written out before the generic fold so they win.
                    replace("net.minecraft.client.gui.components.PlayerFaceExtractor", "net.minecraft.client.gui.components.PlayerFaceRenderer")
                    replace("PlayerFaceExtractor.extractRenderState", "PlayerFaceRenderer.draw")
                    replace("meteordevelopment.meteorclient.mixininterface.IVec3;", "meteordevelopment.meteorclient.mixininterface.IVec3d;")
                    replace("(IVec3) ", "(IVec3d) ")
                    replace("component.extractText(", "component.renderText(")
                    replace("extractTooltipBackground", "renderTooltipBackground")
                    replace("extractEntityInInventoryFollowsMouse", "renderEntityInInventoryFollowsMouse")
                    replace("extractContent(", "renderContent(")
                    // Only our own screen overrides are renamed; MapRenderer#extractRenderState keeps its name.
                    replace("public void extractRenderState(", "public void render(")
                    replace("super.extractRenderState(", "super.render(")
                    replace("method = \"extractRenderState\"", "method = \"render\"")
                    replace("extractContents", "renderContents")
                    replace("extractSlots", "renderSlots")
                    replace("extractSlot", "renderSlot")
                    replace("extractSlotHighlightBack", "renderSlotHighlightBack")
                    replace("extractSlotHighlightFront", "renderSlotHighlightFront")
                    replace("extractCarriedItem", "renderCarriedItem")
                    replace("extractSnapbackItem", "renderSnapbackItem")
                    replace("extractFloatingItem", "renderFloatingItem")
                    replace("extractWidgetRenderState", "renderWidget")
                    replace("extractScrollingStringOverContents", "renderScrollingStringOverContents")
                    replace("GuiGraphicsExtractor", "GuiGraphics")
                    // The 1.21.x MultiPlayerGameMode click path uses ClickType.
                    replace("handleContainerInput", "handleInventoryMouseClick")
                    replace("net.minecraft.world.inventory.ContainerInput", "net.minecraft.world.inventory.ClickType")
                    replace("ContainerInput", "ClickType")
                    replace("nonEmptyItemCopyStream()", "nonEmptyStream()")
                    // The Render/HUD flavour change: the 26.x events keep their graphics in
                    // `graphics`; the 1.21.x pair uses drawContext.
                    replace("graphics.text(", "graphics.drawString(")
                    replace("context.text(", "context.drawString(")
                    replace("statusContext.text(", "statusContext.drawString(")
                    replace("context.centeredText(", "context.drawCenteredString(")
                    replace("graphics.centeredText(", "graphics.drawCenteredString(")
                    replace("context.item(", "context.renderItem(")
                    replace("graphics.item(", "graphics.renderItem(")
                    replace("statusContext.item(", "statusContext.renderItem(")
                    replace("context.itemDecorations(", "context.renderItemDecorations(")
                    replace("graphics.itemDecorations(", "graphics.renderItemDecorations(")
                    replace("statusContext.itemDecorations(", "statusContext.renderItemDecorations(")
                    // Event/field/instance renames; the 1.21.x events use drawContext.
                    // The composite forms outrank `event.graphics` by being longer at the same position.
                    replace("event.graphics.text(", "event.drawContext.drawString(")
                    replace("event.graphics.centeredText(", "event.drawContext.drawCenteredString(")
                    replace("event.graphics.item(", "event.drawContext.renderItem(")
                    replace("event.graphics.itemDecorations(", "event.drawContext.renderItemDecorations(")
                    replace("event.graphics", "event.drawContext")
                    replace("renderer.graphics", "renderer.drawContext")
                    // The 1.21.x GuiGraphics spells these methods differently than the 26.x extractor.
                    replace("extractBackground", "renderBackground")
                    // ChunkPos accessors are record accessors on 26.x; plain fields/toLong on 1.21.x.
                    replace("\"item(Lnet/minecraft/world/item/ItemStack;II)V\"", "\"renderItem(Lnet/minecraft/world/item/ItemStack;II)V\"")
                    replace("chunk.getPos().pack()", "chunk.getPos().toLong()")
                    replace("ChunkPos.pack(", "ChunkPos.asLong(")
                    replace(".getChunk(chunkPos.x(), chunkPos.z())", ".getChunk(chunkPos.x, chunkPos.z)")
                    replace(".getChunk(c.x(), c.z())", ".getChunk(c.x, c.z)")
                    replace("FarmlandBlock", "FarmBlock")
                    // PayloadTypeRegistry on 1.21.x split server/client registries with yarn-style names.
                    replace("serverboundPlay()", "playC2S()")
                    replace("getLightDampening()", "getLightBlock()")
                    // The 1.21.x game mode names the 4-argument EntityHitResult interact interactAt.
                    replace("mc.gameMode.interact(", "mc.gameMode.interactAt(")
                    replace(".map(renderState)", ".submitMapRenderState(renderState)")
                    // Meteor's command arguments are typed SharedSuggestionProvider there.
                }
            }
        }
    }
}
