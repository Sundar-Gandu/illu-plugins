package net.runelite.client.plugins.iutils.scripts;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.iutils.api.Combat;
import net.runelite.client.plugins.iutils.api.EquipmentSlot;
import net.runelite.client.plugins.iutils.api.Magic;
import net.runelite.client.plugins.iutils.api.Prayers;
import net.runelite.client.plugins.iutils.bot.Bot;
import net.runelite.client.plugins.iutils.bot.InventoryItem;
import net.runelite.client.plugins.iutils.bot.iNPC;
import net.runelite.client.plugins.iutils.scene.Area;
import net.runelite.client.plugins.iutils.scene.RectangularArea;
import net.runelite.client.plugins.iutils.ui.*;
import net.runelite.client.plugins.iutils.walking.BankLocations;
import net.runelite.client.plugins.iutils.walking.Walking;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
public abstract class QuestScript extends Plugin implements Runnable {
    private static final RectangularArea GRAND_EXCHANGE = new RectangularArea(3159, 3493, 3169, 3485);

    @Inject protected Bot bot;
    @Inject protected Walking walking;
    @Inject protected Chatbox chatbox;
    @Inject protected Equipment equipment;
    @Inject protected Combat combat;
    @Inject protected StandardSpellbook standardSpellbook;
    @Inject protected Prayers prayers;

    protected void equip(int... ids) {
        obtain(Arrays.stream(ids)
                .filter(i -> !equipment.isEquipped(i))
                .mapToObj(i -> new ItemQuantity(i, 1))
                .toArray(ItemQuantity[]::new));

        bot.inventory().withId(ids).forEach(i -> i.interact(1));
        bot.waitUntil(() -> Arrays.stream(ids).allMatch(equipment::isEquipped));
    }

    protected void equip(String name) {
        if (bot.equipment().withName(name).exists()) {
            return;
        }

        bot.inventory().withName(name).first().interact(0);
        bot.waitUntil(() -> bot.equipment().withName(name).exists());
    }

    protected void obtain(ItemQuantity... items) {
        if (hasItems(items)) {
            return;
        }

        obtainBank(items);
        withdraw(items);
    }

    protected void withdraw(ItemQuantity... items) {
        Arrays.stream(items)
                .map(i -> new ItemQuantity(i.id, i.quantity - bot.inventory().withId(i.id).quantity()))
                .filter(i -> i.quantity > 0)
                .collect(Collectors.toList())
                .forEach(i -> bank().withdraw(i.id, i.quantity, false));
    }

    protected void obtainBank(ItemQuantity... items) {
        Arrays.stream(items)
                .map(i -> new ItemQuantity(i.id, i.quantity - bank().quantity(i.id) - bot.inventory().withId(i.id).quantity()))
                .filter(i -> i.quantity > 0)
                .collect(Collectors.toList())
                .forEach(i -> grandExchange().buy(i.id, i.quantity));

        bank().depositInventory();
    }

    protected boolean inventoryHasItems(ItemQuantity... items) {
        for (var item : items) {
            if (bot.inventory().withId(item.id).quantity() < item.quantity) {
                return false;
            }
        }
        return true;
    }

    protected boolean hasItems(ItemQuantity... items) { //TODO needs fixing
        for (var item : items) {
            if (equipment.quantity(item.id) < item.quantity && bot.inventory().withId(item.id).quantity() < item.quantity) {
                return false;
            }
        }
        return true;
    }

    protected void handleLevelUp() {
        if (bot.widget(162, 562).nestedInterface() == 233) {
            System.out.println("Closing chat dialog");
            bot.widget(233, 3).select();
            bot.tick();
            chat();
        }
    }

    protected Bank bank() {
        var bank = new Bank(bot);

        if (!bank.isOpen()) {
            BankLocations.walkToBank(bot);
            if (bot.npcs().withName("Banker").exists()) {
                bot.npcs().withName("Banker").nearest().interact("Bank");
            } else if (bot.objects().withName("Bank booth").withAction("Bank").exists()) {
                bot.objects().withName("Bank booth").withAction("Bank").nearest().interact("Bank");
            } else {
                bot.objects().withName("Bank chest").nearest().interact("Use");
            }
            bot.waitUntil(bank::isOpen, 10);
        }

        return bank;
    }

    protected GrandExchange grandExchange() {
        if (!GRAND_EXCHANGE.contains(bot.localPlayer().position())) {
            System.out.println(GRAND_EXCHANGE.toString() + " doesn't contain player: " + bot.localPlayer().position());
            walking.walkTo(GRAND_EXCHANGE);
        }

        if (!bot.inventory().withId(995).exists()) {
            bank().withdraw(995, Integer.MAX_VALUE, false);
        }

        var grandExchange = new GrandExchange(bot);

        if (!grandExchange.isOpen()) {

            bot.npcs().withName("Grand Exchange Clerk").nearest().interact("Exchange");
            bot.waitUntil(grandExchange::isOpen);
        }

        return grandExchange;
    }

    protected void teleportToLumbridge() {
        standardSpellbook.lumbridgeHomeTeleport();
    }

    protected void killNpc(String name, Prayer... prayers) {
        bot.waitUntil(() -> bot.npcs().withName(name).withAction("Attack").exists());
        var npc = bot.npcs().withName(name).nearest();
        killNpc(npc, prayers);
    }

