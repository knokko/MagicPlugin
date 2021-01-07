package com.elmakers.mine.bukkit.action.builtin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.elmakers.mine.bukkit.action.BaseSpellAction;
import com.elmakers.mine.bukkit.api.action.CastContext;
import com.elmakers.mine.bukkit.api.magic.Mage;
import com.elmakers.mine.bukkit.api.spell.Spell;
import com.elmakers.mine.bukkit.api.spell.SpellResult;
import com.elmakers.mine.bukkit.block.MaterialAndData;
import com.elmakers.mine.bukkit.spell.BaseSpell;
import com.elmakers.mine.bukkit.utility.CompatibilityUtils;
import com.elmakers.mine.bukkit.utility.ConfigurationUtils;
import com.elmakers.mine.bukkit.utility.InventoryUtils;
import com.elmakers.mine.bukkit.utility.NMSUtils;

public class LockAction extends BaseSpellAction
{
    private enum LockActionType {
        LOCK,
        UNLOCK,
        KEY
    }

    private LockActionType actionType;
    private MaterialAndData iconType;
    private String keyName;
    private String keyDescription;
    private boolean override;

    @Override
    public SpellResult perform(CastContext context)
    {
        String keyName = this.keyName;
        if (keyName.isEmpty())
        {
            keyName = context.getMessage("key_name");
        }
        keyName = keyName
                    .replace("$name", context.getMage().getName())
                    .replace("$uuid", context.getMage().getId());
        String keyDescription = this.keyDescription;
        if (keyDescription.isEmpty())
        {
            keyDescription = context.getMessage("key_description");
        }
        keyDescription = keyDescription.replace("$name", context.getMage().getName());
        Mage mage = context.getMage();
        boolean result = false;
        if (actionType == LockActionType.KEY) {
            if (!giveKey(mage, keyName, keyDescription)) {
                context.sendMessageKey("already_key");
                return SpellResult.NO_TARGET;
            }
            return SpellResult.CAST;
        }

        Block targetBlock = context.getTargetBlock();
        if (targetBlock == null)
        {
            return SpellResult.NO_TARGET;
        }
        if (!context.hasBuildPermission(targetBlock))
        {
            return SpellResult.INSUFFICIENT_PERMISSION;
        }

        // The isLocked check here is a work-around for re-securing chests
        // Since locked chests are normally indestructible!
        if (!context.isDestructible(targetBlock) && !CompatibilityUtils.isLocked(targetBlock))
        {
            mage.sendDebugMessage("Destructible fallback, can't lock " + targetBlock.getType());
            return SpellResult.NO_TARGET;
        }

        if (actionType == LockActionType.LOCK) {
            String lock = CompatibilityUtils.getLock(targetBlock);
            if (lock != null && !lock.isEmpty())
            {
                if (lock.equals(keyName))
                {
                    context.sendMessageKey("already");
                    return SpellResult.NO_TARGET;
                }
                if (!override && !InventoryUtils.hasItem(mage.getInventory(), lock))
                {
                    // Check for old alternate keys
                    boolean isAlternate = false;
                    String altTemplate = context.getMessage("key_name_alternate", null);
                    if (altTemplate != null && altTemplate.length() > 0) {
                        altTemplate = altTemplate
                                .replace("$name", context.getMage().getName())
                                .replace("$uuid", context.getMage().getId());
                        isAlternate = lock.equals(altTemplate);
                    }
                    if (!isAlternate) {
                        mage.sendDebugMessage("Already locked with different key, tried alternate");
                        return SpellResult.FAIL;
                    }
                }
                context.sendMessageKey("acquire");
            }
            result = CompatibilityUtils.setLock(targetBlock, keyName);
            if (!giveKey(mage, keyName, keyDescription)) {
                context.sendMessageKey("already_key");
                return SpellResult.NO_TARGET;
            }
        } else {
            String lock = CompatibilityUtils.getLock(targetBlock);
            if (lock == null || lock.isEmpty())
            {
                return SpellResult.FAIL;
            }
            if (!keyName.equals(lock) && !override && !InventoryUtils.hasItem(mage.getInventory(), lock))
            {
                return SpellResult.FAIL;
            }
            result = CompatibilityUtils.clearLock(targetBlock);
        }

        if (!result) {
            mage.sendDebugMessage("Failed to lock");
        }
        return result ? SpellResult.CAST : SpellResult.FAIL;
    }

    protected boolean giveKey(Mage mage, String keyName, String keyDescription) {
        if (InventoryUtils.hasItem(mage.getInventory(), keyName)) {
            return false;
        }

        ItemStack keyItem = null;
        keyItem = iconType.getItemStack(1);
        ItemMeta meta = keyItem.getItemMeta();
        meta.setDisplayName(keyName);
        if (!keyDescription.isEmpty()) {
            List<String> lore = new ArrayList<>();
            String[] lines = StringUtils.split(keyDescription, '\n');
            for (String line : lines) {
                lore.add(line);
            }
            meta.setLore(lore);
        }
        keyItem.setItemMeta(meta);
        keyItem = CompatibilityUtils.makeReal(keyItem);
        if (!NMSUtils.isLegacy()) {
            CompatibilityUtils.setDisplayNameRaw(keyItem, "{\"text\":\"" + keyName + "\"}");
        }
        CompatibilityUtils.makeUnplaceable(keyItem);
        InventoryUtils.makeKeep(keyItem);
        mage.giveItem(keyItem);
        return true;
    }

    @Override
    public boolean isUndoable() {
        return false;
    }

    @Override
    public boolean requiresTarget() {
        return actionType != LockActionType.KEY;
    }

    @Override
    public boolean requiresBuildPermission() {
        return actionType != LockActionType.KEY;
    }

    @Override
    public void prepare(CastContext context, ConfigurationSection parameters) {
        super.prepare(context, parameters);

        actionType = LockActionType.LOCK;
        String type = parameters.getString("type", "lock");
        if (type.equalsIgnoreCase("unlock")) {
            actionType = LockActionType.UNLOCK;
        } else if (type.equalsIgnoreCase("key")) {
            actionType = LockActionType.KEY;
        }
        keyName = parameters.getString("key_name", "");
        keyDescription = parameters.getString("key_description", "");
        iconType = ConfigurationUtils.getMaterialAndData(parameters, "key_icon", new MaterialAndData(Material.TRIPWIRE_HOOK));
        override = parameters.getBoolean("override", false);
    }

    @Override
    public void getParameterNames(Spell spell, Collection<String> parameters)
    {
        super.getParameterNames(spell, parameters);
        parameters.add("type");
        parameters.add("key_icon");
        parameters.add("key_name");
        parameters.add("key_description");
        parameters.add("override");
    }

    @Override
    public void getParameterOptions(Spell spell, String parameterKey, Collection<String> examples)
    {
        if (parameterKey.equals("type")) {
            examples.add("lock");
            examples.add("unlock");
            examples.add("key");
        } else if (parameterKey.equals("override")) {
            examples.addAll(Arrays.asList(BaseSpell.EXAMPLE_BOOLEANS));
        } else {
            super.getParameterOptions(spell, parameterKey, examples);
        }
    }
}