    protected void killNpc(int id, Prayer... prayers) {
        bot.waitUntil(() -> bot.npcs().withId(id).exists());
        var npc = bot.npcs().withId(id).nearest();

        if (bot.npcs().withId(id).withTarget(bot.localPlayer()).nearest() != null) {
            npc = bot.npcs().withId(id).withTarget(bot.localPlayer()).nearest();
        }

        killNpc(npc, prayers);
    }

    public void killNpc(iNPC npc, Prayer... prayers) {
        combat.kill(npc, prayers);
    }

    private boolean needsStatRestore() {
        var matters = new Skill[]{Skill.ATTACK, Skill.DEFENCE, Skill.STRENGTH};
        for (var skill : matters) {
            if (bot.modifiedLevel(skill) < bot.baseLevel(skill)) {
                return true;
            }
        }
        return false;
    }

    protected void chatNpc(Area location, String npcName, String... chatOptions) {
        walking.walkTo(location);
        bot.npcs().withName(npcName).nearest().interact("Talk-to");
        chatbox.chat(chatOptions);
        bot.tick();
    }

    protected void chatNpc(Area location, int npcId, String... chatOptions) {
        walking.walkTo(location);
        bot.npcs().withId(npcId).nearest().interact("Talk-to");
        chatbox.chat(chatOptions);
        bot.tick();
    }

    protected void unequip(String item, EquipmentSlot slot) {
        if (bot.equipment().withName(item).exists()) {
            bot.widget(slot.widgetID, slot.widgetChild).interact(0);
            bot.tick();
        }
    }

    protected void castSpellNpc(String name, Magic spell) {
        var npc = bot.npcs().withName(name).nearest();
        castSpellNpc(npc, spell);
    }

    protected void castSpellNpc(iNPC npc, Magic spell) {
        if (npc != null) {
            bot.widget(218, spell.widgetChild).useOn(npc);
            bot.tick();
        }
    }

    protected void castSpellItem(InventoryItem it, Magic spell) {
        if (it != null) {
            bot.widget(218, spell.widgetChild).useOn(it);
            bot.tick();
        }
    }

    protected boolean inCombat() {
        return bot.npcs().withTarget(bot.localPlayer()).exists() || bot.localPlayer().target() != null;
    }

    protected boolean itemOnGround(String item) {
        return bot.groundItems().withName(item).exists();
    }

    protected void chat(String... options) {
        chatbox.chat(options);
    }

    protected void chat(int n) {
        for (int i = 0; i < n; i++) {
            chat();
        }
    }

    protected void interactObject(Area area, String object, String action) {
        walking.walkTo(area);
        bot.objects().withName(object).withAction(action).nearest().interact(action);
    }

    protected void interactObject(Area area, int id, String action) {
        walking.walkTo(area);
        bot.objects().withId(id).withAction(action).nearest().interact(action);
    }

    protected void useItemItem(String item1, String item2) {
        bot.inventory().withName(item1).first().useOn(bot.inventory().withName(item2).first());
    }

    protected void useItemItem(int item1, String item2) {
        bot.inventory().withId(item1).first().useOn(bot.inventory().withName(item2).first());
    }


    protected void take(Area area, String item) {
        if (area != null) {
            walking.walkTo(area);
        }

        bot.waitUntil(() -> bot.groundItems().withName(item).exists());
        bot.groundItems().withName(item).nearest().interact("Take");
        waitItem(item);
    }

    protected boolean hasItem(String item) {
        return bot.inventory().withName(item).exists() || bot.equipment().withName(item).exists();
    }

    protected void waitItem(String item) {
        bot.waitUntil(() -> bot.inventory().withName(item).size() > 0);
    }

    protected void useItemObject(Area area, String item, String object) {
        walking.walkTo(area);
        bot.inventory().withName(item).first().useOn(bot.objects().withName(object).nearest());
    }

    protected void useItemObject(Area area, int item, String object) {
        walking.walkTo(area);
        bot.inventory().withId(item).first().useOn(bot.objects().withName(object).nearest());
    }

    protected void interactNpc(RectangularArea area, String npc, String action) {
        walking.walkTo(area);
        bot.npcs().withName(npc).nearest().interact(action);
    }

    protected void interactNpc(RectangularArea area, int npc, String action) {
        walking.walkTo(area);
        bot.npcs().withId(npc).nearest().interact(action);
    }

    public void waitNpc(String name) {
        bot.waitUntil(() -> bot.npcs().withName("Restless ghost").exists());
    }

    public boolean hasItem(String name, int quantity) {
        return bot.inventory().withName(name).count() >= quantity;
    }

    protected void interactItem(String item, String action) {
        bot.inventory().withName(item).first().interact(action);
    }

    protected void useItemNpc(String item, String npc) {
        bot.inventory().withName(item).first().useOn(bot.npcs().withName(npc).nearest());
    }

    protected void waitAnimationEnd(int id) {
        bot.waitUntil(() -> bot.localPlayer().animation() == id);
        bot.waitUntil(() -> bot.localPlayer().animation() == -1);
    }

    public static class ItemQuantity {
        public final int id;
        public int quantity;


        public ItemQuantity(int id, int quantity) {
            this.id = id;
            this.quantity = quantity;
        }
    }
}
